package com.nexthci.ringfitness

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One persisted network job drains real sessions; BLE ownership remains in the collection service. */
object RealUploadScheduler {
    private const val JOB_ID = 741_025
    private val main = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArrayList<() -> Unit>()
    private val inFlight = UploadFlightOwners()
    private val coordination = UploadJobCoordination()
    private val enqueueWorker by lazy { Executors.newSingleThreadExecutor() }
    private val restoring = AtomicBoolean(false)

    /** Upload recovery is independent of preparation permissions and BLE service ownership. */
    fun restore(context: Context) {
        if (!restoring.compareAndSet(false, true)) return
        val application = context.applicationContext
        enqueueWorker.execute {
            try {
                if (queue(application).restore(BuildConfig.ACTIVITY_UPLOAD_LINK.trim())) schedule(application)
            } catch (error: Exception) {
                Log.e("RingFitnessUpload", "Saved upload tasks could not be restored", error)
            } finally {
                restoring.set(false)
                notifyChanged()
            }
        }
    }

    fun enqueue(context: Context, sessionId: String, retry: Boolean = false) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val application = context.applicationContext
            enqueueWorker.execute {
                runCatching { enqueue(application, sessionId, retry) }.onFailure {
                    Log.e("RingFitnessUpload", "Upload request could not be recorded", it)
                    notifyChanged()
                }
            }
            return
        }
        if (!queue(context).enqueue(sessionId, BuildConfig.ACTIVITY_UPLOAD_LINK.trim(), retry)) return
        schedule(context)
    }

    /** The collection owner commits the tombstone before calling this asynchronous cleanup. */
    fun discard(context: Context, sessionId: String, atMs: Long, connectionOwnerId: String? = null,
        connectionGeneration: Long? = null) {
        val application = context.applicationContext
        enqueueWorker.execute {
            runCatching { queue(application).discardSession(sessionId, atMs, connectionOwnerId, connectionGeneration) }
                .onFailure { Log.e("RingFitnessUpload", "Discarded session cleanup will retry on restore", it) }
            notifyChanged()
        }
    }

    private fun schedule(context: Context) {
        val application = context.applicationContext
        main.post {
            // Enqueue and finish decisions share this main-thread protocol. File I/O has completed.
            val request = coordination.enqueued()
            runCatching {
                val scheduler = application.getSystemService(JobScheduler::class.java)
                if (request != UploadJobCoordination.Scheduling.KEEP_ACTIVE &&
                    (request == UploadJobCoordination.Scheduling.REPLACE_FINISHED || scheduler.getPendingJob(JOB_ID) == null)) {
                    val job = JobInfo.Builder(JOB_ID, ComponentName(application, RealUploadService::class.java))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                        .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
                    check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) { "上传排队失败，请重试" }
                    coordination.scheduled()
                }
            }.onFailure { Log.e("RingFitnessUpload", "Persisted upload task could not be scheduled", it) }
            notifyChanged()
        }
    }

    fun isInFlight(sessionId: String): Boolean = inFlight.isInFlight(sessionId)
    fun observe(listener: () -> Unit): AutoCloseable {
        observers += listener
        return AutoCloseable { observers -= listener }
    }
    internal fun queue(context: Context): RealUploadQueue {
        val directory = File(context.filesDir, "collection-real")
        return RealUploadQueue(directory, FreeLivingSessionStore(File(directory, "session.json")), changed = ::notifyChanged)
    }
    internal fun started(owner: Any) { coordination.started(owner) }
    internal fun finished(owner: Any, retry: Boolean): Boolean? = coordination.finished(owner, retry)
    internal fun stopped(owner: Any) { coordination.stopped(owner) }
    internal fun uploading(sessionId: String, owner: Any, active: Boolean) {
        if (active) inFlight.begin(sessionId, owner) else inFlight.end(sessionId, owner)
        notifyChanged()
    }
    internal fun notifyChanged() { main.post { observers.forEach { it() } } }
}

class RealUploadService : JobService() {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var foregroundActive = false // Accessed only on main.
    private data class Run(val params: JobParameters, val owner: Any = Any(),
        val cancelled: AtomicBoolean = AtomicBoolean(false))
    // Android may parcel a new JobParameters object for stop; callbacks are ordered per job ID.
    private val runs = mutableMapOf<Int, Run>() // Accessed only on main.

    override fun onStartJob(params: JobParameters): Boolean {
        val run = Run(params)
        val stop = run.cancelled
        runs[params.jobId] = run
        RealUploadScheduler.started(run.owner)
        try {
            startForeground(NOTIFICATION_ID, uploadNotification("正在准备上传"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            foregroundActive = true
        } catch (error: RuntimeException) {
            // A regular background job has no general Android 12+ exemption to start an FGS.
            // JobScheduler still owns this run and supplies its wake lock and execution window.
            // Its stop callback cancels transport and leaves durable work for the next window.
            Log.w(TAG, "Foreground protection unavailable; continuing scheduled upload", error)
        }
        executor.execute {
            var retry = false
            try {
                if (BuildConfig.DEBUG) Log.i(TAG, "Upload job queue processing started")
                val queue = RealUploadScheduler.queue(this)
                val attempted = mutableSetOf<String>()
                while (!stop.get()) {
                    val id = queue.queuedIds().firstOrNull { it !in attempted } ?: break
                    attempted += id
                    main.post { if (runs[params.jobId] === run) updateNotification("正在上传采集数据") }
                    RealUploadScheduler.uploading(id, run.owner, true)
                    try { retry = queue.run(id, stop::get) || retry }
                    finally { RealUploadScheduler.uploading(id, run.owner, false) }
                }
                // This captures already durable work; the main-thread protocol covers later enqueues.
                retry = queue.queuedIds().isNotEmpty() || retry
            } catch (error: Exception) {
                Log.e("RingFitnessUpload", "Local upload task could not be processed", error)
                retry = true
            } finally {
                main.post { finish(params, run, retry) }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        runs.remove(params.jobId)?.let { run ->
            run.cancelled.set(true)
            RealUploadScheduler.stopped(run.owner)
        }
        if (runs.isEmpty()) stopForegroundProtection()
        return true
    }

    /** Android 15+ limits data-sync foreground time; durable tasks resume through JobScheduler. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        val timedOut = runs.values.toList()
        runs.clear()
        timedOut.forEach { run ->
            run.cancelled.set(true)
            val retry = RealUploadScheduler.finished(run.owner, retry = true) ?: true
            jobFinished(run.params, retry)
        }
        stopForegroundProtection()
        stopSelf()
    }

    override fun onDestroy() {
        runs.values.forEach { run ->
            run.cancelled.set(true)
            RealUploadScheduler.stopped(run.owner)
        }
        runs.clear()
        stopForegroundProtection()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun finish(params: JobParameters, run: Run, retry: Boolean) {
        if (runs[params.jobId] !== run) return
        runs.remove(params.jobId)
        if (runs.isEmpty()) stopForegroundProtection()
        if (!run.cancelled.get()) RealUploadScheduler.finished(run.owner, retry)?.let { needsRetry ->
            jobFinished(params, needsRetry)
        }
    }

    private fun updateNotification(text: String) {
        if (!foregroundActive) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, uploadNotification(text))
    }

    private fun stopForegroundProtection() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundActive = false
    }

    private fun uploadNotification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "采集数据上传", NotificationManager.IMPORTANCE_LOW))
        val content = PendingIntent.getActivity(this, NOTIFICATION_ID,
            Intent(this, RealCollectionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("步数采集")
            .setContentText(text)
            .setContentIntent(content)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
    }

    companion object {
        private const val TAG = "RingFitnessUpload"
        internal const val CHANNEL_ID = "real-upload"
        internal const val NOTIFICATION_ID = 4202
    }
}
