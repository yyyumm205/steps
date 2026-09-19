package com.nexthci.ringfitness

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** The shared native flow renders real results supplied by its foreground service. */
class RealCollectionActivity : StepCollectionActivity() {
    private val pageLease = Any()

    override fun onStop() {
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
        val permissions = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            RealCollectionBridge.fail("请允许蓝牙权限后继续")
            requestPermissions(permissions, 41)
        } else startOwner()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startOwner()
    }

    private fun startOwner() {
        if (isFinishing || isDestroyed || isChangingConfigurations) return
        runCatching { RealCollectionBridge.ensureStarted(this, pageLease) }.onFailure {
            RealCollectionBridge.fail("采集服务未能启动，请返回后重试")
        }
    }
}
