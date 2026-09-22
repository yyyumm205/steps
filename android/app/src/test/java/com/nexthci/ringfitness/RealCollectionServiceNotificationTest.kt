package com.nexthci.ringfitness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealCollectionServiceNotificationTest {
    @Test fun downloadProgressKeepsOneNotificationSemantic() {
        val gate = CollectionNotificationGate()
        val initial = CollectionFlowState(
            isSimulation = false,
            taskPage = CollectionPage.DOWNLOADING,
            downloadSavedBytes = 0,
            downloadTotalBytes = 16L * 1024 * 1024,
        )

        assertEquals("正在保存戒指数据", collectionForegroundText(initial))
        assertTrue(gate.shouldPublish(collectionForegroundText(initial)))
        repeat(1_000) { index ->
            val progress = initial.copy(downloadSavedBytes = (index + 1L) * 16 * 1024)
            assertEquals("正在保存戒指数据", collectionForegroundText(progress))
            assertFalse(gate.shouldPublish(collectionForegroundText(progress)))
        }
    }

    @Test fun semanticTransitionsPublishImmediatelyAndThenDeduplicate() {
        val gate = CollectionNotificationGate()
        gate.record("正在连接戒指")

        val connecting = CollectionFlowState(isSimulation = false, connecting = true, connected = false)
        assertFalse(gate.shouldPublish(collectionForegroundText(connecting)))

        val collecting = connecting.copy(
            connecting = false,
            connected = true,
            taskPage = CollectionPage.COLLECTING,
        )
        assertTrue(gate.shouldPublish(collectionForegroundText(collecting)))
        assertFalse(gate.shouldPublish(collectionForegroundText(collecting)))

        val downloading = collecting.copy(taskPage = CollectionPage.DOWNLOADING)
        assertTrue(gate.shouldPublish(collectionForegroundText(downloading)))
        assertFalse(gate.shouldPublish(collectionForegroundText(downloading)))
    }
}
