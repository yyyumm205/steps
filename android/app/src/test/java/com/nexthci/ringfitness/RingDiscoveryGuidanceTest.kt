package com.nexthci.ringfitness

import org.junit.Assert.assertEquals
import org.junit.Test

class RingDiscoveryGuidanceTest {
    @Test fun installedLegacyAppProvidesTheSpecificRecoveryStep() {
        assertEquals(
            "未找到戒指。将戒指放入充电盒 10 秒后完全取出，并关闭曾连接它的其他手机蓝牙。" +
                "若旧版 RingFitness 正在使用戒指，请先结束并保存，再点击下方按钮到系统页强行停止。",
            RingDiscoveryGuidance.notFound(legacyAppInstalled = true),
        )
    }

    @Test fun absentLegacyAppStillCoversAnotherPhoneAndChargingCase() {
        assertEquals(
            "未找到戒指。将戒指放入充电盒 10 秒后完全取出，并关闭曾连接它的其他手机蓝牙，再重新搜索。",
            RingDiscoveryGuidance.notFound(legacyAppInstalled = false),
        )
    }

    @Test fun samePhoneConnectionNamesTheRingAndTheLegacyRecovery() {
        val environment = RingDiscoveryEnvironment(
            legacyAppInstalled = true,
            connectedRingNames = listOf("Ringo5422"),
        )
        assertEquals(
            "Ringo5422 正由本机其他 App 连接。请先在旧版 RingFitness 中结束并保存当前采集，" +
                "再点击下方按钮，并在系统页点“强行停止”。",
            RingDiscoveryGuidance.connectedElsewhere(environment),
        )
    }

    @Test fun samePhoneConnectionWithoutLegacyAppRemainsActionable() {
        val environment = RingDiscoveryEnvironment(
            legacyAppInstalled = false,
            connectedRingNames = listOf("Ringo8296"),
        )
        assertEquals(
            "Ringo8296 正由本机其他 App 连接。请先结束并保存当前采集，关闭该 App 后重试。",
            RingDiscoveryGuidance.connectedElsewhere(environment),
        )
    }

    @Test fun connectedGattFilteringUsesRingoNameAndKnownAddressOnly() {
        val known = PreparedRing("F7:DE:EB:C4:54:22", "Ringo5422")
        assertEquals(
            listOf("RINGO8296", "Ringo5422"),
            RingDiscoveryGuidance.ringNames(
                listOf(
                    ConnectedGattIdentity(null, null),
                    ConnectedGattIdentity("11:22:33:44:55:66", "Polar H10"),
                    ConnectedGattIdentity("22:33:44:55:66:77", "Headphones"),
                    ConnectedGattIdentity("33:44:55:66:77:88", "RINGO8296"),
                    ConnectedGattIdentity("f7:de:eb:c4:54:22", null),
                ),
                known,
            ),
        )
    }

    @Test fun scanFailuresGiveDeviceSpecificRecovery() {
        assertEquals("手机蓝牙扫描暂时不可用，请关闭并重新打开蓝牙后重试",
            RingDiscoveryGuidance.scanFailure(2))
        assertEquals("手机蓝牙资源正被其他应用占用，请关闭其他蓝牙应用后重试",
            RingDiscoveryGuidance.scanFailure(5))
        assertEquals("搜索操作过于频繁，请等待 30 秒后重试",
            RingDiscoveryGuidance.scanFailure(6))
    }
}
