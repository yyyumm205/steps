package com.nexthci.ringfitness

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreparationInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun independentLauncherOpensAndRecreatesWithoutStartingCapture() = withIsolatedFiles {
        assertEquals(BuildConfig.APPLICATION_ID, context.packageName)
        val launch = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        assertEquals(StepPreparationActivity::class.java.name, launch.component?.className)
        ActivityScenario.launch<StepPreparationActivity>(launch).use { scenario ->
            repeat(2) { index ->
                if (index == 1) scenario.recreate()
                awaitPreparationLoaded(scenario) { activity ->
                    val views = descendants(activity.findViewById(android.R.id.content)).filter { it.isShown }.toList()
                    assertTrue("Expected the registration heading after launcher pass $index; visible text: " +
                        views.filterIsInstance<TextView>().joinToString { "${it.tag}='${it.text}'" },
                        views.filterIsInstance<TextView>().any {
                            it.tag == "heading" && it.text.toString() == "开始使用"
                        })
                    assertFalse(views.filterIsInstance<Button>().any {
                        it.isEnabled && (it.text.startsWith("开始采集") || it.text.contains("登录") || it.text.contains("Oura"))
                    })
                }
            }
        }
    }

    private fun awaitPreparationLoaded(
        scenario: ActivityScenario<StepPreparationActivity>,
        assertions: (StepPreparationActivity) -> Unit,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var lastState = "No activity state observed"
        do {
            var loaded = false
            scenario.onActivity { activity ->
                val content = activity.findViewById<View>(android.R.id.content)
                val primary = content.findViewWithTag<Button>("primary")
                val progress = content.findViewWithTag<View>("loading_state")
                val heading = content.findViewWithTag<TextView>("heading")
                val progressStatus = content.findViewWithTag<TextView>("loading_status")
                lastState = "focus=${activity.hasWindowFocus()}, attached=${content.isAttachedToWindow}, " +
                    "laidOut=${content.isLaidOut}, heading='${heading?.text}' shown=${heading?.isShown}, " +
                    "primary='${primary?.text}' visibility=${primary?.visibility} enabled=${primary?.isEnabled}, " +
                    "progress=${progress?.visibility} '${progressStatus?.text}'"
                // Loading retains the business label (e.g. 下一步) on a hidden, disabled
                // primary action. Wait for its rendered state, including after recreation.
                loaded = content.isAttachedToWindow && content.isLaidOut && activity.hasWindowFocus() &&
                    heading?.isShown == true && primary?.isShown == true && primary.isEnabled &&
                    progress?.visibility == View.GONE
                // Keep the readiness check and assertions in the same main-thread callback.
                if (loaded) assertions(activity)
            }
            if (loaded) return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Preparation page did not become visible and ready within 10 seconds: $lastState")
    }

    @Test
    fun androidPrivateFilesystemRestoresAtomicUpdatesAndPreservesCorruptRecord() {
        // A dedicated temporary directory keeps real preparation details untouched.
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "preparation-test-").toFile()
        try {
            val file = File(directory, "profile.properties")
            val store = PreparationStore(file)
            val identity = store.register("TEST001")
            store.savePlacement(RingPlacement.RIGHT_RING)
            val ring = PreparedRing("AA:BB:CC:DD:EE:01", "Ringo test")
            store.selectRing(ring)
            assertEquals(identity.copy(placement = RingPlacement.RIGHT_RING, ring = ring), PreparationStore(file).read())
            file.writeText("version=1\n", Charsets.UTF_8)
            assertEquals(identity.copy(placement = RingPlacement.RIGHT_RING, ring = ring), PreparationStore(file).read())
            assertEquals("version=1\n", File(directory, "profile.properties.damaged").readText(Charsets.UTF_8))
            file.writeText("version=1\n", Charsets.UTF_8)
            File(directory, "profile.properties.last-good").writeText("version=1\n", Charsets.UTF_8)
            assertThrows(IOException::class.java) { PreparationStore(file).register("TEST002") }
            assertEquals("version=1\n", file.readText(Charsets.UTF_8))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun withIsolatedFiles(test: () -> Unit) {
        assumeTrue(
            Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("emulator") ||
                Build.MODEL.contains("Android SDK") || Build.PRODUCT.contains("sdk"),
        )
        waitForPreparationDisk()
        val roots = listOf(File(context.filesDir, "preparation"), File(context.filesDir, "collection-real"))
        val backup = File(context.cacheDir, "preparation-launcher-backup-${System.nanoTime()}")
        check(backup.mkdirs())
        roots.forEachIndexed { index, root ->
            if (root.exists()) check(root.copyRecursively(File(backup, index.toString()), true))
        }
        roots.forEach { if (it.exists()) check(it.deleteRecursively()) }
        try {
            test()
        } finally {
            waitForPreparationDisk()
            roots.forEach { if (it.exists()) check(it.deleteRecursively()) }
            roots.forEachIndexed { index, root ->
                val saved = File(backup, index.toString())
                if (saved.exists()) check(saved.copyRecursively(root, true))
            }
            check(backup.deleteRecursively())
        }
    }

    private fun waitForPreparationDisk() {
        val disk = StepPreparationActivity::class.java.getDeclaredField("disk").apply { isAccessible = true }
            .get(null) as ExecutorService
        disk.submit {}.get(10, TimeUnit.SECONDS)
    }
}
