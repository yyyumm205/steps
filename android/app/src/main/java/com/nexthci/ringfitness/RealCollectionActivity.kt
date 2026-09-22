package com.nexthci.ringfitness

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** The shared native flow renders real results supplied by its foreground service. */
class RealCollectionActivity : StepCollectionActivity() {
    private val pageLease = Any()
    private var permissionRequestInFlight = false
    private var ownerStarted = false
    private var permissionDialog: AlertDialog? = null

    override fun onResume() {
        super.onResume()
        // Returning from system settings can grant access without a permission callback.
        // The first onResume must not duplicate the request made by provideFlow().
        if (!permissionRequestInFlight && BluetoothPermissionRecovery.missing(this).isEmpty() &&
            (!ownerStarted || !RealCollectionBridge.isRunning())) startOwner()
    }

    override fun onStop() {
        permissionDialog?.dismiss()
        permissionDialog = null
        super.onStop()
        if (isFinishing) RealCollectionBridge.releaseIfIdle(pageLease)
    }

    override fun provideFlow(): CollectionFlow {
        prepareOwner()
        return RealCollectionBridge
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        prepareOwner()
    }

    private fun prepareOwner() {
        val missing = BluetoothPermissionRecovery.missing(this)
        if (missing.isNotEmpty()) {
            RealCollectionBridge.fail("请允许蓝牙权限后继续")
            if (permissionRequestInFlight) return
            if (BluetoothPermissionRecovery.requiresSettings(this, missing)) showPermissionSettings()
            else {
                BluetoothPermissionRecovery.recordRequest(this, missing)
                permissionRequestInFlight = true
                requestPermissions(missing.toTypedArray(), 41)
            }
        } else startOwner()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 41) return
        permissionRequestInFlight = false
        if (BluetoothPermissionRecovery.missing(this).isEmpty()) startOwner()
        else {
            RealCollectionBridge.fail("请允许蓝牙权限后继续")
            showPermissionSettings()
        }
    }

    private fun showPermissionSettings() {
        if (isFinishing || isDestroyed || permissionDialog?.isShowing == true) return
        permissionDialog = AlertDialog.Builder(this)
            .setTitle(BluetoothPermissionRecovery.actionLabel)
            .setMessage(if (Build.VERSION.SDK_INT >= 31)
                "请在系统设置中允许蓝牙权限，返回后会继续当前记录。"
            else "Android 11 需要位置权限来搜索戒指。请在系统设置中允许位置权限，返回后会继续当前记录。")
            .setPositiveButton("打开权限设置") { _, _ ->
                startActivity(BluetoothPermissionRecovery.settingsIntent(this))
            }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun startOwner() {
        if (isFinishing || isDestroyed || isChangingConfigurations) return
        if (ownerStarted && RealCollectionBridge.isRunning()) return
        runCatching { RealCollectionBridge.ensureStarted(this, pageLease) }.onSuccess {
            ownerStarted = true
        }.onFailure {
            ownerStarted = false
            RealCollectionBridge.fail("采集服务未能启动，请返回后重试")
        }
    }
}

/** Shared request history distinguishes first use from Android's permanent-denial state. */
internal object BluetoothPermissionRecovery {
    private const val PREFERENCES = "bluetooth_access"
    private const val REQUESTED = "requested_permissions"
    val actionLabel: String get() = if (Build.VERSION.SDK_INT >= 31) "允许蓝牙权限" else "允许位置权限"
    val scanHint: String get() = if (Build.VERSION.SDK_INT >= 31) "允许蓝牙权限后即可搜索" else "允许位置权限后即可搜索戒指"

    fun missing(context: Context): List<String> = required().filter {
        context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }

    fun required(): List<String> = if (Build.VERSION.SDK_INT >= 31)
        listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun requiresSettings(activity: Activity, missing: List<String>): Boolean {
        val requested = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getStringSet(REQUESTED, emptySet()).orEmpty()
        return missing.any { it in requested && !activity.shouldShowRequestPermissionRationale(it) }
    }

    fun recordRequest(context: Context, permissions: List<String>) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val requested = preferences.getStringSet(REQUESTED, emptySet()).orEmpty() + permissions
        preferences.edit().putStringSet(REQUESTED, requested).apply()
    }

    fun settingsIntent(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
}
