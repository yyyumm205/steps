package com.nexthci.ringfitness

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in: only reads the already selected ring; never starts/stops or downloads data. */
@RunWith(AndroidJUnit4::class)
class PreparationBleInstrumentedTest {
    @Test
    fun repeatedConnectionsReadTheSameRingWithoutChangingStoredPreparation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Requires an authorized real ring and existing BLE permissions",
            InstrumentationRegistry.getArguments().getString("verifyPreparedRing") == "true")
        val context = instrumentation.targetContext
        val profile = File(context.filesDir, "preparation/profile.properties")
        val before = profile.readBytes()
        val ring = requireNotNull(PreparationStore(profile).read()?.ring)
        var initialStatus: HealthMessage.Status? = null
        repeat(5) { index ->
            val completed = CountDownLatch(1)
            var result: RingPreparationState? = null
            lateinit var controller: RingPreparationController
            instrumentation.runOnMainSync {
                controller = RingPreparationController(AndroidPreparationTransport(context)) { state ->
                    if (state.connected && state.batteryPercent != null && state.firmwareVersion != null && state.healthStatus != null) {
                        result = state
                        completed.countDown()
                    }
                }
                controller.connect(ring)
            }
            try {
                assertTrue("Cycle ${index + 1}: all three replies within 30 seconds", completed.await(30, TimeUnit.SECONDS))
                val state = requireNotNull(result)
                assertEquals(ring, state.ring)
                assertNotNull(state.firmwareVersion)
                val status = requireNotNull(state.healthStatus)
                // This test observes an idle ring; it never changes its recording state.
                assertTrue("Use an idle ring for this preparation check", !status.collecting)
                assertEquals(0, status.errorCode)
                if (initialStatus == null) initialStatus = status else assertEquals(initialStatus, status)
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("stream", "Cycle ${index + 1}/5: battery=${state.batteryPercent}, firmware=${state.firmwareVersion}, idle=true, bytes=${status.bytes}, records=${status.records}\n")
                })
            } finally {
                instrumentation.runOnMainSync { controller.disconnect() }
            }
        }
        assertArrayEquals(before, profile.readBytes())
    }
}
