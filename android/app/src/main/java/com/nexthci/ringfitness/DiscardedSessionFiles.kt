package com.nexthci.ringfitness

import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Explicit, repeatable cleanup after the journal has committed the discard tombstone. */
internal object DiscardedSessionFiles {
    fun cleanup(directory: File, session: FreeLivingSession, syncDirectory: (File) -> Unit) {
        require(session.isDiscarded)
        val root = directory.canonicalFile
        val id = session.sessionId
        val prefix = session.deviceSessionId?.let { "$id-ring-$it" }
        val names = buildSet {
            session.localData?.files?.forEach { add(it.fileName) }
            if (prefix != null) listOf(".part", ".download.json", ".rfbin", ".raw-evidence.json").forEach { add(prefix + it) }
            add("$id.clock-sync.json")
            add("$id.clock-recovery.json")
            add("$id-simulated-signal.txt")
            add("$id-simulated-receipt.txt")
        }
        names.forEach { deleteFile(child(root, it)) }
        // Crash leftovers have a generated suffix and only these known temporary extensions.
        if (prefix != null) root.listFiles()?.filter {
            it.name.startsWith("$prefix-") && (it.name.endsWith(".rfbin.tmp") || it.name.endsWith(".json.tmp"))
        }?.forEach { deleteFile(child(root, it.name)) }
        val tasks = child(root, "upload-tasks")
        if (tasks.exists()) {
            require(tasks.isDirectory)
            listOf("$id.json", "$id.json.tmp").forEach { deleteFile(child(tasks, it)) }
            syncDirectory(tasks)
        }
        val packages = child(root, "packages")
        if (packages.exists()) {
            require(packages.isDirectory)
            val targets = packages.listFiles()?.filter {
                it.name == id || it.name.startsWith(".$id-") || it.name.startsWith(".obsolete-$id-")
            }.orEmpty()
            targets.forEach { target ->
                val folder = child(packages, target.name)
                require(folder.isDirectory)
                listOf(
                    "ringfitness-session-$id.zip",
                    "ringfitness-session-walking-$id.zip",
                    "ringfitness-session-running-$id.zip",
                    "manifest.snapshot.json",
                    "package.json",
                ).forEach {
                    deleteFile(child(folder, it))
                }
                syncDirectory(folder)
                if (folder.list()?.isEmpty() == true && !folder.delete()) throw IOException("本段文件清理未完成")
            }
            syncDirectory(packages)
        }
        syncDirectory(root)
    }

    private fun child(parent: File, name: String): File {
        require(name.isNotBlank() && '/' !in name && '\\' !in name)
        val file = File(parent, name)
        require(!Files.isSymbolicLink(file.toPath())) { "本段文件位置无效" }
        return file.canonicalFile.also { require(it.parentFile == parent && it.name == name) }
    }

    private fun deleteFile(file: File) {
        if (!file.exists()) return
        require(file.isFile) { "本段文件类型无效" }
        if (!file.delete()) throw IOException("本段文件清理未完成")
    }
}
