package com.abfilepro.app.core

import android.content.Context
import android.net.Uri
import java.io.File

object FileUndoStore {
    private const val PREFS = "ab_file_pro_file_undo"
    private const val KEY = "last_operation_v1"

    private fun enc(v: String) = Uri.encode(v)
    private fun dec(v: String) = Uri.decode(v)

    fun recordRename(context: Context, old: File, new: File) = save(context, "rename|${enc(old.absolutePath)}|${enc(new.absolutePath)}")

    fun recordMoves(context: Context, moves: Map<String, String>) {
        if (moves.isEmpty()) return
        save(context, "move|" + moves.entries.joinToString(";") { "${enc(it.key)},${enc(it.value)}" })
    }

    fun clear(context: Context) = context.getSharedPreferences(PREFS, 0).edit().remove(KEY).apply()

    fun canUndo(context: Context): Boolean = !context.getSharedPreferences(PREFS, 0).getString(KEY, null).isNullOrBlank()

    fun undo(context: Context): Result<Int> = runCatching {
        val raw = context.getSharedPreferences(PREFS, 0).getString(KEY, null) ?: error("لا توجد عملية قابلة للتراجع")
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
                require(current.renameTo(old)) { "تعذر التراجع عن إعادة التسمية" }
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
                    old.parentFile?.mkdirs()
                    if (current.renameTo(old)) {
                        restored++
                        pending.remove(pair)
                        // Persist each successful restoration before ancillary path
                        // remapping. Conflicts remain available for a later retry.
                        if (pending.isEmpty()) clear(context) else save(context, "move|" + pending.joinToString(";"))
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
    }

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

    private fun save(context: Context, value: String) {
        context.getSharedPreferences(PREFS, 0).edit().putString(KEY, value).apply()
    }
}
