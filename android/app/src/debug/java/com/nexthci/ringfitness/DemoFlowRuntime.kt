package com.nexthci.ringfitness

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Application-scoped ownership; leaving a page only removes its observer. */
object DemoFlowRuntime {
    @Volatile private var instance: CollectionFlow? = null
    @Volatile private var testOverride: TestHandle? = null

    fun get(context: Context): CollectionFlow = testOverride?.flow ?: instance ?: synchronized(this) {
        instance ?: create(context.applicationContext, File(context.filesDir, "collection-demo")).flow.also { instance = it }
    }

    /** An isolated instrumentation owner; the normal demonstration remains untouched. */
    @Synchronized fun installForTesting(context: Context): TestHandle {
        check(testOverride == null)
        val root = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "qa-flow-").toFile()
        val handle = create(context.applicationContext, File(root, "collection-demo"))
        testOverride = handle
        return handle
    }

    class TestHandle internal constructor(val flow: CollectionFlow, val directory: File, private val shutdown: () -> Unit) : AutoCloseable {
        override fun close() {
            synchronized(DemoFlowRuntime) {
                if (testOverride === this) testOverride = null
                shutdown()
            }
        }
    }

    private fun create(context: Context, directory: File): TestHandle {
        check(BuildConfig.DEBUG)
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "collection-demo-owner").apply { isDaemon = true }
        }
        val main = Handler(Looper.getMainLooper())
        val controller = DemoFlowController(directory,
            DemoFlowScheduler { delay, action -> executor.schedule({ action() }, delay, TimeUnit.MILLISECONDS) },
            object : CaptureClock {
                override fun nowEpochMs() = System.currentTimeMillis()
                override fun timeZoneId() = ZoneId.systemDefault().id
            }, { action -> main.post { action() } })
        executor.execute { controller.initialize() }
        val flow = object : CollectionFlow {
            override val state get() = controller.state
            override fun observe(observer: (CollectionFlowState) -> Unit) = controller.observe(observer)
            override fun register(participantId: String, placement: RingPlacement) { executor.execute { controller.register(participantId, placement) } }
            override fun start() { executor.execute { controller.start() } }
            override fun selectActivity(activity: SessionActivity) { executor.execute { controller.selectActivity(activity) } }
            override fun stop() { executor.execute { controller.stop() } }
            override fun chooseFinish(uploadNow: Boolean) { executor.execute { controller.chooseFinish(uploadNow) } }
            override fun finalizeSession(uploadNow: Boolean, stepsText: String, status: String, reason: String) {
                executor.execute { controller.finalizeSession(uploadNow, stepsText, status, reason) }
            }
            override fun enterFinish() { executor.execute { controller.enterFinish() } }
            override fun discardSession() { executor.execute { controller.discardSession() } }
            override fun enterReference() { executor.execute { controller.enterReference() } }
            override fun saveReference(stepsText: String, status: String, reason: String) { executor.execute { controller.saveReference(stepsText, status, reason) } }
            override fun retry() { executor.execute { controller.retry() } }
            override fun retryUpload(sessionId: String) { executor.execute { controller.retryUpload(sessionId) } }
            override fun resumeRingTransfer() { executor.execute { controller.resumeRingTransfer() } }
            override fun reviseReference(sessionId: String, stepsText: String, status: String, reason: String) {
                executor.execute { controller.reviseReference(sessionId, stepsText, status, reason) }
            }
            override fun home() { executor.execute { controller.home() } }
            override fun setFault(fault: FlowTestFault) { executor.execute { controller.setFault(fault) } }
            override fun disconnect() { executor.execute { controller.disconnect() } }
            override fun reconnect() { executor.execute { controller.reconnect() } }
        }
        return TestHandle(flow, directory) { executor.shutdownNow() }
    }
}
