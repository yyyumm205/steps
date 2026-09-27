package com.nexthci.ringfitness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HeartRateUiStateTest {
    private val recording = PolarUiState(selectedDeviceId = "H10-TEST", connected = true,
        hrReady = true, recording = true, lastHeartRate = 72)

    @Test fun disabledHeartRateHidesAStaleSample() {
        assertEquals("未启用", heartRateStatusLabel(false, recording, captureActive = true))
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
