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
import android.net.Uri
import android.os.PersistableBundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object UploadScheduler {
    fun enqueue(context: Context, sessionId: String) {
        val store = UploadSessionStore(context)
        store.update(sessionId) { payload ->
            payload
                .put("state", UploadSessionStore.STATE_QUEUED)
                .put("user_decision", UploadSessionStore.DECISION_UPLOAD_NOW)
                .put("attempt_count", 0)
            payload.remove("error")
            payload.remove("wifi_only")
        }
        val jobId = sessionId.hashCode() and Int.MAX_VALUE
        val extras = PersistableBundle().apply { putString(KEY_SESSION_ID, sessionId) }
        val job = JobInfo.Builder(jobId, ComponentName(context, UploadJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setExtras(extras)
            .build()
        val result = context.getSystemService(JobScheduler::class.java).schedule(job)
        if (result != JobScheduler.RESULT_SUCCESS) {
            store.update(sessionId) {
                it.put("state", UploadSessionStore.STATE_FAILED).put("error", "无法加入系统上传队列")
            }
        }
    }

    fun migrateLegacyWifiJobs(context: Context) {
        val store = UploadSessionStore(context)
        store.list().forEach { session ->
            if (session.state == UploadSessionStore.STATE_CAPTURING ||
                session.state == UploadSessionStore.STATE_UPLOADED
            ) return@forEach
            val wasWifiOnly = runCatching {
                store.read(session.sessionId).optBoolean("wifi_only", false)
            }.getOrDefault(false)
            if (!wasWifiOnly) return@forEach
            cancel(context, session.sessionId)
            store.update(session.sessionId) { payload ->
                payload
                    .put("state", UploadSessionStore.STATE_PENDING)
                    .put("user_decision", UploadSessionStore.DECISION_DEFERRED)
                payload.remove("wifi_only")
                payload.remove("error")
            }
        }
    }

    fun cancel(context: Context, sessionId: String) {
        context.getSystemService(JobScheduler::class.java)
            .cancel(sessionId.hashCode() and Int.MAX_VALUE)
    }

    private const val KEY_SESSION_ID = "session_id"
}

class UploadJobService : JobService() {
    private val store by lazy { UploadSessionStore(this) }
    private val executor = Executors.newCachedThreadPool()
    private val cancellations = ConcurrentHashMap<Int, AtomicBoolean>()

    override fun onStartJob(params: JobParameters): Boolean {
        val sessionId = params.extras.getString(KEY_SESSION_ID) ?: return false
        val cancelled = AtomicBoolean(false)
        cancellations[params.jobId] = cancelled
        startForeground(notificationId(sessionId), uploadNotification("正在准备上传…", 0))
        executor.execute {
            val retry = runUpload(sessionId, params.jobId)
            cancellations.remove(params.jobId)
            if (cancellations.isEmpty()) stopForeground(STOP_FOREGROUND_REMOVE)
            if (!cancelled.get()) jobFinished(params, retry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        cancellations[params.jobId]?.set(true)
        params.extras.getString(KEY_SESSION_ID)?.let { sessionId ->
            runCatching {
                store.update(sessionId) {
                    it.put("state", UploadSessionStore.STATE_QUEUED)
                        .put("error", "系统暂停了上传，将在网络允许时重试")
                }
            }
        }
        return true
    }

    override fun onDestroy() {
        cancellations.values.forEach { it.set(true) }
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun runUpload(sessionId: String, jobId: Int): Boolean {
        BuildConfig.STUDY_UPLOAD_LINK.trim().takeIf { it.isNotBlank() }?.let { configured ->
            store.migrateUploadLink(if (configured.endsWith('/')) configured else "$configured/")
        }
        val attempt = runCatching {
            val payload = store.read(sessionId)
            val next = payload.optInt("attempt_count", 0) + 1
            store.update(sessionId) { it.put("attempt_count", next) }
            next
        }.getOrDefault(1)
        return try {
            val payload = store.read(sessionId)
            val archive = prepareArchive(payload)
            store.update(sessionId) {
                it.put("state", UploadSessionStore.STATE_UPLOADING).remove("error")
            }
            val uploadResult = upload(payload.getString("upload_link"), archive, sessionId, jobId)
            val archiveSha256 = sha256(FileInputStream(archive))
            store.update(sessionId) {
                it.put("state", UploadSessionStore.STATE_UPLOADED)
                    .put("uploaded_at", Instant.now().toString())
                    .put("archive_bytes", archive.length())
                    .put("archive_sha256", archiveSha256)
                    .put("remote_file_name", uploadResult.first)
                    .put("remote_file_id", uploadResult.second)
                    .remove("error")
            }
            archive.delete()
            notifyFinished(sessionId, true, "RingFitness 数据已上传，本地文件仍保留")
            false
        } catch (error: PermanentUploadException) {
            store.update(sessionId) {
                it.put("state", UploadSessionStore.STATE_FAILED).put("error", error.message ?: "上传失败")
            }
            notifyFinished(sessionId, false, error.message ?: "上传失败，请检查被试绑定")
            false
        } catch (error: Exception) {
            store.update(sessionId) {
                it.put(
                    "state",
                    if (attempt >= MAX_RETRIES) UploadSessionStore.STATE_FAILED else UploadSessionStore.STATE_QUEUED,
                ).put("error", error.message ?: error.javaClass.simpleName)
            }
            if (attempt >= MAX_RETRIES) {
                notifyFinished(sessionId, false, "上传多次失败，数据已保存在本地暂存")
                false
            } else {
                true
            }
        }
    }

    private fun prepareArchive(payload: JSONObject): File {
        val sessionId = payload.getString("session_id")
        val archive = store.archiveFile(
            sessionId,
            payload.getString("participant_name"),
            payload.getLong("started_at_ms"),
        )
        if (archive.isFile && archive.length() > 0L) return archive
        store.update(sessionId) { it.put("state", UploadSessionStore.STATE_PACKING).remove("error") }
        val temporary = File(archive.parentFile, "${archive.name}.part")
        temporary.delete()
        val captures = payload.getJSONArray("captures")
        require(captures.length() > 0) { "本次会话没有可上传的采集文件" }
        val fileManifest = JSONArray()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(temporary), BUFFER_SIZE)).use { zip ->
            for (index in 0 until captures.length()) {
                val capture = captures.getJSONObject(index)
                val uri = Uri.parse(capture.getString("uri"))
                val fileName = capture.getString("file")
                val signal = capture.getString("signal")
                val folder = when {
                    signal == CaptureKind.HEALTH_RAW_V2.fileLabel -> "ring"
                    signal.startsWith("imu") -> "imu"
                    signal.startsWith("polar") -> "polar"
                    else -> "ppg"
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                zip.putNextEntry(ZipEntry("$folder/$fileName"))
                applicationContext.contentResolver.openInputStream(uri)?.use { raw ->
                    BufferedInputStream(raw, BUFFER_SIZE).use { source ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            zip.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            bytes += count
                        }
                    }
                } ?: throw PermanentUploadException("无法读取采集文件：$fileName")
                zip.closeEntry()
                val manifestItem = JSONObject()
                        .put("path", "$folder/$fileName")
                        .put("signal", signal)
                        .put("bytes", bytes)
                        .put("sha256", digest.digest().toHex())
                        .put("sample_count", capture.optLong("sample_count"))
                        .put("started_at_ms", capture.optLong("started_at_ms"))
                        .put("ended_at_ms", capture.optLong("ended_at_ms"))
                if (signal == CaptureKind.HEALTH_RAW_V2.fileLabel) {
                    manifestItem.put("format", HealthRawV2.FORMAT).put("media_type", HealthRawV2.MIME)
                }
                fileManifest.put(manifestItem)
            }
            val publicManifest = JSONObject()
                .put(
                    "version",
                    if ((0 until captures.length()).any {
                            captures.getJSONObject(it).optString("signal") == CaptureKind.HEALTH_RAW_V2.fileLabel
                        }
                    ) 2 else 1,
                )
                .put("session_id", sessionId)
                .put("participant_id", payload.getString("participant_id"))
                .put("participant_name", payload.getString("participant_name"))
                .put(
                    "capture_purpose",
                    CapturePurpose.fromWireValue(payload.optString("capture_purpose")).wireValue,
                )
                .put("ring_name", payload.opt("ring_name"))
                .put("started_at_ms", payload.getLong("started_at_ms"))
                .put("ended_at_ms", payload.getLong("ended_at_ms"))
                .put("timezone", payload.getString("timezone"))
                .put("app_version", appVersion())
                .put(
                    "collection_modes",
                    payload.optJSONObject("collection_modes") ?: JSONObject(),
                )
                .put("created_at", Instant.now().toString())
                .put("files", fileManifest)
            if (CapturePurpose.fromWireValue(payload.optString("capture_purpose")) == CapturePurpose.DAILY_ACTIVITY) {
                publicManifest
                    .put("activity_schema", "daily_activity_v1")
                    .put("activity_code", payload.getString("activity_code"))
                RingPlacement.fromWireValue(payload.optString("ring_placement"))?.let { placement ->
                    publicManifest
                        .put("ring_placement_schema", RingPlacement.SCHEMA)
                        .put("ring_placement", placement.wireValue)
                        .put("ring_hand", placement.hand)
                        .put("ring_finger", placement.finger)
                }
            }
            payload.optJSONObject("subjective_feedback")?.let { feedback ->
                publicManifest.put("subjective_feedback", JSONObject(feedback.toString()))
            }
            writeZipText(zip, "manifest.json", publicManifest.toString(2))
            payload.optJSONObject("summary")?.let {
                writeZipText(zip, "metadata/session_summary_${sessionId}.json", it.toString(2))
            }
        }
        check(temporary.renameTo(archive) || run { archive.delete(); temporary.renameTo(archive) }) {
            "无法完成上传包"
        }
        store.update(sessionId) {
            it.put("archive_file", archive.absolutePath).put("archive_bytes", archive.length())
        }
        return archive
    }

    private fun upload(
        uploadPageLink: String,
        archive: File,
        sessionId: String,
        jobId: Int,
    ): Pair<String, String> {
        val pageUri = Uri.parse(uploadPageLink)
        if (pageUri.scheme != "https" || pageUri.host != ALLOWED_HOST) {
            throw PermanentUploadException("上传链接不是受支持的清华云盘地址")
        }
        val token = Regex("/u/d/([A-Za-z0-9]+)/?").find(pageUri.path.orEmpty())?.groupValues?.get(1)
            ?: throw PermanentUploadException("上传链接缺少有效 token")
        val pageHtml = getText(uploadPageLink)
        val parentDir = Regex("path:\\s*\"([^\"]+)\"").find(pageHtml)?.groupValues?.get(1)
            ?: throw PermanentUploadException("无法从上传链接取得云盘目标目录")
        if (!parentDir.startsWith('/') || ".." in parentDir) {
            throw PermanentUploadException("云盘目标目录无效")
        }
        val origin = "https://$ALLOWED_HOST"
        val uploadMetadata = JSONObject(getText("$origin/api/v2.1/upload-links/$token/upload/"))
        val uploadUrl = uploadMetadata.getString("upload_link")
        val uploadUri = Uri.parse(uploadUrl)
        if (uploadUri.scheme != "https" || uploadUri.host != ALLOWED_HOST) {
            throw PermanentUploadException("云盘返回了不受信任的上传地址")
        }
        updateUploadNotification(sessionId, "正在上传 ${archive.name}", 10)
        return multipartUpload("$uploadUrl?ret-json=1", parentDir, archive, sessionId, jobId)
    }

    private fun multipartUpload(
        url: String,
        parentDir: String,
        file: File,
        sessionId: String,
        jobId: Int,
    ): Pair<String, String> {
        val boundary = "----RingFitness${UUID.randomUUID().toString().replace("-", "")}"
        val prefix = ByteArrayOutputStream().apply {
            writeFormField(this, boundary, "parent_dir", parentDir)
            writeFormField(this, boundary, "replace", "0")
            write("--$boundary\r\n".toByteArray())
            write("Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n".toByteArray())
            write("Content-Type: application/zip\r\n\r\n".toByteArray())
        }.toByteArray()
        val suffix = "\r\n--$boundary--\r\n".toByteArray()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = UPLOAD_TIMEOUT_MS
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("User-Agent", USER_AGENT)
            setFixedLengthStreamingMode(prefix.size.toLong() + file.length() + suffix.size)
        }
        try {
            connection.outputStream.use { raw ->
                BufferedOutputStream(raw, BUFFER_SIZE).use { output ->
                    output.write(prefix)
                    FileInputStream(file).use { source ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var uploaded = 0L
                        var lastProgress = -1
                        while (true) {
                            if (cancellations[jobId]?.get() == true) throw InterruptedException("Upload cancelled")
                            val count = source.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            uploaded += count
                            val progress = (uploaded * 100L / file.length().coerceAtLeast(1L)).toInt()
                            if (progress >= lastProgress + 5) {
                                lastProgress = progress
                                updateUploadNotification(sessionId, "正在上传 ${file.name}", progress)
                            }
                        }
                    }
                    output.write(suffix)
                }
            }
            val status = connection.responseCode
            val response = readResponse(connection)
            if (status !in 200..299) {
                if (status in 400..499 && status != 408 && status != 429) {
                    throw PermanentUploadException("清华云盘拒绝上传（HTTP $status）：$response")
                }
                error("清华云盘暂时不可用（HTTP $status）：$response")
            }
            val item = JSONArray(response).getJSONObject(0)
            return item.getString("name") to item.optString("id")
        } finally {
            connection.disconnect()
        }
    }

    private fun getText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json,text/html")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val status = connection.responseCode
            val response = readResponse(connection)
            if (status !in 200..299) {
                if (status in 400..499 && status != 408 && status != 429) {
                    throw PermanentUploadException("上传链接不可用（HTTP $status）")
                }
                error("读取清华云盘失败（HTTP $status）")
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun readResponse(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
    }

    private fun uploadNotification(text: String, progress: Int): Notification {
        createNotificationChannel()
        val intent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("RingFitness 数据上传")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress.coerceIn(0, 100), progress <= 0)
            .setContentIntent(intent)
            .build()
    }

    private fun updateUploadNotification(sessionId: String, text: String, progress: Int) {
        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(notificationId(sessionId), uploadNotification(text, progress))
    }

    private fun notifyFinished(sessionId: String, success: Boolean, text: String) {
        createNotificationChannel()
        val intent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(if (success) android.R.drawable.stat_sys_upload_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (success) "RingFitness 上传完成" else "RingFitness 上传失败")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(completionNotificationId(sessionId), notification)
    }

    private fun createNotificationChannel() {
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "RingFitness 数据上传", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notificationId(sessionId: String): Int = NOTIFICATION_BASE + (sessionId.hashCode() and 0x0FFF)
    private fun completionNotificationId(sessionId: String): Int = COMPLETION_NOTIFICATION_BASE +
        (sessionId.hashCode() and 0x0FFF)

    private fun appVersion(): String = runCatching {
        applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0).versionName
    }.getOrNull().orEmpty()

    private fun writeZipText(zip: ZipOutputStream, path: String, text: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun sha256(input: FileInputStream): String = input.use { source ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val count = source.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun writeFormField(output: ByteArrayOutputStream, boundary: String, name: String, value: String) {
        output.write("--$boundary\r\n".toByteArray())
        output.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
        output.write(value.toByteArray(Charsets.UTF_8))
        output.write("\r\n".toByteArray())
    }

    private class PermanentUploadException(message: String) : RuntimeException(message)

    companion object {
        const val KEY_SESSION_ID = "session_id"
        private const val ALLOWED_HOST = "cloud.tsinghua.edu.cn"
        private const val USER_AGENT = "RingFitness-Android/0.1"
        private const val CHANNEL_ID = "ringfitness_upload"
        private const val NOTIFICATION_BASE = 8400
        private const val COMPLETION_NOTIFICATION_BASE = 14_000
        private const val BUFFER_SIZE = 64 * 1024
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val UPLOAD_TIMEOUT_MS = 30 * 60_000
        private const val MAX_RETRIES = 5
    }
}
