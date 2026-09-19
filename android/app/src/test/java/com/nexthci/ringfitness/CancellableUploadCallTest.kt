package com.nexthci.ringfitness

import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Actual loopback sockets prove that cancelling can release blocked I/O, not only chunk boundaries. */
class CancellableUploadCallTest {
    @Test fun cancelledBeforeRequestDoesNotConnect() {
        Peer { _, stop -> stop.await() }.use { peer ->
            assertThrows(InterruptedException::class.java) {
                CancellableUploadCall().execute(peer.request(), 5_000, { true }) { it.code() }
            }
            assertEquals(0, peer.connections.get())
        }
    }

    @Test fun cancellationInterruptsWaitingForResponseHeaders() {
        Peer { _, stop -> stop.await() }.use { peer ->
            val cancelled = AtomicBoolean(false)
            runAsync({ CancellableUploadCall().execute(peer.request(), 10_000, cancelled::get) { it.code() } }) { task ->
                assertTrue(peer.accepted.await(2, TimeUnit.SECONDS))
                cancelled.set(true)
                assertFailureWithin(task, InterruptedException::class.java)
                assertEquals(1, peer.connections.get())
            }
        }
    }

    @Test fun cancellationInterruptsAnIncompleteResponseBody() {
        val responseSent = CountDownLatch(1)
        Peer { socket, stop ->
            readHeaders(socket)
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\na".toByteArray())
                flush()
            }
            responseSent.countDown()
            stop.await()
        }.use { peer ->
            val cancelled = AtomicBoolean(false)
            runAsync({ CancellableUploadCall().execute(peer.request(), 10_000, cancelled::get) { it.body()!!.string() } }) { task ->
                assertTrue(responseSent.await(2, TimeUnit.SECONDS))
                cancelled.set(true)
                assertFailureWithin(task, InterruptedException::class.java)
            }
        }
    }

    @Test fun cancellationInterruptsBlockedUploadWhenServerDoesNotReadBody() {
        Peer { _, stop -> stop.await() }.use { peer ->
            val cancelled = AtomicBoolean(false)
            val body = StreamingBody()
            val request = peer.request().newBuilder().post(body).build()
            runAsync({ CancellableUploadCall().execute(request, 10_000, cancelled::get) { it.code() } }) { task ->
                assertTrue(peer.accepted.await(2, TimeUnit.SECONDS))
                awaitBlockedBody(body, task)
                cancelled.set(true)
                assertFailureWithin(task, InterruptedException::class.java)
                assertTrue(body.written.get() < body.contentLength())
                assertEquals(1, peer.connections.get())
            }
        }
    }

    @Test fun deadlineInterruptsResponseBlackholeWithoutReplayingRequest() {
        Peer { _, stop -> stop.await() }.use { peer ->
            runAsync({ CancellableUploadCall().execute(peer.request(), 300, { false }) { it.code() } }) { task ->
                assertTrue(peer.accepted.await(2, TimeUnit.SECONDS))
                assertFailureWithin(task, IOException::class.java)
                assertEquals(1, peer.connections.get())
            }
        }
    }

    @Test fun deadlineInterruptsBlockedUploadWithoutRelyingOnReadTimeout() {
        Peer { _, stop -> stop.await() }.use { peer ->
            val body = StreamingBody()
            val request = peer.request().newBuilder().post(body).build()
            runAsync({ CancellableUploadCall().execute(request, 600, { false }) { it.code() } }) { task ->
                assertTrue(peer.accepted.await(2, TimeUnit.SECONDS))
                assertFailureWithin(task, IOException::class.java)
                assertTrue(body.written.get() > 0)
                assertTrue(body.written.get() < body.contentLength())
                assertEquals(1, peer.connections.get())
            }
        }
    }

    @Test fun responseLossAfterServerReadsPostDoesNotReplayItInsideTransport() {
        val bodyReceived = CountDownLatch(1)
        Peer { socket, _ ->
            val headers = readHeaders(socket)
            val bytes = headers.lineSequence().first { it.startsWith("Content-Length:", true) }
                .substringAfter(':').trim().toInt()
            repeat(bytes) { check(socket.getInputStream().read() >= 0) }
            bodyReceived.countDown()
            socket.close()
        }.use { peer ->
            val post = RequestBody.create(MediaType.parse("application/octet-stream"), ByteArray(1024))
            runAsync({ CancellableUploadCall().execute(peer.request().newBuilder().post(post).build(), 2_000, { false }) { it.code() } }) { task ->
                assertTrue(bodyReceived.await(2, TimeUnit.SECONDS))
                assertFailureWithin(task, IOException::class.java)
                assertEquals(1, peer.connections.get())
            }
        }
    }

    @Test fun redirectsAreReturnedWithoutConnectingToTheirDestination() {
        Peer { _, stop -> stop.await() }.use { destination ->
            Peer { socket, _ ->
                readHeaders(socket)
                socket.getOutputStream().apply {
                    write(("HTTP/1.1 302 Found\r\nLocation: ${destination.request().url()}\r\nContent-Length: 0\r\n\r\n").toByteArray())
                    flush()
                }
            }.use { peer ->
                assertEquals(302, CancellableUploadCall().execute(peer.request(), 2_000, { false }) { it.code() })
                assertEquals(0, destination.connections.get())
            }
        }
    }

    private class StreamingBody : RequestBody() {
        val written = AtomicLong()
        override fun contentType() = MediaType.parse("application/octet-stream")
        override fun contentLength() = 128L * 1024 * 1024
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) {
            val bytes = ByteArray(64 * 1024)
            while (written.get() < contentLength()) {
                sink.write(bytes)
                written.addAndGet(bytes.size.toLong())
            }
        }
    }

    private fun awaitBlockedBody(body: StreamingBody, task: Future<*>) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        var previous = -1L
        var unchanged = 0
        while (System.nanoTime() < end) {
            assertFalse("Upload returned before its blocked-I/O cancellation check", task.isDone)
            val current = body.written.get()
            unchanged = if (current > 0 && current == previous) unchanged + 1 else 0
            if (unchanged >= 3) return
            previous = current
            Thread.sleep(50)
        }
        fail("Expected the unread upload to fill its socket buffer")
    }

    private fun assertFailureWithin(task: Future<*>, expected: Class<out Throwable>) {
        val failure = assertThrows(ExecutionException::class.java) { task.get(3, TimeUnit.SECONDS) }
        assertTrue("Expected ${expected.simpleName}, got ${failure.cause}", expected.isInstance(failure.cause))
    }

    private fun <T> runAsync(operation: () -> T, inspect: (Future<T>) -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        val task = executor.submit<T> { operation() }
        try { inspect(task) } finally {
            task.cancel(true)
            executor.shutdownNow()
            check(executor.awaitTermination(3, TimeUnit.SECONDS)) { "Upload worker did not terminate" }
        }
    }

    private class Peer(private val handle: (Socket, CountDownLatch) -> Unit) : Closeable {
        private val sockets = Collections.synchronizedList(mutableListOf<Socket>())
        private val stop = CountDownLatch(1)
        private val server = ServerSocket().apply {
            receiveBufferSize = 1024
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        }
        val accepted = CountDownLatch(1)
        val connections = AtomicInteger()
        private val worker = Thread({
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                sockets += socket
                connections.incrementAndGet()
                accepted.countDown()
                Thread({
                    try { socket.use { handle(it, stop) } } catch (_: IOException) { /* A cancelled call closes its socket. */ }
                }, "upload-test-peer").apply { isDaemon = true; start() }
            }
        }, "upload-test-listener").apply { isDaemon = true; start() }

        fun request(): Request = Request.Builder().url("http://127.0.0.1:${server.localPort}/fixture").build()
        override fun close() {
            server.close()
            stop.countDown()
            synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
            worker.join(2_000)
        }
    }

    companion object {
        private fun readHeaders(socket: Socket): String {
            val header = StringBuilder()
            while (!header.endsWith("\r\n\r\n")) {
                val byte = socket.getInputStream().read()
                check(byte >= 0 && header.length < 32_768)
                header.append(byte.toChar())
            }
            return header.toString()
        }
    }
}
