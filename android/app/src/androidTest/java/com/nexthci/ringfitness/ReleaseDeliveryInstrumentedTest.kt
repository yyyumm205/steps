package com.nexthci.ringfitness

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Explicit opt-in, read-only inspection of the real installation across a signing upgrade. */
@RunWith(AndroidJUnit4::class)
class ReleaseDeliveryInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val snapshot get() = File(instrumentation.targetContext.cacheDir, "release-upgrade-snapshot.json")

    @Test fun snapshotBeforeUpgrade() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyReleaseDelivery") == "true")
        val hashes = snapshotHashes()
        assertTrue("There must be existing files to verify an in-place upgrade", hashes.isNotEmpty())
        snapshot.parentFile!!.mkdirs()
        snapshot.writeText(JSONObject(hashes).toString())
    }

    @Test fun releaseRetainsFilesAndOmitsDevelopmentEntry() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyReleaseDelivery") == "true")
        val context = instrumentation.targetContext
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals("0.8.8-ring-defer", packageInfo.versionName)
        assertEquals(50L, packageInfo.longVersionCode)
        assertNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        assertThrows(android.content.pm.PackageManager.NameNotFoundException::class.java) {
            context.packageManager.getActivityInfo(ComponentName(context.packageName,
                "com.nexthci.ringfitness.DemoCollectionActivity"), 0)
        }
        val before = JSONObject(snapshot.readText())
        val after = snapshotHashes()
        assertEquals(before.keys().asSequence().toSet(), after.keys)
        before.keys().forEach { name -> assertEquals("Upgrade must preserve $name", before.getString(name), after[name]) }
    }

    @Test fun exportRealSessionEvidence() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("verifyReleaseDelivery") == "true")
        val context = instrumentation.targetContext
        val directory = File(context.filesDir, "collection-real")
        val session = requireNotNull(FreeLivingSessionStore(File(directory, "session.json")).read())
        args.getString("expectedSteps")?.let { assertEquals(it.toLong(), session.reference?.steps) }
        args.getString("expectedPolicy")?.let { assertEquals(it, session.completionPolicy?.wireValue) }
        args.getString("expectedDownload")?.let { assertEquals(it.toBoolean(), session.localData != null) }
        val destination = File(context.getExternalFilesDir(null), "release-qa-evidence.zip")
        ZipOutputStream(destination.outputStream()).use { zip ->
            context.filesDir.walkTopDown().filter { it.isFile }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(context.filesDir).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("session_id", session.sessionId)
            putString("completion_policy", session.completionPolicy?.wireValue)
            putString("reference_steps", session.reference?.steps?.toString())
            putString("local_complete", (session.localData != null).toString())
            putString("upload_status", session.transfer.status.wireValue)
            putString("evidence_path", destination.absolutePath)
        })
    }

    private fun snapshotHashes(): Map<String, String> {
        val root = instrumentation.targetContext.filesDir
        return root.walkTopDown().filter { it.isFile }.associate { file ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            file.relativeTo(root).invariantSeparatorsPath to digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
