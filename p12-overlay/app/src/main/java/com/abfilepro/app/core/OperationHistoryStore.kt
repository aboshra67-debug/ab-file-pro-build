package com.abfilepro.app.core

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Small local audit trail for AB smart tools.
 * Only generated files inside the AB File Pro workspace may be undone.
 */
object OperationHistoryStore {
    data class Entry(
        val id: String,
        val tool: String,
        val title: String,
        val outputs: List<String>,
        val createdAt: Long,
        val outputSnapshots: Map<String, String> = emptyMap()
    ) {
        fun canUndo(context: Context): Boolean = outputs.any { path ->
            runCatching {
                val file = File(path)
                outputSnapshots[path]?.startsWith("v1:") == true && file.exists() &&
                    FileUtils.isInsideRoot(context, file) && file.canonicalFile != FileUtils.rootDir(context).canonicalFile
            }.getOrDefault(false)
        }
    }

    private const val MAX_ENTRIES = 100
    private val lock = Any()
    private val undoMutex = Mutex()

    suspend fun record(context: Context, tool: String, title: String, outputs: List<File>): Entry = withContext(Dispatchers.IO) {
        val safeOutputs = outputs.mapNotNull { file ->
            runCatching {
                if (file.exists() && FileUtils.isInsideRoot(context, file) && file.canonicalFile != FileUtils.rootDir(context).canonicalFile) {
                    file.canonicalPath
                } else null
            }.getOrNull()
        }.distinct()
        require(safeOutputs.isNotEmpty()) { "لا توجد نتائج قابلة للتسجيل" }
        val snapshots = LinkedHashMap<String, String>()
        for (path in safeOutputs) {
            currentCoroutineContext().ensureActive()
            try {
                snapshots[path] = OwnedOutputSnapshot.capture(File(path)).token
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the generated result and its history. An unreadable or
                // changing output simply has no destructive undo permission.
            }
        }

        val entry = Entry(
            id = "${System.currentTimeMillis()}_${(1000..9999).random()}",
            tool = tool.take(60),
            title = title.take(120),
            outputs = safeOutputs,
            createdAt = System.currentTimeMillis(),
            outputSnapshots = snapshots
        )
        synchronized(lock) {
            val all = (listOf(entry) + readUnlocked(context)).distinctBy { it.id }.take(MAX_ENTRIES)
            writeUnlocked(context, all)
        }
        entry
    }

    fun list(context: Context, limit: Int = 30): List<Entry> = synchronized(lock) {
        readUnlocked(context).take(limit.coerceIn(1, MAX_ENTRIES))
    }

    suspend fun hasPending(context: Context, id: String): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) { readUnlocked(context).any { it.id == id } }
    }

    suspend fun undo(context: Context, id: String): Boolean = withContext(Dispatchers.IO) {
        undoMutex.withLock {
            val entry = synchronized(lock) { readUnlocked(context).firstOrNull { it.id == id } }
                ?: return@withLock false
            val root = FileUtils.rootDir(context).canonicalFile
            val completed = HashSet<String>()
            var changed = false
            try {
                for (rawPath in entry.outputs) {
                    currentCoroutineContext().ensureActive()
                    // Five-column legacy entries never acquire ownership merely
                    // because a new file happens to occupy the same old path.
                    val expected = entry.outputSnapshots[rawPath]?.takeIf { it.startsWith("v1:") } ?: continue
                    val target = File(rawPath)
                    val canonical = runCatching { target.canonicalFile }.getOrNull() ?: continue
                    if (canonical == root || !canonical.path.startsWith(root.path + File.separator) || !target.exists()) continue
                    val result = OwnedOutputSnapshot.deleteIfUnchanged(target, expected)
                    changed = result.changed || changed
                    if (result.complete) completed += rawPath
                }
            } finally {
                if (completed.isNotEmpty()) synchronized(lock) {
                    // Read again so concurrent records are preserved. Retain
                    // every output that could not be completely undone.
                    val entries = readUnlocked(context).mapNotNull { current ->
                        if (current.id != id) current else {
                            val remaining = current.outputs.filterNot { it in completed }
                            if (remaining.isEmpty()) null else current.copy(
                                outputs = remaining,
                                outputSnapshots = current.outputSnapshots.filterKeys { it in remaining }
                            )
                        }
                    }
                    writeUnlocked(context, entries)
                }
            }
            changed
        }
    }

    private fun historyFile(context: Context): File = File(context.filesDir, "ab_smart_history.tsv")

    private fun readUnlocked(context: Context): List<Entry> {
        val file = historyFile(context)
        if (!file.exists()) return emptyList()
        return file.readLines(Charsets.UTF_8).mapNotNull { line ->
            runCatching {
                val p = line.split('\t')
                if (p.size < 5) return@runCatching null
                val proof = if (p.size >= 6 && p[5].isNotBlank()) JSONObject(decode(p[5])) else null
                val snapshots = LinkedHashMap<String, String>()
                val recordedOutputs = if (proof != null) {
                    val items = proof.getJSONArray("outputs")
                    (0 until items.length()).map { index ->
                        val item = items.getJSONObject(index)
                        val path = item.getString("path")
                        item.optString("snapshot").takeIf { it.startsWith("v1:") }?.let { snapshots[path] = it }
                        path
                    }
                } else decode(p[3]).split('|').filter { it.isNotBlank() }
                Entry(
                    id = decode(p[0]),
                    tool = decode(p[1]),
                    title = decode(p[2]),
                    outputs = recordedOutputs,
                    createdAt = p[4].toLong(),
                    outputSnapshots = snapshots
                )
            }.getOrNull()
        }.sortedByDescending { it.createdAt }
    }

    private fun writeUnlocked(context: Context, entries: List<Entry>) {
        val file = historyFile(context)
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { stream ->
            val writer = stream.bufferedWriter(Charsets.UTF_8)
            entries.take(MAX_ENTRIES).forEach { entry ->
                val proof = JSONObject().put("outputs", org.json.JSONArray().apply {
                    entry.outputs.forEach { path -> put(JSONObject().put("path", path).put("snapshot", entry.outputSnapshots[path].orEmpty())) }
                })
                writer.append(encode(entry.id)).append('\t')
                    .append(encode(entry.tool)).append('\t')
                    .append(encode(entry.title)).append('\t')
                    .append(encode(entry.outputs.joinToString("|"))).append('\t')
                    .append(entry.createdAt.toString()).append('\t')
                    .append(encode(proof.toString())).appendLine()
            }
            writer.flush()
            stream.fd.sync()
        }
        // Atomic replacement keeps the previous history if publishing fails.
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun encode(value: String): String = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    private fun decode(value: String): String = String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)
}
