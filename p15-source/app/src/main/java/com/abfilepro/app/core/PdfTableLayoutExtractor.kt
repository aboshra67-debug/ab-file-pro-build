package com.abfilepro.app.core

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max

/** Only exports PDF rows that look like a repeating multi-column table.
 *  Prose, screenshots, and uncertain layouts are NOT silently made into XLSX. */
internal object PdfTableLayoutExtractor {
    private const val MAX_PAGES = 250
    private const val MAX_GLYPHS_PER_PAGE = 150_000
    private data class Glyph(val text: String, val x: Float, val right: Float, val y: Float, val height: Float, val order: Int)
    private data class Cell(val x: Float, val right: Float, val text: String)
    private data class Line(val cells: List<Cell>, val y: Float)

    suspend fun extract(context: Context, uri: Uri): List<Pair<String, List<List<String>>>> {
        context.contentResolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "تعذر فتح ملف PDF" }
            PDDocument.load(stream).use { pdf ->
                require(pdf.numberOfPages in 1..MAX_PAGES) { "حجم PDF غير مناسب للتحويل الآمن إلى Excel" }
                val result = mutableListOf<Pair<String, List<List<String>>>>()
                for (pageIndex in 0 until pdf.numberOfPages) {
                    currentCoroutineContext().ensureActive()
                    val stripper = GeometryStripper().apply {
                        sortByPosition = true
                        startPage = pageIndex + 1
                        endPage = pageIndex + 1
                    }
                    stripper.getText(pdf)
                    val rows = detectRows(stripper.glyphs, pdf.getPage(pageIndex).mediaBox.width)
                    if (rows.isNotEmpty()) result += "Page ${pageIndex + 1}" to rows
                }
                require(result.isNotEmpty()) {
                    "لم أجد جدولًا واضحًا بصفوف وأعمدة متكررة داخل PDF؛ لم أحوّل النص العادي إلى جدول Excel وهمي. إذا كان الملف صورة، استخدم OCR للجداول."
                }
                return result
            }
        }
    }

    private class GeometryStripper : PDFTextStripper() {
        val glyphs = mutableListOf<Glyph>()
        override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
            textPositions?.forEach { p ->
                if (glyphs.size >= MAX_GLYPHS_PER_PAGE) throw IOException("صفحة PDF مزدحمة جدًا لمعالجة الجداول بأمان")
                val value = p.unicode.orEmpty()
                if (value.isNotEmpty()) glyphs += Glyph(value, p.xDirAdj, p.xDirAdj + p.widthDirAdj,
                    p.yDirAdj, p.heightDir, glyphs.size)
            }
        }
    }

    private fun detectRows(glyphs: List<Glyph>, pageWidth: Float): List<List<String>> {
        if (glyphs.size < 12) return emptyList()
        val medianHeight = glyphs.map { it.height }.sorted().let { it[it.size / 2] }.coerceAtLeast(7f)
        val lines = mutableListOf<MutableList<Glyph>>()
        glyphs.sortedWith(compareBy<Glyph> { it.y }.thenBy { it.x }).forEach { glyph ->
            val line = lines.lastOrNull()
            if (line != null && abs(line.first().y - glyph.y) <= max(2.5f, medianHeight * .30f)) line += glyph
            else lines += mutableListOf(glyph)
        }
        val geomLines = lines.map { glyphLine ->
            val sorted = glyphLine.sortedBy { it.x }
            val widths = sorted.map { max(1f, (it.right - it.x) / it.text.length.coerceAtLeast(1)) }.sorted()
            val typical = widths[widths.size / 2]
            val gap = max(18f, max(medianHeight * 1.8f, typical * 3.0f))
            val groups = mutableListOf<MutableList<Glyph>>()
            sorted.forEach { glyph ->
                val previous = groups.lastOrNull()?.lastOrNull()
                if (previous == null || glyph.x - previous.right > gap) groups += mutableListOf(glyph)
                else groups.last() += glyph
            }
            val cells = groups.map { group ->
                // Keep the PDF writer's original per-character order inside each cell.
                val sourceOrder = group.sortedBy { it.order }
                Cell(group.minOf { it.x }, group.maxOf { it.right },
                    sourceOrder.joinToString("") { it.text }.replace(Regex("\\s+"), " ").trim())
            }.filter { it.text.isNotBlank() }
            Line(cells, glyphLine.first().y)
        }.filter { it.cells.isNotEmpty() }
        val candidates = geomLines.filter { it.cells.size in 2..12 }
        if (candidates.size < max(3, (geomLines.size + 3) / 4)) return emptyList()
        val rtl = candidates.sumOf { line -> line.cells.sumOf { cell -> cell.text.count { ch -> ch in '\u0600'..'\u06FF' } } } >
            candidates.sumOf { line -> line.cells.sumOf { cell -> cell.text.count { ch -> ch in 'A'..'z' } } }
        val anchors = mutableListOf<MutableList<Float>>()
        val tolerance = max(12f, pageWidth * .025f)
        candidates.forEach { line ->
            line.cells.forEach { cell ->
                val x = if (rtl) cell.right else cell.x
                val cluster = anchors.minByOrNull { abs(it.average().toFloat() - x) }
                if (cluster != null && abs(cluster.average().toFloat() - x) < tolerance) cluster += x
                else anchors += mutableListOf(x)
            }
        }
        val frequent = anchors.filter { cluster -> cluster.size >= 3 }
            .map { it.average().toFloat() }.sortedWith(if (rtl) compareByDescending<Float> { it } else compareBy<Float> { it })
        if (frequent.size !in 2..12) return emptyList()
        if (frequent.zipWithNext().any { (a, b) -> abs(a - b) < max(32f, pageWidth * .07f) }) return emptyList()
        val grid = candidates.mapNotNull { line ->
            val cells = MutableList(frequent.size) { "" }
            for (cell in line.cells) {
                val x = if (rtl) cell.right else cell.x
                val i = frequent.indices.minByOrNull { abs(frequent[it] - x) } ?: continue
                if (abs(frequent[i] - x) > tolerance * 1.65f || cells[i].isNotEmpty()) return@mapNotNull null
                cells[i] = cell.text
            }
            cells.takeIf { it.count(String::isNotBlank) >= 2 }
        }
        if (grid.size < 3 || grid.size < candidates.size * .65f) return emptyList()
        if (grid.count { row -> row.count(String::isNotBlank) >= 2 } < 3) return emptyList()
        return grid
    }
}
