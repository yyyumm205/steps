package com.nexthci.ringfitness

import android.content.Context
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreparationInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun independentLauncherOpensAndRecreatesWithoutStartingCapture() {
        assertEquals("com.nexthci.ringfitness.steps", context.packageName)
        val launch = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        assertEquals(StepPreparationActivity::class.java.name, launch.component?.className)
        ActivityScenario.launch<StepPreparationActivity>(launch).use { scenario ->
            repeat(2) { index ->
                if (index == 1) scenario.recreate()
                awaitPreparationLoaded(scenario)
                scenario.onActivity { activity ->
                    val views = descendants(activity.findViewById(android.R.id.content)).filter { it.isShown }.toList()
                    assertTrue(views.filterIsInstance<TextView>().any {
                        it.tag == "heading" && it.text.contains("步数采集")
                    })
                    assertFalse(views.filterIsInstance<Button>().any {
                        it.isEnabled && (it.text.startsWith("开始采集") || it.text.contains("登录") || it.text.contains("Oura"))
                    })
                }
            }
        }
    }

    private fun awaitPreparationLoaded(scenario: ActivityScenario<StepPreparationActivity>) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            var loaded = false
            scenario.onActivity { activity ->
                val primary = activity.findViewById<View>(android.R.id.content).findViewWithTag<Button>("primary")
                loaded = primary != null && primary.text.toString() !in listOf("正在读取…", "正在保存…")
            }
            if (loaded) return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Preparation page did not finish reading its profile within 10 seconds")
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
}
