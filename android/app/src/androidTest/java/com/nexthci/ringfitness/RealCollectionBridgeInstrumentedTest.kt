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
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/** Emulator-only bridge lifecycle; real owner/file fixtures replace BLE and serial scheduling. */
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

    @Test fun leavingBeforeOwnerInitializationReleasesOnceItBecomesIdle() = withIdleBridge {
        OwnerFixture().use { f ->
            RealCollectionBridge.releaseIfIdle(pageLease)
            f.runWorker()
            assertTrue(RealCollectionBridge.isRunning())
            assertEquals(0, f.releases)

            f.owner.initialize()
            f.runWorker()
            assertFalse(RealCollectionBridge.isRunning())
            assertEquals(1, f.releases)
            assertNull(f.store.read())
        }
    }

    @Test fun leavingDuringDownloadReleasesAfterLocalCommitWhileUploadRemainsInFlight() = withIdleBridge {
        OwnerFixture().use { f ->
            f.beginDownload()
            val sessionId = requireNotNull(f.store.readPending()).sessionId
            val disconnects = f.port.disconnects
            RealCollectionBridge.releaseIfIdle(pageLease)
            f.runWorker()
            assertTrue(RealCollectionBridge.isRunning())
            assertEquals(disconnects, f.port.disconnects)
            assertNull(f.store.readPending()!!.localData)

            f.finishDownload()
            assertNull(f.store.readPending())
            assertNotNull(f.store.read(sessionId)!!.localData)
            assertTrue(f.uploads.isInFlight(sessionId))
            f.runWorker()

            assertFalse("Preparation can change ring/placement once its background download is safe", RealCollectionBridge.isRunning())
            assertEquals(1, f.releases)
            assertEquals(disconnects + 1, f.port.disconnects)
            assertTrue("Releasing collection leaves the independent upload alive", f.uploads.isInFlight(sessionId))
            assertEquals(listOf(sessionId), f.uploads.requests)
            assertEquals(17L, f.store.read(sessionId)!!.reference!!.steps)
            RealCollectionBridge.publish(f.token, f.owner.state)
            f.runWorker()
            assertEquals("Late completion callbacks cannot release twice", 1, f.releases)
        }
    }

    @Test fun reopeningAfterDownloadCancelsTheAlreadyQueuedRelease() = withIdleBridge {
        OwnerFixture().use { f ->
            f.beginDownload()
            RealCollectionBridge.releaseIfIdle(pageLease)
            f.runWorker()
            f.finishDownload()
            assertTrue("Completion has queued another release attempt", f.hasWorkerTasks())

            val nextPage = Any()
            RealCollectionBridge.ensureStarted(f.context, nextPage)
            RealCollectionBridge.releaseIfIdle(pageLease)
            val disconnects = f.port.disconnects
            f.runWorker()
            assertTrue(RealCollectionBridge.isRunning())
            assertEquals(0, f.releases)
            assertEquals(disconnects, f.port.disconnects)
            assertTrue(f.owner.canReleaseIfIdle())

            RealCollectionBridge.releaseIfIdle(nextPage)
            f.runWorker()
            assertFalse(RealCollectionBridge.isRunning())
            assertEquals(1, f.releases)
        }
    }

    @Test fun aQueuedIdlePublicationCannotReleaseANewerPendingCapture() = withIdleBridge {
        OwnerFixture().use { f ->
            f.beginDownload()
            val previousSession = requireNotNull(f.store.readPending()).sessionId
            RealCollectionBridge.releaseIfIdle(pageLease)
            f.runWorker()
            f.finishDownload()
            assertTrue(f.hasWorkerTasks())

            val nextPage = Any()
            RealCollectionBridge.ensureStarted(f.context, nextPage)
            // The next owner's operation is already running when an older progress callback arrives.
            f.owner.home()
            f.owner.selectActivity(SessionActivity.RUNNING)
            f.owner.start()
            f.observe(f.stopped(), listOf(f.finalRecord))
            val nextSession = requireNotNull(f.store.readPending())
            assertNotEquals(previousSession, nextSession.sessionId)
            assertEquals(SessionActivity.RUNNING, nextSession.activity)
            assertFalse(f.owner.canReleaseIfIdle())
            RealCollectionBridge.releaseIfIdle(nextPage)
            val disconnects = f.port.disconnects
            f.runWorker()

            assertTrue(RealCollectionBridge.isRunning())
            assertEquals(0, f.releases)
            assertEquals(disconnects, f.port.disconnects)
            assertEquals(nextSession.sessionId, f.store.readPending()!!.sessionId)
            assertTrue(f.uploads.isInFlight(previousSession))
        }
    }

    /** The queue models the service worker; all release attempts read the actual owner's current state. */
    private inner class OwnerFixture : AutoCloseable {
        val token = Any()
        val context = FakeStartContext()
        private val directory = Files.createTempDirectory(instrumentation.targetContext.cacheDir.toPath(), "bridge-owner-").toFile()
        private val ring = PreparedRing("AA:BB:CC:DD:EE:07", "Bridge fixture")
        private var now = 1_789_804_800_000L
        private val firstPacket = imu(2, 1_000)
        private val payload = firstPacket + imu(3, 1_060)
        private val initialRecord = HealthMessage.ListItem(7, firstPacket.size.toLong(), 1, 900, now)
        val finalRecord = initialRecord.copy(bytes = payload.size.toLong(), records = 2)
        private val preparation = PreparationStore(File(directory, "profile")).apply {
            register("bridge001", RingPlacement.LEFT_INDEX)
            selectRing(ring)
        }
        val store = FreeLivingSessionStore(File(directory, "session.json"))
        val port = FixturePort()
        val uploads = FixtureUploads()
        private val workerTasks = ArrayDeque<() -> Unit>()
        private val errors = mutableListOf<Exception>()
        var releases = 0
            private set
        val owner = RealCollectionController(directory, preparation, store, port,
            CollectionScheduler { delay, action ->
                if (delay in setOf(500L, 1_000L, 5_000L)) action()
            }, object : CaptureClock {
                override fun nowEpochMs() = now
                override fun nowElapsedMs() = now - 1_789_804_790_000L
                override fun timeZoneId() = "Asia/Shanghai"
            }, uploads = uploads, reportError = { errors += it })
        private val subscription: AutoCloseable

        init {
            RealCollectionBridge.ensureStarted(context, pageLease)
            RealCollectionBridge.begin(token)
            subscription = owner.observe { RealCollectionBridge.publish(token, it) }
            RealCollectionBridge.attach(token, { action -> workerTasks.addLast { action(owner) } }, {
                workerTasks.addLast {
                    if (owner.canReleaseIfIdle() && RealCollectionBridge.beginRelease(token)) {
                        owner.close()
                        releases++
                        RealCollectionBridge.detach(token)
                    }
                }
            })
        }

        fun hasWorkerTasks() = workerTasks.isNotEmpty()
        fun runWorker() {
            while (workerTasks.isNotEmpty()) workerTasks.removeFirst().invoke()
            assertTrue("Controller operations should succeed: $errors", errors.isEmpty())
        }

        private fun idle() = HealthMessage.Status(false, 0, 0, 0, 6)
        fun stopped() = HealthMessage.Status(false, finalRecord.bytes, finalRecord.records, 0, 7)
        fun observe(status: HealthMessage.Status, records: List<HealthMessage.ListItem> = emptyList()) {
            health(status)
            records.forEach(::health)
            health(HealthMessage.ListEnd(records.size))
        }

        private fun health(message: HealthMessage) = owner.onHealth(port.generation, SensorPacket.Health(message, ++now))

        fun beginDownload() {
            owner.initialize()
            owner.onConnected(port.generation)
            observe(idle())
            owner.selectActivity(SessionActivity.WALKING)
            owner.start()
            observe(idle())
            observe(HealthMessage.Status(true, initialRecord.bytes, initialRecord.records, 0, 7), listOf(initialRecord))
            now += 60_000
            owner.stop()
            assertEquals(1, port.stopCommands)
            observe(stopped(), listOf(finalRecord))
            assertEquals(CollectionPage.FINISH, owner.state.taskPage)
            assertEquals(0, port.readRequests)
            observe(stopped(), listOf(finalRecord))
            assertEquals(1, port.stopCommands)
            owner.chooseFinish(true)
            owner.saveReference("17", "valid", "")
            observe(stopped(), listOf(finalRecord))
            assertEquals("errors=$errors", CollectionPage.DOWNLOADING, owner.state.taskPage)
            assertEquals(1, port.readRequests)
            assertFalse(owner.canReleaseIfIdle())
        }

        fun finishDownload() {
            health(HealthMessage.DataChunk(0, payload))
            health(HealthMessage.ReadEnd(payload.size.toLong(), true))
            observe(stopped(), listOf(finalRecord))
            assertTrue("Download and durable commit should succeed: $errors", errors.isEmpty())
        }

        override fun close() {
            subscription.close()
            owner.close()
            RealCollectionBridge.detach(token)
            check(directory.deleteRecursively())
        }
    }

    private class FixturePort : RealCollectionPort {
        var generation = 0L
        var disconnects = 0
        var stopCommands = 0
        var readRequests = 0
        override fun connect(ring: PreparedRing, generation: Long): Boolean { this.generation = generation; return true }
        override fun disconnect() { disconnects++ }
        override fun queryStatus() = true
        override fun queryBattery() = true
        override fun queryRecords() = true
        override fun start() = true
        override fun stop(): Boolean { stopCommands++; return true }
        override fun read(sessionId: Int, offset: Long, length: Int): Boolean { readRequests++; return true }
    }

    private class FixtureUploads : RealUploadPort {
        val requests = mutableListOf<String>()
        private val inFlight = mutableSetOf<String>()
        override fun enqueue(sessionId: String, retry: Boolean) { requests += sessionId; inFlight += sessionId }
        override fun isInFlight(sessionId: String) = sessionId in inFlight
        override fun discard(sessionId: String, atMs: Long, ownerId: String, generation: Long) { inFlight -= sessionId }
    }

    private fun imu(count: Int, uptime: Long): ByteArray = ByteArrayOutputStream().apply {
        write(0x32); write(0x12); write(count)
        repeat(4) { write((uptime ushr (it * 8)).toInt()) }
        repeat(count * 6) { write(it + 1) }
    }.toByteArray()

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
