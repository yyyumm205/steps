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

enum class PreparationIdentityType(val wireValue: String) {
    RESEARCH_ID("research_id"),
    LOCAL_NAME("local_name");

    companion object {
        fun fromWireValue(value: String?): PreparationIdentityType? = entries.singleOrNull { it.wireValue == value }
    }
}

data class PreparationSnapshot(
    val participantId: String,
    val installationId: String,
    val placement: RingPlacement? = null,
    val ring: PreparedRing? = null,
    /** Local-only label. Session manifests continue to use [participantId]. */
    val displayLabel: String = participantId,
    /** Local profile semantics only; research sessions continue to use [participantId]. */
    val identityType: PreparationIdentityType = PreparationIdentityType.RESEARCH_ID,
)

/**
 * Signals that neither the active profile nor its last-known-good backup can be read.
 * Session files are stored separately and are never removed by this store.
 */
class PreparationProfileUnreadableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Confirmed preparation only: connection readiness and sessions are never stored here. */
class PreparationStore internal constructor(
    file: File,
    private val commitFile: (File, File) -> Unit,
) {
    constructor(file: File) : this(file, ::replaceAtomically)

    private val file = file.canonicalFile
    private val backupFile = File(file.parentFile, "${file.name}.last-good")
    private val damagedFile = File(file.parentFile, "${file.name}.damaged")
    private val installationFile = File(file.parentFile, "${file.name}.installation-id")

    fun read(): PreparationSnapshot? = synchronized(processLock) { readLocked() }

    /** Existing profiles cannot be replaced through registration by an accidental edit. */
    fun register(
        raw: String,
        placement: RingPlacement? = null,
        identityType: PreparationIdentityType = PreparationIdentityType.RESEARCH_ID,
    ): PreparationSnapshot = synchronized(processLock) {
        val existing = readLocked()
        if (existing != null) {
            require(matchesExistingProfile(existing, raw, identityType)) { "当前身份已存在，请使用切换身份操作" }
            return@synchronized existing
        }
        val installationId = readInstallationId() ?: UUID.randomUUID().toString()
        val identity = identityFor(raw, installationId, identityType)
        persist(
            PreparationSnapshot(
                participantId = identity.participantId,
                installationId = installationId,
                placement = placement,
                displayLabel = identity.displayLabel,
                identityType = identityType,
            ),
        )
    }

    /** Replaces the idle profile; a new local identity deliberately reselects its device and placement. */
    fun replaceCurrentProfile(
        raw: String,
        collectionIsIdle: Boolean,
        identityType: PreparationIdentityType = PreparationIdentityType.RESEARCH_ID,
    ): PreparationSnapshot =
        synchronized(processLock) {
            require(collectionIsIdle) { "采集结束后才能切换身份" }
            val existing = checkNotNull(readLocked()) { "当前没有可切换的身份" }
            if (matchesExistingProfile(existing, raw, identityType)) return@synchronized existing
            val identity = identityFor(raw, existing.installationId, identityType)
            persist(
                PreparationSnapshot(
                    participantId = identity.participantId,
                    installationId = existing.installationId,
                    displayLabel = identity.displayLabel,
                    identityType = identityType,
                ),
            )
        }

    /** Removes only the current profile; installation identity and historical sessions remain. */
    fun clearCurrentProfile(collectionIsIdle: Boolean) = synchronized(processLock) {
        require(collectionIsIdle) { "采集结束后才能清除身份" }
        val installationId = readInstallationId()
            ?: readableSnapshot(file)?.installationId
            ?: readableSnapshot(backupFile)?.installationId
            ?: UUID.randomUUID().toString()
        persistInstallationId(installationId)
        deleteOrThrow(backupFile)
        deleteOrThrow(damagedFile)
        deleteOrThrow(file)
    }

    fun savePlacement(placement: RingPlacement): PreparationSnapshot = update { it.copy(placement = placement) }

    fun selectRing(ring: PreparedRing): PreparationSnapshot {
        val normalized = normalizeRing(ring)
        return update { it.copy(ring = normalized) }
    }

    private fun update(change: (PreparationSnapshot) -> PreparationSnapshot): PreparationSnapshot =
        synchronized(processLock) {
            val existing = checkNotNull(readLocked()) { "请先登记身份" }
            val updated = change(existing)
            if (updated == existing) existing else persist(updated)
        }

    private fun readLocked(): PreparationSnapshot? {
        if (!file.exists()) {
            if (!backupFile.exists()) return null
            val backup = runCatching { parse(backupFile) }.getOrElse { error ->
                throw PreparationProfileUnreadableException(
                    "身份资料需要重新登记，已有采集记录仍保留",
                    error,
                )
            }
            return restoreBackup(backup)
        }
        val primary = runCatching { parse(file) }
        primary.getOrNull()?.let { snapshot ->
            ensureInstallationId(snapshot.installationId)
            refreshBackup(snapshot)
            return snapshot
        }

        val backup = runCatching { parse(backupFile) }.getOrNull()
            ?: throw PreparationProfileUnreadableException(
                "身份资料需要重新登记，已有采集记录仍保留",
                primary.exceptionOrNull(),
            )
        try {
            preserveDamagedPrimary()
            return restoreBackup(backup)
        } catch (error: IOException) {
            throw PreparationProfileUnreadableException("身份资料恢复失败，已有采集记录仍保留", error)
        }
    }

    private fun restoreBackup(backup: PreparationSnapshot): PreparationSnapshot = try {
        writeSnapshot(backup, file, ::replaceAtomically)
        ensureInstallationId(backup.installationId)
        backup
    } catch (error: IOException) {
        throw PreparationProfileUnreadableException("身份资料恢复失败，已有采集记录仍保留", error)
    }

    private fun parse(source: File): PreparationSnapshot {
        if (!source.isFile) throw IOException("身份资料不存在")
        try {
            val properties = Properties().apply { source.reader(Charsets.UTF_8).use { load(it) } }
            val version = properties.getProperty("version")
            val fields = when (version) {
                VERSION_1 -> fieldsV1
                VERSION_2 -> fieldsV2
                VERSION_3 -> fieldsV3
                else -> throw IllegalArgumentException("版本不受支持")
            }
            require(properties.stringPropertyNames() == fields.toSet() + CHECKSUM) { "字段不完整或版本不受支持" }
            require(properties.getProperty(CHECKSUM) == checksum(properties, fields)) { "完整性检查失败" }
            val participantId = properties.getProperty("participant_id")
            require(participantPattern.matches(participantId))
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
            val displayLabel = if (version == VERSION_1) participantId else {
                val stored = properties.getProperty("display_label")
                stored
            }
            val identityType = when (version) {
                VERSION_1 -> PreparationIdentityType.RESEARCH_ID
                VERSION_2 -> inferLegacyIdentityType(participantId, displayLabel)
                else -> requireNotNull(
                    PreparationIdentityType.fromWireValue(properties.getProperty("identity_type")),
                ) { "身份类型无效" }
            }
            validateIdentity(participantId, installationId, displayLabel, identityType)
            return PreparationSnapshot(participantId, installationId, placement, ring, displayLabel, identityType)
        } catch (error: IllegalArgumentException) {
            throw IOException("身份资料格式无效", error)
        }
    }

    private fun persist(snapshot: PreparationSnapshot): PreparationSnapshot {
        require(participantPattern.matches(snapshot.participantId)) { "内部身份编号无效" }
        require(UUID.fromString(snapshot.installationId).toString() == snapshot.installationId)
        validateIdentity(snapshot.participantId, snapshot.installationId, snapshot.displayLabel, snapshot.identityType)
        writeSnapshot(snapshot, file, commitFile)
        ensureInstallationId(snapshot.installationId)
        refreshBackup(snapshot)
        return snapshot
    }

    private fun writeSnapshot(snapshot: PreparationSnapshot, target: File, commit: (File, File) -> Unit) {
        val properties = Properties().apply {
            setProperty("version", VERSION_3)
            setProperty("participant_id", snapshot.participantId)
            setProperty("installation_id", snapshot.installationId)
            setProperty("placement", snapshot.placement?.wireValue.orEmpty())
            setProperty("ring_address", snapshot.ring?.address.orEmpty())
            setProperty("ring_name", snapshot.ring?.name.orEmpty())
            setProperty("display_label", snapshot.displayLabel)
            setProperty("identity_type", snapshot.identityType.wireValue)
            setProperty(CHECKSUM, checksum(this, fieldsV3))
        }
        val directory = target.parentFile ?: throw IOException("身份资料保存目录无效")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建身份资料保存目录")
        val temporary = File.createTempFile("preparation-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { stream ->
                val writer = stream.writer(Charsets.UTF_8)
                properties.store(writer, "RingFitness preparation v3")
                writer.flush()
                stream.fd.sync()
            }
            commit(temporary, target)
        } finally {
            temporary.delete()
        }
    }

    private fun refreshBackup(snapshot: PreparationSnapshot) {
        if (readableSnapshot(backupFile) == snapshot) return
        runCatching { writeSnapshot(snapshot, backupFile, ::replaceAtomically) }
    }

    private fun readableSnapshot(source: File): PreparationSnapshot? = runCatching { parse(source) }.getOrNull()

    private fun preserveDamagedPrimary() {
        runCatching {
            val temporary = File.createTempFile("damaged-preparation-", ".tmp", file.parentFile)
            try {
                Files.copy(file.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING)
                replaceAtomically(temporary, damagedFile)
            } finally {
                temporary.delete()
            }
        }
    }

    private fun ensureInstallationId(installationId: String) {
        val stored = readInstallationId()
        if (stored == null) persistInstallationId(installationId)
        else if (stored != installationId) {
            throw PreparationProfileUnreadableException("身份资料需要重新登记，已有采集记录仍保留")
        }
    }

    private fun readInstallationId(): String? {
        if (!installationFile.isFile) return null
        return runCatching {
            installationFile.readText(Charsets.US_ASCII).trim().also {
                require(UUID.fromString(it).toString() == it)
            }
        }.getOrNull()
    }

    private fun persistInstallationId(installationId: String) {
        require(UUID.fromString(installationId).toString() == installationId)
        if (readInstallationId() == installationId) return
        val directory = installationFile.parentFile ?: throw IOException("安装标识保存目录无效")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建安装标识保存目录")
        val temporary = File.createTempFile("installation-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(installationId.toByteArray(Charsets.US_ASCII))
                stream.fd.sync()
            }
            replaceAtomically(temporary, installationFile)
        } finally {
            temporary.delete()
        }
    }

    private fun deleteOrThrow(target: File) {
        if (target.exists() && !target.delete()) throw IOException("无法清除当前身份资料")
    }

    companion object {
        private val processLock = Any()
        private val participantPattern = Regex("^[a-z0-9]{3,24}$")
        private val legacyParticipantPattern = Regex("^[A-Za-z0-9]{3,24}$")
        private val addressPattern = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$")
        private const val VERSION_1 = "1"
        private const val VERSION_2 = "2"
        private const val VERSION_3 = "3"
        private const val CHECKSUM = "checksum_sha256"
        private val fieldsV1 = listOf("version", "participant_id", "installation_id", "placement", "ring_address", "ring_name")
        private val fieldsV2 = fieldsV1 + "display_label"
        private val fieldsV3 = fieldsV2 + "identity_type"

        private data class Identity(val participantId: String, val displayLabel: String)

        private fun normalizeResearchLabel(raw: String): String {
            val label = raw.trim()
            require(legacyParticipantPattern.matches(label)) { "研究编号需为 3–24 位英文字母或数字" }
            return label
        }

        private fun normalizeLocalName(raw: String): String {
            val label = raw.trim()
            require(label.isNotEmpty()) { "请输入本地姓名" }
            require(label.codePointCount(0, label.length) <= 40) { "本地姓名最多 40 个字符" }
            require(label.none { it.isISOControl() }) { "本地姓名包含无效字符" }
            return label
        }

        private fun localParticipantId(label: String, installationId: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(installationId.toByteArray(Charsets.US_ASCII))
            digest.update(0.toByte())
            digest.update(label.toByteArray(Charsets.UTF_8))
            return "local${digest.digest().take(8).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }}"
        }

        private fun identityFor(raw: String, installationId: String, identityType: PreparationIdentityType): Identity =
            when (identityType) {
                PreparationIdentityType.RESEARCH_ID -> normalizeResearchLabel(raw).let { label ->
                    Identity(label.lowercase(Locale.ROOT), label)
                }
                PreparationIdentityType.LOCAL_NAME -> normalizeLocalName(raw).let { label ->
                    Identity(localParticipantId(label, installationId), label)
                }
            }

        private fun matchesExistingProfile(
            snapshot: PreparationSnapshot,
            raw: String,
            identityType: PreparationIdentityType,
        ): Boolean {
            if (snapshot.identityType != identityType) return false
            val identity = identityFor(raw, snapshot.installationId, identityType)
            return snapshot.participantId == identity.participantId &&
                (identityType == PreparationIdentityType.RESEARCH_ID || snapshot.displayLabel == identity.displayLabel)
        }

        private fun inferLegacyIdentityType(participantId: String, displayLabel: String): PreparationIdentityType =
            if (legacyParticipantPattern.matches(displayLabel) &&
                participantId == displayLabel.lowercase(Locale.ROOT)) {
                PreparationIdentityType.RESEARCH_ID
            } else {
                PreparationIdentityType.LOCAL_NAME
            }

        private fun validateIdentity(
            participantId: String,
            installationId: String,
            displayLabel: String,
            identityType: PreparationIdentityType,
        ) {
            when (identityType) {
                PreparationIdentityType.RESEARCH_ID -> {
                    val normalized = normalizeResearchLabel(displayLabel).lowercase(Locale.ROOT)
                    require(participantId == normalized) { "研究编号与内部身份不一致" }
                }
                PreparationIdentityType.LOCAL_NAME -> {
                    val normalized = normalizeLocalName(displayLabel)
                    require(normalized == displayLabel) { "本地姓名格式无效" }
                    require(participantId == localParticipantId(displayLabel, installationId)) {
                        "本地姓名与匿名身份不一致"
                    }
                }
            }
        }

        private fun normalizeRing(ring: PreparedRing): PreparedRing {
            val address = ring.address.trim().uppercase(Locale.ROOT)
            val name = ring.name.trim()
            require(addressPattern.matches(address)) { "戒指蓝牙地址无效，请重新扫描" }
            require(name.isNotEmpty()) { "戒指名称为空，请重新扫描" }
            return PreparedRing(address, name)
        }

        private fun checksum(properties: Properties, fields: List<String>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            for (field in fields) {
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
