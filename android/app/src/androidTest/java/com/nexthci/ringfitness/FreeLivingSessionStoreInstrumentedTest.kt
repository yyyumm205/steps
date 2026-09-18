package com.nexthci.ringfitness

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FreeLivingSessionStoreInstrumentedTest {
    @Test fun productionAtomicWriteAndDirectorySyncWorkOnAndroidPrivateStorage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "session-storage-test-${UUID.randomUUID()}.json")
        val preparation = PreparationSnapshot("qat001", UUID.randomUUID().toString(), RingPlacement.LEFT_INDEX,
            PreparedRing("AA:BB:CC:DD:EE:01", "Synthetic storage test ring"))
        val address = requireNotNull(preparation.ring).address
        val now = System.currentTimeMillis()
        try {
            val requested = FreeLivingSessionStore(file).requestStart(preparation, now, "Asia/Shanghai")
            val collecting = FreeLivingSessionStore(file).confirmStart(requested.sessionId, address,
                HealthMessage.Status(true, 0, 0, 0, 41), now + 100)
            assertEquals(collecting, FreeLivingSessionStore(file).read())
            FreeLivingSessionStore(file).requestStop(requested.sessionId, now + 1_000)
            val ended = FreeLivingSessionStore(file).confirmStop(requested.sessionId, address,
                HealthMessage.Status(false, 128, 1, 0, 41), now + 1_100)
            assertEquals(ended, FreeLivingSessionStore(file).read())
            assertEquals(FreeLivingSessionPhase.AWAITING_REFERENCE, ended.phase)
            assertNull(ended.startedAtMs)
            assertNull(ended.endedAtMs)
        } finally {
            // Only this uniquely named synthetic test file is removed; no app settings are cleared.
            file.delete()
        }
    }
}
