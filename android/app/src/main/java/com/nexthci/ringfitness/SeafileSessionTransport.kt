package com.nexthci.ringfitness

import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

data class RemoteSessionReceipt(val fileName: String, val fileId: String, val bytes: Long)

fun interface SessionUploadTransport {
    fun upload(link: String, archive: File, cancelled: () -> Boolean): RemoteSessionReceipt
}

/** Upload-link transport shared in shape with the legacy worker; no participant account required. */
class SeafileSessionTransport : SessionUploadTransport {
    override fun upload(link: String, archive: File, cancelled: () -> Boolean): RemoteSessionReceipt {
        val page = validateLink(link)
        checkCancelled(cancelled)
        val html = get(page, cancelled)
        val parent = Regex("path:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            ?: error("上传位置暂时无法读取")
        require(parent.startsWith('/') && ".." !in parent && '\r' !in parent && '\n' !in parent)
        val token = page.path.trimEnd('/').substringAfterLast('/')
        val metadata = JsonParser.parseString(get(URI("https://$HOST/api/v2.1/upload-links/$token/upload/"), cancelled)).asJsonObject
        val upload = trustedUri(metadata.get("upload_link").asString)
        val destination = URI(upload.toString() + if (upload.rawQuery == null) "?ret-json=1" else "&ret-json=1")
        val boundary = "----RingFitness${UUID.randomUUID().toString().replace("-", "")}"
        require(archive.name.matches(Regex("ringfitness-session-[a-f0-9-]+\\.zip")))
        val prefix = ByteArrayOutputStream().apply {
            for ((key, value) in listOf("parent_dir" to parent, "replace" to "0")) {
                write("--$boundary\r\nContent-Disposition: form-data; name=\"$key\"\r\n\r\n$value\r\n".toByteArray())
            }
            write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${archive.name}\"\r\nContent-Type: application/zip\r\n\r\n".toByteArray())
        }.toByteArray()
        val suffix = "\r\n--$boundary--\r\n".toByteArray()
        val connection = open(destination).apply {
            requestMethod = "POST"; doOutput = true; readTimeout = 120_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setFixedLengthStreamingMode(prefix.size.toLong() + archive.length() + suffix.size)
        }
        try {
            connection.outputStream.buffered().use { out ->
                out.write(prefix)
                archive.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkCancelled(cancelled)
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                    }
                }
                out.write(suffix)
            }
            checkCancelled(cancelled)
            check(connection.responseCode in 200..299) { "上传暂未完成（HTTP ${connection.responseCode}）" }
            return parseReceipt(readLimited(connection), archive.length())
        } finally { connection.disconnect() }
    }

    private fun get(uri: URI, cancelled: () -> Boolean): String {
        checkCancelled(cancelled)
        val connection = open(uri)
        try {
            check(connection.responseCode in 200..299) { "云盘暂时无法连接（HTTP ${connection.responseCode}）" }
            checkCancelled(cancelled)
            return readLimited(connection)
        } finally { connection.disconnect() }
    }

    private fun open(uri: URI) = (trustedUri(uri.toString()).toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000; readTimeout = 30_000; instanceFollowRedirects = false
        setRequestProperty("Accept", "application/json,text/html")
        setRequestProperty("User-Agent", "RingFitnessSteps/0.7")
    }

    private fun readLimited(connection: HttpURLConnection): String = connection.inputStream.use { input ->
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(bytes.size() + count <= 1_048_576) { "云盘响应过大" }
            bytes.write(buffer, 0, count)
        }
        bytes.toString(Charsets.UTF_8.name())
    }

    companion object {
        private const val HOST = "cloud.tsinghua.edu.cn"
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
