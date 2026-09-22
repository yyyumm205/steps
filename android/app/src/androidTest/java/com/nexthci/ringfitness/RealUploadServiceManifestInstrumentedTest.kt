package com.nexthci.ringfitness

import android.app.job.JobService
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RealUploadServiceManifestInstrumentedTest {
    @Test fun uploadJobDeclaresPrivateDataSyncForegroundProtection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val service = context.packageManager.getServiceInfo(
            ComponentName(context, RealUploadService::class.java),
            PackageManager.GET_META_DATA,
        )

        assertEquals(JobService.PERMISSION_BIND, service.permission)
        assertFalse(service.exported)
        assertTrue(service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0)
    }

    @Test fun missingPersistedJobMakesQueuedImmediateUploadRetryable() {
        assumeTrue("JobScheduler mutation is restricted to the isolated recovery QA installation",
            BuildConfig.APPLICATION_ID.endsWith(".recoveryqa"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "upload-scheduler-${UUID.randomUUID()}").apply { check(mkdir()) }
        val wrapped = object : ContextWrapper(context) { override fun getFilesDir() = root }
        val directory = File(root, "collection-real").apply { check(mkdir()) }
        val store = FreeLivingSessionStore(File(directory, "session.json"))
        val scheduler = context.getSystemService(JobScheduler::class.java)
        try {
            val now = 1_789_804_800_000L
            val profile = PreparationSnapshot("upload001", UUID.randomUUID().toString(), RingPlacement.RIGHT_INDEX,
                PreparedRing("AA:BB:CC:DD:EE:07", "Upload fixture"))
            val requested = store.requestStart(profile, now, "Asia/Shanghai", activity = SessionActivity.WALKING)
            val id = requested.sessionId
            store.confirmStart(id, profile.ring!!.address, HealthMessage.Status(true, 20, 1, 0, 7), now + 1)
            store.requestStop(id, now + 2)
            store.confirmStop(id, profile.ring.address, HealthMessage.Status(false, 20, 1, 0, 7), now + 3)
            store.finalizeStoppedSession(id, CompletionPolicy.SAVE_UPLOAD,
                SessionReference(ReferenceStatus.VALID, 12, now + 4))
            val raw = File(directory, "$id-ring-7.rfbin").apply { writeText("scheduler fixture") }
            val sha = MessageDigest.getInstance("SHA-256").digest(raw.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            store.completeLocalData(id, listOf(SessionRawFile(raw.name, 7, raw.length(), sha)), now + 5)
            assertTrue("A locally complete request with no durable task can be queued again",
                RealUploadScheduler.canRetryUpload(wrapped, id))
            assertTrue(RealUploadQueue(directory, store).enqueue(id,
                "https://cloud.tsinghua.edu.cn/u/d/fixture/", retry = false))

            scheduler.cancel(RealUploadScheduler.JOB_ID)
            store.markTransferStarted(id)
            assertEquals(SessionTransferStatus.TRANSFERRING, store.read(id)!!.transfer.status)
            assertTrue("A queued task remains replayable after its local transfer claim",
                RealUploadScheduler.canRetryUpload(wrapped, id))

            val taskFile = File(directory, "upload-tasks/$id.json")
            val gson = Gson()
            val queued = gson.fromJson(taskFile.readText(), RealUploadTask::class.java)
            taskFile.writeText(gson.toJson(queued.copy(
                state = "sending", dispatchStarted = true, payloadStarted = false)))
            assertTrue("A v3 request interrupted before its body starts can be safely replayed",
                RealUploadScheduler.canRetryUpload(wrapped, id))

            val pending = JobInfo.Builder(RealUploadScheduler.JOB_ID,
                ComponentName(context, RealUploadService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(24 * 60 * 60 * 1_000L)
                .build()
            assertEquals(JobScheduler.RESULT_SUCCESS, scheduler.schedule(pending))
            assertFalse(RealUploadScheduler.canRetryUpload(wrapped, id))
            scheduler.cancel(RealUploadScheduler.JOB_ID)
            assertTrue(RealUploadScheduler.canRetryUpload(wrapped, id))

            taskFile.writeText(gson.toJson(queued.copy(
                state = "sending", dispatchStarted = true, payloadStarted = true)))
            assertFalse("A request whose body started must not be replayed",
                RealUploadScheduler.canRetryUpload(wrapped, id))
            taskFile.writeText(gson.toJson(queued.copy(failureStage = "outcome")))
            assertFalse("An uncertain server outcome requires review",
                RealUploadScheduler.canRetryUpload(wrapped, id))
            taskFile.writeText(gson.toJson(queued.copy(failureStage = "destination")))
            assertFalse("A rejected destination must not be replayed",
                RealUploadScheduler.canRetryUpload(wrapped, id))
            taskFile.writeText(gson.toJson(queued.copy(
                receipt = RemoteSessionReceipt("bad.zip", "bad", 1), receivedAtMs = null)))
            assertFalse("An inconsistent receipt must not be replayed",
                RealUploadScheduler.canRetryUpload(wrapped, id))
        } finally {
            scheduler.cancel(RealUploadScheduler.JOB_ID)
            root.deleteRecursively()
        }
    }
}
