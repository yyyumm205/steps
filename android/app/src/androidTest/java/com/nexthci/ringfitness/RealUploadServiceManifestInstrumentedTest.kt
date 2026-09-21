package com.nexthci.ringfitness

import android.app.job.JobService
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealUploadServiceManifestInstrumentedTest {
    @Test fun uploadJobDeclaresPrivateDataSyncForegroundProtection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val service = context.packageManager.getServiceInfo(
            ComponentName(context, RealUploadService::class.java),
            PackageManager.GET_META_DATA,
        )

        assertEquals(JobService.PERMISSION_BIND, service.permission)
        assertFalse(service.exported)
        assertTrue(service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0)
    }
}
