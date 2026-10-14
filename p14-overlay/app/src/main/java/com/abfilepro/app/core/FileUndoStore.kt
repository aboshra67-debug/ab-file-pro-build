package com.abfilepro.app.core

import android.content.Context
import android.net.Uri
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

object FileUndoStore {
    private const val PREFS = "ab_file_pro_file_undo"
    private const val KEY = "last_operation_v1"
    private const val PROOFS_KEY = "last_operation_proofs_v2"
    private val operationLock = Mutex()

    private fun enc(v: String) = Uri.encode(v)
    private fun dec(v: String) = Uri.decode(v)

    suspend fun recordRename(context: Context, old: File, new: File) = withContext(Dispatchers.IO) {
        operationLock.withLock {
            val value = "rename|${enc(old.absolutePath)}|${enc(new.absolutePath)}"
            val token = snapshotOrNull(new)
            save(context, value, if (token == null) emptyMap() else mapOf(new.absolutePath to token))
        }
    }

    suspend fun recordMoves(context: Context, moves: Map<String, String>) = withContext(Dispatchers.IO) {
        if (moves.isEmpty()) return@withContext
        operationLock.withLock {
            val proofs = LinkedHashMap<String, String>()
            for (path in moves.values) snapshotOrNull(File(path))?.let { proofs[path] = it }
            save(context, "move|" + moves.entries.joinToString(";") { "${enc(it.key)},${enc(it.value)}" }, proofs)
        }
    }

    fun clear(context: Context) = context.getSharedPreferences(PREFS, 0).edit().remove(KEY).remove(PROOFS_KEY).apply()

    fun canUndo(context: Context): Boolean = !context.getSharedPreferences(PREFS, 0).getString(KEY, null).isNullOrBlank()

    suspend fun undo(context: Context): Result<Int> = withContext(Dispatchers.IO) { operationLock.withLock { runCatching {
        val raw = context.getSharedPreferences(PREFS, 0).getString(KEY, null) ?: error("لا توجد عملية قابلة للتراجع")
        val proofs = readProofs(context, raw)
        val type = raw.substringBefore('|')
        val payload = raw.substringAfter('|', "")
        var restored = 0
        when (type) {
            "rename" -> {
                val p = payload.split('|', limit = 2)
                require(p.size == 2)
                val old = File(dec(p[0]))
                val current = File(dec(p[1]))
                require(current.exists() && FileUtils.isInsideRoot(context, current)) { "الملف الحالي غير موجود" }
                require(FileUtils.isInsideRoot(context, old)) { "المسار الأصلي خارج مساحة العمل" }
                require(!old.exists()) { "الاسم الأصلي مستخدم حاليًا" }
                requireUnchanged(current, proofs[current.absolutePath])
                Files.move(current.toPath(), old.toPath())
                FileTagStore.movePath(context, current, old)
                remapSavedPaths(context, mapOf(current.absolutePath to old.absolutePath))
                restored = 1
            }
            "move" -> {
                val pending = payload.split(';').filter { it.isNotBlank() }.toMutableList()
                pending.toList().forEach { pair ->
                    val p = pair.split(',', limit = 2)
                    if (p.size != 2) return@forEach
                    val old = File(dec(p[0]))
                    val current = File(dec(p[1]))
                    if (!current.exists() || !FileUtils.isInsideRoot(context, current) ||
                        !FileUtils.isInsideRoot(context, old) || old.exists()) return@forEach
                    val expected = proofs[current.absolutePath]
                    if (expected == null || snapshotOrNull(current) != expected) return@forEach
                    old.parentFile?.mkdirs()
                    if (runCatching { Files.move(current.toPath(), old.toPath()) }.isSuccess) {
                        restored++
                        pending.remove(pair)
                        // Persist each successful restoration before ancillary path
                        // remapping. Conflicts remain available for a later retry.
                        if (pending.isEmpty()) clear(context) else {
                            val remainingPaths = pending.mapNotNull { it.split(',', limit = 2).getOrNull(1)?.let(::dec) }.toSet()
                            save(context, "move|" + pending.joinToString(";"), proofs.filterKeys { it in remainingPaths })
                        }
                        FileTagStore.movePath(context, current, old)
                        remapSavedPaths(context, mapOf(current.absolutePath to old.absolutePath))
                    }
                }
            }
            else -> error("نوع عملية غير معروف")
        }
        require(restored > 0) { "تعذر التراجع لأن الملفات تغيرت بعد العملية" }
        if (type == "rename") clear(context)
        restored
    } } }

    private suspend fun snapshotOrNull(file: File): String? = try {
        OwnedOutputSnapshot.capture(file).token
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { null }

    private suspend fun requireUnchanged(file: File, expected: String?) {
        // Old records are retained, but never acquire ownership of whatever
        // now occupies their paths merely because no proof was recorded.
        require(expected != null && expected.startsWith("v1:") && snapshotOrNull(file) == expected) {
            "تعذر التراجع لأن هوية الملف أو محتواه تغيرت بعد العملية"
        }
    }

    private fun readProofs(context: Context, operation: String): Map<String, String> = runCatching {
        val raw = context.getSharedPreferences(PREFS, 0).getString(PROOFS_KEY, null) ?: return@runCatching emptyMap<String, String>()
        val stored = JSONObject(raw)
        if (stored.optString("operation") != operation) return@runCatching emptyMap<String, String>()
        val items = stored.getJSONObject("outputs")
        buildMap { val keys = items.keys(); while (keys.hasNext()) { val path = keys.next(); put(path, items.getString(path)) } }
    }.getOrDefault(emptyMap())

    private fun remapSavedPaths(context: Context, mapping: Map<String, String>) {
        if (mapping.isEmpty()) return
        val prefs = context.getSharedPreferences("ab_file_pro_files", 0)
        fun remapPath(path: String): String {
            mapping.forEach { (from, to) ->
                if (path == from || path.startsWith(from + File.separator)) return to + path.removePrefix(from)
            }
            return path
        }
        val favorites = prefs.getStringSet("favorites", emptySet()).orEmpty().mapTo(mutableSetOf()) { remapPath(it) }
        val recent = prefs.getStringSet("recent_files_v2", emptySet()).orEmpty().mapTo(mutableSetOf()) { raw ->
            val sep = raw.indexOf('|')
            if (sep <= 0) raw else {
                val ts = raw.substring(0, sep)
                val path = raw.substring(sep + 1)
                "$ts|${remapPath(path)}"
            }
        }
        prefs.edit().putStringSet("favorites", favorites).putStringSet("recent_files_v2", recent).apply()
    }

    private fun save(context: Context, value: String, proofs: Map<String, String>) {
        val outputProofs = JSONObject().apply { proofs.forEach { (path, token) -> put(path, token) } }
        val proof = JSONObject().put("operation", value).put("outputs", outputProofs)
        context.getSharedPreferences(PREFS, 0).edit().putString(KEY, value).putString(PROOFS_KEY, proof.toString()).apply()
    }
}
