package com.nexthci.ringfitness

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
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
    private data class Run(val owner: Any = Any(), val cancelled: AtomicBoolean = AtomicBoolean(false))
    // Android may parcel a new JobParameters object for stop; callbacks are ordered per job ID.
    private val runs = mutableMapOf<Int, Run>() // Accessed only on main.

    override fun onStartJob(params: JobParameters): Boolean {
        val run = Run()
        val stop = run.cancelled
        runs[params.jobId] = run
        RealUploadScheduler.started(run.owner)
        executor.execute {
            var retry = false
            try {
                val queue = RealUploadScheduler.queue(this)
                val attempted = mutableSetOf<String>()
                while (!stop.get()) {
                    val id = queue.queuedIds().firstOrNull { it !in attempted } ?: break
                    attempted += id
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
                main.post {
                    if (runs[params.jobId] === run) runs.remove(params.jobId)
                    if (!stop.get()) RealUploadScheduler.finished(run.owner, retry)?.let { needsRetry ->
                        jobFinished(params, needsRetry)
                    }
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        runs.remove(params.jobId)?.let { run ->
            run.cancelled.set(true)
            RealUploadScheduler.stopped(run.owner)
        }
        return true
    }
    override fun onDestroy() {
        runs.values.forEach { run ->
            run.cancelled.set(true)
            RealUploadScheduler.stopped(run.owner)
        }
        runs.clear()
        executor.shutdownNow()
        super.onDestroy()
    }
}
