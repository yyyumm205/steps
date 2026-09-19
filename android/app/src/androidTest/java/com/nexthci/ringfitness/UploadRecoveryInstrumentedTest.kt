package com.nexthci.ringfitness

import android.Manifest
import android.app.Activity
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Host-driven recovery checks for a separate installation containing locally verified copies.
 * Default suites skip these tests. Each invocation requires verifyUploadRecovery=true and the
 * recoveryqa application ID. The host controls networking, process death and fixture task states.
 * These checks never grant BLE permission, create sessions, alter source files or fabricate receipts.
 *
 * files/recovery-validation.json (local only): version=1, purpose=upload-recovery-validation,
 * applicationId=<target package>, sessions=[{sessionId, archiveSha256, expectedSteps}, ...].
 * Exactly two complete real sessions and their original frozen packages must already be present.
 * Select one method with the instrumentation class argument. requestManualRetry also requires
 * sessionId; verifyPreservedRecords optionally checks all tasks against expectedTaskState.
 */
@RunWith(AndroidJUnit4::class)
class UploadRecoveryInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val context get() = instrumentation.targetContext
    private val directory get() = File(context.filesDir, "collection-real")
    private val store get() = FreeLivingSessionStore(File(directory, "session.json"))
    private val queue get() = RealUploadScheduler.queue(context)
    private val scheduler get() = context.getSystemService(JobScheduler::class.java)
    private val component get() = ComponentName(context, RealUploadService::class.java)

    private data class Source(val sessionId: String, val archiveSha256: String, val expectedSteps: Long)
    private data class Fixture(val sources: List<Source>, val markerSha256: String)

    @Test fun launcherRestoresPendingJobsWithoutBle() {
        val fixture = verifiedFixture()
        requireOffline()
        val before = fixture.sources.associate { it.sessionId to requireNotNull(queue.task(it.sessionId)) }
        assertTrue("Prepare at least one queued or interrupted sending task locally",
            before.values.any { it.state in setOf("queued", "sending") })
        assertTrue("Use only pending or failed transport copies for this case",
            before.values.all { it.state in setOf("queued", "sending", "failed") })
        cancelUploadJobs()

        withLauncher {
            await("Opening preparation must restore the persisted upload job") { uploadJobs().size == 1 }
            val job = uploadJobs().single()
            assertTrue("Recovery must survive a later process exit", job.isPersisted)
            @Suppress("DEPRECATION")
            assertEquals("Offline work must wait for a network", JobInfo.NETWORK_TYPE_ANY, job.networkType)
            requireOffline()
            requireNoBleOwner()
            fixture.sources.forEach { source ->
                assertTrue("Offline recovery must not change a queued, sending or failed task",
                    before.getValue(source.sessionId) == queue.task(source.sessionId))
            }
            assertPreserved(fixture)
        }
    }

    @Test fun launcherDoesNotRetryFailedJobs() {
        val fixture = verifiedFixture()
        requireOffline()
        val before = fixture.sources.associate { it.sessionId to requireNotNull(queue.task(it.sessionId)) }
        assertTrue("Prepare failed copies before running this case", before.values.all { it.state == "failed" })
        cancelUploadJobs()

        withLauncher {
            assertTrue("Failed work waits for an explicit retry", uploadJobs().isEmpty())
            requireOffline()
            requireNoBleOwner()
            fixture.sources.forEach { source ->
                assertTrue("Opening preparation must retain each failure and its destination binding",
                    before.getValue(source.sessionId) == queue.task(source.sessionId))
            }
            assertPreserved(fixture)
        }
    }

    @Test fun requestManualRetry() {
        val fixture = verifiedFixture()
        requireOffline()
        val sessionId = requireNotNull(arguments.getString("sessionId")) { "Pass an explicit fixture sessionId" }
        assertTrue("Only a locally verified fixture can be retried", fixture.sources.any { it.sessionId == sessionId })
        val before = fixture.sources.associate { it.sessionId to requireNotNull(queue.task(it.sessionId)) }
        val selected = before.getValue(sessionId)
        assertEquals("Prepare a failed task before requesting retry", "failed", selected.state)

        RealUploadScheduler.enqueue(context, sessionId, retry = true)
        await("An explicit retry must persist and schedule before the host changes connectivity") {
            queue.task(sessionId)?.state == "queued" && uploadJobs().size == 1
        }
        assertTrue("Retry preserves the frozen package, destination and failure evidence",
            selected.copy(state = "queued") == queue.task(sessionId))
        fixture.sources.filter { it.sessionId != sessionId }.forEach { source ->
            assertTrue("Retrying one record must leave the other record unchanged",
                before.getValue(source.sessionId) == queue.task(source.sessionId))
        }
        requireOffline()
        requireNoBleOwner()
        assertPreserved(fixture)
    }

    @Test fun verifyPreservedRecords() {
        val fixture = verifiedFixture()
        arguments.getString("expectedTaskState")?.let { expected ->
            require(expected in setOf("queued", "sending", "failed", "complete"))
            fixture.sources.forEach { source ->
                val task = requireNotNull(queue.task(source.sessionId))
                assertEquals("Unexpected persisted transport state", expected, task.state)
                if (expected == "complete") {
                    val session = requireNotNull(store.read(source.sessionId))
                    assertNotNull("Completion needs a real server receipt", task.receipt)
                    assertNotNull("Completion needs the receipt persisted in the journal", session.transfer.receipt)
                    assertEquals(SessionTransferStatus.COMPLETE, session.transfer.status)
                    assertFalse("A copied real session cannot receive a simulated receipt", session.transfer.receipt!!.simulated)
                    assertEquals(task.receipt!!.fileId, session.transfer.receipt!!.receiptId)
                    assertEquals(source.archiveSha256, task.archiveSha256)
                }
            }
        }
        requireNoBleOwner()
        assertPreserved(fixture)
    }

    private fun verifiedFixture(): Fixture {
        assumeTrue("Recovery tests require explicit opt-in", arguments.getString("verifyUploadRecovery") == "true")
        assumeTrue("Fault injection is confined to the separate recovery installation",
            BuildConfig.DEBUG && context.packageName == "com.nexthci.ringfitness.steps.recoveryqa")
        requireNoBleOwner()
        val marker = File(context.filesDir, "recovery-validation.json")
        check(marker.isFile && marker.length() in 2..32_768) { "Install a reviewed local preservation marker first" }
        val json = JsonParser.parseString(marker.readText(Charsets.UTF_8)).asJsonObject
        require(json.get("version")?.asInt == 1 && json.get("purpose")?.asString == "upload-recovery-validation")
        require(json.get("applicationId")?.asString == context.packageName)
        val sources = requireNotNull(json.getAsJsonArray("sessions")).map { element ->
            val item = element.asJsonObject
            val id = item.get("sessionId").asString
            val sha = item.get("archiveSha256").asString
            val steps = item.get("expectedSteps").asLong
            require(UUID.fromString(id).toString() == id && sha.matches(Regex("[a-f0-9]{64}")) && steps >= 0)
            Source(id, sha, steps)
        }
        require(sources.size == 2 && sources.map { it.sessionId }.distinct().size == 2)
        return Fixture(sources, sha256(marker)).also(::assertPreserved)
    }

    private fun assertPreserved(fixture: Fixture) {
        assertEquals("The preservation marker must remain unchanged", fixture.markerSha256,
            sha256(File(context.filesDir, "recovery-validation.json")))
        assertNull("Recovery copies must contain no unfinished capture", store.readPending())
        assertEquals("Recovery must preserve exactly the copied sessions", fixture.sources.map { it.sessionId }.toSet(),
            store.listSessions().map { it.sessionId }.toSet())
        fixture.sources.forEach { source ->
            val session = requireNotNull(store.read(source.sessionId))
            val reference = requireNotNull(session.reference)
            assertEquals(ReferenceStatus.VALID, reference.status)
            assertEquals(source.expectedSteps, reference.steps)
            assertTrue("Only complete real source files belong to this case",
                requireNotNull(session.localData).files.let { it.isNotEmpty() && it.none { file -> file.simulated } })
            val archive = File(directory, "packages/${source.sessionId}/ringfitness-session-${source.sessionId}.zip")
            assertTrue("The source package must already be frozen", archive.isFile)
            assertEquals("The ZIP must match the original package", source.archiveSha256, sha256(archive))
            // freeze verifies an existing package against the journal, all raw hashes, CRCs,
            // acquisition metadata and sidecars. The existence assertion prevents new packaging.
            val verified = FreeLivingSessionPackage(directory, store).freeze(session)
            assertEquals(source.archiveSha256, verified.sha256)
        }
    }

    private fun requireNoBleOwner() {
        check(Build.VERSION.SDK_INT >= 31) { "This permission-isolation case requires Android API 31 or later" }
        listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN).forEach { permission ->
            assertEquals("Keep BLE permissions denied in the recovery installation", PackageManager.PERMISSION_DENIED,
                context.checkSelfPermission(permission))
        }
        instrumentation.runOnMainSync {
            assertFalse("Upload recovery must not acquire a collection service owner", RealCollectionBridge.isRunning())
        }
    }

    private fun requireOffline() {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val connected = connectivity.allNetworks.any { network ->
            connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
        assertFalse("The host must disable Wi-Fi and mobile data before this scheduling check", connected)
        assertTrue("Wait for previous transport work to stop before this scheduling check",
            store.listSessions().none { RealUploadScheduler.isInFlight(it.sessionId) })
    }

    private fun uploadJobs() = scheduler.allPendingJobs.filter { it.service == component }

    private fun cancelUploadJobs() {
        uploadJobs().forEach { scheduler.cancel(it.id) }
        await("The previous isolated upload job must be cancelled") { uploadJobs().isEmpty() }
    }

    private fun withLauncher(checks: () -> Unit) {
        val restored = AtomicBoolean(false)
        var activity: Activity? = null
        RealUploadScheduler.observe { restored.set(true) }.use {
            try {
                val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
                assertEquals("Exercise the normal preparation launcher", StepPreparationActivity::class.java.name,
                    intent.component?.className)
                activity = instrumentation.startActivitySync(intent)
                await("The launch-time restore must finish") { restored.get() }
                instrumentation.waitForIdleSync()
                checks()
            } finally {
                activity?.let { opened -> instrumentation.runOnMainSync { opened.finish() } }
                instrumentation.waitForIdleSync()
            }
        }
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!predicate()) {
            if (SystemClock.elapsedRealtime() >= deadline) fail(message)
            SystemClock.sleep(50)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val size = stream.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
