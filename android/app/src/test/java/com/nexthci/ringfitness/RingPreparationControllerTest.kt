package com.nexthci.ringfitness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RingPreparationControllerTest {
    private val firstRing = PreparedRing("AA:BB:CC:DD:EE:01", "Ringo one")
    private val secondRing = PreparedRing("AA:BB:CC:DD:EE:02", "Ringo two")

    @Test
    fun preparationWaitsForActualConnectionAndStatus() {
        val transport = FakeTransport()
        val controller = controller(transport)
        controller.connect(firstRing)
        assertTrue(controller.state.connecting)
        assertFalse(controller.state.canPrepare)
        assertTrue(transport.queries.isEmpty())
        transport.listener.onConnection("已连接", true)
        assertTrue(controller.state.connected)
        assertFalse(controller.state.canPrepare)
        transport.listener.onPacket(health())
        assertTrue(controller.state.canPrepare)
    }

    @Test
    fun readySendsOnlyThreeReadOnlyQueriesAndRepeatedReadyDoesNotRepeatThem() {
        val transport = FakeTransport()
        val controller = controller(transport)
        controller.connect(firstRing)
        transport.listener.onConnection("已连接", true)
        transport.listener.onConnection("仍然连接", true)
        assertEquals(listOf("battery", "info", "status"), transport.queries)
    }

    @Test
    fun runningCaptureIsReportedWithoutTakingItOver() {
        val transport = FakeTransport()
        val controller = readyController(transport)
        transport.listener.onPacket(health(collecting = true, sessionId = 123))
        assertFalse(controller.state.canPrepare)
        assertTrue(controller.state.message.contains("正在采集"))
        assertEquals(listOf("battery", "info", "status"), transport.queries)
    }

    @Test
    fun storedBytesOrRecordsBlockPreparationWithoutClaimingDownloadStatus() {
        val transport = FakeTransport()
        val controller = readyController(transport)
        transport.listener.onPacket(health(bytes = 32))
        assertFalse(controller.state.canPrepare)
        assertEquals("进入后自动保存戒指中的已有数据", controller.state.message)
        transport.listener.onPacket(health(records = 1))
        assertFalse(controller.state.canPrepare)
        assertFalse(controller.state.message.contains("未下载"))
    }

    @Test
    fun historicalNonzeroSessionIdDoesNotBlockIdleEmptyRing() {
        val transport = FakeTransport()
        val controller = readyController(transport)
        transport.listener.onPacket(health(sessionId = 123))
        assertTrue(controller.state.canPrepare)
    }

    @Test
    fun errorStatusAndMalformedCountsCannotBecomePrepared() {
        val transport = FakeTransport()
        val controller = readyController(transport)
        transport.listener.onPacket(health(errorCode = 4))
        assertFalse(controller.state.canPrepare)
        assertTrue(controller.state.message.contains("错误码 4"))
        transport.listener.onPacket(health(bytes = -1))
        assertFalse(controller.state.canPrepare)
        transport.listener.onPacket(health(records = -1))
        assertFalse(controller.state.canPrepare)
    }

    @Test
    fun disconnectClearsLiveMetadataAndIgnoresLateReplies() {
        val transport = FakeTransport()
        val controller = readyController(transport)
        fillMetadata(transport.listener)
        val oldListener = transport.listener
        controller.disconnect()
        oldListener.onConnection("旧连接", true)
        fillMetadata(oldListener)
        oldListener.onError("旧错误")
        assertEquals(firstRing, controller.state.ring)
        assertEquals("未连接", controller.state.message)
        assertCleared(controller.state)
    }

    @Test
    fun physicalDisconnectInvalidatesTheListenerAndRequiresFreshConnection() {
        val transport = FakeTransport()
        val controller = readyController(transport)
        fillMetadata(transport.listener)
        val oldListener = transport.listener
        oldListener.onConnection("蓝牙断开", false)
        oldListener.onPacket(health())
        oldListener.onConnection("旧连接恢复", true)
        assertEquals("蓝牙断开", controller.state.message)
        assertCleared(controller.state)
    }

    @Test
    fun switchingRingsDiscardsAllPreviousRingCallbacksAndTimeouts() {
        val transport = FakeTransport()
        val controller = readyController(transport)
        fillMetadata(transport.listener)
        val firstAttempt = controller.state.attemptId
        val oldListener = transport.listener
        controller.connect(secondRing)
        oldListener.onConnection("旧连接", true)
        fillMetadata(oldListener)
        oldListener.onConnection("旧断开", false)
        oldListener.onError("旧错误")
        controller.timeout(firstAttempt)
        assertEquals(secondRing, controller.state.ring)
        assertTrue(controller.state.connecting)
        assertNull(controller.state.healthStatus)
        assertNull(controller.state.batteryPercent)
        assertNull(controller.state.firmwareVersion)
        assertFalse(controller.state.queryTimedOut)
        transport.listener.onConnection("新连接", true)
        transport.listener.onPacket(health())
        assertTrue(controller.state.canPrepare)
    }

    @Test
    fun refreshRechecksSameRingWithoutReusingCachedSuccess() {
        val transport = FakeTransport()
        val controller = readyController(transport)
        fillMetadata(transport.listener)
        val oldListener = transport.listener
        val previousAttempt = controller.state.attemptId
        controller.refresh()
        assertEquals(firstRing, controller.state.ring)
        assertTrue(controller.state.attemptId > previousAttempt)
        assertTrue(controller.state.connecting)
        assertFalse(controller.state.canPrepare)
        assertNull(controller.state.batteryPercent)
        assertNull(controller.state.firmwareVersion)
        fillMetadata(oldListener)
        assertNull(controller.state.healthStatus)
        transport.listener.onConnection("刷新后已连接", true)
        transport.listener.onPacket(health())
        assertTrue(controller.state.canPrepare)
        assertEquals(listOf("battery", "info", "status", "battery", "info", "status"), transport.queries)
    }

    @Test
    fun connectionTimeoutKeepsSelectionAndRejectsLateReady() {
        val transport = FakeTransport()
        val controller = controller(transport)
        controller.connect(firstRing)
        val listener = transport.listener
        controller.timeout(controller.state.attemptId)
        listener.onConnection("迟到的成功", true)
        fillMetadata(listener)
        assertEquals(firstRing, controller.state.ring)
        assertTrue(controller.state.queryTimedOut)
        assertCleared(controller.state)
    }

    @Test
    fun connectedTimeoutKeepsReceivedInformationButUnknownStatusBlocksPreparation() {
        val transport = FakeTransport()
        val controller = readyController(transport)
        transport.listener.onPacket(battery())
        controller.timeout(controller.state.attemptId)
        assertTrue(controller.state.connected)
        assertEquals(68, controller.state.batteryPercent)
        assertTrue(controller.state.queryTimedOut)
        assertFalse(controller.state.canPrepare)
    }

    @Test
    fun partialTimeoutWithConfirmedIdleStatusPreservesItsMeaning() {
        val transport = FakeTransport()
        val controller = readyController(transport)
        transport.listener.onPacket(health())
        controller.timeout(controller.state.attemptId)
        assertTrue(controller.state.queryTimedOut)
        assertTrue(controller.state.canPrepare)
        assertNull(controller.state.firmwareVersion)
    }

    @Test
    fun partialTimeoutDoesNotHideExistingRecordingWarning() {
        val transport = FakeTransport()
        val controller = readyController(transport)
        transport.listener.onPacket(health(collecting = true))
        controller.timeout(controller.state.attemptId)
        assertFalse(controller.state.canPrepare)
        assertTrue(controller.state.message.contains("正在采集"))
        assertTrue(controller.state.queryTimedOut)
    }

    @Test
    fun timeoutAfterAllRepliesIsIgnored() {
        val transport = FakeTransport()
        val controller = readyController(transport)
        fillMetadata(transport.listener)
        val completed = controller.state
        controller.timeout(completed.attemptId)
        assertEquals(completed, controller.state)
    }

    @Test
    fun lateRepliesClearTimeoutButKeepExistingRecordingWarning() {
        val transport = FakeTransport()
        val controller = readyController(transport)
        transport.listener.onPacket(health(collecting = true))
        controller.timeout(controller.state.attemptId)
        assertTrue(controller.state.queryTimedOut)
        transport.listener.onPacket(battery())
        assertTrue(controller.state.queryTimedOut)
        transport.listener.onPacket(SensorPacket.Info(1, 1, 0, 5, 3, 0, 1, 1))
        assertFalse(controller.state.queryTimedOut)
        assertFalse(controller.state.message.contains("超时"))
        assertTrue(controller.state.message.contains("正在采集"))
        assertFalse(controller.state.canPrepare)
    }

    @Test
    fun connectionFailureAllowsRetryWithoutStaleReadyState() {
        val transport = FakeTransport().apply { acceptConnection = false }
        val controller = controller(transport)
        controller.connect(firstRing)
        assertCleared(controller.state)
        transport.acceptConnection = true
        controller.refresh()
        transport.listener.onConnection("重试成功", true)
        transport.listener.onPacket(health())
        assertTrue(controller.state.canPrepare)
    }

    @Test
    fun transportErrorClearsPreviouslyConfirmedState() {
        val transport = FakeTransport()
        val controller = readyController(transport)
        fillMetadata(transport.listener)
        transport.listener.onError("蓝牙权限已收回")
        assertEquals("蓝牙权限已收回", controller.state.message)
        assertCleared(controller.state)
    }

    @Test
    fun failedQueryStopsSubsequentQueriesAndClearsState() {
        val transport = FakeTransport().apply { rejectedQuery = "info" }
        val controller = readyController(transport)
        assertEquals(listOf("battery", "info"), transport.queries)
        assertCleared(controller.state)
    }

    @Test
    fun unsolicitedStoredDataDoesNotTriggerDownloadOrAffectPreparation() {
        val transport = FakeTransport()
        val controller = readyController(transport)
        val before = controller.state
        transport.listener.onPacket(SensorPacket.Health(HealthMessage.ListEnd(1), 1))
        transport.listener.onPacket(SensorPacket.Health(HealthMessage.DataChunk(0, byteArrayOf(1)), 1))
        assertEquals(before, controller.state)
        assertEquals(listOf("battery", "info", "status"), transport.queries)
    }

    @Test
    fun newControllerStartsWithoutPersistedConnectionClaims() {
        val transport = FakeTransport()
        val original = readyController(transport)
        fillMetadata(transport.listener)
        assertTrue(original.state.canPrepare)
        val restarted = controller(FakeTransport())
        assertNull(restarted.state.ring)
        assertCleared(restarted.state)
    }

    private fun controller(transport: FakeTransport) = RingPreparationController(transport) {}

    private fun readyController(transport: FakeTransport): RingPreparationController = controller(transport).apply {
        connect(firstRing)
        transport.listener.onConnection("已连接", true)
    }

    private fun fillMetadata(listener: PreparationTransport.Listener) {
        listener.onPacket(battery())
        listener.onPacket(SensorPacket.Info(1, 1, 0, 5, 3, 0, 1, 1))
        listener.onPacket(health())
    }

    private fun battery() = SensorPacket.Battery(3900, 68, null, 1)

    private fun health(
        collecting: Boolean = false,
        bytes: Long = 0,
        records: Long = 0,
        errorCode: Int = 0,
        sessionId: Int = 0,
    ) = SensorPacket.Health(HealthMessage.Status(collecting, bytes, records, errorCode, sessionId), 1)

    private fun assertCleared(state: RingPreparationState) {
        assertFalse(state.connected)
        assertFalse(state.connecting)
        assertFalse(state.canPrepare)
        assertNull(state.batteryPercent)
        assertNull(state.firmwareVersion)
        assertNull(state.healthStatus)
    }

    private class FakeTransport : PreparationTransport {
        lateinit var listener: PreparationTransport.Listener
        var acceptConnection = true
        var rejectedQuery: String? = null
        val queries = mutableListOf<String>()

        override fun connect(ring: PreparedRing, listener: PreparationTransport.Listener): Boolean {
            this.listener = listener
            return acceptConnection
        }

        override fun disconnect() = Unit
        override fun requestBattery() = query("battery")
        override fun requestDeviceInfo() = query("info")
        override fun requestHealthStatus() = query("status")

        private fun query(name: String): Boolean {
            queries += name
            return name != rejectedQuery
        }
    }
}
