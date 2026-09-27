package com.nexthci.ringfitness

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant

data class HeartRateGap(val startedAtMs: Long, val endedAtMs: Long?, val reason: String)

data class SessionHeartRateFile(val fileName: String, val bytes: Long, val sha256: String)

/** One optional H10 stream, bound to the session before the first ring START command. */
data class SessionHeartRate(
    val deviceId: String,
    val deviceName: String,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val sampleCount: Long = 0,
    val firstSampleAtMs: Long? = null,
    val lastSampleAtMs: Long? = null,
    val gaps: List<HeartRateGap> = emptyList(),
    val file: SessionHeartRateFile? = null,
) {
    val status: String get() = when {
        sampleCount == 0L -> "no_samples"
        gaps.isNotEmpty() -> "partial"
        else -> "recorded"
    }

    fun validate(sessionId: String, finalized: Boolean = false) {
        require(deviceId.isNotBlank() && deviceId == deviceId.trim() && deviceName.isNotBlank() && deviceName == deviceName.trim())
        require(startedAtMs > 0 && sampleCount >= 0)
        endedAtMs?.let { require(it > 0) }
        require((sampleCount == 0L) == (firstSampleAtMs == null && lastSampleAtMs == null))
        if (sampleCount > 0) {
            require(firstSampleAtMs != null && lastSampleAtMs != null)
            require(firstSampleAtMs > 0 && lastSampleAtMs > 0)
        }
        gaps.forEach {
            require(it.reason in GAP_REASONS && it.startedAtMs > 0)
            it.endedAtMs?.let { end -> require(end > 0) }
            if (finalized) require(it.endedAtMs != null) { "心率缺口尚未收尾" }
        }
        file?.let {
            require(it.fileName == fileName(sessionId)) { "心率文件不属于本段" }
            require(it.bytes > 0 && Regex("^[a-f0-9]{64}$").matches(it.sha256)) { "心率文件校验信息无效" }
        }
        if (finalized) {
            require(endedAtMs != null) { "心率数据尚未保存完成" }
            require(file != null || (sampleCount == 0L && gaps.any { it.reason == "storage_error" })) {
                "心率文件尚未保存完成"
            }
        }
    }

    internal fun encode(): JsonObject = manifest(this).apply {
        remove("enabled")
        remove("status")
        remove("timestamp_source")
        add("file", file?.let { JsonObject().apply {
            addProperty("file_name", it.fileName)
            addProperty("bytes", it.bytes)
            addProperty("sha256", it.sha256)
        } } ?: JsonNull.INSTANCE)
    }

    companion object {
        const val CSV_HEADER = "timestamp_iso,timestamp_unix_ms,sample_index,hr_bpm,corrected_hr_bpm,ppg_quality,rr_available,contact_supported,contact_status,rr_ms,rr_1_1024s"
        val GAP_REASONS = setOf("disconnected", "process_restart", "stream_error", "storage_error", "no_data")
        fun fileName(sessionId: String): String = "${sessionId}_polar_hr_rr.csv"

        internal fun manifest(value: SessionHeartRate?): JsonObject = JsonObject().apply {
            addProperty("enabled", value != null)
            add("device_id", value?.deviceId?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
            add("device_name", value?.deviceName?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
            addProperty("status", value?.status ?: "not_requested")
            addProperty("timestamp_source", "phone_receipt")
            nullable("started_at_ms", value?.startedAtMs)
            nullable("ended_at_ms", value?.endedAtMs)
            nullable("first_sample_at_ms", value?.firstSampleAtMs)
            nullable("last_sample_at_ms", value?.lastSampleAtMs)
            addProperty("sample_count", value?.sampleCount ?: 0L)
            add("gaps", JsonArray().apply { value?.gaps?.forEach { gap -> add(JsonObject().apply {
                addProperty("started_at_ms", gap.startedAtMs)
                nullable("ended_at_ms", gap.endedAtMs)
                addProperty("reason", gap.reason)
            }) } })
        }

        internal fun decode(value: JsonObject): SessionHeartRate = SessionHeartRate(
            value.string("device_id"), value.string("device_name"), value.long("started_at_ms"),
            value.optionalLong("ended_at_ms"), value.long("sample_count"),
            value.optionalLong("first_sample_at_ms"), value.optionalLong("last_sample_at_ms"),
            value.getAsJsonArray("gaps").map { item -> item.asJsonObject.let {
                HeartRateGap(it.long("started_at_ms"), it.optionalLong("ended_at_ms"), it.string("reason"))
            } },
            if (value.get("file").isJsonNull) null else value.getAsJsonObject("file").let {
                SessionHeartRateFile(it.string("file_name"), it.long("bytes"), it.string("sha256"))
            },
        )

        /** Streaming validation keeps ten-hour recordings off the heap and rejects mismatched CSVs. */
        internal fun verifyFile(directory: File, sessionId: String, value: SessionHeartRate) {
            value.validate(sessionId, finalized = true)
            val entry = value.file ?: return
            val candidate = File(directory, entry.fileName)
            require(!Files.isSymbolicLink(candidate.toPath())) { "心率文件位置无效" }
            val source = candidate.canonicalFile
            require(source.parentFile == directory.canonicalFile && source.name == entry.fileName)
            require(source.isFile && source.length() == entry.bytes) { "心率文件尚未保存完整" }
            val digest = MessageDigest.getInstance("SHA-256")
            source.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            require(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == entry.sha256) {
                "心率文件校验失败，原文件已保留"
            }
            var samples = 0L
            var first: Long? = null
            var last: Long? = null
            source.bufferedReader(Charsets.UTF_8).use { reader ->
                require(reader.readLine() == CSV_HEADER) { "心率文件表头无效" }
                while (true) {
                    val line = reader.readLine() ?: break
                    require(line.length <= 64 * 1024) { "心率记录长度无效" }
                    val row = line.split(',')
                    require(row.size == 11) { "心率记录列数无效" }
                    val timestamp = requireNotNull(row[1].toLongOrNull())
                    require(timestamp > 0)
                    require(Instant.parse(row[0]).toEpochMilli() == timestamp)
                    require(row[2].toLongOrNull() == samples + 1) { "心率记录序号不连续" }
                    require(row[3].toIntOrNull() in 0..65535 && row[4].toIntOrNull() != null)
                    require(row[5].toIntOrNull() != null && row[6] in setOf("true", "false") &&
                        row[7] in setOf("true", "false") && row[8] in setOf("true", "false"))
                    val rrLists = listOf(row[9], row[10]).map { list -> if (list.isEmpty()) emptyList() else list.split('|') }
                    require(rrLists[0].size == rrLists[1].size && rrLists[0].size <= 4096)
                    rrLists.forEach { list -> require(list.all { it.toIntOrNull() in 0..65535 }) }
                    if (first == null) first = timestamp
                    last = timestamp
                    samples++
                }
            }
            require(samples == value.sampleCount && first == value.firstSampleAtMs && last == value.lastSampleAtMs) {
                "心率文件记录量与采集段不一致"
            }
        }

        private fun JsonObject.nullable(name: String, value: Long?) {
            if (value == null) add(name, JsonNull.INSTANCE) else addProperty(name, value)
        }
        private fun JsonObject.string(name: String): String = requireNotNull(get(name)).let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
            it.asString
        }
        private fun JsonObject.long(name: String): Long = requireNotNull(get(name)).let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && Regex("-?[0-9]+").matches(it.asString))
            it.asString.toLong()
        }
        private fun JsonObject.optionalLong(name: String): Long? = if (requireNotNull(get(name)).isJsonNull) null else long(name)
    }
}
