package com.nexthci.ringfitness

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId

data class UploadSessionSummary(
    val sessionId: String,
    val participantId: String,
    val participantName: String,
    val ringAddress: String?,
    val ringName: String?,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val captureCount: Int,
    val sampleCount: Long,
    val state: String,
    val error: String?,
    val archiveBytes: Long?,
    val userDecision: String?,
    val healthSessionId: Int?,
    val ringTotalBytes: Long,
    val ringDownloadedBytes: Long,
    val capturePurpose: CapturePurpose,
    val activityCode: DailyActivity?,
    val ringPlacement: RingPlacement?,
    val subjectiveFeedback: SubjectiveFeedback?,
)

data class ClosedCapture(
    val groupId: String,
    val kind: CaptureKind,
    val uri: Uri,
    val displayName: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val sampleCount: Long,
)

data class ExportedCSV(val uri: Uri, val displayName: String)

class UploadSessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val sessionDir = File(appContext.filesDir, "upload_sessions").apply { mkdirs() }
    val archiveDir = File(appContext.filesDir, "upload_archives").apply { mkdirs() }

    @Synchronized
    fun beginSession(
        sessionId: String,
        profile: ParticipantProfile,
        startedAtMs: Long,
        ringAddress: String?,
        ringName: String?,
        capturePurpose: CapturePurpose = CapturePurpose.SLEEP_HR,
        activityCode: DailyActivity? = null,
        ringPlacement: RingPlacement? = null,
        uploadLink: String = profile.uploadLink,
    ) {
        val file = sessionFile(sessionId)
        if (file.exists()) return
        write(
            file,
            JSONObject()
                .put("version", 1)
                .put("session_id", sessionId)
                .put("participant_id", profile.participantId)
                .put("participant_name", profile.displayName)
                .put("upload_link", ParticipantProfileStore.normalizeUploadLink(uploadLink))
                .put("capture_purpose", capturePurpose.wireValue)
                .put("activity_schema", if (capturePurpose == CapturePurpose.DAILY_ACTIVITY) "daily_activity_v1" else JSONObject.NULL)
                .put("activity_code", activityCode?.wireValue ?: JSONObject.NULL)
                .put(
                    "ring_placement_schema",
                    if (capturePurpose == CapturePurpose.DAILY_ACTIVITY && ringPlacement != null) RingPlacement.SCHEMA else JSONObject.NULL,
                )
                .put("ring_placement", ringPlacement?.wireValue ?: JSONObject.NULL)
                .put("ring_hand", ringPlacement?.hand ?: JSONObject.NULL)
                .put("ring_finger", ringPlacement?.finger ?: JSONObject.NULL)
                .put("ring_address", ringAddress ?: JSONObject.NULL)
                .put("ring_name", ringName ?: JSONObject.NULL)
                .put("started_at_ms", startedAtMs)
                .put("ended_at_ms", JSONObject.NULL)
                .put("timezone", ZoneId.systemDefault().id)
                .put("state", STATE_CAPTURING)
                .put("captures", JSONArray())
                .put("created_at", Instant.now().toString()),
        )
    }

    @Synchronized
    fun recordCapture(capture: ClosedCapture) {
        val payload = read(capture.groupId)
        payload.getJSONArray("captures").put(
            JSONObject()
                .put("kind", capture.kind.name)
                .put("signal", capture.kind.fileLabel)
                .put("uri", capture.uri.toString())
                .put("file", capture.displayName)
                .put("started_at_ms", capture.startedAtMs)
                .put("ended_at_ms", capture.endedAtMs)
                .put("sample_count", capture.sampleCount),
        )
        write(sessionFile(capture.groupId), payload)
    }

    @Synchronized
    fun finalizeSession(sessionId: String, endedAtMs: Long, summaryJson: String) {
        val payload = read(sessionId)
        payload
            .put("ended_at_ms", endedAtMs)
            .put("state", STATE_PENDING)
            .put("summary", JSONObject(summaryJson))
            .put("updated_at", Instant.now().toString())
        write(sessionFile(sessionId), payload)
    }

    @Synchronized
    fun read(sessionId: String): JSONObject = JSONObject(sessionFile(sessionId).readText(Charsets.UTF_8))

    @Synchronized
    fun update(sessionId: String, changes: (JSONObject) -> Unit) {
        val payload = read(sessionId)
        changes(payload)
        payload.put("updated_at", Instant.now().toString())
        write(sessionFile(sessionId), payload)
    }

    @Synchronized
    fun markUserDecision(sessionId: String, decision: String) {
        update(sessionId) { it.put("user_decision", decision) }
    }

    @Synchronized
    fun setCollectionModes(sessionId: String, ouraSleep: Boolean, polarHrRr: Boolean) {
        update(sessionId) {
            it.put(
                "collection_modes",
                JSONObject().put("oura_sleep", ouraSleep).put("polar_hr_rr", polarHrRr),
            )
        }
    }

    @Synchronized
    fun markSubjectiveFeedbackRequired(sessionId: String, target: SubjectiveFeedbackTarget) {
        update(sessionId) {
            it.put("subjective_feedback_required", true)
                .put("subjective_feedback_target", target.wireValue)
                .remove("subjective_feedback")
        }
    }

    @Synchronized
    fun saveSubjectiveFeedback(sessionId: String, feedback: SubjectiveFeedback) {
        val feedbackPayload = JSONObject()
            .put("schema", SubjectiveFeedback.SCHEMA)
            .put("target", feedback.target.wireValue)
            .put("comment", feedback.comment)
            .put("recorded_at_ms", feedback.recordedAtMs)
        feedback.score?.let { feedbackPayload.put("score", it) }
        feedback.activityDetail?.let { feedbackPayload.put("activity_detail", it) }
        update(sessionId) {
            it.put("subjective_feedback_required", false)
                .put("subjective_feedback_target", feedback.target.wireValue)
                .put("subjective_feedback", feedbackPayload)
        }
    }

    @Synchronized
    fun markRingTransfer(
        sessionId: String,
        state: String,
        healthSessionId: Int? = null,
        totalBytes: Long? = null,
        downloadedBytes: Long? = null,
        error: String? = null,
    ) {
        update(sessionId) { payload ->
            payload.put("state", state)
            healthSessionId?.let { payload.put("health_session_id", it) }
            totalBytes?.let { payload.put("ring_total_bytes", it) }
            downloadedBytes?.let { payload.put("ring_downloaded_bytes", it) }
            if (error.isNullOrBlank()) payload.remove("error") else payload.put("error", error)
        }
    }

    @Synchronized
    fun updateRingProgress(sessionId: String, downloadedBytes: Long, totalBytes: Long) {
        update(sessionId) {
            it.put("state", STATE_RING_DOWNLOADING)
                .put("ring_downloaded_bytes", downloadedBytes)
                .put("ring_total_bytes", totalBytes)
                .remove("error")
        }
    }

    @Synchronized
    fun saveRingAnchor(sessionId: String, item: HealthMessage.ListItem) {
        update(sessionId) {
            it.put("health_session_id", item.sessionId)
                .put("ring_total_bytes", item.bytes)
                .put("ring_record_count", item.records)
                .put("ring_anchor_uptime_ms", item.uptimeMs)
                .put("ring_anchor_unix_ms", item.unixMs)
        }
    }

    fun ringTaskForParticipant(participantId: String): UploadSessionSummary? = list()
        .firstOrNull { it.participantId == participantId && it.state in RING_TRANSFER_STATES }

    fun blockingTaskForRing(ringAddress: String?): UploadSessionSummary? {
        if (ringAddress.isNullOrBlank()) return null
        return list().firstOrNull {
            it.ringAddress.equals(ringAddress, ignoreCase = true) && it.state in RING_TRANSFER_STATES
        }
    }

    fun anyRingTransferTask(): UploadSessionSummary? = list()
        .firstOrNull { it.state in RING_TRANSFER_STATES }

    fun failedUploadsForParticipant(
        participantId: String,
        capturePurpose: CapturePurpose? = null,
    ): List<UploadSessionSummary> = list()
        .filter {
            it.participantId == participantId && it.state == STATE_FAILED &&
                (capturePurpose == null || it.capturePurpose == capturePurpose)
        }

    fun uploadedForParticipant(
        participantId: String,
        capturePurpose: CapturePurpose? = null,
    ): List<UploadSessionSummary> = list()
        .filter {
            it.participantId == participantId && it.state == STATE_UPLOADED &&
                (capturePurpose == null || it.capturePurpose == capturePurpose)
        }

    @Synchronized
    fun captures(sessionId: String): List<JSONObject> {
        val items = read(sessionId).optJSONArray("captures") ?: JSONArray()
        return (0 until items.length()).map { items.getJSONObject(it) }
    }

    @Synchronized
    fun exportedCSV(sessionId: String): List<ExportedCSV> {
        val items = read(sessionId).optJSONArray("csv_exports") ?: JSONArray()
        return (0 until items.length()).mapNotNull { index ->
            items.optJSONObject(index)?.let {
                ExportedCSV(Uri.parse(it.optString("uri")), it.optString("file"))
            }
        }
    }

    @Synchronized
    fun saveCSVExports(sessionId: String, captures: List<ConvertedHealthCapture>) {
        update(sessionId) { payload ->
            val previous = payload.optJSONArray("csv_exports") ?: JSONArray()
            for (index in 0 until previous.length()) {
                previous.optJSONObject(index)?.optString("uri")?.takeIf(String::isNotBlank)?.let {
                    runCatching { appContext.contentResolver.delete(Uri.parse(it), null, null) }
                }
            }
            payload.put("csv_exports", JSONArray().apply {
                captures.forEach { put(JSONObject().put("uri", it.uri.toString()).put("file", it.displayName)) }
            })
        }
    }

    /** Sessions that are actually waiting in local storage for a user upload action. */
    fun pendingCount(): Int = list().count { session ->
        session.endedAtMs != null && when (session.state) {
            STATE_FAILED -> true
            STATE_PENDING -> session.userDecision != DECISION_UPLOAD_NOW
            else -> false
        }
    }

    @Synchronized
    fun list(): List<UploadSessionSummary> = sessionDir.listFiles()
        ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
        ?.mapNotNull { file -> runCatching { summary(JSONObject(file.readText(Charsets.UTF_8))) }.getOrNull() }
        ?.sortedByDescending { it.startedAtMs }
        .orEmpty()

    fun archiveFile(sessionId: String, participantName: String, startedAtMs: Long): File {
        val safeName = participantName.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val date = Instant.ofEpochMilli(startedAtMs).atZone(ZoneId.systemDefault()).toLocalDate()
        return File(archiveDir, "${safeName}__${date}__${sessionId}.zip")
    }

    @Synchronized
    fun discardSession(sessionId: String) {
        sessionFile(sessionId).delete()
        archiveDir.listFiles()?.filter { it.name.contains(sessionId) }?.forEach { it.delete() }
    }

    /** Removes Android-owned capture files only after the user explicitly deletes a session. */
    @Synchronized
    fun deleteCaptureFiles(sessionId: String): Int {
        val payload = read(sessionId)
        val captures = payload.optJSONArray("captures") ?: JSONArray()
        var failures = 0
        for (index in 0 until captures.length()) {
            val uriText = captures.optJSONObject(index)?.optString("uri").orEmpty()
            if (uriText.isBlank()) continue
            runCatching { appContext.contentResolver.delete(Uri.parse(uriText), null, null) }
                .onFailure { failures += 1 }
        }
        val exports = payload.optJSONArray("csv_exports") ?: JSONArray()
        for (index in 0 until exports.length()) {
            val uriText = exports.optJSONObject(index)?.optString("uri").orEmpty()
            if (uriText.isBlank()) continue
            runCatching { appContext.contentResolver.delete(Uri.parse(uriText), null, null) }
                .onFailure { failures += 1 }
        }
        archiveDir.listFiles()?.filter { it.name.contains(sessionId) }?.forEach { file ->
            if (file.exists() && !file.delete()) failures += 1
        }
        if (failures == 0) {
            payload.put("local_files_deleted_at", Instant.now().toString())
                .remove("local_cleanup_error")
        } else {
            payload.put("local_cleanup_error", "$failures 个本地文件未能删除")
        }
        write(sessionFile(sessionId), payload)
        return failures
    }

    /** Deletes a finalized session locally and removes its upload receipt. */
    @Synchronized
    fun deleteSessionData(sessionId: String): Int {
        val failures = deleteCaptureFiles(sessionId)
        if (failures == 0) discardSession(sessionId)
        return failures
    }

    @Synchronized
    fun migrateUploadLink(uploadLink: String) {
        sessionDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            ?.forEach { file ->
                val payload = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull()
                    ?: return@forEach
                if (CapturePurpose.fromWireValue(payload.optString("capture_purpose")) != CapturePurpose.SLEEP_HR) {
                    return@forEach
                }
                if (payload.optString("state") == STATE_UPLOADED) return@forEach
                if (payload.optString("upload_link") == uploadLink) return@forEach
                payload.put("upload_link", uploadLink).put("updated_at", Instant.now().toString())
                write(file, payload)
            }
    }

    private fun summary(payload: JSONObject): UploadSessionSummary {
        val captures = payload.optJSONArray("captures") ?: JSONArray()
        var samples = 0L
        for (index in 0 until captures.length()) samples += captures.getJSONObject(index).optLong("sample_count")
        return UploadSessionSummary(
            sessionId = payload.getString("session_id"),
            participantId = payload.optString("participant_id"),
            participantName = payload.getString("participant_name"),
            ringAddress = payload.optString("ring_address").takeIf { it.isNotBlank() && it != "null" },
            ringName = payload.optString("ring_name").takeIf { it.isNotBlank() && it != "null" },
            startedAtMs = payload.getLong("started_at_ms"),
            endedAtMs = payload.optLong("ended_at_ms").takeIf { !payload.isNull("ended_at_ms") },
            captureCount = captures.length(),
            sampleCount = samples,
            state = payload.optString("state", STATE_PENDING),
            error = payload.optString("error").takeIf { it.isNotBlank() },
            archiveBytes = payload.optLong("archive_bytes").takeIf { payload.has("archive_bytes") },
            userDecision = payload.optString("user_decision").takeIf { it.isNotBlank() },
            healthSessionId = payload.optInt("health_session_id").takeIf { payload.has("health_session_id") },
            ringTotalBytes = payload.optLong("ring_total_bytes", 0L),
            ringDownloadedBytes = payload.optLong("ring_downloaded_bytes", 0L),
            capturePurpose = CapturePurpose.fromWireValue(payload.optString("capture_purpose")),
            activityCode = DailyActivity.fromWireValue(payload.optString("activity_code")),
            ringPlacement = RingPlacement.fromWireValue(payload.optString("ring_placement")),
            subjectiveFeedback = payload.optJSONObject("subjective_feedback")?.let { feedback ->
                val target = SubjectiveFeedbackTarget.fromWireValue(feedback.optString("target"))
                    ?: return@let null
                val score = if (feedback.has("score") && !feedback.isNull("score")) {
                    feedback.optInt("score", 0).takeIf { it in 1..10 }
                } else null
                SubjectiveFeedback(
                    target = target,
                    score = score,
                    comment = SubjectiveFeedback.normalizedComment(feedback.optString("comment")),
                    activityDetail = SubjectiveFeedback.normalizedActivityDetail(
                        feedback.optString("activity_detail"),
                    ),
                    recordedAtMs = feedback.optLong("recorded_at_ms", 0L),
                )
            },
        )
    }

    private fun sessionFile(sessionId: String): File {
        require(Regex("[A-Za-z0-9-]{8,64}").matches(sessionId)) { "Invalid session id" }
        return File(sessionDir, "$sessionId.json")
    }

    private fun write(file: File, payload: JSONObject) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.part")
        temporary.writeText(payload.toString(2), Charsets.UTF_8)
        check(temporary.renameTo(file) || run { file.delete(); temporary.renameTo(file) }) {
            "无法保存上传会话"
        }
    }

    companion object {
        const val STATE_CAPTURING = "capturing"
        const val STATE_RING_FINALIZING = "ring_finalizing"
        const val STATE_RING_PENDING = "ring_pending"
        const val STATE_RING_DOWNLOADING = "ring_downloading"
        const val STATE_RING_PROCESSING = "ring_processing"
        const val STATE_PENDING = "pending"
        const val STATE_PACKING = "packing"
        const val STATE_QUEUED = "queued"
        const val STATE_UPLOADING = "uploading"
        const val STATE_UPLOADED = "uploaded"
        const val STATE_FAILED = "failed"
        const val DECISION_UPLOAD_NOW = "upload_now"
        const val DECISION_DEFERRED = "deferred"

        val RING_TRANSFER_STATES = setOf(
            STATE_RING_FINALIZING,
            STATE_RING_PENDING,
            STATE_RING_DOWNLOADING,
            STATE_RING_PROCESSING,
        )
    }
}
