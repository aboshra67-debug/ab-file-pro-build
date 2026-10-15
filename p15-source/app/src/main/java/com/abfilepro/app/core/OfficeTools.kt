package com.abfilepro.app.core

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Xml
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.max

object OfficeTools {
    private const val PAGE_BREAK = "\u000C"
    private const val MAX_XML_ENTRY_BYTES = 32 * 1024 * 1024
    private const val MAX_TOTAL_XML_BYTES = 96 * 1024 * 1024
    private const val MAX_PDF_PAGES_FOR_OFFICE = 250
    private const val MAX_EXTRACTED_TEXT_CHARS = 8_000_000

    suspend fun docxToPdf(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        RuntimeSafety.ensureStorage(context)
        currentCoroutineContext().ensureActive()
        val packageNames = readZipEntryNames(context, uri)
        val entries = readZipEntries(context, uri) { name -> name == "word/document.xml" }
        val documentXml = entries["word/document.xml"] ?: error("ملف Word غير صالح أو غير مدعوم")
        validateSimpleDocx(packageNames, documentXml)
        // Unlike the old flattened-text path, preserve paragraph alignment, basic font size,
        // page boundaries and document margins for locally supported DOCX files.
        SimpleDocxPdfRenderer.convert(context, documentXml)
    }

    suspend fun xlsxToPdf(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        RuntimeSafety.ensureStorage(context)
        currentCoroutineContext().ensureActive()
        val packageNames = readZipEntryNames(context, uri)
        validateSimpleXlsxPackage(packageNames)
        val entries = readZipEntries(context, uri) { name ->
            name == "xl/sharedStrings.xml" ||
                (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml"))
        }
        val sharedStrings = entries["xl/sharedStrings.xml"]?.let(::parseSharedStrings).orEmpty()
        val sheetNames = entries.keys
            .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
            .sortedWith(compareBy { sheetNumber(it) })
        require(sheetNames.isNotEmpty()) { "ملف Excel غير صالح أو لا يحتوي على أوراق" }
        sheetNames.forEach { name -> validateSimpleWorksheet(entries.getValue(name)) }

        val lines = mutableListOf<String>()
        sheetNames.forEachIndexed { index, name ->
            if (index > 0) lines += PAGE_BREAK
            lines += "ورقة ${index + 1}"
            parseWorksheet(entries.getValue(name), sharedStrings).forEach { row ->
                lines += row.joinToString("   |   ")
            }
        }
        require(lines.any { it.isNotBlank() && it != PAGE_BREAK }) { "لم يتم العثور على بيانات داخل Excel" }
        renderLinesToPdf(context, lines, "Excel_${System.currentTimeMillis()}.pdf", fontSize = 11.5f)
    }

    suspend fun imageToDocx(
        context: Context,
        uri: Uri,
        language: OcrProcessor.Language
    ): File = withContext(Dispatchers.IO) {
        RuntimeSafety.ensureStorage(context)
        currentCoroutineContext().ensureActive()
        val text = OcrProcessor.recognize(context, uri, language)
        require(text.isNotBlank()) { "لم يتم العثور على نص واضح داخل الصورة" }
        val out = File(FileUtils.outputDir(context, "office"), "Image_OCR_${System.currentTimeMillis()}.docx")
        writeDocx(out, text.lines())
        out
    }

    /** Word is only generated after the OCR text was displayed and reviewed by the user. */
    suspend fun reviewedTextToDocx(context: Context, reviewedText: String): File = withContext(Dispatchers.IO) {
        RuntimeSafety.ensureStorage(context)
        currentCoroutineContext().ensureActive()
        val text = reviewedText.trim()
        require(text.isNotBlank()) { "راجع النص المستخرج قبل إنشاء Word" }
        val out = File(FileUtils.outputDir(context, "office"), "Image_OCR_Reviewed_${System.currentTimeMillis()}.docx")
        writeDocx(out, text.lines())
        out
    }

    suspend fun imageTableToXlsx(
        context: Context,
        uri: Uri,
        language: OcrProcessor.Language
    ): File = withContext(Dispatchers.IO) {
        RuntimeSafety.ensureStorage(context)
        currentCoroutineContext().ensureActive()
        val layout = OcrProcessor.recognizeLayout(context, uri, language)
        require(layout.plainText.isNotBlank() || layout.words.isNotEmpty()) { "لم يتم العثور على بيانات واضحة داخل الصورة" }
        val rows = inferLayoutTableRows(layout).ifEmpty { inferTableRows(layout.plainText) }
        require(rows.size >= 2 && rows.count { row -> row.count { it.isNotBlank() } >= 2 } >= 2) {
            "تعذر تمييز صفوف وأعمدة الجدول بدقة كافية؛ لم يتم إنشاء Excel بعمود واحد من نص الصورة"
        }
        val out = File(FileUtils.outputDir(context, "office"), "Image_Table_${System.currentTimeMillis()}.xlsx")
        writeXlsxSheets(out, listOf("OCR Table" to rows))
        out
    }

    suspend fun pdfToDocx(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        RuntimeSafety.ensureStorage(context)
        currentCoroutineContext().ensureActive()
        val pages = extractPdfPages(context, uri)
        require(pages.any { it.isNotBlank() }) {
            "ملف PDF لا يحتوي على نص قابل للاستخراج. استخدم OCR للملفات المصورة."
        }
        val lines = mutableListOf<String>()
        pages.forEachIndexed { index, page ->
            if (index > 0) lines += PAGE_BREAK
            lines += page.lines()
        }
        val out = File(FileUtils.outputDir(context, "office"), "PDF_${System.currentTimeMillis()}.docx")
        writeDocx(out, lines)
        out
    }

    suspend fun pdfToXlsx(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        RuntimeSafety.ensureStorage(context)
        currentCoroutineContext().ensureActive()
        // Geometry-confirmed tables only: never turn a page of prose into a fake spreadsheet.
        val sheets = PdfTableLayoutExtractor.extract(context, uri)
        val out = File(FileUtils.outputDir(context, "office"), "PDF_${System.currentTimeMillis()}.xlsx")
        try {
            writeXlsxSheets(out, sheets)
            require(out.length() > 500) { "نتيجة Excel غير مكتملة" }
            out
        } catch (error: Exception) {
            out.delete()
            throw error
        }
    }

    fun previewGeneratedOffice(file: File, maxLines: Int = 28): String {
        require(file.exists() && file.isFile) { "ملف المعاينة غير موجود" }
        return when (file.extension.lowercase()) {
            "docx" -> previewDocx(file, maxLines)
            "xlsx" -> previewXlsx(file, maxLines)
            else -> "${file.name}\n${humanSize(file.length())}"
        }
    }

    private fun previewDocx(file: File, maxLines: Int): String {
        val entries = readZipEntries(file) { it == "word/document.xml" }
        val xml = entries["word/document.xml"] ?: return "تعذر إنشاء معاينة Word"
        val lines = extractDocxLines(xml).filter { it != PAGE_BREAK }.filter { it.isNotBlank() }.take(maxLines)
        return buildString {
            append("معاينة Word — ").append(humanSize(file.length())).append('\n')
            append(lines.joinToString("\n"))
            if (lines.size >= maxLines) append("\n…")
        }
    }

    private fun previewXlsx(file: File, maxLines: Int): String {
        val entries = readZipEntries(file) { name ->
            name == "xl/sharedStrings.xml" || (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml"))
        }
        val shared = entries["xl/sharedStrings.xml"]?.let(::parseSharedStrings).orEmpty()
        val sheets = entries.keys.filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
            .sortedWith(compareBy { sheetNumber(it) })
        val lines = mutableListOf<String>()
        for ((sheetIndex, name) in sheets.withIndex()) {
            if (lines.size >= maxLines) break
            lines += "[ورقة ${sheetIndex + 1}]"
            parseWorksheet(entries.getValue(name), shared).take((maxLines - lines.size).coerceAtLeast(0)).forEach { row ->
                lines += row.joinToString(" | ")
            }
        }
        return buildString {
            append("معاينة Excel — ").append(humanSize(file.length())).append('\n')
            append(lines.take(maxLines).joinToString("\n"))
            if (lines.size >= maxLines) append("\n…")
        }
    }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun validateSimpleDocx(names: Set<String>, documentXml: ByteArray) {
        val unsupportedPackage = names.any { name ->
            name.startsWith("word/media/") || name.startsWith("word/charts/") ||
                name.startsWith("word/embeddings/") || name.startsWith("word/header") ||
                name.startsWith("word/footer") || name == "word/footnotes.xml" ||
                name == "word/endnotes.xml" || name == "word/comments.xml"
        }
        val xml = documentXml.toString(Charsets.UTF_8)
        val unsupportedMarkup = listOf("<w:tbl", "<w:drawing", "<w:pict", "<w:object", "<w:txbxContent", "<mc:AlternateContent")
            .any(xml::contains)
        require(!unsupportedPackage && !unsupportedMarkup) {
            "هذا Word يحتوي على صور/جداول/رؤوس أو عناصر معقدة لا يستطيع AB File Pro الحفاظ على تنسيقها محليًا بعد. لم يتم التحويل حتى لا ينتج ملف مضلل."
        }
    }

    private fun validateSimpleXlsxPackage(names: Set<String>) {
        val unsupported = names.any { name ->
            name.startsWith("xl/charts/") || name.startsWith("xl/drawings/") ||
                name.startsWith("xl/media/") || name.startsWith("xl/pivot") ||
                name.startsWith("xl/externalLinks/") || name.startsWith("xl/embeddings/")
        }
        require(!unsupported) {
            "هذا Excel يحتوي على مخططات/صور/Pivot أو عناصر معقدة لا يمكن الحفاظ على شكلها محليًا بعد. لم يتم التحويل."
        }
    }

    private fun validateSimpleWorksheet(xmlBytes: ByteArray) {
        val xml = xmlBytes.toString(Charsets.UTF_8)
        val unsupported = listOf("<mergeCells", "<conditionalFormatting", "<dataValidations", "<extLst").any(xml::contains)
        require(!unsupported) {
            "ورقة Excel تحتوي على دمج خلايا أو تنسيق متقدم قد يتغير في PDF. أُلغي التحويل بدل إنشاء نتيجة غير دقيقة."
        }
    }

    private suspend fun readZipEntryNames(context: Context, uri: Uri): Set<String> {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "تعذر فتح ملف Office" }
            ZipInputStream(input.buffered()).use { zip ->
                val names = LinkedHashSet<String>()
                var count = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) names += entry.name
                    zip.closeEntry()
                    count++
                    require(count <= 20_000) { "حزمة Office تحتوي عددًا غير طبيعي من العناصر" }
                }
                return names
            }
        }
    }

    private fun readZipEntries(file: File, keep: (String) -> Boolean): Map<String, ByteArray> {
        FileInputStream(file).buffered().use { input ->
            ZipInputStream(input).use { zip ->
                val result = linkedMapOf<String, ByteArray>()
                var totalBytes = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && keep(entry.name)) {
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        var entryBytes = 0
                        while (true) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            entryBytes += read
                            totalBytes += read
                            require(entryBytes <= MAX_XML_ENTRY_BYTES && totalBytes <= MAX_TOTAL_XML_BYTES) { "ملف Office كبير جدًا للمعالجة الآمنة" }
                            output.write(buffer, 0, read)
                        }
                        result[entry.name] = output.toByteArray()
                    }
                    zip.closeEntry()
                }
                return result
            }
        }
    }

    private suspend fun extractPdfPages(context: Context, uri: Uri): List<String> {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "تعذر فتح PDF" }
            PDDocument.load(input).use { document ->
                require(document.numberOfPages <= MAX_PDF_PAGES_FOR_OFFICE) {
                    "الملف كبير جدًا للتحويل المباشر (${document.numberOfPages} صفحة). الحد الحالي $MAX_PDF_PAGES_FOR_OFFICE صفحة للحفاظ على ثبات الهاتف."
                }
                val pages = ArrayList<String>(document.numberOfPages)
                val stripper = PDFTextStripper().apply { sortByPosition = true }
                var totalChars = 0
                for (pageNumber in 1..document.numberOfPages) {
                    currentCoroutineContext().ensureActive()
                    stripper.startPage = pageNumber
                    stripper.endPage = pageNumber
                    val text = stripper.getText(document).trim()
                    totalChars += text.length
                    require(totalChars <= MAX_EXTRACTED_TEXT_CHARS) {
                        "حجم النص المستخرج كبير جدًا في عملية واحدة. قسم الملف إلى أجزاء أصغر للحفاظ على استقرار الهاتف."
                    }
                    pages += text
                }
                return pages
            }
        }
    }

    private data class TableSegment(
        val left: Int,
        val right: Int,
        val text: String
    ) {
        val centerX: Float get() = (left + right) / 2f
    }

    private fun inferLayoutTableRows(layout: OcrProcessor.OcrLayout): List<List<String>> {
        val words = layout.words
            .filter { it.text.isNotBlank() && it.confidence >= 18f }
            .sortedWith(compareBy<OcrProcessor.OcrWord> { it.centerY }.thenBy { it.left })
        if (words.size < 4) return emptyList()

        val medianHeight = median(words.map { it.height.toFloat() }).coerceAtLeast(8f)
        val medianCharWidth = median(words.map { it.width.toFloat() / it.text.length.coerceAtLeast(1) }).coerceAtLeast(3f)
        val rowTolerance = max(8f, medianHeight * .68f)

        val rowGroups = mutableListOf<MutableList<OcrProcessor.OcrWord>>()
        words.forEach { word ->
            val row = rowGroups.lastOrNull()
            val rowCenter = row?.map { it.centerY }?.average()?.toFloat()
            if (row != null && rowCenter != null && abs(word.centerY - rowCenter) <= rowTolerance) {
                row += word
            } else {
                rowGroups += mutableListOf(word)
            }
        }

        val gapThreshold = max(12f, max(medianHeight * .78f, medianCharWidth * 2.35f))
        val segmentedRows = rowGroups.mapNotNull { row ->
            val ordered = row.sortedBy { it.left }
            if (ordered.isEmpty()) return@mapNotNull null
            val segments = mutableListOf<TableSegment>()
            var buffer = mutableListOf(ordered.first())
            fun flush() {
                if (buffer.isEmpty()) return
                val text = joinVisualWords(buffer)
                if (text.isNotBlank()) segments += TableSegment(buffer.minOf { it.left }, buffer.maxOf { it.right }, text)
                buffer = mutableListOf()
            }
            for (word in ordered.drop(1)) {
                val previous = buffer.last()
                val gap = (word.left - previous.right).toFloat()
                if (gap > gapThreshold) {
                    flush()
                    buffer += word
                } else {
                    buffer += word
                }
            }
            flush()
            segments.takeIf { it.isNotEmpty() }
        }
        if (segmentedRows.size < 2) return emptyList()

        val rtlLayout = containsArabic(layout.plainText)
        fun anchorPosition(segment: TableSegment): Float = if (rtlLayout) segment.right.toFloat() else segment.left.toFloat()
        val centerTolerance = max(layout.imageWidth * .028f, medianHeight * 1.55f)
        val anchors = mutableListOf<MutableList<Float>>()
        segmentedRows.flatten().map(::anchorPosition).sorted().forEach { position ->
            val target = anchors.minByOrNull { abs(it.average().toFloat() - position) }
            if (target != null && abs(target.average().toFloat() - position) <= centerTolerance) target += position
            else anchors += mutableListOf(position)
        }
        val rawAnchors = anchors.map { it.average().toFloat() }
        val columnAnchors = if (rtlLayout) rawAnchors.sortedDescending() else rawAnchors.sorted()
        if (columnAnchors.size !in 2..20) return emptyList()

        val rows = segmentedRows.map { segments ->
            val cells = MutableList(columnAnchors.size) { "" }
            segments.forEach { segment ->
                val position = anchorPosition(segment)
                val index = columnAnchors.indices.minByOrNull { abs(columnAnchors[it] - position) } ?: 0
                cells[index] = if (cells[index].isBlank()) segment.text else mergeCellText(cells[index], segment.text)
            }
            cells
        }

        val keepColumns = columnAnchors.indices.filter { col -> rows.any { it.getOrNull(col).orEmpty().isNotBlank() } }
        if (keepColumns.size < 2) return emptyList()
        val cleaned = rows.map { row -> keepColumns.map { col -> row.getOrNull(col).orEmpty() } }
        val usefulRows = cleaned.count { row -> row.count { it.isNotBlank() } >= 2 }
        return if (usefulRows >= max(2, cleaned.size / 3)) cleaned else emptyList()
    }

    private fun joinVisualWords(words: List<OcrProcessor.OcrWord>): String {
        val parts = words.map { it.text.trim() }.filter { it.isNotBlank() }
        if (parts.isEmpty()) return ""
        val arabicParts = parts.count(::containsArabic)
        val ordered = if (arabicParts * 2 >= parts.size) parts.asReversed() else parts
        return ordered.joinToString(" ").replace(Regex("\\s+"), " ").trim()
    }

    private fun mergeCellText(first: String, second: String): String {
        val parts = listOf(first, second).filter { it.isNotBlank() }
        val arabicParts = parts.count(::containsArabic)
        return (if (arabicParts * 2 >= parts.size) parts.asReversed() else parts).joinToString(" ").trim()
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2f else sorted[middle]
    }

    private fun inferTableRows(text: String): List<List<String>> {
        val rawRows = text.lines()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .map { line ->
                val pipeParts = line.split(Regex("\\s*[|¦]\\s*")).filter { it.isNotBlank() }
                val tabParts = line.split(Regex("\\t+")).filter { it.isNotBlank() }
                val spacedParts = line.trim().split(Regex(" {2,}")).filter { it.isNotBlank() }
                when {
                    pipeParts.size > 1 -> pipeParts
                    tabParts.size > 1 -> tabParts
                    spacedParts.size > 1 -> spacedParts
                    else -> listOf(line.trim())
                }.map { it.trim() }
            }

        if (rawRows.isEmpty()) return emptyList()
        val multiColumnRows = rawRows.count { it.size > 1 }
        if (multiColumnRows < max(2, rawRows.size / 4)) return rawRows

        val widths = rawRows.filter { it.size > 1 }.groupingBy { it.size }.eachCount()
        val preferred = widths.maxByOrNull { it.value }?.key ?: rawRows.maxOf { it.size }
        val maxColumns = preferred.coerceIn(1, 20)
        return rawRows.map { row ->
            when {
                row.size == maxColumns -> row
                row.size < maxColumns -> row + List(maxColumns - row.size) { "" }
                else -> row.take(maxColumns - 1) + row.drop(maxColumns - 1).joinToString(" ")
            }
        }
    }

    private suspend fun readZipEntries(
        context: Context,
        uri: Uri,
        wanted: (String) -> Boolean
    ): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        var totalBytes = 0
        context.contentResolver.openInputStream(uri).use { raw ->
            requireNotNull(raw) { "تعذر فتح الملف" }
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && wanted(entry.name)) {
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var entryBytes = 0
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = zip.read(buffer)
                            if (read < 0) break
                            entryBytes += read
                            totalBytes += read
                            require(entryBytes <= MAX_XML_ENTRY_BYTES) {
                                "أحد أجزاء ملف Office كبير جدًا للمعالجة الآمنة على الهاتف"
                            }
                            require(totalBytes <= MAX_TOTAL_XML_BYTES) {
                                "ملف Office يحتوي على بيانات XML كبيرة جدًا في عملية واحدة"
                            }
                            out.write(buffer, 0, read)
                        }
                        result[entry.name] = out.toByteArray()
                    }
                    zip.closeEntry()
                }
            }
        }
        return result
    }

    private fun extractDocxLines(xml: ByteArray): List<String> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(ByteArrayInputStream(xml), "UTF-8")
        }
        val lines = mutableListOf<String>()
        val paragraph = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "t" -> paragraph.append(parser.nextText())
                    "tab" -> paragraph.append('\t')
                    "br" -> {
                        val type = parser.getAttributeValue(null, "type")
                        if (type == "page") {
                            val value = paragraph.toString().trimEnd('\t', ' ', '\n')
                            if (value.isNotBlank()) lines += value
                            paragraph.clear()
                            lines += PAGE_BREAK
                        } else paragraph.append('\n')
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "tc" -> if (paragraph.isNotEmpty() && paragraph.lastOrNull() != '\t') paragraph.append('\t')
                    "p" -> {
                        val value = paragraph.toString().trimEnd('\t', ' ', '\n')
                        if (value.isNotBlank()) lines += value
                        paragraph.clear()
                    }
                }
            }
            event = parser.next()
        }
        return lines
    }

    private fun parseSharedStrings(xml: ByteArray): List<String> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(ByteArrayInputStream(xml), "UTF-8")
        }
        val result = mutableListOf<String>()
        var inSi = false
        val current = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "si") {
                        inSi = true
                        current.clear()
                    } else if (inSi && parser.name == "t") current.append(parser.nextText())
                }
                XmlPullParser.END_TAG -> if (parser.name == "si") {
                    result += current.toString()
                    inSi = false
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun parseWorksheet(xml: ByteArray, sharedStrings: List<String>): List<List<String>> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(ByteArrayInputStream(xml), "UTF-8")
        }
        val rows = linkedMapOf<Int, MutableMap<Int, String>>()
        var currentRow = 1
        var currentCol = 0
        var cellType: String? = null
        var currentValue = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> currentRow = parser.getAttributeValue(null, "r")?.toIntOrNull() ?: (rows.size + 1)
                    "c" -> {
                        val ref = parser.getAttributeValue(null, "r").orEmpty()
                        currentCol = columnIndex(ref.takeWhile { it.isLetter() })
                        cellType = parser.getAttributeValue(null, "t")
                        currentValue = ""
                    }
                    "v", "t" -> currentValue = parser.nextText()
                }
                XmlPullParser.END_TAG -> if (parser.name == "c") {
                    val value = when (cellType) {
                        "s" -> currentValue.toIntOrNull()?.let { sharedStrings.getOrNull(it) }.orEmpty()
                        else -> currentValue
                    }
                    rows.getOrPut(currentRow) { linkedMapOf() }[currentCol] = value
                }
            }
            event = parser.next()
        }
        return rows.toSortedMap().values.map { cells ->
            val maxCol = cells.keys.maxOrNull() ?: 0
            (0..maxCol).map { cells[it].orEmpty() }
        }
    }

    private fun sheetNumber(name: String): Int =
        Regex("sheet(\\d+)\\.xml").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Int.MAX_VALUE

    private fun columnIndex(letters: String): Int {
        if (letters.isBlank()) return 0
        var value = 0
        letters.uppercase().forEach { ch -> value = value * 26 + (ch - 'A' + 1) }
        return (value - 1).coerceAtLeast(0)
    }

    private suspend fun renderLinesToPdf(
        context: Context,
        sourceLines: List<String>,
        fileName: String,
        fontSize: Float = 16f
    ): File {
        val out = File(FileUtils.outputDir(context, "pdf"), fileName)
        val document = PdfDocument()
        val width = 595
        val height = 842
        val margin = 40f
        val usableWidth = width - margin * 2
        val lineHeight = fontSize * 1.55f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = fontSize
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun newPage() {
            page?.let { document.finishPage(it) }
            pageNumber += 1
            page = document.startPage(PdfDocument.PageInfo.Builder(width, height, pageNumber).create())
            y = margin
        }

        newPage()
        sourceLines.forEach { source ->
            currentCoroutineContext().ensureActive()
            if (source == PAGE_BREAK) {
                if (y > margin + 1f) newPage()
                return@forEach
            }
            wrapLine(source, paint, usableWidth).forEach { line ->
                if (y + lineHeight > height - margin) newPage()
                val rtl = containsArabic(line)
                paint.textAlign = if (rtl) Paint.Align.RIGHT else Paint.Align.LEFT
                val x = if (rtl) width - margin else margin
                page!!.canvas.drawText(line.ifBlank { " " }, x, y + fontSize, paint)
                y += lineHeight
            }
        }
        page?.let { document.finishPage(it) }
        FileOutputStream(out).use { document.writeTo(it) }
        document.close()
        return out
    }

    private fun wrapLine(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isBlank()) return listOf("")
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val result = mutableListOf<String>()
        var current = ""
        words.forEach { word ->
            val candidate = if (current.isBlank()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current = candidate
            } else {
                if (current.isNotBlank()) result += current
                if (paint.measureText(word) <= maxWidth) current = word
                else {
                    var piece = ""
                    word.forEach { ch ->
                        val next = piece + ch
                        if (paint.measureText(next) > maxWidth && piece.isNotEmpty()) {
                            result += piece
                            piece = ch.toString()
                        } else piece = next
                    }
                    current = piece
                }
            }
        }
        if (current.isNotBlank()) result += current
        return result.ifEmpty { listOf("") }
    }

    private suspend fun writeDocx(file: File, lines: List<String>) {
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putText(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>"""
            )
            zip.putText(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
            )
            zip.putText(
                "word/_rels/document.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""
            )
            zip.putText("word/styles.xml", wordStyles())

            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.writeUtf8("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
            lines.forEach { line ->
                currentCoroutineContext().ensureActive()
                if (line == PAGE_BREAK) {
                    zip.writeUtf8("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
                } else {
                    zip.writeUtf8(wordParagraphXml(line))
                }
            }
            zip.writeUtf8("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1080\" w:right=\"1080\" w:bottom=\"1080\" w:left=\"1080\"/></w:sectPr></w:body></w:document>")
            zip.closeEntry()
        }
    }

    private fun wordParagraphXml(line: String): String {
        val rtl = containsArabic(line)
        val align = if (rtl) "right" else "left"
        val paragraphProps = "<w:pPr>${if (rtl) "<w:bidi/>" else ""}<w:jc w:val=\"$align\"/><w:spacing w:after=\"120\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr>"
        val runs = buildString {
            val chunks = line.split('\t')
            chunks.forEachIndexed { index, chunk ->
                if (index > 0) append("<w:r><w:tab/></w:r>")
                val pieces = chunk.split('\n')
                pieces.forEachIndexed { pieceIndex, piece ->
                    if (pieceIndex > 0) append("<w:r><w:br/></w:r>")
                    if (piece.isNotEmpty()) {
                        append("<w:r><w:rPr><w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\" w:cs=\"Arial\"/>")
                        if (rtl || containsArabic(piece)) append("<w:rtl/>")
                        append("<w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/></w:rPr><w:t xml:space=\"preserve\">")
                        append(xmlEscape(piece)).append("</w:t></w:r>")
                    }
                }
            }
        }
        return "<w:p>$paragraphProps$runs</w:p>"
    }

    private fun wordStyles(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Arial" w:hAnsi="Arial" w:cs="Arial"/><w:sz w:val="22"/><w:szCs w:val="22"/><w:lang w:val="en-US" w:bidi="ar-SA"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style></w:styles>"""

    private suspend fun writeXlsxSheets(file: File, sheets: List<Pair<String, List<List<String>>>>) {
        require(sheets.isNotEmpty()) { "لا توجد بيانات لإنشاء Excel" }
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            val contentOverrides = sheets.indices.joinToString("") { index ->
                "<Override PartName=\"/xl/worksheets/sheet${index + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
            }
            zip.putText(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>$contentOverrides</Types>"""
            )
            zip.putText(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
            )
            val workbookSheets = sheets.mapIndexed { index, pair ->
                val safeName = safeSheetName(pair.first, index)
                "<sheet name=\"${xmlEscape(safeName)}\" sheetId=\"${index + 1}\" r:id=\"rId${index + 1}\"/>"
            }.joinToString("")
            zip.putText(
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>$workbookSheets</sheets></workbook>"""
            )
            val workbookRels = buildString {
                sheets.indices.forEach { index ->
                    append("<Relationship Id=\"rId${index + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${index + 1}.xml\"/>")
                }
                append("<Relationship Id=\"rId${sheets.size + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
            }
            zip.putText(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$workbookRels</Relationships>"""
            )
            zip.putText("xl/styles.xml", xlsxStyles())

            sheets.forEachIndexed { sheetIndex, (_, rows) ->
                currentCoroutineContext().ensureActive()
                require(rows.size <= 50_000) { "الجدول يحتوي على أكثر من 50,000 صف في ورقة واحدة؛ قسم البيانات للحفاظ على ثبات الهاتف" }
                val normalized = rows.ifEmpty { listOf(listOf("")) }.map { it.take(50) }
                val rtl = normalized.any { row -> row.any(::containsArabic) }
                val maxCols = normalized.maxOfOrNull { it.size }?.coerceIn(1, 50) ?: 1
                val widths = (0 until maxCols).map { col ->
                    val longest = normalized.maxOfOrNull { row -> row.getOrNull(col).orEmpty().length } ?: 0
                    (longest + 3).coerceIn(10, 45)
                }
                val colsXml = widths.mapIndexed { col, width ->
                    "<col min=\"${col + 1}\" max=\"${col + 1}\" width=\"$width\" customWidth=\"1\"/>"
                }.joinToString("")
                val sheetView = if (rtl) "<sheetView workbookViewId=\"0\" rightToLeft=\"1\">" else "<sheetView workbookViewId=\"0\">"
                val freeze = if (normalized.size > 1) "<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>" else ""

                zip.putNextEntry(ZipEntry("xl/worksheets/sheet${sheetIndex + 1}.xml"))
                zip.writeUtf8("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews>$sheetView$freeze</sheetView></sheetViews><cols>$colsXml</cols><sheetData>""")
                normalized.forEachIndexed { rowIndex, row ->
                    currentCoroutineContext().ensureActive()
                    zip.writeUtf8("<row r=\"${rowIndex + 1}\">")
                    row.forEachIndexed { colIndex, value ->
                        val ref = columnName(colIndex) + (rowIndex + 1)
                        val style = if (rowIndex == 0 && normalized.size > 1) 1 else 0
                        val numeric = numericCellValue(value)
                        if (numeric != null) {
                            zip.writeUtf8("<c r=\"$ref\" s=\"$style\"><v>$numeric</v></c>")
                        } else {
                            zip.writeUtf8("<c r=\"$ref\" t=\"inlineStr\" s=\"$style\"><is><t xml:space=\"preserve\">${xmlEscape(value)}</t></is></c>")
                        }
                    }
                    zip.writeUtf8("</row>")
                }
                zip.writeUtf8("</sheetData></worksheet>")
                zip.closeEntry()
            }
        }
    }

    private fun numericCellValue(value: String): String? {
        if (value.isBlank()) return null
        val normalized = buildString {
            value.trim().forEach { ch ->
                when (ch) {
                    in '٠'..'٩' -> append(('0'.code + (ch.code - '٠'.code)).toChar())
                    in '۰'..'۹' -> append(('0'.code + (ch.code - '۰'.code)).toChar())
                    '٫' -> append('.')
                    '٬' -> Unit
                    else -> append(ch)
                }
            }
        }.replace(" ", "")
        // Preserve ID numbers, postal codes, and telephone-like strings as text.
        if (normalized.matches(Regex("[+\\-]?0[0-9]+")) || normalized.filter(Char::isDigit).length >= 11) return null
        return normalized.takeIf { it.matches(Regex("[-+]?\\d+(?:\\.\\d+)?")) }
    }

    private fun xlsxStyles(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFDDEBF7"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style="thin"><color auto="1"/></left><right style="thin"><color auto="1"/></right><top style="thin"><color auto="1"/></top><bottom style="thin"><color auto="1"/></bottom><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf><xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf></cellXfs></styleSheet>"""

    private fun safeSheetName(name: String, index: Int): String {
        val cleaned = name.replace(Regex("""[\/:*?\[\]]"""), " ").trim().ifBlank { "Sheet ${index + 1}" }
        return cleaned.take(31)
    }

    private fun columnName(index: Int): String {
        var value = index + 1
        val result = StringBuilder()
        while (value > 0) {
            val rem = (value - 1) % 26
            result.append(('A'.code + rem).toChar())
            value = (value - 1) / 26
        }
        return result.reverse().toString()
    }

    private fun containsArabic(value: String): Boolean = value.any {
        it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' || it in '\u08A0'..'\u08FF'
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun ZipOutputStream.writeUtf8(value: String) {
        write(value.toByteArray(Charsets.UTF_8))
    }

    private fun ZipOutputStream.putText(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        write(value.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
