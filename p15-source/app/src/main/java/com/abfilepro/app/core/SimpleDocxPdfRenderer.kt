package com.abfilepro.app.core

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.util.Xml
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/** Lightweight, local DOCX text renderer. Does not claim to reproduce complex Word layout. */
internal object SimpleDocxPdfRenderer {
    private const val WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private data class Paragraph(
        val text: String,
        val pt: Float = 12f,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val align: String = "auto",
        val beforePt: Float = 0f,
        val afterPt: Float = 5f,
        val pageBreakBefore: Boolean = false
    )
    private data class Layout(
        val paragraphs: List<Paragraph>, val width: Int, val height: Int,
        val left: Float, val right: Float, val top: Float, val bottom: Float
    )
    private fun attr(p: XmlPullParser, name: String) =
        p.getAttributeValue(WORD_NS, name) ?: p.getAttributeValue(null, name)
    private fun twips(value: String?, fallback: Float): Float =
        value?.toFloatOrNull()?.div(20f)?.takeIf { it >= 0f } ?: fallback

    private fun parse(documentXml: ByteArray): Layout {
        val xml = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(ByteArrayInputStream(documentXml), "UTF-8")
        }
        val paragraphs = mutableListOf<Paragraph>()
        var paragraph = StringBuilder()
        var inParagraph = false
        var inParaProperties = false
        var inRunProperties = false
        var fontPt = 11f
        var isBold = false
        var isItalic = false
        var align = "auto"
        var spaceBefore = 0f
        var spaceAfter = 5f
        var pageBreakBefore = false
        var pageWidth = 595
        var pageHeight = 842
        var left = 54f
        var right = 54f
        var top = 54f
        var bottom = 54f
        while (xml.eventType != XmlPullParser.END_DOCUMENT) {
            when (xml.eventType) {
                XmlPullParser.START_TAG -> when (xml.name) {
                    "p" -> {
                        paragraph = StringBuilder(); inParagraph = true
                        fontPt = 11f; isBold = false; isItalic = false
                        align = "auto"; spaceBefore = 0f; spaceAfter = 5f
                        pageBreakBefore = false
                    }
                    "pPr" -> if (inParagraph) inParaProperties = true
                    "rPr" -> if (inParagraph) inRunProperties = true
                    "sz", "szCs" -> if (inRunProperties) {
                        fontPt = max(fontPt, (attr(xml, "val")?.toFloatOrNull() ?: 24f) / 2f)
                            .coerceIn(8f, 40f)
                    }
                    "b" -> if (inRunProperties) isBold = attr(xml, "val") != "0"
                    "i" -> if (inRunProperties) isItalic = attr(xml, "val") != "0"
                    "jc" -> if (inParaProperties) align = attr(xml, "val") ?: "auto"
                    "spacing" -> if (inParaProperties) {
                        spaceBefore = twips(attr(xml, "before"), 0f).coerceAtMost(54f)
                        spaceAfter = twips(attr(xml, "after"), 5f).coerceAtMost(54f)
                    }
                    "pageBreakBefore" -> if (inParaProperties) pageBreakBefore = true
                    "t" -> if (inParagraph) paragraph.append(xml.nextText())
                    "tab" -> if (inParagraph) paragraph.append("    ")
                    "br" -> if (inParagraph) {
                        if (attr(xml, "type") == "page") paragraph.append('\u000C')
                        else paragraph.append('\n')
                    }
                    "pgSz" -> {
                        pageWidth = twips(attr(xml, "w"), 595f).toInt().coerceIn(400, 1200)
                        pageHeight = twips(attr(xml, "h"), 842f).toInt().coerceIn(400, 1600)
                    }
                    "pgMar" -> {
                        left = twips(attr(xml, "left"), 54f)
                        right = twips(attr(xml, "right"), 54f)
                        top = twips(attr(xml, "top"), 54f)
                        bottom = twips(attr(xml, "bottom"), 54f)
                    }
                }
                XmlPullParser.END_TAG -> when (xml.name) {
                    "pPr" -> inParaProperties = false
                    "rPr" -> inRunProperties = false
                    "p" -> {
                        paragraphs += Paragraph(paragraph.toString(), fontPt, isBold, isItalic,
                            align, spaceBefore, spaceAfter, pageBreakBefore)
                        inParagraph = false
                    }
                }
            }
            xml.next()
        }
        require(paragraphs.any { it.text.isNotBlank() }) { "ملف Word لا يحتوي على نص" }
        require(paragraphs.size <= 75_000) { "المستند يحتوي على فقرات كثيرة جدًا للمعالجة الآمنة" }
        require(left + right + 80f < pageWidth && top + bottom + 80f < pageHeight) {
            "هوامش Word لا تترك مساحة كافية للمحتوى"
        }
        return Layout(paragraphs, pageWidth, pageHeight, left, right, top, bottom)
    }

    suspend fun convert(context: Context, documentXml: ByteArray): File {
        val layout = parse(documentXml)
        val result = File(FileUtils.outputDir(context, "pdf"), "Word_${System.currentTimeMillis()}.pdf")
        val pdf = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { color = Color.BLACK }
        var page: PdfDocument.Page? = null
        var pageIndex = 0
        var baseline = layout.top
        fun beginPage() {
            page?.let(pdf::finishPage)
            pageIndex++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(layout.width, layout.height, pageIndex).create())
            baseline = layout.top
        }
        try {
            beginPage()
            val available = layout.width - layout.left - layout.right
            for (para in layout.paragraphs) {
                currentCoroutineContext().ensureActive()
                if (para.pageBreakBefore && baseline > layout.top + 1f) beginPage()
                val pt = para.pt.coerceIn(8f, 40f)
                paint.textSize = pt
                paint.typeface = Typeface.create("sans-serif", when {
                    para.bold && para.italic -> Typeface.BOLD_ITALIC
                    para.bold -> Typeface.BOLD
                    para.italic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                })
                val lineHeight = pt * 1.45f
                baseline += para.beforePt
                val source = para.text.split('\u000C')
                source.forEachIndexed { section, text ->
                    if (section > 0 && baseline > layout.top + 1f) beginPage()
                    for (line in text.split('\n')) {
                        val wrapped = wrap(line, paint, available)
                        for (part in wrapped) {
                            if (baseline + lineHeight > layout.height - layout.bottom) beginPage()
                            val rtl = part.any { it in '\u0600'..'\u06FF' }
                            val align = if (para.align == "auto") (if (rtl) "right" else "left") else para.align
                            paint.textAlign = when (align) {
                                "right", "end" -> Paint.Align.RIGHT
                                "center" -> Paint.Align.CENTER
                                else -> Paint.Align.LEFT
                            }
                            val x = when (paint.textAlign) {
                                Paint.Align.RIGHT -> layout.width - layout.right
                                Paint.Align.CENTER -> layout.left + available / 2f
                                else -> layout.left
                            }
                            if (part.isNotBlank()) page!!.canvas.drawText(part, x, baseline + pt, paint)
                            baseline += lineHeight
                        }
                    }
                }
                baseline += para.afterPt
            }
            page?.let(pdf::finishPage)
            FileOutputStream(result).use { pdf.writeTo(it) }
            require(result.length() > 100L) { "ملف PDF الناتج غير مكتمل" }
            return result
        } catch (error: Exception) {
            result.delete()
            throw error
        } finally {
            pdf.close()
        }
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isBlank()) return listOf("")
        val parts = mutableListOf<String>()
        var line = ""
        for (word in text.split(Regex("\\s+"))) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(next) <= maxWidth) { line = next; continue }
            if (line.isNotEmpty()) { parts += line; line = "" }
            if (paint.measureText(word) <= maxWidth) line = word
            else {
                var segment = ""
                for (ch in word) {
                    if (segment.isNotEmpty() && paint.measureText(segment + ch) > maxWidth) {
                        parts += segment; segment = ""
                    }
                    segment += ch
                }
                line = segment
            }
        }
        if (line.isNotEmpty()) parts += line
        return parts.ifEmpty { listOf("") }
    }
}
