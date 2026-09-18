package com.nexthci.ringfitness

import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Durable, explicitly synthetic device evidence. Never used to infer a real ring's state. */
data class DemoDeviceRecord(
    val ownerSessionId: String,
    val deviceSessionId: Int,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
) {
    val collecting: Boolean get() = endedAtMs == null
    fun status() = HealthMessage.Status(collecting, 256, 16, 0, deviceSessionId)
    fun listItem() = HealthMessage.ListItem(deviceSessionId, 256, 16, 1000, startedAtMs)
}

internal class DemoDeviceStore(
    private val file: File,
    private val syncDirectory: (File) -> Unit = ::syncDemoDirectory,
) {
    private data class Envelope(val record: DemoDeviceRecord?, val sha256: String)
    private val gson = Gson()

    fun read(): DemoDeviceRecord? {
        if (!file.exists()) return null
        val envelope = gson.fromJson(file.readText(), Envelope::class.java)
        require(envelope.sha256 == demoDigest(gson.toJson(envelope.record).toByteArray()))
        return envelope.record?.also {
            require(it.ownerSessionId.isNotBlank() && it.deviceSessionId in 1..65535 && it.startedAtMs > 0)
            require(it.endedAtMs == null || it.endedAtMs >= it.startedAtMs)
        }
    }

    fun save(record: DemoDeviceRecord?) {
        val payload = gson.toJson(Envelope(record, demoDigest(gson.toJson(record).toByteArray()))).toByteArray()
        writeDemoFile(file, payload, syncDirectory)
    }
}

internal fun writeDemoFile(file: File, payload: ByteArray, syncDirectory: (File) -> Unit) {
    val temporary = File.createTempFile("demo-", ".tmp", file.parentFile)
    try {
        FileOutputStream(temporary).use { it.write(payload); it.fd.sync() }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncDirectory(requireNotNull(file.parentFile))
    } finally {
        temporary.delete()
    }
}

internal fun syncDemoDirectory(directory: File) {
    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
}

internal fun demoDigest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }
