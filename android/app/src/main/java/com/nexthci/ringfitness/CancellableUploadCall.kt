package com.nexthci.ringfitness

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Cancellation must close the socket while an upload is blocked inside network I/O. */
internal class CancellableUploadCall(private val client: OkHttpClient = standardClient()) {
    fun <T> execute(request: Request, deadlineMs: Long, cancelled: () -> Boolean,
        onDispatch: () -> Unit = {}, consume: (Response) -> T): T {
        require(deadlineMs > 0)
        val caller = Thread.currentThread()
        checkCancelled(cancelled, caller)
        val call = client.newCall(request)
        call.timeout().timeout(deadlineMs, TimeUnit.MILLISECONDS)
        val interrupted = AtomicBoolean(false)
        val watcher = cancellations.scheduleAtFixedRate({
            if (cancelled() || caller.isInterrupted) {
                interrupted.set(true)
                call.cancel()
            }
        }, 0, 100, TimeUnit.MILLISECONDS)
        try {
            checkCancelled(cancelled, caller)
            onDispatch()
            return call.execute().use { response ->
                checkCancelled(cancelled, caller)
                consume(response).also { checkCancelled(cancelled, caller) }
            }
        } catch (error: Exception) {
            if (interrupted.get() || cancelled() || caller.isInterrupted) {
                throw InterruptedException("Upload interrupted").also { it.initCause(error) }
            }
            throw error
        } finally {
            watcher.cancel(false)
            call.cancel()
        }
    }

    companion object {
        private val cancellations = Executors.newSingleThreadScheduledExecutor { work ->
            Thread(work, "RingFitness-upload-cancellation").apply { isDaemon = true }
        }

        internal fun standardClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

        private fun checkCancelled(cancelled: () -> Boolean, caller: Thread) {
            if (cancelled() || caller.isInterrupted) throw InterruptedException("Upload interrupted")
        }
    }
}
