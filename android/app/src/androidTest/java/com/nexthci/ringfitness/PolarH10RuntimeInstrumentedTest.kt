package com.nexthci.ringfitness

import android.Manifest
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Loads the real Polar AAR without scanning, connecting or opening any session files. */
@RunWith(AndroidJUnit4::class)
class PolarH10RuntimeInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun polarSdkInitializesResetsAndShutsDownOnTheMainThread() {
        assumeTrue("This runtime smoke test runs only on an Android emulator", isEmulator())
        val automation = instrumentation.uiAutomation
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                automation.adoptShellPermissionIdentity(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                )
            } else {
                automation.adoptShellPermissionIdentity(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            onMain {
                assertFalse("Run the SDK smoke test without an active collection owner", RealCollectionBridge.isRunning())
                val published = AtomicReference<PolarH10State?>()
                var client: PolarH10Client? = null
                try {
                    val active = PolarH10Client(instrumentation.targetContext) { published.set(it) }
                    client = active
                    assertEquals(PolarH10State(), active.snapshot())

                    active.beginFreshSelection()

                    val fresh = PolarH10State(message = "")
                    assertEquals(fresh, active.snapshot())
                    assertEquals(fresh, published.get())
                    active.shutdown()
                } finally {
                    // Also covers assertion failures and verifies idempotent normal shutdown.
                    client?.shutdown()
                }
            }
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    /** Keep linkage failures and assertions on the test thread, outside Android's main loop. */
    private fun <T> onMain(action: () -> T): T {
        val result = AtomicReference<Result<T>?>()
        instrumentation.runOnMainSync { result.set(runCatching(action)) }
        return requireNotNull(result.get()).getOrThrow()
    }

    private fun isEmulator(): Boolean = Build.HARDWARE in setOf("ranchu", "goldfish") ||
        Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
        Build.MODEL.contains("sdk_gphone", ignoreCase = true)
}
