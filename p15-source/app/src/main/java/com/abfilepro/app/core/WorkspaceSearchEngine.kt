package com.abfilepro.app.core

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

object WorkspaceSearchEngine {
    data class Hit(val file: File, val matchedContent: Boolean)

    fun search(context: Context, rawQuery: String, limit: Int = 300): List<Hit> {
        val query = rawQuery.trim()
        if (query.isBlank()) return emptyList()
        val files = FileUtils.allWorkspaceEntries(context, maxVisited = 12_000)
        val results = ArrayList<Hit>()
        var pdfContentChecks = 0
        var textContentChecks = 0
        for (entry in files) {
            if (results.size >= limit) break
            if (entry.name.contains(query, ignoreCase = true)) {
                results += Hit(entry, false)
                continue
            }
            if (!entry.isFile) continue
            val contentMatch = when {
                FileUtils.isTextFile(entry) && textContentChecks < 250 && entry.length() <= 2L * 1024L * 1024L -> {
                    textContentChecks++
                    runCatching { FileUtils.readTextPreview(entry, maxBytes = 512 * 1024).contains(query, ignoreCase = true) }.getOrDefault(false)
                }
                entry.extension.equals("pdf", true) && pdfContentChecks < 80 && entry.length() <= 30L * 1024L * 1024L -> {
                    pdfContentChecks++
                    pdfContains(entry, query)
                }
                else -> false
            }
            if (contentMatch) results += Hit(entry, true)
        }
        return results.sortedWith(
            compareBy<Hit> { if (it.file.name.equals(query, true)) 0 else if (!it.matchedContent) 1 else 2 }
                .thenByDescending { it.file.lastModified() }
        )
    }

    private fun pdfContains(file: File, query: String): Boolean = runCatching {
        PDDocument.load(file).use { doc ->
            if (doc.isEncrypted) return@use false
            val stripper = PDFTextStripper().apply {
                startPage = 1
                endPage = minOf(doc.numberOfPages, 30)
            }
            stripper.getText(doc).contains(query, ignoreCase = true)
        }
    }.getOrDefault(false)
}
