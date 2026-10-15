package com.abfilepro.app.core

import android.content.Context
import android.net.Uri
import java.io.File

object TrashStore {
    private const val PREFS = "ab_file_pro_trash"
    private const val KEY = "items_v1"

    data class TrashItem(val timestamp: Long, val trashFile: File, val originalPath: String)

    private fun trashRoot(context: Context): File = File(FileUtils.rootDir(context), ".ab_trash").apply { mkdirs() }
    private fun encode(v: String) = Uri.encode(v)
    private fun decode(v: String) = Uri.decode(v)

    fun items(context: Context): List<TrashItem> = context.getSharedPreferences(PREFS, 0)
        .getStringSet(KEY, emptySet()).orEmpty()
        .mapNotNull { raw ->
            val p = raw.split('|', limit = 3)
            if (p.size != 3) return@mapNotNull null
            val ts = p[0].toLongOrNull() ?: return@mapNotNull null
            val trash = File(decode(p[1]))
            val original = decode(p[2])
            if (!trash.exists() || !FileUtils.isInsideRoot(context, trash)) null else TrashItem(ts, trash, original)
        }
        .sortedByDescending { it.timestamp }

    private fun write(context: Context, values: Collection<TrashItem>) {
        val raw = values.filter { it.trashFile.exists() }.mapTo(mutableSetOf()) {
            "${it.timestamp}|${encode(it.trashFile.absolutePath)}|${encode(it.originalPath)}"
        }
        context.getSharedPreferences(PREFS, 0).edit().putStringSet(KEY, raw).apply()
    }

    fun moveToTrash(context: Context, entry: File): TrashItem {
        require(entry.exists() && FileUtils.isInsideRoot(context, entry)) { "العنصر غير صالح" }
        require(!entry.absolutePath.startsWith(trashRoot(context).absolutePath + File.separator)) { "العنصر موجود في السلة بالفعل" }
        val stamp = System.currentTimeMillis()
        val safeName = entry.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        var target = File(trashRoot(context), "${stamp}_$safeName")
        var n = 1
        while (target.exists()) target = File(trashRoot(context), "${stamp}_${n++}_$safeName")
        require(entry.renameTo(target)) { "تعذر نقل العنصر إلى سلة المحذوفات بأمان" }
        val item = TrashItem(stamp, target, entry.absolutePath)
        write(context, items(context) + item)
        return item
    }

    fun restore(context: Context, item: TrashItem): File {
        require(item.trashFile.exists() && FileUtils.isInsideRoot(context, item.trashFile)) { "العنصر لم يعد موجودًا في السلة" }
        val root = FileUtils.rootDir(context).canonicalFile
        var requested = File(item.originalPath)
        val requestedCanonical = runCatching { requested.canonicalFile }.getOrNull()
        if (requestedCanonical == null || (requestedCanonical != root && !requestedCanonical.path.startsWith(root.path + File.separator))) {
            requested = File(root, item.trashFile.name.substringAfter('_', item.trashFile.name))
        }
        requested.parentFile?.mkdirs()
        var target = requested
        var n = 1
        val base = requested.nameWithoutExtension
        val ext = requested.extension
        while (target.exists()) {
            target = File(requested.parentFile, if (ext.isBlank()) "${base}_restored_${n++}" else "${base}_restored_${n++}.$ext")
        }
        require(item.trashFile.renameTo(target)) { "تعذر استرجاع العنصر" }
        write(context, items(context).filterNot { it.trashFile.absolutePath == item.trashFile.absolutePath })
        return target
    }

    fun deletePermanently(context: Context, item: TrashItem): Boolean {
        val ok = runCatching { if (item.trashFile.isDirectory) item.trashFile.deleteRecursively() else item.trashFile.delete() }.getOrDefault(false)
        if (ok) {
            FileTagStore.removePaths(context, setOf(item.trashFile.absolutePath))
            write(context, items(context).filterNot { it.trashFile.absolutePath == item.trashFile.absolutePath })
        }
        return ok
    }

    fun empty(context: Context): Int {
        val snapshot = items(context)
        var deleted = 0
        snapshot.forEach { if (deletePermanently(context, it)) deleted++ }
        return deleted
    }
}
