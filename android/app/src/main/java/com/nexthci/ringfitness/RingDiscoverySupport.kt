package com.nexthci.ringfitness

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

internal data class RingDiscoveryEnvironment(
    val legacyAppInstalled: Boolean,
    val connectedRingNames: List<String>,
)

internal data class ConnectedGattIdentity(
    val address: String?,
    val name: String?,
)

internal interface RingDiscoveryAccess {
    fun inspect(context: Context, knownRing: PreparedRing?): RingDiscoveryEnvironment
    fun openLegacyAppSettings(context: Context): Boolean
}

internal object RingDiscoveryGuidance {
    fun ringNames(devices: List<ConnectedGattIdentity>, knownRing: PreparedRing?): List<String> = devices
        .mapNotNull { device ->
            when {
                device.name?.contains("Ringo", ignoreCase = true) == true -> device.name
                knownRing != null && device.address.equals(knownRing.address, ignoreCase = true) -> knownRing.name
                else -> null
            }
        }
        .distinct()
        .sorted()

    fun connectedElsewhere(environment: RingDiscoveryEnvironment): String {
        val ring = environment.connectedRingNames.firstOrNull() ?: "戒指"
        return if (environment.legacyAppInstalled) {
            "$ring 正由本机其他 App 连接。请先在旧版 RingFitness 中结束并保存当前采集，" +
                "再点击下方按钮，并在系统页点“强行停止”。"
        } else {
            "$ring 正由本机其他 App 连接。请先结束并保存当前采集，关闭该 App 后重试。"
        }
    }

    fun notFound(legacyAppInstalled: Boolean): String = if (legacyAppInstalled) {
        "未找到戒指。将戒指放入充电盒 10 秒后完全取出，并关闭曾连接它的其他手机蓝牙。" +
            "若旧版 RingFitness 正在使用戒指，请先结束并保存，再点击下方按钮到系统页强行停止。"
    } else {
        "未找到戒指。将戒指放入充电盒 10 秒后完全取出，并关闭曾连接它的其他手机蓝牙，再重新搜索。"
    }

    fun scanFailure(errorCode: Int): String = when (errorCode) {
        2, 3 -> "手机蓝牙扫描暂时不可用，请关闭并重新打开蓝牙后重试"
        4 -> "这部手机不支持所需的低功耗蓝牙搜索"
        5 -> "手机蓝牙资源正被其他应用占用，请关闭其他蓝牙应用后重试"
        6 -> "搜索操作过于频繁，请等待 30 秒后重试"
        else -> "搜索未能开始，请关闭并重新打开蓝牙后重试"
    }
}

internal object RingDiscoverySupport : RingDiscoveryAccess {
    const val LEGACY_PACKAGE = "com.nexthci.ringfitness"

    override fun inspect(context: Context, knownRing: PreparedRing?): RingDiscoveryEnvironment = RingDiscoveryEnvironment(
        legacyAppInstalled = legacyAppInstalled(context),
        connectedRingNames = connectedRingNames(context, knownRing),
    )

    override fun openLegacyAppSettings(context: Context): Boolean {
        if (!legacyAppInstalled(context)) return false
        return runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$LEGACY_PACKAGE"),
                ),
            )
            true
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun connectedRingNames(context: Context, knownRing: PreparedRing?): List<String> = runCatching {
        val devices = context.getSystemService(BluetoothManager::class.java)
            ?.getConnectedDevices(BluetoothProfile.GATT)
            .orEmpty()
            .map { device ->
                ConnectedGattIdentity(
                    address = runCatching { device.address }.getOrNull(),
                    name = runCatching { device.name }.getOrNull(),
                )
            }
        RingDiscoveryGuidance.ringNames(devices, knownRing)
    }.getOrDefault(emptyList())

    @Suppress("DEPRECATION")
    private fun legacyAppInstalled(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getApplicationInfo(
                LEGACY_PACKAGE,
                PackageManager.ApplicationInfoFlags.of(0),
            )
        } else {
            context.packageManager.getApplicationInfo(LEGACY_PACKAGE, 0)
        }
        true
    }.getOrDefault(false)
}
