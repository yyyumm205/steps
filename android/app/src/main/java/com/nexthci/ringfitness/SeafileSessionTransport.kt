package com.nexthci.ringfitness

import com.google.gson.JsonParser
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI

data class RemoteSessionReceipt(val fileName: String, val fileId: String, val bytes: Long)

fun interface SessionUploadTransport {
    fun upload(link: String, archive: File, cancelled: () -> Boolean,
        onDispatch: () -> Unit): RemoteSessionReceipt
}

/** Upload-link transport shared in shape with the legacy worker; no participant account required. */
class SeafileSessionTransport : SessionUploadTransport {
    private val calls = CancellableUploadCall()

    override fun upload(link: String, archive: File, cancelled: () -> Boolean,
        onDispatch: () -> Unit): RemoteSessionReceipt {
        val page = validateLink(link)
        checkCancelled(cancelled)
        val html = get(page, cancelled, onDispatch)
        val parent = Regex("path:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            ?: error("上传位置暂时无法读取")
        require(parent.startsWith('/') && ".." !in parent && '\r' !in parent && '\n' !in parent)
        val token = page.path.trimEnd('/').substringAfterLast('/')
        val metadata = JsonParser.parseString(get(URI("https://$HOST/api/v2.1/upload-links/$token/upload/"),
            cancelled, onDispatch)).asJsonObject
        val upload = trustedUri(metadata.get("upload_link").asString)
        val destination = URI(upload.toString() + if (upload.rawQuery == null) "?ret-json=1" else "&ret-json=1")
        require(archive.name.matches(Regex("ringfitness-session-[a-f0-9-]+\\.zip")))
        val content = object : RequestBody() {
            override fun contentType() = MediaType.parse("application/zip")
            override fun contentLength() = archive.length()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                archive.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkCancelled(cancelled)
                        val count = input.read(buffer)
                        if (count < 0) break
                        sink.write(buffer, 0, count)
                    }
                }
            }
        }
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("parent_dir", parent)
            .addFormDataPart("replace", "0")
            .addFormDataPart("file", archive.name, content)
            .build()
        // Mark the entire POST one-shot, including on OkHttp versions whose MultipartBody does not
        // propagate this property from its parts. Recovery belongs to the durable session queue.
        val body = object : RequestBody() {
            override fun contentType() = multipart.contentType()
            override fun contentLength() = multipart.contentLength()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) = multipart.writeTo(sink)
        }
        return calls.execute(request(destination).post(body).build(), uploadDeadlineMillis(archive.length()),
            cancelled, onDispatch) { response ->
            check(response.isSuccessful) { "上传暂未完成（HTTP ${response.code()}）" }
            parseReceipt(readLimited(response, cancelled), archive.length())
        }
    }

    private fun get(uri: URI, cancelled: () -> Boolean, onDispatch: () -> Unit): String {
        checkCancelled(cancelled)
        return calls.execute(request(uri).get().build(), 60_000, cancelled, onDispatch) { response ->
            check(response.isSuccessful) { "云盘暂时无法连接（HTTP ${response.code()}）" }
            readLimited(response, cancelled)
        }
    }

    private fun request(uri: URI) = Request.Builder().url(trustedUri(uri.toString()).toString())
        .header("Accept", "application/json,text/html")
        .header("User-Agent", "RingFitnessSteps/0.7")

    private fun readLimited(response: Response, cancelled: () -> Boolean): String = requireNotNull(response.body()).byteStream().use { input ->
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            checkCancelled(cancelled)
            val count = input.read(buffer)
            if (count < 0) break
            require(bytes.size() + count <= 1_048_576) { "云盘响应过大" }
            bytes.write(buffer, 0, count)
        }
        bytes.toString(Charsets.UTF_8.name())
    }

    companion object {
        private const val HOST = "cloud.tsinghua.edu.cn"
        /** Network-attempt budget: two minutes overhead plus 16 KiB/s for the actual archive size.
         * This bounds one retryable transfer; it does not restrict the duration of a capture. */
        internal fun uploadDeadlineMillis(bytes: Long): Long {
            require(bytes >= 0)
            val seconds = bytes / 16_384 + if (bytes % 16_384 == 0L) 0 else 1
            return if (seconds > (Long.MAX_VALUE - 120_000) / 1000) Long.MAX_VALUE else 120_000 + seconds * 1000
        }
        internal fun validateLink(value: String): URI = trustedUri(value).also {
            require(it.path.matches(Regex("/u/d/[A-Za-z0-9]+/?")) && it.query == null) { "请检查实验上传配置" }
        }
        private fun trustedUri(value: String): URI = URI(value).also {
            require(it.scheme == "https" && it.host == HOST && (it.port == -1 || it.port == 443) &&
                it.userInfo == null && it.fragment == null) { "请检查实验上传配置" }
        }
        internal fun parseReceipt(response: String, expectedBytes: Long): RemoteSessionReceipt {
            val array = JsonParser.parseString(response).asJsonArray
            require(array.size() == 1) { "云盘回执不完整，请重试" }
            val row = array.single().asJsonObject
            val name = row.get("name").asString
            val id = row.get("id").asString
            val size = row.get("size")
            require(size.isJsonPrimitive && size.asJsonPrimitive.isNumber && size.asString.matches(Regex("[0-9]+")))
            require(name.isNotBlank() && '/' !in name && '\\' !in name && id.matches(Regex("[a-fA-F0-9]{40,64}")) && size.asLong == expectedBytes) {
                "云盘回执与本次文件不一致，请重试"
            }
            return RemoteSessionReceipt(name, id, size.asLong)
        }
        private fun checkCancelled(cancelled: () -> Boolean) {
            if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedException("Upload interrupted")
        }
    }
}
