package com.nexthci.ringfitness

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import java.util.UUID

data class PreparedRing(val address: String, val name: String)

data class PreparationSnapshot(
    val participantId: String,
    val installationId: String,
    val placement: RingPlacement? = null,
    val ring: PreparedRing? = null,
)

/** Confirmed preparation only: connection readiness and sessions are never stored here. */
class PreparationStore internal constructor(
    file: File,
    private val commitFile: (File, File) -> Unit,
) {
    constructor(file: File) : this(file, ::replaceAtomically)

    private val file = file.canonicalFile

    fun read(): PreparationSnapshot? = synchronized(processLock) { readLocked() }

    fun register(raw: String, placement: RingPlacement? = null): PreparationSnapshot = synchronized(processLock) {
        val trimmed = raw.trim()
        require(participantPattern.matches(trimmed)) { "被试编号需为 3–24 位英文字母或数字" }
        val participantId = trimmed.lowercase(Locale.ROOT)
        val existing = readLocked()
        if (existing != null) {
            require(existing.participantId == participantId) { "此手机已登记编号，更换编号需由研究者处理" }
            return@synchronized existing
        }
        persist(PreparationSnapshot(participantId, UUID.randomUUID().toString(), placement = placement))
    }

    fun savePlacement(placement: RingPlacement): PreparationSnapshot = update { it.copy(placement = placement) }

    fun selectRing(ring: PreparedRing): PreparationSnapshot {
        val normalized = normalizeRing(ring)
        return update { it.copy(ring = normalized) }
    }

    private fun update(change: (PreparationSnapshot) -> PreparationSnapshot): PreparationSnapshot =
        synchronized(processLock) {
            val existing = checkNotNull(readLocked()) { "请先登记被试编号" }
            val updated = change(existing)
            if (updated == existing) existing else persist(updated)
        }

    private fun readLocked(): PreparationSnapshot? {
        if (!file.exists()) return null
        try {
            val properties = Properties().apply { file.reader(Charsets.UTF_8).use { load(it) } }
            require(properties.stringPropertyNames() == fieldNames.toSet() + CHECKSUM) { "字段不完整或版本不受支持" }
            require(properties.getProperty("version") == "1") { "版本不受支持" }
            require(properties.getProperty(CHECKSUM) == checksum(properties)) { "完整性检查失败" }
            val participantId = properties.getProperty("participant_id")
            require(participantPattern.matches(participantId) && participantId == participantId.lowercase(Locale.ROOT))
            val installationId = properties.getProperty("installation_id")
            require(UUID.fromString(installationId).toString() == installationId)
            val placementValue = properties.getProperty("placement")
            val placement = if (placementValue.isEmpty()) null else {
                requireNotNull(RingPlacement.fromWireValue(placementValue)) { "佩戴位置无效" }
            }
            val address = properties.getProperty("ring_address")
            val name = properties.getProperty("ring_name")
            val ring = if (address.isEmpty() && name.isEmpty()) null else {
                PreparedRing(address, name).also { require(normalizeRing(it) == it) { "戒指信息无效" } }
            }
            return PreparationSnapshot(participantId, installationId, placement, ring)
        } catch (error: IllegalArgumentException) {
            throw IOException("准备信息无法读取，原文件已保留，请联系研究者", error)
        }
    }

    private fun persist(snapshot: PreparationSnapshot): PreparationSnapshot {
        val properties = Properties().apply {
            setProperty("version", "1")
            setProperty("participant_id", snapshot.participantId)
            setProperty("installation_id", snapshot.installationId)
            setProperty("placement", snapshot.placement?.wireValue.orEmpty())
            setProperty("ring_address", snapshot.ring?.address.orEmpty())
            setProperty("ring_name", snapshot.ring?.name.orEmpty())
            setProperty(CHECKSUM, checksum(this))
        }
        val directory = file.parentFile ?: throw IOException("准备信息保存目录无效")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建准备信息保存目录")
        val temporary = File.createTempFile("preparation-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { stream ->
                val writer = stream.writer(Charsets.UTF_8)
                properties.store(writer, "RingFitness preparation v1")
                writer.flush()
                stream.fd.sync()
            }
            // A failed replacement leaves the last confirmed snapshot unchanged.
            commitFile(temporary, file)
            return snapshot
        } finally {
            temporary.delete()
        }
    }

    companion object {
        // Serializes read-modify-write even when an Activity recreates its store instance.
        private val processLock = Any()
        private val participantPattern = Regex("^[A-Za-z0-9]{3,24}$")
        private val addressPattern = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$")
        private const val CHECKSUM = "checksum_sha256"
        private val fieldNames = listOf("version", "participant_id", "installation_id", "placement", "ring_address", "ring_name")

        private fun normalizeRing(ring: PreparedRing): PreparedRing {
            val address = ring.address.trim().uppercase(Locale.ROOT)
            val name = ring.name.trim()
            require(addressPattern.matches(address)) { "戒指蓝牙地址无效，请重新扫描" }
            require(name.isNotEmpty()) { "戒指名称为空，请重新扫描" }
            return PreparedRing(address, name)
        }

        private fun checksum(properties: Properties): String {
            val digest = MessageDigest.getInstance("SHA-256")
            for (field in fieldNames) {
                val value = requireNotNull(properties.getProperty(field)) { "缺少字段 $field" }
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update("${bytes.size}:".toByteArray(Charsets.US_ASCII))
                digest.update(bytes)
            }
            return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
        }

        private fun replaceAtomically(source: File, target: File) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
