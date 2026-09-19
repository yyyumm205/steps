package com.nexthci.ringfitness

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Foreground owner: Activities never own an active capture connection or perform disk I/O. */
class RealCollectionService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "collection-real-owner") }
    private var controller: RealCollectionController? = null
    private var client: RingBleClient? = null // Accessed exclusively on main, like the GATT queue.
    @Volatile private var clientGeneration = 0L
    private var clientWasReady = false
    @Volatile private var destroyed = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var subscription: AutoCloseable? = null

    override fun onCreate() {
        super.onCreate()
        RealCollectionBridge.begin(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "戒指采集", NotificationManager.IMPORTANCE_LOW))
        try { startForeground(NOTIFICATION, notification("正在连接戒指")) } catch (error: SecurityException) {
            RealCollectionBridge.fail("请允许蓝牙权限后重新打开采集页面")
            stopSelf()
            return
        }
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:collection")
        worker.execute {
            try {
                if (destroyed) return@execute
                val directory = File(filesDir, "collection-real")
                if (!directory.isDirectory) {
                    check(directory.mkdir())
                    syncDirectory(filesDir)
                }
                val store = FreeLivingSessionStore(File(directory, "session.json"))
                val port = object : RealCollectionPort {
                    override fun connect(ring: PreparedRing, generation: Long): Boolean = onMain {
                        clientGeneration = generation
                        clientWasReady = false
                        val next = RingBleClient(this@RealCollectionService, object : RingBleClient.Listener {
                            private fun active() = !destroyed && generation == clientGeneration
                            override fun onBleState(message: String, ready: Boolean) {
                                if (!active()) return
                                if (ready) {
                                    clientWasReady = true
                                    submit { it.onConnected(generation) }
                                } else if (clientWasReady) {
                                    clientWasReady = false
                                    submit { it.onDisconnected(generation, "连接中断，请将戒指放在手机附近") }
                                }
                            }
                            override fun onSensorPacket(packet: SensorPacket) {
                                if (active() && packet is SensorPacket.Health) submit { it.onHealth(generation, packet) }
                            }
                            override fun onBleError(message: String) {
                                if (active()) submit { it.onDisconnected(generation, message) }
                            }
                            override fun onRingsFound(rings: List<ScannedRing>) = Unit
                        })
                        client = next
                        next.connectKnownAddress(ring.address, ring.name)
                    }
                    override fun disconnect() { onMain(allowDestroyed = true) { closeClient() } }
                    override fun queryStatus() = command { it.requestHealthStatus() }
                    override fun queryRecords() = command { it.requestHealthSessions() }
                    override fun start() = command { it.startHealth() }
                    override fun stop() = command { it.stopHealth() }
                    override fun read(sessionId: Int, offset: Long, length: Int) =
                        command { it.readHealth(sessionId, offset, length) }
                }
                val owner = RealCollectionController(directory, PreparationStore(File(filesDir, "preparation/profile.properties")),
                    store, port, CollectionScheduler { delay, action ->
                        worker.schedule({ if (!destroyed) action() }, delay, TimeUnit.MILLISECONDS)
                    }, object : CaptureClock {
                        override fun nowEpochMs() = System.currentTimeMillis()
                        override fun timeZoneId() = ZoneId.systemDefault().id
                    }, authorizedExisting = {
                        // A one-time, exact baseline is provisioned locally for the authorized old lab record.
                        // Release never consumes this development-only authorization.
                        val file = File(directory, "authorized-existing.json")
                        if (BuildConfig.DEBUG && file.isFile) Gson().fromJson(file.readText(), ExistingRecordAuthorization::class.java)
                        else null
                    }, notifyObserver = { action -> main.post { if (!destroyed) action() } },
                    recordObservation = { observation ->
                        writeObservation(File(directory, "device-observation.json"), Gson().toJson(observation))
                    }, reportError = { error -> Log.e(TAG, "Real collection operation failed", error) })
                controller = owner
                subscription = owner.observe { state ->
                    RealCollectionBridge.publish(this, state)
                    updateForeground(state)
                }
                onMain { RealCollectionBridge.attach(this, { action -> submit(action) }, {
                    submit { current ->
                        if (current.canReleaseIfIdle() && onMain { RealCollectionBridge.beginRelease(this) }) {
                            current.close()
                            main.post { if (!destroyed) stopSelf() }
                        }
                    }
                }) }
                owner.initialize()
            } catch (error: Exception) {
                Log.e(TAG, "Cannot initialize collection owner", error)
                main.post {
                    if (!destroyed) {
                        RealCollectionBridge.fail("采集准备无法读取，请联系研究者")
                        stopSelf()
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    private fun submit(action: (RealCollectionController) -> Unit) {
        if (!destroyed) worker.execute { if (!destroyed) controller?.let(action) }
    }

    private fun command(action: (RingBleClient) -> Boolean): Boolean {
        val expected = clientGeneration
        return onMain { if (expected != clientGeneration) false else client?.let(action) ?: false }
    }

    private fun updateForeground(state: CollectionFlowState) {
        val active = state.session?.localData == null && state.session != null
        wakeLock?.let { lock ->
            if (active && !lock.isHeld) lock.acquire()
            if (!active && lock.isHeld) lock.release()
        }
        val text = when {
            state.connecting -> "正在连接戒指"
            !state.connected && active -> "连接中断，正在保留本次记录"
            state.taskPage == CollectionPage.COLLECTING -> "正在采集，点此查看或结束"
            state.taskPage == CollectionPage.STOPPING -> "正在确认结束"
            state.taskPage == CollectionPage.REFERENCE -> if (state.session?.stopConfirmedAtMs != null)
                "采集已结束，请填写计步器读数" else "结束状态待确认，可先记录读数"
            state.taskPage == CollectionPage.DOWNLOADING -> "正在保存戒指数据"
            state.session?.localData != null -> "记录已保存在手机"
            else -> "戒指采集准备"
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(text))
    }

    private fun notification(text: String): Notification {
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("步数采集").setContentText(text).setContentIntent(notificationContentIntent(this))
            .setOngoing(true).build()
    }

    private fun <T> onMain(allowDestroyed: Boolean = false, action: () -> T): T {
        val guarded = { check(allowDestroyed || !destroyed) { "采集服务已经关闭" }; action() }
        if (Looper.myLooper() == Looper.getMainLooper()) return guarded()
        val task = FutureTask(guarded)
        main.post(task)
        return try { task.get(10, TimeUnit.SECONDS) } catch (error: Exception) {
            // Remove a timed-out command before the main thread can issue a late START/STOP.
            task.cancel(false)
            main.removeCallbacks(task)
            throw error
        }
    }

    private fun closeClient() {
        clientGeneration = -1
        clientWasReady = false
        val previous = client
        client = null
        runCatching { previous?.stop() }
    }

    override fun onDestroy() {
        destroyed = true
        RealCollectionBridge.beginDestroy(this)
        subscription?.close()
        closeClient()
        wakeLock?.let { if (it.isHeld) it.release() }
        worker.execute {
            try { controller?.close() }
            finally { main.post { RealCollectionBridge.detach(this) } }
        }
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "real-collection"
        private const val NOTIFICATION = 4102
        private const val TAG = "RingFitnessReal"
        internal fun notificationContentIntent(context: Context): PendingIntent =
            // Use a new identity: UPDATE_CURRENT preserves launch flags from a pre-upgrade token.
            PendingIntent.getActivity(context, NOTIFICATION, Intent(context, RealCollectionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        private fun syncDirectory(directory: File) =
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        private fun writeObservation(file: File, text: String) {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(tmp).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(requireNotNull(file.parentFile))
        }
    }
}

/** In-process UI proxy. The foreground service owns the controller and the connection. */
object RealCollectionBridge : CollectionFlow {
    private val observers = CopyOnWriteArrayList<(CollectionFlowState) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var dispatch: (((RealCollectionController) -> Unit) -> Unit)? = null
    @Volatile private var reserved = false
    private var owner: Any? = null
    private var release: (() -> Unit)? = null
    private var releasePending = false
    private var retiring: Any? = null
    private var restartContext: Context? = null
    private var pageLease: Any? = null
    @Volatile override var state = CollectionFlowState(isSimulation = false, uploadAvailable = false,
        hasProfile = true, connected = false, connecting = true, busy = true)
        private set

    fun ensureStarted(context: Context, lease: Any) {
        pageLease = lease
        reserved = true
        releasePending = false
        if (owner != null && retiring === owner) {
            // The old owner must finish closing its file and GATT before a new owner starts.
            restartContext = context.applicationContext
            publishWaiting()
            return
        }
        if (owner == null) publish(CollectionFlowState(isSimulation = false, uploadAvailable = false,
            hasProfile = true, connected = false, connecting = true, busy = true))
        try { context.startForegroundService(Intent(context, RealCollectionService::class.java)) }
        catch (error: Exception) {
            if (owner == null) { reserved = false; pageLease = null }
            throw error
        }
    }
    fun isRunning() = reserved || dispatch != null
    fun releaseIfIdle(lease: Any) {
        // An older Activity may finish after its replacement has already entered.
        if (pageLease !== lease) return
        pageLease = null
        releasePending = true
        restartContext = null
        release?.invoke()
    }
    internal fun shouldRelease(token: Any) = owner === token && releasePending
    internal fun beginRelease(token: Any): Boolean {
        if (!shouldRelease(token) || retiring != null) return false
        beginDestroy(token)
        return true
    }
    internal fun beginDestroy(token: Any) {
        if (owner !== token || retiring === token) return
        retiring = token
        dispatch = null
        release = null
        publishWaiting()
    }
    internal fun begin(token: Any) {
        if (owner != null && owner !== token) releasePending = false
        owner = token; reserved = true; dispatch = null; release = null
        retiring = null; restartContext = null
        publishWaiting()
    }
    internal fun attach(token: Any, value: ((RealCollectionController) -> Unit) -> Unit, onRelease: () -> Unit) {
        if (owner !== token || retiring === token) return
        dispatch = value; release = onRelease
        if (releasePending) onRelease()
    }
    internal fun detach(token: Any) {
        if (owner !== token) return
        val restartLease = pageLease
        val restart = restartContext.takeIf { retiring === token && restartLease != null }
        owner = null; dispatch = null; release = null; reserved = false
        releasePending = false; retiring = null; restartContext = null
        if (restart != null && restartLease != null) {
            runCatching { ensureStarted(restart, restartLease) }.onFailure { fail("采集服务未能启动，请返回后重试") }
            return
        }
        pageLease = null
        publish(state.copy(busy = false, connecting = false, connected = false, canStart = false, canStop = false))
    }
    internal fun publish(token: Any, next: CollectionFlowState) {
        if (owner === token && retiring !== token) publish(next)
    }
    private fun publishWaiting() = publish(state.copy(busy = true, connecting = true,
        connected = false, canStart = false, canStop = false, canRetry = false))
    internal fun publish(next: CollectionFlowState) {
        state = next
        observers.forEach { it(next) }
    }
    internal fun fail(message: String) = publish(state.copy(page = CollectionPage.ERROR, busy = false,
        connecting = false, connected = false, canStart = false, canStop = false, canRetry = false, error = message))
    override fun observe(observer: (CollectionFlowState) -> Unit): AutoCloseable {
        observers += observer
        main.post { if (observer in observers) observer(state) }
        return AutoCloseable { observers -= observer }
    }
    override fun register(participantId: String, placement: RingPlacement) = Unit
    override fun start() { dispatch?.invoke { it.start() } }
    override fun stop() { dispatch?.invoke { it.stop() } }
    override fun enterReference() { dispatch?.invoke { it.enterReference() } }
    override fun saveReference(stepsText: String, status: String, reason: String) { dispatch?.invoke { it.saveReference(stepsText, status, reason) } }
    override fun retry() { dispatch?.invoke { it.retry() } }
    override fun retryUpload(sessionId: String) { dispatch?.invoke { it.retryUpload(sessionId) } }
    override fun home() { dispatch?.invoke { it.home() } }
    override fun setFault(fault: FlowTestFault) = Unit
    override fun disconnect() = Unit
    override fun reconnect() { dispatch?.invoke { it.reconnect() } }
}
