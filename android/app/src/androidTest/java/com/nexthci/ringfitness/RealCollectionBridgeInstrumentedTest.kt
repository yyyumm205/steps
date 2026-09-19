package com.nexthci.ringfitness

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Bridge lifecycle only: fake Context intercepts service starts; no Activity, BLE or files. */
@RunWith(AndroidJUnit4::class)
class RealCollectionBridgeInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val pageLease = Any()

    @Test fun notificationMigratesLegacyIdentityAndRetainsTheSingleTopContract() = withIdleBridge {
        val context = instrumentation.targetContext
        val legacy = PendingIntent.getActivity(context, 0, Intent(context, RealCollectionActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        var current: PendingIntent? = null
        try {
            val info = context.packageManager.getActivityInfo(ComponentName(context, RealCollectionActivity::class.java), 0)
            assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, info.launchMode)
            current = RealCollectionService.notificationContentIntent(context)
            assertNotEquals("Pre-upgrade tokens can retain launch flags; the new notification must migrate identity",
                legacy, current)
            assertEquals("Status updates reuse the new token without cancelling an already displayed notification",
                current, RealCollectionService.notificationContentIntent(context))
        } finally {
            legacy.cancel()
            current?.cancel()
        }
    }

    @Test fun reservationIsVisibleBeforeAttachAndARejectedLaunchReleasesOwnership() = withIdleBridge {
        val context = FakeStartContext()
        context.reject = true
        assertThrows(IllegalStateException::class.java) { RealCollectionBridge.ensureStarted(context, pageLease) }
        assertFalse(RealCollectionBridge.isRunning())
        context.reject = false
        val ownership = mutableListOf<Boolean>()
        RealCollectionBridge.observe { ownership += RealCollectionBridge.isRunning() }.use {
            RealCollectionBridge.ensureStarted(context, pageLease)
            assertTrue("Preparation must be locked before asynchronous service creation", RealCollectionBridge.isRunning())
            assertEquals("Existing preparation observers must see the reservation", true, ownership.lastOrNull())
            val token = Any()
            RealCollectionBridge.begin(token)
            RealCollectionBridge.attach(token, {}, {})
            RealCollectionBridge.detach(token)
            assertFalse(RealCollectionBridge.isRunning())
            assertEquals(false, ownership.last())
        }
        assertEquals(2, context.requests)
    }

    @Test fun aRetiredServiceCannotPublishDetachOrReceiveActionsForItsReplacement() = withIdleBridge {
        val old = Any()
        val current = Any()
        var oldActions = 0
        var currentActions = 0
        RealCollectionBridge.ensureStarted(FakeStartContext(), pageLease)
        RealCollectionBridge.begin(old)
        RealCollectionBridge.attach(old, { oldActions++ }, {})
        RealCollectionBridge.begin(current)
        RealCollectionBridge.attach(current, { currentActions++ }, {})
        val expected = CollectionFlowState(isSimulation = false, uploadAvailable = false,
            hasProfile = true, participantId = "bridge001", connected = true, canStart = true)
        RealCollectionBridge.publish(current, expected)
        RealCollectionBridge.publish(old, expected.copy(page = CollectionPage.ERROR, error = "stale fixture"))
        RealCollectionBridge.detach(old)
        assertEquals(expected, RealCollectionBridge.state)
        assertTrue(RealCollectionBridge.isRunning())
        RealCollectionBridge.start()
        assertEquals(0, oldActions)
        assertEquals(1, currentActions)
        RealCollectionBridge.releaseIfIdle(pageLease)
        assertFalse(RealCollectionBridge.shouldRelease(old))
        assertTrue(RealCollectionBridge.shouldRelease(current))
        RealCollectionBridge.detach(current)
        assertFalse(RealCollectionBridge.isRunning())
    }

    @Test fun leavingDuringInitializationRequestsReleaseAndReopeningCancelsThatRequest() = withIdleBridge {
        val context = FakeStartContext()
        val token = Any()
        var releaseRequests = 0
        RealCollectionBridge.ensureStarted(context, pageLease)
        RealCollectionBridge.releaseIfIdle(pageLease)
        RealCollectionBridge.begin(token)
        RealCollectionBridge.attach(token, {}, { releaseRequests++ })
        assertEquals(1, releaseRequests)
        assertTrue(RealCollectionBridge.shouldRelease(token))
        RealCollectionBridge.ensureStarted(context, pageLease)
        assertFalse(RealCollectionBridge.shouldRelease(token))
        assertFalse("A reopened page cancels release before the worker commits it",
            RealCollectionBridge.beginRelease(token))
        assertTrue(RealCollectionBridge.isRunning())
        assertEquals(2, context.requests)
        RealCollectionBridge.detach(token)
    }

    @Test fun reopeningDuringCommittedReleaseWaitsForOldOwnerAndStartsOneReplacement() = withIdleBridge {
        val context = FakeStartContext()
        val old = Any()
        val current = Any()
        var oldActions = 0
        var currentActions = 0
        RealCollectionBridge.ensureStarted(context, pageLease)
        RealCollectionBridge.begin(old)
        RealCollectionBridge.attach(old, { oldActions++ }, {})
        val ready = CollectionFlowState(isSimulation = false, uploadAvailable = false,
            hasProfile = true, connected = true, canStart = true, canRetry = true)
        RealCollectionBridge.publish(old, ready)
        RealCollectionBridge.releaseIfIdle(pageLease)
        assertTrue(RealCollectionBridge.beginRelease(old))
        assertFalse("A release is committed only once", RealCollectionBridge.beginRelease(old))
        RealCollectionBridge.ensureStarted(context, pageLease)
        RealCollectionBridge.ensureStarted(context, pageLease)
        assertEquals("Reentry waits until the old files and BLE client have closed", 1, context.requests)
        assertTrue(RealCollectionBridge.state.busy)
        assertTrue(RealCollectionBridge.state.connecting)
        assertFalse(RealCollectionBridge.state.canStart)
        assertFalse(RealCollectionBridge.state.canRetry)
        RealCollectionBridge.publish(old, ready)
        RealCollectionBridge.attach(old, { oldActions++ }, {})
        RealCollectionBridge.start()
        assertFalse("Late old-owner callbacks cannot enable actions", RealCollectionBridge.state.canStart)
        assertEquals(0, oldActions)
        RealCollectionBridge.detach(Any())
        assertEquals(1, context.requests)
        RealCollectionBridge.beginDestroy(old)
        RealCollectionBridge.detach(old)
        assertEquals(2, context.requests)
        assertTrue("The replacement reserves ownership before service creation", RealCollectionBridge.isRunning())
        RealCollectionBridge.begin(current)
        RealCollectionBridge.attach(current, { currentActions++ }, {})
        RealCollectionBridge.publish(current, ready)
        RealCollectionBridge.beginDestroy(old)
        RealCollectionBridge.detach(old)
        RealCollectionBridge.start()
        assertEquals(0, oldActions)
        assertEquals(1, currentActions)
        assertTrue(RealCollectionBridge.state.canStart)
        assertEquals(2, context.requests)
        RealCollectionBridge.detach(current)
    }

    @Test fun leavingAgainWhileOldOwnerClosesCancelsTheQueuedRestart() = withIdleBridge {
        val context = FakeStartContext()
        val token = Any()
        val newPage = Any()
        RealCollectionBridge.ensureStarted(context, pageLease)
        RealCollectionBridge.begin(token)
        RealCollectionBridge.attach(token, {}, {})
        RealCollectionBridge.releaseIfIdle(pageLease)
        assertTrue(RealCollectionBridge.beginRelease(token))
        RealCollectionBridge.ensureStarted(context, newPage)
        RealCollectionBridge.releaseIfIdle(pageLease)
        RealCollectionBridge.releaseIfIdle(newPage)
        RealCollectionBridge.detach(token)
        assertEquals(1, context.requests)
        assertFalse(RealCollectionBridge.isRunning())
        assertFalse(RealCollectionBridge.state.busy)
        assertFalse(RealCollectionBridge.state.connected)
    }

    @Test fun reopeningDuringDestructionWaitsAndRejectedReplacementShowsRecoverableError() = withIdleBridge {
        val context = FakeStartContext()
        val token = Any()
        RealCollectionBridge.ensureStarted(context, pageLease)
        RealCollectionBridge.begin(token)
        RealCollectionBridge.attach(token, {}, {})
        RealCollectionBridge.beginDestroy(token)
        RealCollectionBridge.ensureStarted(context, pageLease)
        assertEquals(1, context.requests)
        context.reject = true
        RealCollectionBridge.detach(token)
        assertEquals(2, context.requests)
        assertFalse(RealCollectionBridge.isRunning())
        assertEquals(CollectionPage.ERROR, RealCollectionBridge.state.page)
        assertFalse(RealCollectionBridge.state.busy)
        assertFalse(RealCollectionBridge.state.canStart)
        assertEquals("采集服务未能启动，请返回后重试", RealCollectionBridge.state.error)
    }

    @Test fun newPageEnteringBeforeOldPageStopsDoesNotReleaseTheLiveOwner() = withIdleBridge {
        val context = FakeStartContext()
        val service = Any()
        val newPage = Any()
        var releaseRequests = 0
        var actions = 0
        RealCollectionBridge.ensureStarted(context, pageLease)
        RealCollectionBridge.begin(service)
        RealCollectionBridge.attach(service, { actions++ }, { releaseRequests++ })
        RealCollectionBridge.publish(service, CollectionFlowState(isSimulation = false, uploadAvailable = false,
            hasProfile = true, connected = true, canStart = true))

        RealCollectionBridge.ensureStarted(context, newPage)
        RealCollectionBridge.releaseIfIdle(pageLease)
        assertEquals(0, releaseRequests)
        assertFalse(RealCollectionBridge.shouldRelease(service))
        assertFalse(RealCollectionBridge.beginRelease(service))
        assertTrue(RealCollectionBridge.state.canStart)
        RealCollectionBridge.start()
        assertEquals(1, actions)

        // SINGLE_TOP reuses the new page's lease; Home does not release it.
        RealCollectionBridge.ensureStarted(context, newPage)
        RealCollectionBridge.releaseIfIdle(pageLease)
        assertFalse(RealCollectionBridge.shouldRelease(service))
        RealCollectionBridge.releaseIfIdle(newPage)
        assertEquals(1, releaseRequests)
        assertTrue(RealCollectionBridge.beginRelease(service))
        RealCollectionBridge.detach(service)
    }

    @Test fun newPageEnteringBeforeOldPageStopsKeepsTheInitializationReservation() = withIdleBridge {
        val context = FakeStartContext()
        val service = Any()
        val newPage = Any()
        var releaseRequests = 0
        RealCollectionBridge.ensureStarted(context, pageLease)
        RealCollectionBridge.ensureStarted(context, newPage)
        RealCollectionBridge.releaseIfIdle(pageLease)
        RealCollectionBridge.begin(service)
        RealCollectionBridge.attach(service, {}, { releaseRequests++ })
        assertEquals(0, releaseRequests)
        assertTrue(RealCollectionBridge.isRunning())
        assertFalse(RealCollectionBridge.shouldRelease(service))
        RealCollectionBridge.releaseIfIdle(newPage)
        assertEquals(1, releaseRequests)
        assertTrue(RealCollectionBridge.beginRelease(service))
        RealCollectionBridge.detach(service)
    }

    @Test fun stalePageStopCannotCancelAReplacementQueuedDuringRetirement() = withIdleBridge {
        val context = FakeStartContext()
        val oldService = Any()
        val newService = Any()
        val newPage = Any()
        RealCollectionBridge.ensureStarted(context, pageLease)
        RealCollectionBridge.begin(oldService)
        RealCollectionBridge.attach(oldService, {}, {})
        RealCollectionBridge.releaseIfIdle(pageLease)
        assertTrue(RealCollectionBridge.beginRelease(oldService))
        RealCollectionBridge.ensureStarted(context, newPage)
        RealCollectionBridge.releaseIfIdle(pageLease)
        assertEquals(1, context.requests)
        RealCollectionBridge.detach(oldService)
        assertEquals("The new page's queued restart survives a late old-page stop", 2, context.requests)
        assertTrue(RealCollectionBridge.isRunning())
        RealCollectionBridge.begin(newService)
        RealCollectionBridge.attach(newService, {}, {})
        RealCollectionBridge.releaseIfIdle(pageLease)
        assertFalse(RealCollectionBridge.shouldRelease(newService))
        RealCollectionBridge.releaseIfIdle(newPage)
        assertTrue(RealCollectionBridge.beginRelease(newService))
        RealCollectionBridge.detach(newService)
        assertEquals(2, context.requests)
        assertFalse(RealCollectionBridge.isRunning())
    }

    private fun withIdleBridge(test: () -> Unit) {
        assumeTrue("Explicit collection opt-in is required",
            InstrumentationRegistry.getArguments().getString("verifyCollectionFlow") == "true")
        assumeTrue("Bridge fixtures run only in an emulator",
            Build.FINGERPRINT.contains("generic", true) || Build.FINGERPRINT.contains("emulator", true) ||
                Build.MODEL.contains("Emulator", true) || Build.MODEL.startsWith("sdk_gphone") ||
                Build.MODEL.startsWith("Android SDK built for"))
        assumeTrue("A live real-device owner must be left untouched", !RealCollectionBridge.isRunning())
        instrumentation.runOnMainSync {
            val before = RealCollectionBridge.state
            val pending = RealCollectionBridge::class.java.getDeclaredField("releasePending").apply { isAccessible = true }
            val previousPending = pending.getBoolean(RealCollectionBridge)
            try { test() } finally {
                RealCollectionBridge::class.java.getDeclaredField("restartContext").apply { isAccessible = true }
                    .set(RealCollectionBridge, null)
                val owner = RealCollectionBridge::class.java.getDeclaredField("owner").apply { isAccessible = true }
                    .get(RealCollectionBridge)
                if (owner != null) RealCollectionBridge.detach(owner)
                RealCollectionBridge::class.java.getDeclaredField("reserved").apply { isAccessible = true }
                    .setBoolean(RealCollectionBridge, false)
                RealCollectionBridge::class.java.getDeclaredField("pageLease").apply { isAccessible = true }
                    .set(RealCollectionBridge, null)
                pending.setBoolean(RealCollectionBridge, previousPending)
                RealCollectionBridge.publish(before)
            }
        }
    }

    private inner class FakeStartContext : ContextWrapper(instrumentation.targetContext) {
        var requests = 0
        var reject = false
        override fun getApplicationContext(): Context = this
        override fun startForegroundService(service: Intent): ComponentName {
            requests++
            check(!reject) { "Injected foreground launch rejection" }
            return requireNotNull(service.component)
        }
    }
}
