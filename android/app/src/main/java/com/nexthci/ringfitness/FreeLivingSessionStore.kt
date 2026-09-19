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

enum class ReferenceStatus(val wireValue: String) {
    VALID("valid"), MISSING("missing"), UNRELIABLE("unreliable"),
}

data class SessionReference(
    val status: ReferenceStatus,
    val steps: Long?,
    val recordedAtMs: Long,
    val reason: String? = null,
) {
    /** Saving a missing reason records an observation, while numeric confirmation remains absent. */
    val groundTruthRecordedAtMs: Long? get() = recordedAtMs.takeIf { steps != null }
}

data class SessionRawFile(
    val fileName: String,
    val deviceSessionId: Int,
    val bytes: Long,
    val sha256: String,
    val simulated: Boolean = false,
)

data class SessionLocalData(val files: List<SessionRawFile>, val completedAtMs: Long)

enum class SessionTransferStatus(val wireValue: String) {
    PENDING("pending"), TRANSFERRING("transferring"), FAILED("failed"), COMPLETE("complete"),
}

data class SessionTransferReceipt(
    val receiptId: String,
    val receivedAtMs: Long,
    val simulated: Boolean,
    val sessionId: String,
)

data class SessionTransfer(
    val status: SessionTransferStatus = SessionTransferStatus.PENDING,
    val attempts: Int = 0,
    val receipt: SessionTransferReceipt? = null,
)

/** Device-derived evidence only. Phone receipt timestamps belong in the separate confirmation fields. */
data class DeviceBoundaryEvidence(
    val source: DeviceBoundarySource,
    val epochMs: Long,
    val deviceUptimeMs: Long?,
    val rawEvidence: String,
)

/** Full pre-command observation. LIST anchors are record fingerprints, not capture timestamps. */
data class DeviceStartBaseline(
    val status: HealthMessage.Status,
    val records: List<HealthMessage.ListItem>,
    val observedAtMs: Long,
)

/** Durable association established by a complete STATUS/LIST round on the issuing connection. */
data class DeviceRecordEvidence(
    val record: HealthMessage.ListItem,
    val status: HealthMessage.Status,
    val observedAtMs: Long,
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
    val reference: SessionReference? = null,
    val localData: SessionLocalData? = null,
    val transfer: SessionTransfer = SessionTransfer(),
    val startBaseline: DeviceStartBaseline? = null,
    val deviceRecordEvidence: DeviceRecordEvidence? = null,
    val deviceAssociationInvalidated: Boolean = false,
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
        // An anomalous observation may precede a delayed stop confirmation. Compare only the
        // causal dependencies, so recovery does not turn that valid order into a clock warning.
        val referenceEarliest = if (reference?.status == ReferenceStatus.VALID) stopConfirmedAtMs else stopRequestedAtMs
        val referenceWentBack = reference?.recordedAtMs?.let { recorded -> referenceEarliest?.let { recorded < it } } == true
        val downloadWentBack = localData?.completedAtMs?.let { completed ->
            listOfNotNull(stopConfirmedAtMs, reference?.recordedAtMs).any { completed < it }
        } == true
        if (phoneTimes.zipWithNext().any { (before, after) -> after < before } || referenceWentBack || downloadWentBack) {
            add("phone_clock_order_uncertain")
        }
        if (endBoundaryEvidence != null && endedAtMs == null) add("device_boundary_order_uncertain")
    }
}

/**
 * Atomic journal for the current session and locally complete sessions awaiting transmission.
 * Only verified local files release pending-data protection. Callers must durably record requests
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
    private data class Journal(val current: FreeLivingSession, val archived: List<FreeLivingSession> = emptyList())

    fun read(): FreeLivingSession? = synchronized(processLock) { readLocked() }

    fun readPending(): FreeLivingSession? = read()?.takeIf { it.localData == null }

    fun read(sessionId: String): FreeLivingSession? = listSessions().singleOrNull { it.sessionId == sessionId }

    fun listSessions(): List<FreeLivingSession> = synchronized(processLock) {
        readJournalLocked()?.let { it.archived + it.current }.orEmpty()
    }

    fun requestStart(preparation: PreparationSnapshot, requestedAtMs: Long, timeZoneId: String,
        baseline: DeviceStartBaseline? = null): FreeLivingSession =
        synchronized(processLock) {
            validatePreparation(preparation)
            require(requestedAtMs > 0) { "开始请求时间无效" }
            val zone = ZoneId.of(timeZoneId)
            val journal = readJournalLocked()
            val existing = journal?.current
            if (existing != null && existing.localData == null) {
                require(existing.preparation == preparation) { "已有采集段的编号、位置或戒指不能更改" }
                require(existing.phase == FreeLivingSessionPhase.START_REQUESTED ||
                    existing.phase == FreeLivingSessionPhase.COLLECTING) { "上一段原始数据尚未安全保存，暂不能开始新采集" }
                return@synchronized existing
            }
            existing?.let { verifyLocalFiles(it, requireNotNull(it.localData).files) }
            persist(FreeLivingSession(
                sessionId = UUID.randomUUID().toString(), preparation = preparation,
                phase = FreeLivingSessionPhase.START_REQUESTED,
                timeZoneId = zone.id,
                utcOffsetSeconds = zone.rules.getOffset(Instant.ofEpochMilli(requestedAtMs)).totalSeconds,
                startRequestedAtMs = requestedAtMs,
                startBaseline = baseline,
            ), if (journal == null) emptyList() else journal.archived + journal.current)
        }

    fun confirmStart(
        sessionId: String,
        ringAddress: String,
        status: HealthMessage.Status,
        receivedAtMs: Long,
        boundary: DeviceBoundaryEvidence? = null,
        recordEvidence: DeviceRecordEvidence? = null,
    ): FreeLivingSession = update(sessionId) { current ->
        validateReply(current, ringAddress, status, receivedAtMs, collecting = true)
        validateBoundary(boundary)
        require(recordEvidence == null || recordEvidence.status == status) { "设备关联与开始状态不一致" }
        if (current.startConfirmedAtMs != null) return@update current
        require(current.phase == FreeLivingSessionPhase.START_REQUESTED) { "当前阶段不接受开始确认" }
        current.copy(phase = FreeLivingSessionPhase.COLLECTING, startConfirmedAtMs = receivedAtMs,
            startStatusEvidence = status, startBoundaryEvidence = boundary, deviceRecordEvidence = recordEvidence)
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
        recordEvidence: DeviceRecordEvidence? = null,
    ): FreeLivingSession = update(sessionId) { current ->
        validateReply(current, ringAddress, status, receivedAtMs, collecting = false)
        validateBoundary(boundary)
        require(recordEvidence == null || recordEvidence.status == status) { "设备关联与停止状态不一致" }
        if (current.stopConfirmedAtMs != null) return@update current
        require(current.phase == FreeLivingSessionPhase.STOP_REQUESTED) { "尚未请求停止采集" }
        current.deviceRecordEvidence?.let { previous ->
            val next = requireNotNull(recordEvidence) { "缺少停止记录关联证据" }
            require(!current.deviceAssociationInvalidated && sameDeviceRecord(previous.record, next.record) &&
                next.record.bytes >= previous.record.bytes && next.record.records >= previous.record.records &&
                next.status.bytes >= previous.status.bytes && next.status.records >= previous.status.records) { "设备记录已变化" }
        }
        current.copy(phase = FreeLivingSessionPhase.AWAITING_REFERENCE, stopConfirmedAtMs = receivedAtMs,
            stopStatusEvidence = status, endBoundaryEvidence = boundary,
            deviceRecordEvidence = recordEvidence ?: current.deviceRecordEvidence)
    }

    /** Refresh only a previously proved identity; never promote a same-ID record to ownership. */
    fun updateDeviceEvidence(sessionId: String, evidence: DeviceRecordEvidence): FreeLivingSession = update(sessionId) { current ->
        require(!current.deviceAssociationInvalidated) { "设备记录需要研究者核对" }
        val previous = requireNotNull(current.deviceRecordEvidence) { "缺少设备记录关联证据" }
        require(sameDeviceRecord(previous.record, evidence.record)) { "设备记录已变化" }
        require(evidence.record.bytes >= previous.record.bytes && evidence.record.records >= previous.record.records &&
            evidence.status.bytes >= previous.status.bytes && evidence.status.records >= previous.status.records) { "设备计数发生回退" }
        current.copy(deviceRecordEvidence = evidence)
    }

    fun invalidateDeviceAssociation(sessionId: String): FreeLivingSession = update(sessionId) {
        it.copy(deviceAssociationInvalidated = true)
    }

    /** Every device record must exactly match a verified local copy before it can be replaced. */
    fun hasPreservedDeviceRecords(ringAddress: String, records: List<HealthMessage.ListItem>): Boolean =
        synchronized(processLock) {
            if (records.isEmpty()) return@synchronized false
            val sessions = readJournalLocked()?.let { it.archived + it.current }.orEmpty()
            records.all { record ->
                // Uptime alone cannot prove that the device still holds the same boot's record.
                if (record.unixMs == 0L) return@all false
                val saved = sessions.singleOrNull { it.preparation.ring?.address == ringAddress &&
                    !it.deviceAssociationInvalidated && it.deviceRecordEvidence?.record == record && it.localData != null }
                    ?: return@all false
                verifyLocalFiles(saved, saved.localData!!.files)
                saved.localData.files.none { it.simulated }
            }
        }

    fun saveReference(sessionId: String, reference: SessionReference): FreeLivingSession = update(sessionId) { current ->
        validateReference(reference)
        current.reference?.let { saved ->
            require(saved.status == reference.status && saved.steps == reference.steps && saved.reason == reference.reason) {
                "本次读数已保存，请联系研究者核对修改"
            }
            return@update current
        }
        require(current.phase == FreeLivingSessionPhase.AWAITING_REFERENCE ||
            current.phase == FreeLivingSessionPhase.STOP_REQUESTED) { "请先结束本次采集" }
        require(current.stopConfirmedAtMs != null || reference.status != ReferenceStatus.VALID) {
            "停止尚待确认，请记录读数异常"
        }
        current.copy(reference = reference)
    }

    fun completeLocalData(sessionId: String, files: List<SessionRawFile>, completedAtMs: Long): FreeLivingSession =
        update(sessionId) { current ->
            require(current.phase == FreeLivingSessionPhase.AWAITING_REFERENCE && current.reference != null) {
                "请先确认结束并保存本次读数"
            }
            require(completedAtMs > 0)
            verifyLocalFiles(current, files)
            current.localData?.let {
                require(it.files == files) { "本次文件已确认，不能替换" }
                return@update current
            }
            current.copy(localData = SessionLocalData(files.toList(), completedAtMs))
        }

    fun markTransferStarted(sessionId: String): FreeLivingSession = update(sessionId, allowArchived = true) { current ->
        verifyLocalFiles(current, requireNotNull(current.localData) { "文件尚未保存完整" }.files)
        if (current.transfer.status == SessionTransferStatus.COMPLETE ||
            current.transfer.status == SessionTransferStatus.TRANSFERRING) return@update current
        current.copy(transfer = current.transfer.copy(status = SessionTransferStatus.TRANSFERRING,
            attempts = Math.addExact(current.transfer.attempts, 1)))
    }

    fun markTransferFailed(sessionId: String): FreeLivingSession = update(sessionId, allowArchived = true) { current ->
        require(current.transfer.status == SessionTransferStatus.TRANSFERRING ||
            current.transfer.status == SessionTransferStatus.FAILED) { "当前没有待确认的传输" }
        current.copy(transfer = current.transfer.copy(status = SessionTransferStatus.FAILED))
    }

    fun completeTransfer(sessionId: String, receipt: SessionTransferReceipt): FreeLivingSession =
        update(sessionId, allowArchived = true) { current ->
            require(receipt.receiptId.isNotBlank() && receipt.receivedAtMs > 0 && receipt.sessionId == current.sessionId) {
                "传输回执不属于本次采集"
            }
            current.transfer.receipt?.let {
                require(it == receipt) { "传输回执已保存，不能替换" }
                return@update current
            }
            require(current.transfer.status == SessionTransferStatus.TRANSFERRING) { "尚未开始传输" }
            val files = requireNotNull(current.localData).files
            require(files.all { it.simulated == receipt.simulated }) { "模拟与真实传输结果不能混用" }
            verifyLocalFiles(current, files)
            current.copy(transfer = current.transfer.copy(status = SessionTransferStatus.COMPLETE, receipt = receipt))
        }

    private fun update(sessionId: String, allowArchived: Boolean = false,
        change: (FreeLivingSession) -> FreeLivingSession): FreeLivingSession =
        synchronized(processLock) {
            val journal = checkNotNull(readJournalLocked()) { "没有可恢复的采集段" }
            val current = if (journal.current.sessionId == sessionId) journal.current
                else if (allowArchived) journal.archived.singleOrNull { it.sessionId == sessionId } else null
            requireNotNull(current) { "采集段不匹配，已保留原记录" }
            val next = change(current)
            if (next == current) current else {
                val updated = if (current.sessionId == journal.current.sessionId) journal.copy(current = next)
                    else journal.copy(archived = journal.archived.map { if (it.sessionId == sessionId) next else it })
                persistJournal(updated)
                next
            }
        }

    private fun readLocked(): FreeLivingSession? = readJournalLocked()?.current

    private fun readJournalLocked(): Journal? {
        if (!file.exists()) return null
        try {
            val envelope = JsonReader(StringReader(file.readText(Charsets.UTF_8))).use { reader ->
                reader.strictness = Strictness.STRICT
                val value = JsonParser.parseReader(reader)
                require(reader.peek() == JsonToken.END_DOCUMENT) { "采集段后存在额外内容" }
                value.asJsonObject
            }
            val version = envelope.strictLong("journal_version")
            require(version in 1L..3L) { "版本不受支持" }
            val payload = envelope.getAsJsonObject("session") ?: error("缺少采集段")
            val archives = if (version >= 2L) envelope.required("archived_sessions").asJsonArray else JsonArray()
            val hashed = if (version == 1L) payload else journalPayload(payload, archives)
            require(envelope.strictString("sha256") == digest(hashed.toString())) { "采集段完整性检查失败" }
            return Journal(decode(payload, version), archives.map { decode(it.asJsonObject, version) }).also {
                validateJournal(it)
                // A previous rename may have succeeded while syncing its directory failed.
                // Do not acknowledge an idempotent retry until directory persistence succeeds.
                syncDirectory(requireNotNull(file.parentFile))
            }
        } catch (error: RuntimeException) {
            throw IOException("采集段无法读取，原文件已保留，请联系研究者", error)
        }
    }

    private fun persist(session: FreeLivingSession, archived: List<FreeLivingSession>): FreeLivingSession {
        persistJournal(Journal(session, archived))
        return session
    }

    private fun persistJournal(journal: Journal) {
        validateJournal(journal)
        val payload = encode(journal.current)
        val archives = JsonArray().apply { journal.archived.forEach { add(encode(it)) } }
        val envelope = JsonObject().apply {
            addProperty("journal_version", 3)
            add("session", payload)
            add("archived_sessions", archives)
            addProperty("sha256", digest(journalPayload(payload, archives).toString()))
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
        } finally {
            temporary.delete()
        }
    }

    private fun verifyLocalFiles(session: FreeLivingSession, files: List<SessionRawFile>) {
        validateFiles(session, files)
        val directory = requireNotNull(file.parentFile)
        for (entry in files) {
            val source = File(directory, entry.fileName).canonicalFile
            require(source.parentFile == directory && source.name == entry.fileName && source != file) { "文件位置与采集段不匹配" }
            if (!source.isFile || source.length() != entry.bytes) throw IOException("采集文件尚未保存完整")
            val hash = MessageDigest.getInstance("SHA-256")
            source.inputStream().use { stream ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    hash.update(buffer, 0, count)
                }
            }
            if (hex(hash.digest()) != entry.sha256) throw IOException("采集文件校验失败，已保留原记录")
            FileOutputStream(source, true).use { it.fd.sync() }
        }
        syncDirectory(directory)
    }

    companion object {
        private val processLock = Any()
        private val participantPattern = Regex("^[a-z0-9]{3,24}$")
        private val ringAddressPattern = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$")

        private fun validateJournal(journal: Journal) {
            val sessions = journal.archived + journal.current
            require(sessions.map { it.sessionId }.distinct().size == sessions.size) { "采集段标识重复" }
            sessions.forEach(::validateSession)
            require(journal.archived.all { it.localData != null }) { "尚未保存完整的采集段不能归档" }
        }

        private fun validateReference(reference: SessionReference) {
            require(reference.recordedAtMs > 0)
            require(reference.steps == null || reference.steps >= 0) { "请输入非负整数步数" }
            require(reference.reason == null || (reference.reason.isNotBlank() && reference.reason == reference.reason.trim()))
            when (reference.status) {
                ReferenceStatus.VALID -> require(reference.steps != null && reference.reason == null)
                ReferenceStatus.MISSING -> require(reference.steps == null && reference.reason != null)
                ReferenceStatus.UNRELIABLE -> require(reference.reason != null)
            }
        }

        private fun validateFiles(session: FreeLivingSession, files: List<SessionRawFile>) {
            require(files.isNotEmpty() && files.map { it.fileName }.distinct().size == files.size) { "文件清单为空或重复" }
            require(files.map { it.simulated }.distinct().size == 1) { "模拟文件与真实文件不能混用" }
            files.forEach {
                require(it.fileName.startsWith("${session.sessionId}-") &&
                    Regex("^[a-zA-Z0-9._-]+$").matches(it.fileName)) { "文件不属于当前采集段" }
                require(it.deviceSessionId == session.deviceSessionId) { "文件戒指记录不匹配" }
                require(it.bytes > 0 && Regex("^[a-f0-9]{64}$").matches(it.sha256)) { "文件校验信息无效" }
            }
        }

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

        private fun validateDeviceStatus(status: HealthMessage.Status) {
            require(status.sessionId in 0..65535 && status.bytes in 0..0xFFFF_FFFFL &&
                status.records in 0..0xFFFF_FFFFL && status.errorCode == 0)
        }

        private fun validateDeviceRecord(record: HealthMessage.ListItem) {
            require(record.sessionId in 1..65535 && record.bytes in 0..0xFFFF_FFFFL &&
                record.records in 0..0xFFFF_FFFFL && record.uptimeMs in 0..0xFFFF_FFFFL && record.unixMs >= 0)
        }

        fun sameDeviceRecord(first: HealthMessage.ListItem, second: HealthMessage.ListItem): Boolean =
            first.sessionId == second.sessionId && first.uptimeMs == second.uptimeMs && first.unixMs == second.unixMs

        /** A reused numeric ID needs two changed, nonzero anchors in the reviewed idle record. */
        fun isDistinctStartRecord(baseline: DeviceStartBaseline, candidate: HealthMessage.ListItem): Boolean {
            val previous = baseline.records.singleOrNull { it.sessionId == candidate.sessionId }
                ?: return candidate.sessionId != baseline.status.sessionId
            return previous.unixMs > 0 && candidate.unixMs > 0 && previous.uptimeMs > 0 && candidate.uptimeMs > 0 &&
                previous.unixMs != candidate.unixMs && previous.uptimeMs != candidate.uptimeMs
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
            session.startBaseline?.let { baseline ->
                validateDeviceStatus(baseline.status)
                require(!baseline.status.collecting && baseline.observedAtMs > 0)
                require(baseline.records.size <= 255 && baseline.records.map { it.sessionId }.distinct().size == baseline.records.size)
                baseline.records.forEach(::validateDeviceRecord)
                if (baseline.records.isEmpty()) require(baseline.status.bytes == 0L && baseline.status.records == 0L)
                else require(baseline.records.any { it.sessionId == baseline.status.sessionId &&
                    it.bytes == baseline.status.bytes && it.records == baseline.status.records })
            }
            session.deviceRecordEvidence?.let { evidence ->
                require(hasStart && session.startBaseline != null && evidence.observedAtMs > 0)
                validateDeviceStatus(evidence.status)
                validateDeviceRecord(evidence.record)
                require(evidence.record.unixMs > 0 || evidence.record.uptimeMs > 0)
                require(evidence.record.sessionId == session.deviceSessionId && evidence.status.sessionId == session.deviceSessionId)
                require(isDistinctStartRecord(session.startBaseline, evidence.record))
                // An unsolicited STATUS may be newer than the most recently completed LIST.
                if (hasStop) require(!evidence.status.collecting && evidence.record.bytes == evidence.status.bytes &&
                    evidence.record.records == evidence.status.records)
                else require(evidence.status.collecting)
            }
            session.reference?.let {
                validateReference(it)
                require(hasStopRequest && (hasStop || it.status != ReferenceStatus.VALID))
            }
            session.localData?.let {
                require(hasStop && session.reference != null && it.completedAtMs > 0)
                validateFiles(session, it.files)
            }
            val transfer = session.transfer
            require(transfer.attempts >= 0)
            require((transfer.receipt != null) == (transfer.status == SessionTransferStatus.COMPLETE))
            if (transfer.status == SessionTransferStatus.PENDING) require(transfer.attempts == 0)
            else require(session.localData != null && transfer.attempts > 0)
            transfer.receipt?.let {
                require(it.receiptId.isNotBlank() && it.receivedAtMs > 0 && it.sessionId == session.sessionId)
                require(session.localData!!.files.all { file -> file.simulated == it.simulated })
            }
        }

        private fun encode(s: FreeLivingSession, version: Long = 3L) = JsonObject().apply {
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
            addProperty("ground_truth_status", s.reference?.status?.wireValue ?: "missing")
            addNullable("ground_truth_steps", s.reference?.steps)
            addNullable("ground_truth_recorded_at_ms", s.reference?.groundTruthRecordedAtMs)
            addProperty("data_integrity_status", if (s.localData != null) "complete" else "pending")
            addNullable("download_completed_at_ms", s.localData?.completedAtMs)
            if (version >= 2L) {
                addNullable("reference_saved_at_ms", s.reference?.recordedAtMs)
                add("ground_truth_reason", s.reference?.reason?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
                add("raw_files", JsonArray().apply { s.localData?.files?.forEach { add(encodeFile(it)) } })
                add("transfer", JsonObject().apply {
                    addProperty("status", s.transfer.status.wireValue)
                    addProperty("attempts", s.transfer.attempts)
                    add("receipt", s.transfer.receipt?.let { receipt -> JsonObject().apply {
                        addProperty("receipt_id", receipt.receiptId)
                        addProperty("received_at_ms", receipt.receivedAtMs)
                        addProperty("simulated", receipt.simulated)
                        addProperty("session_id", receipt.sessionId)
                    } } ?: JsonNull.INSTANCE)
                })
            }
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
            if (version >= 3L) {
                add("start_baseline", s.startBaseline?.let { baseline -> JsonObject().apply {
                    add("status", encodeStatus(baseline.status))
                    add("records", JsonArray().apply { baseline.records.forEach { add(encodeRecord(it)) } })
                    addProperty("observed_at_ms", baseline.observedAtMs)
                } } ?: JsonNull.INSTANCE)
                add("device_record_evidence", s.deviceRecordEvidence?.let { evidence -> JsonObject().apply {
                    add("record", encodeRecord(evidence.record))
                    add("status", encodeStatus(evidence.status))
                    addProperty("observed_at_ms", evidence.observedAtMs)
                } } ?: JsonNull.INSTANCE)
                addProperty("device_association_invalidated", s.deviceAssociationInvalidated)
            }
        }

        private fun decode(p: JsonObject, version: Long): FreeLivingSession {
            val extended = version >= 2L
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
                reference = if (!extended || p.nullableLong("reference_saved_at_ms") == null) null else SessionReference(
                    ReferenceStatus.entries.single { it.wireValue == p.strictString("ground_truth_status") },
                    p.nullableLong("ground_truth_steps"), p.strictLong("reference_saved_at_ms"),
                    if (p.required("ground_truth_reason").isJsonNull) null else p.strictString("ground_truth_reason")),
                localData = if (!extended || p.nullableLong("download_completed_at_ms") == null) null else SessionLocalData(
                    p.required("raw_files").asJsonArray.map { decodeFile(it.asJsonObject) },
                    p.strictLong("download_completed_at_ms")),
                transfer = if (extended) decodeTransfer(p.required("transfer").asJsonObject) else SessionTransfer(),
                startBaseline = if (version < 3L || p.required("start_baseline").isJsonNull) null else
                    p.getAsJsonObject("start_baseline").let { DeviceStartBaseline(requireNotNull(decodeStatus(it.required("status"))),
                        it.required("records").asJsonArray.map { record -> decodeRecord(record.asJsonObject) }, it.strictLong("observed_at_ms")) },
                deviceRecordEvidence = if (version < 3L || p.required("device_record_evidence").isJsonNull) null else
                    p.getAsJsonObject("device_record_evidence").let { DeviceRecordEvidence(decodeRecord(it.getAsJsonObject("record")),
                        requireNotNull(decodeStatus(it.required("status"))), it.strictLong("observed_at_ms")) },
                deviceAssociationInvalidated = version >= 3L && p.strictBoolean("device_association_invalidated"),
            )
            // Re-encoding checks required fields, fixed metadata, explicit nulls and derived values.
            require(encode(s, version) == p) { "采集段字段不完整或数据含义不一致" }
            return s
        }

        private fun encodeFile(file: SessionRawFile) = JsonObject().apply {
            addProperty("file_name", file.fileName)
            addProperty("device_session_id", file.deviceSessionId)
            addProperty("bytes", file.bytes)
            addProperty("sha256", file.sha256)
            addProperty("simulated", file.simulated)
        }

        private fun encodeRecord(record: HealthMessage.ListItem) = JsonObject().apply {
            addProperty("device_session_id", record.sessionId)
            addProperty("bytes", record.bytes)
            addProperty("records", record.records)
            addProperty("uptime_ms", record.uptimeMs)
            addProperty("unix_ms", record.unixMs)
        }

        private fun decodeRecord(p: JsonObject) = HealthMessage.ListItem(Math.toIntExact(p.strictLong("device_session_id")),
            p.strictLong("bytes"), p.strictLong("records"), p.strictLong("uptime_ms"), p.strictLong("unix_ms"))

        private fun decodeFile(p: JsonObject) = SessionRawFile(p.strictString("file_name"),
            Math.toIntExact(p.strictLong("device_session_id")), p.strictLong("bytes"),
            p.strictString("sha256"), p.strictBoolean("simulated"))

        private fun decodeTransfer(p: JsonObject): SessionTransfer {
            val receipt = p.required("receipt")
            return SessionTransfer(SessionTransferStatus.entries.single { it.wireValue == p.strictString("status") },
                Math.toIntExact(p.strictLong("attempts")), if (receipt.isJsonNull) null else receipt.asJsonObject.let {
                    SessionTransferReceipt(it.strictString("receipt_id"), it.strictLong("received_at_ms"),
                        it.strictBoolean("simulated"), it.strictString("session_id"))
                })
        }

        private fun journalPayload(session: JsonObject, archives: JsonArray) = JsonObject().apply {
            add("session", session)
            add("archived_sessions", archives)
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
        private fun JsonObject.strictBoolean(name: String): Boolean = required(name).let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean); it.asBoolean
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
            .digest(value.toByteArray(Charsets.UTF_8)).let(::hex)
        private fun hex(value: ByteArray): String = value.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    }
}
