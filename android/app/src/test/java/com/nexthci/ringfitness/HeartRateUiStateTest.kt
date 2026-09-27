package com.nexthci.ringfitness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HeartRateUiStateTest {
    private val recording = PolarUiState(selectedDeviceId = "H10-TEST", connected = true,
        hrReady = true, recording = true, lastHeartRate = 72)

    @Test fun disabledHeartRateHidesAStaleSample() {
        assertEquals("未启用", heartRateStatusLabel(false, recording, captureActive = true))
        assertEquals("未启用", heartRateStatusLabel(false,
            recording.copy(storageError = "上次保存失败", operationError = "上次操作失败")))
    }

    @Test fun storageFailureRemainsVisibleAboveTransportAndSampleStates() {
        val error = "心率数据保存中断，戒指采集继续；请检查手机空间"
        val failed = recording.copy(storageError = error, operationError = "请重新连接")
        listOf(
            failed,
            failed.copy(scanning = true),
            failed.copy(connecting = true),
            failed.copy(connected = false, hrReady = false),
        ).forEach { state ->
            assertEquals(error, heartRateStatusLabel(true, state, captureActive = true))
            assertFalse(heartRateStatusLabel(true, state, captureActive = true).contains("72"))
        }
    }

    @Test fun recoverablePreparationFailureRemainsVisibleUntilRetryClearsIt() {
        val error = "心率带暂时无法启用，请重试或关闭心率带"
        val failed = recording.copy(operationError = error)
        assertEquals(error, heartRateStatusLabel(true, failed))
        assertEquals(error, heartRateStatusLabel(true, failed.copy(scanning = true)))
        assertEquals(error, heartRateStatusLabel(true, failed.copy(connecting = true)))
        assertEquals("Polar H10 已就绪",
            heartRateStatusLabel(true, failed.copy(operationError = null, recording = false)))
    }

    @Test fun emptySearchAndDiscoveredDevicesProvideTheNextAction() {
        val emptySearch = PolarUiState(message = "没有发现 Polar H10")
        assertEquals("未发现 Polar H10，请靠近心率带后重新搜索",
            heartRateStatusLabel(true, emptySearch))
        assertEquals("正在搜索 Polar H10…",
            heartRateStatusLabel(true, emptySearch.copy(scanning = true)))
        val discovered = PolarUiState(
            devices = listOf(PolarUiDevice("H10-TEST", "Polar H10", -45)),
            message = "请选择 Polar H10",
        )
        assertEquals("请选择下方的 Polar H10", heartRateStatusLabel(true, discovered))
    }

    @Test fun sdkPreparationFailuresUseStableUserFacingPrompts() {
        val failedSearch = PolarUiState(scanning = true,
            message = "搜索 H10 失败：java.lang.SecurityException: BLUETOOTH_SCAN denied")
        assertEquals("搜索失败，请检查蓝牙和权限后重试",
            heartRateStatusLabel(true, failedSearch))
        assertEquals("搜索失败，请检查蓝牙和权限后重试",
            heartRateStatusLabel(true, failedSearch.copy(scanning = false)))
        val failedConnection = PolarUiState(selectedDeviceId = "H10-TEST",
            message = "连接 H10 失败：SDK internal error")
        assertEquals("连接失败，请重试或重新搜索", heartRateStatusLabel(true, failedConnection))
        assertEquals("连接超时，请靠近心率带后重试", heartRateStatusLabel(true,
            failedConnection.copy(message = "连接 H10 失败：等待心率服务超时")))
        assertEquals("心率服务暂不可用，请重新连接", heartRateStatusLabel(true,
            failedConnection.copy(message = "连接 H10 失败：心率服务不可用")))
        assertEquals("心率带连接已中断，等待恢复",
            heartRateStatusLabel(true, failedConnection, captureActive = true))
        assertEquals("请搜索并连接 Polar H10",
            heartRateStatusLabel(true, PolarUiState(message = "Unexpected SDK diagnostic")))
    }

    @Test fun disconnectedPreparationAsksForAConnectionWithoutClaimingRecovery() {
        val disconnected = recording.copy(connected = false, hrReady = false)
        assertEquals("连接已中断，请重新连接", heartRateStatusLabel(true, disconnected))
        assertFalse(heartRateStatusLabel(true, disconnected).contains("72"))
    }

    @Test fun captureDisconnectionAndReconnectTakePriorityOverTheLastSample() {
        val disconnected = recording.copy(connected = false, hrReady = false)
        assertEquals("心率带连接已中断，等待恢复",
            heartRateStatusLabel(true, disconnected, captureActive = true))
        assertEquals("心率带正在重连…",
            heartRateStatusLabel(true, disconnected.copy(connecting = true), captureActive = true))
    }

    @Test fun connectedServiceAndStreamMustBeReadyBeforeShowingCurrentHeartRate() {
        assertEquals("已连接，等待 HR/RR 就绪",
            heartRateStatusLabel(true, recording.copy(hrReady = false), captureActive = true))
        assertEquals("已连接，等待 HR/RR 数据",
            heartRateStatusLabel(true, recording.copy(recording = false), captureActive = true))
        assertEquals("72 bpm · 正在采集", heartRateStatusLabel(true, recording, captureActive = true))
    }

    @Test fun preparationShowsReadyOnlyAfterBothConnectionAndHrService() {
        assertEquals("请搜索并连接 Polar H10", heartRateStatusLabel(true, PolarUiState()))
        assertEquals("正在搜索 Polar H10…", heartRateStatusLabel(true, PolarUiState(scanning = true)))
        assertEquals("正在连接 Polar H10…",
            heartRateStatusLabel(true, PolarUiState(selectedDeviceId = "H10-TEST", connecting = true)))
        assertEquals("Polar H10 已就绪", heartRateStatusLabel(true, recording.copy(recording = false)))
    }
}
