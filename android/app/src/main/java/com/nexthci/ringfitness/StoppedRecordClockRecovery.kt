package com.nexthci.ringfitness

import com.google.gson.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.math.abs

/** Read-only continuity check; it never supplies or rewrites a raw record's missing UNIX time. */
internal object StoppedRecordClockRecovery {
    private const val UINT32_MASK = 0xFFFF_FFFFL

    fun validateStart(session: FreeLivingSession, start: PhoneClockSyncEvidence) {
        PhoneClockSync.validate(start)
        val record = requireNotNull(session.deviceRecordEvidence).record
        val dispatch = requireNotNull(session.startCommandDispatch)
        val accepted = requireNotNull(dispatch.acceptedAtMs)
        val baseline = requireNotNull(session.startBaseline)
        require(session.phase == FreeLivingSessionPhase.AWAITING_REFERENCE && session.stopConfirmedAtMs != null &&
            session.reference != null && session.startConfirmedAtMs != null && !session.deviceAssociationInvalidated &&
            session.startAbort == null && !session.isDiscarded && record.unixMs == 0L && record.uptimeMs > 0 &&
            session.preparation.ring?.address == start.ringAddress &&
            start.connectionGeneration == dispatch.connectionGeneration &&
            session.startRequestedAtMs <= baseline.observedAtMs &&
            baseline.observedAtMs - start.receivedAtMs in 0..PhoneClockSync.MAX_START_AGE_MS &&
            start.receivedAtMs <= session.startRequestedAtMs && accepted >= start.receivedAtMs &&
            accepted - start.receivedAtMs <= PhoneClockSync.MAX_START_AGE_MS &&
            accepted <= session.startConfirmedAtMs) { "本次时间依据不足，已保留步数与数据" }
        val startAge = (record.uptimeMs - (start.deviceUptimeMs and UINT32_MASK)) and UINT32_MASK
        require(startAge <= PhoneClockSync.MAX_START_AGE_MS) { "戒指记录与本次开始不一致，已保留数据" }
    }

    fun validateReply(start: PhoneClockSyncEvidence, requestedAtMs: Long, requestedElapsedMs: Long,
        receivedElapsedMs: Long, reply: SensorPacket.TimeStatus) {
        PhoneClockSync.validate(start)
        require(requestedAtMs >= start.receivedAtMs && requestedElapsedMs >= 0 &&
            receivedElapsedMs >= requestedElapsedMs && reply.receivedEpochMs >= requestedAtMs &&
            receivedElapsedMs - requestedElapsedMs <= PhoneClockSync.TIMEOUT_MS &&
            abs((reply.receivedEpochMs - requestedAtMs) - (receivedElapsedMs - requestedElapsedMs)) <=
                PhoneClockSync.MAX_WALL_CLOCK_SKEW_MS) { "时间核对超时，请重新连接后继续保存" }
        require(reply.synced && reply.unixMs >= start.deviceUnixMs && reply.uptimeMs >= start.deviceUptimeMs) {
            "戒指时间已变化，已保留步数与数据"
        }
        val uptimeDelta = reply.uptimeMs - start.deviceUptimeMs
        val unixDelta = reply.unixMs - start.deviceUnixMs
        // Recovery checks elapsed-time continuity against the saved SET window. Clock drift
        // accumulated since START must not be judged by the immediate post-SET UTC tolerance.
        require(abs(unixDelta - uptimeDelta) <= PhoneClockSync.MAX_WALL_CLOCK_SKEW_MS &&
            abs((reply.receivedEpochMs - start.receivedAtMs) - uptimeDelta) <=
                2 * PhoneClockSync.TIMEOUT_MS + 2 * PhoneClockSync.MAX_WALL_CLOCK_SKEW_MS) {
            "戒指时间已变化，已保留步数与数据"
        }
    }

    /** Retain the last checked recovery round; future connections always obtain a new TIME reply. */
    fun save(directory: File, session: FreeLivingSession, start: PhoneClockSyncEvidence,
        observed: HealthRecordObservation, requestedAtMs: Long, requestedElapsedMs: Long,
        receivedElapsedMs: Long, reply: SensorPacket.TimeStatus,
        syncDirectory: (File) -> Unit = {
            FileChannel.open(it.toPath(), StandardOpenOption.READ).use { channel -> channel.force(true) }
        }) {
        validateStart(session, start)
        validateReply(start, requestedAtMs, requestedElapsedMs, receivedElapsedMs, reply)
        require(observed.address == start.ringAddress && observed.connectionGeneration > 0)
        val record = requireNotNull(session.deviceRecordEvidence).record
        val json = JsonObject().apply {
            addProperty("version", 1); addProperty("session_id", session.sessionId)
            addProperty("ring_address", observed.address); addProperty("connection_generation", observed.connectionGeneration)
            addProperty("start_clock_attempt_id", start.attemptId)
            addProperty("device_session_id", record.sessionId); addProperty("record_uptime_ms", record.uptimeMs)
            addProperty("record_unix_ms", record.unixMs)
            addProperty("requested_at_ms", requestedAtMs); addProperty("received_at_ms", reply.receivedEpochMs)
            addProperty("requested_elapsed_ms", requestedElapsedMs); addProperty("received_elapsed_ms", receivedElapsedMs)
            addProperty("device_unix_ms", reply.unixMs); addProperty("device_uptime_ms", reply.uptimeMs)
            addProperty("source", "time_get_continuity_after_confirmed_stop")
        }
        val root = directory.canonicalFile
        val target = File(root, "${session.sessionId}.clock-recovery.json")
        require(!Files.isSymbolicLink(target.toPath()))
        val temporary = File.createTempFile("${session.sessionId}-clock-recovery-", ".tmp", root)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(json.toString().toByteArray(Charsets.UTF_8)); output.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(root)
        } finally { temporary.delete() }
    }
}
