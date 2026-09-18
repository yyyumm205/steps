package com.nexthci.ringfitness

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.StringReader
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

enum class FreeLivingSessionPhase(val wireValue: String) {
    START_REQUESTED("start_requested"),
    COLLECTING("collecting"),
    STOP_REQUESTED("stop_requested"),
    AWAITING_REFERENCE("awaiting_reference"),
}

enum class DeviceBoundarySource(val wireValue: String) {
    DEVICE_TIME_ANCHOR("device_time_anchor"),
    RAW_SAMPLE("raw_sample"),
}

/** Device-derived evidence only. Phone receipt timestamps belong in the separate confirmation fields. */
data class DeviceBoundaryEvidence(
    val source: DeviceBoundarySource,
    val epochMs: Long,
    val deviceUptimeMs: Long?,
    val rawEvidence: String,
)

data class FreeLivingSession(
    val sessionId: String,
    val preparation: PreparationSnapshot,
    val phase: FreeLivingSessionPhase,
    val timeZoneId: String,
    val utcOffsetSeconds: Int,
    val startRequestedAtMs: Long,
    val startConfirmedAtMs: Long? = null,
    val stopRequestedAtMs: Long? = null,
    val stopConfirmedAtMs: Long? = null,
    val startStatusEvidence: HealthMessage.Status? = null,
    val stopStatusEvidence: HealthMessage.Status? = null,
    val startBoundaryEvidence: DeviceBoundaryEvidence? = null,
    val endBoundaryEvidence: DeviceBoundaryEvidence? = null,
) {
    val deviceSessionId: Int? get() = startStatusEvidence?.sessionId
    val startedAtMs: Long? get() = startBoundaryEvidence?.epochMs
    // Preserve contradictory raw evidence, while keeping the effective end explicitly unknown.
    val endedAtMs: Long? get() = endBoundaryEvidence?.epochMs?.takeUnless {
        startedAtMs?.let { start -> it < start } == true
    }
    val captureBoundaryStatus: String get() =
        if (startedAtMs != null && endedAtMs != null) "confirmed" else "uncertain"
    val timingWarnings: List<String> get() = buildList {
        val phoneTimes = listOfNotNull(startRequestedAtMs, startConfirmedAtMs, stopRequestedAtMs, stopConfirmedAtMs)
        if (phoneTimes.zipWithNext().any { (before, after) -> after < before }) add("phone_clock_order_uncertain")
        if (endBoundaryEvidence != null && endedAtMs == null) add("device_boundary_order_uncertain")
    }
}

/**
 * T2a journal for one session whose raw data is not yet safely stored on the phone.
 * No API releases its pending-data protection in this slice. Callers must durably record requests
 * before sending BLE commands and correlate a start reply with that request before confirming it.
 * This store neither sends commands nor treats a GATT write callback as capture confirmation.
 * Confirmations retain their first evidence. Enriching unknown boundaries from a later download
 * is a separate future operation, not a side effect of repeating a confirmation.
 * Place the journal directly in an existing app-private directory (for example Context.filesDir).
 */
class FreeLivingSessionStore internal constructor(
    file: File,
    private val commitFile: (File, File) -> Unit,
    private val syncDirectory: (File) -> Unit,
) {
    constructor(file: File) : this(
        file,
        { source, target ->
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        },
        { directory -> FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) } },
    )

    private val file = file.canonicalFile

    fun read(): FreeLivingSession? = synchronized(processLock) { readLocked() }

    fun requestStart(preparation: PreparationSnapshot, requestedAtMs: Long, timeZoneId: String): FreeLivingSession =
        synchronized(processLock) {
            validatePreparation(preparation)
            require(requestedAtMs > 0) { "开始请求时间无效" }
            val zone = ZoneId.of(timeZoneId)
            val existing = readLocked()
            if (existing != null) {
                require(existing.preparation == preparation) { "已有采集段的编号、位置或戒指不能更改" }
                require(existing.phase == FreeLivingSessionPhase.START_REQUESTED ||
                    existing.phase == FreeLivingSessionPhase.COLLECTING) { "上一段原始数据尚未安全保存，暂不能开始新采集" }
                return@synchronized existing
            }
            persist(FreeLivingSession(
                sessionId = UUID.randomUUID().toString(), preparation = preparation,
                phase = FreeLivingSessionPhase.START_REQUESTED,
                timeZoneId = zone.id,
                utcOffsetSeconds = zone.rules.getOffset(Instant.ofEpochMilli(requestedAtMs)).totalSeconds,
                startRequestedAtMs = requestedAtMs,
            ))
        }

    fun confirmStart(
        sessionId: String,
        ringAddress: String,
        status: HealthMessage.Status,
        receivedAtMs: Long,
        boundary: DeviceBoundaryEvidence? = null,
    ): FreeLivingSession = update(sessionId) { current ->
        validateReply(current, ringAddress, status, receivedAtMs, collecting = true)
        validateBoundary(boundary)
        if (current.startConfirmedAtMs != null) return@update current
        require(current.phase == FreeLivingSessionPhase.START_REQUESTED) { "当前阶段不接受开始确认" }
        current.copy(phase = FreeLivingSessionPhase.COLLECTING, startConfirmedAtMs = receivedAtMs,
            startStatusEvidence = status, startBoundaryEvidence = boundary)
    }

    fun requestStop(sessionId: String, requestedAtMs: Long): FreeLivingSession = update(sessionId) { current ->
        require(requestedAtMs > 0) { "停止请求时间无效" }
        if (current.stopRequestedAtMs != null) return@update current
        require(current.phase == FreeLivingSessionPhase.COLLECTING) { "尚未确认开始采集" }
        current.copy(phase = FreeLivingSessionPhase.STOP_REQUESTED, stopRequestedAtMs = requestedAtMs)
    }

    fun confirmStop(
        sessionId: String,
        ringAddress: String,
        status: HealthMessage.Status,
        receivedAtMs: Long,
        boundary: DeviceBoundaryEvidence? = null,
    ): FreeLivingSession = update(sessionId) { current ->
        validateReply(current, ringAddress, status, receivedAtMs, collecting = false)
        validateBoundary(boundary)
        if (current.stopConfirmedAtMs != null) return@update current
        require(current.phase == FreeLivingSessionPhase.STOP_REQUESTED) { "尚未请求停止采集" }
        current.copy(phase = FreeLivingSessionPhase.AWAITING_REFERENCE, stopConfirmedAtMs = receivedAtMs,
            stopStatusEvidence = status, endBoundaryEvidence = boundary)
    }

    private fun update(sessionId: String, change: (FreeLivingSession) -> FreeLivingSession): FreeLivingSession =
        synchronized(processLock) {
            val current = checkNotNull(readLocked()) { "没有可恢复的采集段" }
            require(current.sessionId == sessionId) { "采集段不匹配，已保留原记录" }
            val next = change(current)
            if (next == current) current else persist(next)
        }

    private fun readLocked(): FreeLivingSession? {
        if (!file.exists()) return null
        try {
            val envelope = JsonReader(StringReader(file.readText(Charsets.UTF_8))).use { reader ->
                reader.strictness = Strictness.STRICT
                val value = JsonParser.parseReader(reader)
                require(reader.peek() == JsonToken.END_DOCUMENT) { "采集段后存在额外内容" }
                value.asJsonObject
            }
            require(envelope.strictLong("journal_version") == 1L) { "版本不受支持" }
            val payload = envelope.getAsJsonObject("session") ?: error("缺少采集段")
            require(envelope.strictString("sha256") == digest(payload.toString())) { "采集段完整性检查失败" }
            return decode(payload).also {
                validateSession(it)
                // A previous rename may have succeeded while syncing its directory failed.
                // Do not acknowledge an idempotent retry until directory persistence succeeds.
                syncDirectory(requireNotNull(file.parentFile))
            }
        } catch (error: RuntimeException) {
            throw IOException("采集段无法读取，原文件已保留，请联系研究者", error)
        }
    }

    private fun persist(session: FreeLivingSession): FreeLivingSession {
        validateSession(session)
        val payload = encode(session)
        val envelope = JsonObject().apply {
            addProperty("journal_version", 1)
            add("session", payload)
            addProperty("sha256", digest(payload.toString()))
        }
        val directory = file.parentFile ?: throw IOException("采集段目录无效")
        // Android provides a durable app-private files directory. Creating another directory tree
        // here would also require persisting every new ancestor before any BLE command is sent.
        if (!directory.isDirectory) throw IOException("采集段目录尚未就绪")
        val temporary = File.createTempFile("session-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(envelope.toString().toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            commitFile(temporary, file)
            syncDirectory(directory)
            return session
        } finally {
            temporary.delete()
        }
    }

    companion object {
        private val processLock = Any()
        private val participantPattern = Regex("^[a-z0-9]{3,24}$")
        private val ringAddressPattern = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$")

        private fun validatePreparation(preparation: PreparationSnapshot) {
            require(participantPattern.matches(preparation.participantId)) { "请使用已登记的规范编号" }
            require(UUID.fromString(preparation.installationId).toString() == preparation.installationId)
            requireNotNull(preparation.placement) { "请先确认佩戴位置" }
            val ring = requireNotNull(preparation.ring) { "请先选择戒指" }
            require(ringAddressPattern.matches(ring.address) && ring.name.isNotBlank() && ring.name == ring.name.trim())
        }

        private fun validateReply(current: FreeLivingSession, address: String, status: HealthMessage.Status,
            receivedAtMs: Long, collecting: Boolean) {
            require(current.preparation.ring?.address == address.uppercase(Locale.ROOT)) { "戒指不匹配，已保留原记录" }
            require(receivedAtMs > 0 && status.collecting == collecting && status.errorCode == 0) { "戒指未确认所需采集状态" }
            require(status.sessionId in 0..65535 && status.bytes in 0..0xFFFF_FFFFL &&
                status.records in 0..0xFFFF_FFFFL) { "戒指状态无效" }
            require(current.deviceSessionId == null || current.deviceSessionId == status.sessionId) { "戒指记录不属于本采集段" }
        }

        private fun validateBoundary(boundary: DeviceBoundaryEvidence?) {
            if (boundary == null) return
            require(boundary.epochMs > 0 && (boundary.deviceUptimeMs == null || boundary.deviceUptimeMs in 0..0xFFFF_FFFFL))
            require(boundary.rawEvidence.isNotBlank()) { "设备时间须保留来源证据" }
        }

        private fun validateSession(session: FreeLivingSession) {
            require(UUID.fromString(session.sessionId).toString() == session.sessionId)
            validatePreparation(session.preparation)
            require(session.startRequestedAtMs > 0)
            ZoneId.of(session.timeZoneId)
            // Preserve the offset captured at start; later system tzdb updates can change rules.
            require(session.utcOffsetSeconds in -64_800..64_800)
            val hasStart = session.phase != FreeLivingSessionPhase.START_REQUESTED
            val hasStopRequest = session.phase in setOf(FreeLivingSessionPhase.STOP_REQUESTED, FreeLivingSessionPhase.AWAITING_REFERENCE)
            val hasStop = session.phase == FreeLivingSessionPhase.AWAITING_REFERENCE
            require((session.startConfirmedAtMs != null) == hasStart && (session.startStatusEvidence != null) == hasStart)
            require((session.stopRequestedAtMs != null) == hasStopRequest)
            require((session.stopConfirmedAtMs != null) == hasStop && (session.stopStatusEvidence != null) == hasStop)
            require(hasStart || session.startBoundaryEvidence == null)
            require(hasStop || session.endBoundaryEvidence == null)
            session.startStatusEvidence?.let { validateReply(session, session.preparation.ring!!.address, it, session.startConfirmedAtMs!!, true) }
            session.stopStatusEvidence?.let { validateReply(session, session.preparation.ring!!.address, it, session.stopConfirmedAtMs!!, false) }
            session.stopRequestedAtMs?.let { require(it > 0) }
            validateBoundary(session.startBoundaryEvidence)
            validateBoundary(session.endBoundaryEvidence)
        }

        private fun encode(s: FreeLivingSession) = JsonObject().apply {
            addProperty("session_id", s.sessionId)
            addProperty("participant_id", s.preparation.participantId)
            addProperty("participant_name", s.preparation.participantId)
            addProperty("installation_id", s.preparation.installationId)
            addProperty("ring_placement", s.preparation.placement!!.wireValue)
            addProperty("ring_address", s.preparation.ring!!.address)
            addProperty("ring_name", s.preparation.ring.name)
            addProperty("phase", s.phase.wireValue)
            addProperty("time_zone_id", s.timeZoneId)
            addProperty("utc_offset_seconds", s.utcOffsetSeconds)
            addProperty("capture_purpose", "daily_activity")
            addProperty("activity_schema", "daily_activity_v2")
            addProperty("activity_code", "free_living")
            addProperty("activity_label_status", "unlabelled")
            addProperty("activity_label_source", "none")
            addProperty("ground_truth_source", "external_pedometer")
            addProperty("ground_truth_status", "missing")
            add("ground_truth_steps", JsonNull.INSTANCE)
            add("ground_truth_recorded_at_ms", JsonNull.INSTANCE)
            addProperty("data_integrity_status", "pending")
            add("download_completed_at_ms", JsonNull.INSTANCE)
            addProperty("start_requested_at_ms", s.startRequestedAtMs)
            addNullable("start_confirmed_at_ms", s.startConfirmedAtMs)
            addNullable("stop_requested_at_ms", s.stopRequestedAtMs)
            addNullable("stop_confirmed_at_ms", s.stopConfirmedAtMs)
            addNullable("started_at_ms", s.startedAtMs)
            addNullable("ended_at_ms", s.endedAtMs)
            addNullable("device_session_id", s.deviceSessionId?.toLong())
            addProperty("capture_boundary_status", s.captureBoundaryStatus)
            add("timing_warnings", JsonArray().apply { s.timingWarnings.forEach(::add) })
            add("start_status_evidence", encodeStatus(s.startStatusEvidence))
            add("stop_status_evidence", encodeStatus(s.stopStatusEvidence))
            add("start_boundary_evidence", encodeBoundary(s.startBoundaryEvidence))
            add("end_boundary_evidence", encodeBoundary(s.endBoundaryEvidence))
        }

        private fun decode(p: JsonObject): FreeLivingSession {
            val s = FreeLivingSession(
                sessionId = p.strictString("session_id"),
                preparation = PreparationSnapshot(p.strictString("participant_id"), p.strictString("installation_id"),
                    requireNotNull(RingPlacement.fromWireValue(p.strictString("ring_placement"))),
                    PreparedRing(p.strictString("ring_address"), p.strictString("ring_name"))),
                phase = FreeLivingSessionPhase.entries.single { it.wireValue == p.strictString("phase") },
                timeZoneId = p.strictString("time_zone_id"),
                utcOffsetSeconds = Math.toIntExact(p.strictLong("utc_offset_seconds")),
                startRequestedAtMs = p.strictLong("start_requested_at_ms"),
                startConfirmedAtMs = p.nullableLong("start_confirmed_at_ms"),
                stopRequestedAtMs = p.nullableLong("stop_requested_at_ms"),
                stopConfirmedAtMs = p.nullableLong("stop_confirmed_at_ms"),
                startStatusEvidence = decodeStatus(p.required("start_status_evidence")),
                stopStatusEvidence = decodeStatus(p.required("stop_status_evidence")),
                startBoundaryEvidence = decodeBoundary(p.required("start_boundary_evidence")),
                endBoundaryEvidence = decodeBoundary(p.required("end_boundary_evidence")),
            )
            // Re-encoding checks required fields, fixed metadata, explicit nulls and derived values.
            require(encode(s) == p) { "采集段字段不完整或数据含义不一致" }
            return s
        }

        private fun encodeStatus(status: HealthMessage.Status?): JsonElement = status?.let {
            JsonObject().apply {
                addProperty("collecting", it.collecting); addProperty("bytes", it.bytes)
                addProperty("records", it.records); addProperty("error_code", it.errorCode)
                addProperty("device_session_id", it.sessionId)
            }
        } ?: JsonNull.INSTANCE

        private fun decodeStatus(value: JsonElement): HealthMessage.Status? {
            if (value.isJsonNull) return null
            val p = value.asJsonObject
            val collecting = p.required("collecting")
            require(collecting.isJsonPrimitive && collecting.asJsonPrimitive.isBoolean)
            return HealthMessage.Status(collecting.asBoolean, p.strictLong("bytes"), p.strictLong("records"),
                Math.toIntExact(p.strictLong("error_code")), Math.toIntExact(p.strictLong("device_session_id")))
        }

        private fun encodeBoundary(evidence: DeviceBoundaryEvidence?): JsonElement = evidence?.let {
            JsonObject().apply {
                addProperty("source", it.source.wireValue); addProperty("epoch_ms", it.epochMs)
                addNullable("device_uptime_ms", it.deviceUptimeMs); addProperty("raw_evidence", it.rawEvidence)
            }
        } ?: JsonNull.INSTANCE

        private fun decodeBoundary(value: JsonElement): DeviceBoundaryEvidence? {
            if (value.isJsonNull) return null
            val p = value.asJsonObject
            return DeviceBoundaryEvidence(DeviceBoundarySource.entries.single { it.wireValue == p.strictString("source") },
                p.strictLong("epoch_ms"), p.nullableLong("device_uptime_ms"), p.strictString("raw_evidence"))
        }

        private fun JsonObject.required(name: String): JsonElement = requireNotNull(get(name)) { "缺少字段 $name" }
        private fun JsonObject.strictString(name: String): String = required(name).let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString
        }
        private fun JsonObject.strictLong(name: String): Long = required(name).let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && Regex("-?[0-9]+").matches(it.asString))
            it.asString.toLong()
        }
        private fun JsonObject.nullableLong(name: String): Long? =
            if (required(name).isJsonNull) null else strictLong(name)
        private fun JsonObject.addNullable(name: String, value: Long?) {
            if (value == null) add(name, JsonNull.INSTANCE) else addProperty(name, value)
        }
        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    }
}
