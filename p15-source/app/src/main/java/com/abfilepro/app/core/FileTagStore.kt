package com.abfilepro.app.core

import android.content.Context
import android.net.Uri
import java.io.File

object FileTagStore {
    private const val PREFS = "ab_file_pro_tags"
    private const val KEY = "entries_v1"

    private fun encode(value: String): String = Uri.encode(value)
    private fun decode(value: String): String = Uri.decode(value)

    private fun read(context: Context): MutableMap<String, MutableSet<String>> {
        val map = linkedMapOf<String, MutableSet<String>>()
        context.getSharedPreferences(PREFS, 0).getStringSet(KEY, emptySet()).orEmpty().forEach { raw ->
            val sep = raw.indexOf('|')
            if (sep <= 0) return@forEach
            val path = decode(raw.substring(0, sep))
            val tags = raw.substring(sep + 1).split(',')
                .map(::decode).map { it.trim() }.filter { it.isNotBlank() }.toMutableSet()
            if (tags.isNotEmpty()) map[path] = tags
        }
        return map
    }

    private fun write(context: Context, map: Map<String, Set<String>>) {
        val values = map.mapNotNullTo(mutableSetOf()) { (path, tags) ->
            val cleaned = tags.map { it.trim().take(40) }.filter { it.isNotBlank() }.distinct().take(12)
            if (cleaned.isEmpty()) null else "${encode(path)}|${cleaned.joinToString(",") { encode(it) }}"
        }
        context.getSharedPreferences(PREFS, 0).edit().putStringSet(KEY, values).apply()
    }

    fun tagsFor(context: Context, file: File): Set<String> = read(context)[file.absolutePath].orEmpty()

    fun setTags(context: Context, file: File, tags: Collection<String>) {
        require(FileUtils.isInsideRoot(context, file)) { "Invalid file" }
        val map = read(context)
        val cleaned = tags.map { it.trim().replace(",", " ").take(40) }.filter { it.isNotBlank() }.toMutableSet()
        if (cleaned.isEmpty()) map.remove(file.absolutePath) else map[file.absolutePath] = cleaned
        write(context, map)
    }

    fun movePath(context: Context, old: File, new: File) {
        val map = read(context)
        val affected = map.keys.filter { it == old.absolutePath || it.startsWith(old.absolutePath + File.separator) }
        if (affected.isEmpty()) return
        affected.forEach { oldPath ->
            val tags = map.remove(oldPath) ?: return@forEach
            map[new.absolutePath + oldPath.removePrefix(old.absolutePath)] = tags
        }
        write(context, map)
    }

    fun removePaths(context: Context, paths: Set<String>) {
        if (paths.isEmpty()) return
        val map = read(context)
        map.keys.filter { path -> paths.any { root -> path == root || path.startsWith(root + File.separator) } }
            .forEach { map.remove(it) }
        write(context, map)
    }

    fun allTags(context: Context): Set<String> = read(context).values.flatten().toSortedSet(String.CASE_INSENSITIVE_ORDER)

    fun filesWithTag(context: Context, tag: String, limit: Int = 300): List<File> = read(context)
        .asSequence()
        .filter { (_, tags) -> tags.any { it.equals(tag, ignoreCase = true) } }
        .map { (path, _) -> File(path) }
        .filter { it.exists() && FileUtils.isInsideRoot(context, it) }
        .sortedByDescending { it.lastModified() }
        .take(limit)
        .toList()
}
