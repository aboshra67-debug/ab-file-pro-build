package com.abfilepro.app.core

import android.content.Context
import java.io.File
import org.json.JSONArray

/** Lightweight, local history of approved conversion outputs; never deletes the output files. */
object ConversionHistoryStore {
    private const val PREFS = "ab_file_pro_conversions"
    private const val KEY = "approved_output_paths"
    private const val MAX = 25

    fun recent(context: Context): List<File> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val entries = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return (0 until entries.length()).mapNotNull { index ->
            val path = entries.optString(index)
            if (path.isBlank()) null else runCatching { File(path).canonicalFile }.getOrNull()
        }.filter { it.isFile && it.length() > 0 && FileUtils.isInsideRoot(context, it) }
            .distinctBy { it.absolutePath }.take(MAX)
    }

    fun record(context: Context, saved: List<File>) {
        val accepted = saved.filter { FileUtils.verifySavedFile(context, it) }
        require(accepted.size == saved.size && saved.isNotEmpty()) { "لم يتم التحقق من جميع نتائج الحفظ" }
        val combined = (accepted + recent(context)).distinctBy { it.canonicalPath }.take(MAX)
        val serialized = JSONArray().apply { combined.forEach { put(it.canonicalPath) } }.toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, serialized).apply()
    }
}
