package com.nexthci.ringfitness

import com.google.gson.JsonObject
import java.util.UUID
import kotlin.math.abs

data class UnknownTimeRecordProof(
    val record: HealthMessage.ListItem,
    val backupId: String,
    val rawSha256: String,
    val ownerId: String,
    val connectionGeneration: Long,
    val preservedAtMs: Long,
) {
    fun validate(observation: HealthRecordObservation) {
        require(UUID.fromString(backupId).toString() == backupId && UUID.fromString(ownerId).toString() == ownerId)
        require(Regex("[0-9a-f]{64}").matches(rawSha256))
        require(record.unixMs == 0L && record.uptimeMs > 0 && record.bytes > 0 && record.records > 0)
        require(observation.records.filter { it.unixMs == 0L } == listOf(record) && !observation.status.collecting)
        require(connectionGeneration > 0 && connectionGeneration == observation.connectionGeneration &&
            preservedAtMs > 0 && observation.statusReceivedAtMs >= preservedAtMs)
    }

    fun encode() = JsonObject().apply {
        addProperty("backup_id", backupId); addProperty("raw_sha256", rawSha256)
        addProperty("owner_id", ownerId); addProperty("connection_generation", connectionGeneration)
        addProperty("preserved_at_ms", preservedAtMs)
        add("record", JsonObject().apply {
            addProperty("device_session_id", record.sessionId); addProperty("bytes", record.bytes)
            addProperty("records", record.records); addProperty("uptime_ms", record.uptimeMs)
            addProperty("unix_ms", record.unixMs)
        })
    }

    companion object {
        fun decode(json: JsonObject): UnknownTimeRecordProof {
            val r = json.getAsJsonObject("record")
            return UnknownTimeRecordProof(HealthMessage.ListItem(Math.toIntExact(r.proofLong("device_session_id")), r.proofLong("bytes"),
                r.proofLong("records"), r.proofLong("uptime_ms"), r.proofLong("unix_ms")),
                json.proofString("backup_id"), json.proofString("raw_sha256"), json.proofString("owner_id"),
                json.proofLong("connection_generation"), json.proofLong("preserved_at_ms")).also { require(it.encode() == json) }
        }
    }
}

/** A fully reread idle original plus clock synchronization on the uninterrupted issuing connection. */
data class UnknownTimeStartEvidence(
    val record: HealthMessage.ListItem,
    val backupId: String,
    val rawSha256: String,
    val ownerId: String,
    val connectionGeneration: Long,
    val preservedAtMs: Long,
    val clock: PhoneClockSyncEvidence,
) {
    fun recordProof() = UnknownTimeRecordProof(record, backupId, rawSha256, ownerId, connectionGeneration, preservedAtMs)
    fun validate(baseline: DeviceStartBaseline) {
        require(UUID.fromString(backupId).toString() == backupId && UUID.fromString(ownerId).toString() == ownerId)
        require(Regex("[0-9a-f]{64}").matches(rawSha256))
        require(record.unixMs == 0L && record.uptimeMs > 0 && record.bytes > 0 && record.records > 0)
        require(baseline.records.filter { it.unixMs == 0L } == listOf(record))
        require(connectionGeneration > 0 && connectionGeneration == clock.connectionGeneration)
        require(preservedAtMs > 0 && clock.requestedAtMs >= preservedAtMs &&
            baseline.observedAtMs >= clock.receivedAtMs &&
            baseline.observedAtMs - clock.receivedAtMs <= PhoneClockSync.MAX_START_AGE_MS)
        // Reuse the clock acceptance rules; the UUID identifies the already durable reply.
        PhoneClockSync.validate(clock)
        require(clock.deviceUptimeMs in 0..0xFFFF_FFFFL)
        require(UUID.fromString(clock.attemptId).toString() == clock.attemptId)
    }

    fun isDistinct(candidate: HealthMessage.ListItem): Boolean {
        if (candidate.unixMs <= 0 || (candidate.sessionId == record.sessionId && candidate.uptimeMs == record.uptimeMs) ||
            candidate.uptimeMs < clock.deviceUptimeMs) return false
        val age = candidate.uptimeMs - clock.deviceUptimeMs
        return age <= PhoneClockSync.MAX_START_AGE_MS &&
            abs((candidate.unixMs - clock.deviceUnixMs) - age) <= PhoneClockSync.MAX_WALL_CLOCK_SKEW_MS
    }

    /** An on-connection stop guard; this never proves a research capture or an absolute date. */
    fun canStopWithoutUnix(candidate: HealthMessage.ListItem): Boolean = candidate.unixMs == 0L && candidate.uptimeMs > 0 &&
        candidate.uptimeMs >= clock.deviceUptimeMs &&
        candidate.uptimeMs - clock.deviceUptimeMs <= PhoneClockSync.MAX_START_AGE_MS &&
        (candidate.sessionId != record.sessionId || candidate.uptimeMs != record.uptimeMs)

    fun encode() = JsonObject().apply {
        addProperty("version", 1)
        addProperty("backup_id", backupId); addProperty("raw_sha256", rawSha256)
        addProperty("owner_id", ownerId); addProperty("connection_generation", connectionGeneration)
        addProperty("preserved_at_ms", preservedAtMs)
        add("record", JsonObject().apply {
            addProperty("device_session_id", record.sessionId); addProperty("bytes", record.bytes)
            addProperty("records", record.records); addProperty("uptime_ms", record.uptimeMs)
            addProperty("unix_ms", record.unixMs)
        })
        add("clock", JsonObject().apply {
            addProperty("attempt_id", clock.attemptId); addProperty("ring_address", clock.ringAddress)
            addProperty("connection_generation", clock.connectionGeneration)
            addProperty("requested_at_ms", clock.requestedAtMs); addProperty("received_at_ms", clock.receivedAtMs)
            addProperty("requested_elapsed_ms", clock.requestedElapsedMs); addProperty("received_elapsed_ms", clock.receivedElapsedMs)
            addProperty("device_unix_ms", clock.deviceUnixMs); addProperty("device_uptime_ms", clock.deviceUptimeMs)
        })
    }

    companion object {
        fun decode(json: JsonObject): UnknownTimeStartEvidence {
            require(json.proofLong("version") == 1L)
            val record = json.getAsJsonObject("record")
            val clock = json.getAsJsonObject("clock")
            return UnknownTimeStartEvidence(
                HealthMessage.ListItem(Math.toIntExact(record.proofLong("device_session_id")), record.proofLong("bytes"),
                    record.proofLong("records"), record.proofLong("uptime_ms"), record.proofLong("unix_ms")),
                json.proofString("backup_id"), json.proofString("raw_sha256"), json.proofString("owner_id"),
                json.proofLong("connection_generation"), json.proofLong("preserved_at_ms"),
                PhoneClockSyncEvidence(clock.proofString("attempt_id"), clock.proofString("ring_address"),
                    clock.proofLong("connection_generation"), clock.proofLong("requested_at_ms"), clock.proofLong("received_at_ms"),
                    clock.proofLong("requested_elapsed_ms"), clock.proofLong("received_elapsed_ms"),
                    clock.proofLong("device_unix_ms"), clock.proofLong("device_uptime_ms")),
            ).also { require(it.encode() == json) { "旧记录保全证据字段无效" } }
        }
    }
}

private fun JsonObject.proofLong(name: String): Long = requireNotNull(get(name)).let {
    require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && Regex("[0-9]+").matches(it.asString))
    it.asString.toLong()
}

private fun JsonObject.proofString(name: String): String = requireNotNull(get(name)).let {
    require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
    it.asString
}
