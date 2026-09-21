package com.nexthci.ringfitness

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

data class PhoneClockSyncEvidence(
    val attemptId: String,
    val ringAddress: String,
    val connectionGeneration: Long,
    val requestedAtMs: Long,
    val receivedAtMs: Long,
    val requestedElapsedMs: Long,
    val receivedElapsedMs: Long,
    val deviceUnixMs: Long,
    val deviceUptimeMs: Long,
)

/** A checked TIME reply. The accepted window records latency and tolerance, not measured clock accuracy. */
object PhoneClockSync {
    const val TIMEOUT_MS = 3000L
    const val MAX_START_AGE_MS = 30_000L
    const val MAX_WALL_CLOCK_SKEW_MS = 250L
    private val lock = Any()
    private val addressPattern = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$")

    fun accept(
        ringAddress: String,
        generation: Long,
        requestedAtMs: Long,
        requestedElapsedMs: Long,
        receivedElapsedMs: Long,
        packet: SensorPacket.TimeStatus,
    ): PhoneClockSyncEvidence {
        require(packet.synced) { "戒指时间尚未确认" }
        return PhoneClockSyncEvidence(
            UUID.randomUUID().toString(), ringAddress, generation, requestedAtMs,
            packet.receivedEpochMs, requestedElapsedMs, receivedElapsedMs, packet.unixMs, packet.uptimeMs,
        ).also(::validate)
    }

    /** The app's existing private directory must already be durable before saving pre-START evidence. */
    fun save(
        directory: File,
        evidence: PhoneClockSyncEvidence,
        sessionId: String? = null,
        syncDirectory: (File) -> Unit = {
            FileChannel.open(it.toPath(), StandardOpenOption.READ).use { channel -> channel.force(true) }
        },
    ) = synchronized(lock) {
        validate(evidence)
        sessionId?.let(::validateUuid)
        val root = directory.canonicalFile
        if (!root.isDirectory) throw IOException("校时记录目录尚未就绪")
        val name = sessionId?.let { "$it.clock-sync.json" } ?: "clock-sync-${evidence.attemptId}.json"
        val target = File(root, name)
        require(!Files.isSymbolicLink(target.toPath())) { "校时记录位置无效" }
        val bytes = encode(evidence, sessionId).toString().toByteArray(Charsets.UTF_8)
        if (target.exists()) {
            require(target.isFile && target.readBytes().contentEquals(bytes)) { "校时记录存在冲突，原记录已保留" }
            // A previous rename can succeed before directory synchronization fails.
            // An idempotent retry acknowledges durability only after both syncs succeed.
            FileOutputStream(target, true).use { it.fd.sync() }
            syncDirectory(root)
            return@synchronized
        }
        val temporary = File.createTempFile("clock-sync-", ".tmp", root)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(root)
        } finally {
            temporary.delete()
        }
    }

    fun validate(evidence: PhoneClockSyncEvidence) {
        validateUuid(evidence.attemptId)
        require(addressPattern.matches(evidence.ringAddress) && evidence.connectionGeneration > 0) {
            "校时连接信息无效"
        }
        require(evidence.requestedAtMs > 0 && evidence.receivedAtMs >= evidence.requestedAtMs &&
            evidence.requestedElapsedMs >= 0 && evidence.receivedElapsedMs >= evidence.requestedElapsedMs &&
            evidence.deviceUnixMs > 0 && evidence.deviceUptimeMs >= 0) { "校时时间信息无效" }
        // Ordered nonnegative operands make these differences safe across the Long range.
        val elapsed = evidence.receivedElapsedMs - evidence.requestedElapsedMs
        val wall = evidence.receivedAtMs - evidence.requestedAtMs
        require(elapsed <= TIMEOUT_MS) { "校时回复已超时" }
        require(wall in (elapsed - MAX_WALL_CLOCK_SKEW_MS)..(elapsed + MAX_WALL_CLOCK_SKEW_MS)) {
            "手机时间在校时期间发生变化"
        }
        require(evidence.deviceUnixMs - evidence.requestedAtMs >= -MAX_WALL_CLOCK_SKEW_MS &&
            evidence.deviceUnixMs - evidence.receivedAtMs <= MAX_WALL_CLOCK_SKEW_MS) {
            "戒指时间与手机校时窗口不符"
        }
    }

    /** Read the immutable pre-START reply already bound to this local session. */
    fun load(directory: File, sessionId: String): PhoneClockSyncEvidence = synchronized(lock) {
        validateUuid(sessionId)
        val file = File(directory.canonicalFile, "$sessionId.clock-sync.json")
        require(!Files.isSymbolicLink(file.toPath()) && file.isFile && file.length() in 1..8192) {
            "缺少本次校时记录，已保留步数与数据"
        }
        val json = JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonObject
        fun number(name: String): Long = requireNotNull(json[name]).let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && Regex("[0-9]+").matches(it.asString))
            it.asString.toLong()
        }
        fun text(name: String): String = requireNotNull(json[name]).let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
            it.asString
        }
        val evidence = PhoneClockSyncEvidence(text("attempt_id"), text("ring_address"), number("connection_generation"),
            number("requested_at_ms"), number("received_at_ms"), number("requested_elapsed_ms"),
            number("received_elapsed_ms"), number("device_unix_ms"), number("device_uptime_ms"))
        validate(evidence)
        require(encode(evidence, sessionId) == json) { "校时记录不一致，已保留原始数据" }
        evidence
    }

    private fun validateUuid(value: String) {
        require(UUID.fromString(value).toString() == value) { "校时记录编号无效" }
    }

    private fun encode(evidence: PhoneClockSyncEvidence, sessionId: String?) = JsonObject().apply {
        addProperty("version", 1)
        addProperty("attempt_id", evidence.attemptId)
        add("session_id", sessionId?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        addProperty("ring_address", evidence.ringAddress)
        addProperty("connection_generation", evidence.connectionGeneration)
        addProperty("requested_at_ms", evidence.requestedAtMs)
        addProperty("received_at_ms", evidence.receivedAtMs)
        addProperty("requested_elapsed_ms", evidence.requestedElapsedMs)
        addProperty("received_elapsed_ms", evidence.receivedElapsedMs)
        addProperty("device_unix_ms", evidence.deviceUnixMs)
        addProperty("device_uptime_ms", evidence.deviceUptimeMs)
        addProperty("wall_clock_tolerance_ms", MAX_WALL_CLOCK_SKEW_MS)
    }
}
