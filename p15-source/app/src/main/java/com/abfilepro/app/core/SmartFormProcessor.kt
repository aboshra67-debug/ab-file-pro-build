package com.abfilepro.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

object SmartFormProcessor {
    data class Field(val id: Int, val left: Float, val top: Float, val right: Float, val bottom: Float, val hint: String)
    data class Template(val id: String, val sourceImage: File, val metaFile: File, val width: Int, val height: Int, val fields: List<Field>)
    data class FilledResult(val imageFile: File, val pdfFile: File)

    suspend fun analyze(context: Context, uri: Uri): Template = withContext(Dispatchers.IO) {
        RuntimeSafety.ensureStorage(context)
        currentCoroutineContext().ensureActive()
        val bitmap = OcrProcessor.loadPreview(context, uri)
        try {
            val fields = detectLineFields(bitmap).toMutableList()
            if (fields.size < 2) {
                val inferred = try {
                    inferFromLabels(context, uri, bitmap.width, bitmap.height)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    emptyList()
                }
                inferred.forEach { candidate -> if (fields.none { overlaps(it, candidate) }) fields += candidate }
            }
            currentCoroutineContext().ensureActive()
            val finalFields = fields.sortedBy { it.top }.take(24).mapIndexed { index, field -> field.copy(id = index + 1) }
            require(finalFields.isNotEmpty()) { "لم أجد خانات واضحة تلقائيًا. جرّب صورة أوضح أو استمارة بها خطوط كتابة" }

            val id = System.currentTimeMillis().toString()
            val dir = File(context.cacheDir, "smart_form_drafts/$id").apply { mkdirs() }
            val source = File(dir, "form_source.jpg")
            FileOutputStream(source).use { out -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) }
            val meta = File(dir, "template.abform")
            meta.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.appendLine("$id\t${bitmap.width}\t${bitmap.height}\t${source.absolutePath}")
                finalFields.forEach { f -> writer.appendLine("${f.id}\t${f.left}\t${f.top}\t${f.right}\t${f.bottom}\t${f.hint.replace('\t',' ')}") }
            }
            Template(id, source, meta, bitmap.width, bitmap.height, finalFields)
        } finally { bitmap.recycle() }
    }

    suspend fun persistTemplate(context: Context, template: Template): Template = withContext(Dispatchers.IO) {
        RuntimeSafety.ensureStorage(context, template.sourceImage.length().coerceAtLeast(0L))
        currentCoroutineContext().ensureActive()
        require(template.sourceImage.isFile) { "smart_form_template_source_missing" }
        val dir = FileUtils.outputDir(context, "AB Smart Form/templates/${template.id}")
        val source = File(dir, "form_source.jpg")
        try {
            template.sourceImage.inputStream().use { input ->
                FileOutputStream(source).use { output -> RuntimeSafety.copyCancellable(input, output) }
            }
            currentCoroutineContext().ensureActive()
            val meta = File(dir, "template.abform")
            meta.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.appendLine("${template.id}\t${template.width}\t${template.height}\t${source.absolutePath}")
                template.fields.forEach { f ->
                    currentCoroutineContext().ensureActive()
                    writer.appendLine("${f.id}\t${f.left}\t${f.top}\t${f.right}\t${f.bottom}\t${f.hint.replace('\t',' ')}")
                }
            }
            Template(template.id, source, meta, template.width, template.height, template.fields)
        } catch (error: Throwable) {
            dir.deleteRecursively()
            throw error
        }
    }

    fun listTemplates(context: Context, limit: Int = 10): List<Template> {
        val root = File(FileUtils.outputDir(context, "AB Smart Form"), "templates")
        return root.listFiles().orEmpty().filter { it.isDirectory }.sortedByDescending { it.lastModified() }
            .mapNotNull { dir -> runCatching { loadTemplate(File(dir, "template.abform")) }.getOrNull() }
            .take(limit)
    }

    fun loadTemplate(metaFile: File): Template {
        val lines = metaFile.readLines(Charsets.UTF_8)
        require(lines.isNotEmpty()) { "قالب غير صالح" }
        val header = lines.first().split('\t')
        require(header.size >= 4) { "قالب غير صالح" }
        val fields = lines.drop(1).mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 6) null else Field(p[0].toInt(), p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p.drop(5).joinToString(" "))
        }
        return Template(header[0], File(header[3]), metaFile, header[1].toInt(), header[2].toInt(), fields)
    }

    suspend fun fill(
        context: Context,
        template: Template,
        values: Map<Int, String>,
        signatureText: String = "",
        dateText: String = ""
    ): FilledResult = withContext(Dispatchers.IO) {
        RuntimeSafety.ensureStorage(context)
        currentCoroutineContext().ensureActive()
        require(template.sourceImage.exists()) { "صورة القالب غير موجودة" }
        val original = FileUtils.loadImagePreview(template.sourceImage, max(template.width, template.height)) ?: error("تعذر فتح القالب")
        val bitmap = original.copy(Bitmap.Config.ARGB_8888, true)
        original.recycle()
        try {
            val sx = bitmap.width / template.width.toFloat().coerceAtLeast(1f)
            val sy = bitmap.height / template.height.toFloat().coerceAtLeast(1f)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(20, 45, 110); typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
            var signatureFieldFound = false
            var dateFieldFound = false
            template.fields.forEach { field ->
                currentCoroutineContext().ensureActive()
                val isSignature = field.hint.contains("signature", true) || field.hint.contains("توقيع") || field.hint.contains("امضاء") || field.hint.contains("إمضاء")
                val isDate = field.hint.contains("date", true) || field.hint.contains("تاريخ")
                if (isSignature) signatureFieldFound = true
                if (isDate) dateFieldFound = true
                val value = values[field.id].orEmpty().trim().ifBlank {
                    when {
                        isSignature -> signatureText.trim()
                        isDate -> dateText.trim()
                        else -> ""
                    }
                }
                if (value.isBlank()) return@forEach
                val rect = RectF(field.left * sx, field.top * sy, field.right * sx, field.bottom * sy)
                paint.typeface = if (isSignature) Typeface.create("cursive", Typeface.ITALIC) else Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                paint.textSize = (rect.height() * if (isSignature) 0.68f else 0.58f).coerceIn(18f, 58f)
                val maxWidth = rect.width().coerceAtLeast(20f)
                var textSize = paint.textSize
                while (paint.measureText(value) > maxWidth && textSize > 14f) { textSize -= 2f; paint.textSize = textSize }
                canvas.drawText(value, rect.left + 4f, rect.bottom - max(3f, rect.height() * 0.12f), paint)
            }
            if (signatureText.isNotBlank() && !signatureFieldFound) {
                paint.typeface = Typeface.create("cursive", Typeface.ITALIC)
                paint.textSize = (bitmap.width * .038f).coerceIn(24f, 54f)
                canvas.drawText(signatureText.take(60), bitmap.width * .58f, bitmap.height * .95f, paint)
            }
            if (dateText.isNotBlank() && !dateFieldFound) {
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                paint.textSize = (bitmap.width * .022f).coerceIn(18f, 34f)
                canvas.drawText(dateText.take(40), bitmap.width * .06f, bitmap.height * .95f, paint)
            }
            currentCoroutineContext().ensureActive()
            val outDir = FileUtils.outputDir(context, "AB Smart Form/filled")
            val stamp = System.currentTimeMillis()
            val image = File(outDir, "Filled_Form_$stamp.jpg")
            FileOutputStream(image).use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            val pdf = File(outDir, "Filled_Form_$stamp.pdf")
            savePdf(bitmap, pdf)
            FilledResult(image, pdf)
        } finally { bitmap.recycle() }
    }


    fun isGenericFieldHint(hint: String): Boolean =
        hint.isBlank() || hint.startsWith("__field__") || hint.matches(Regex("حقل\\s+\\d+"))

    fun isDateHint(hint: String): Boolean =
        hint.contains("date", true) || hint.contains("تاريخ")

    private fun detectLineFields(bitmap: Bitmap): List<Field> {
        val w = bitmap.width; val h = bitmap.height
        val minRun = max(80, (w * 0.16f).toInt())
        val yStep = max(1, h / 900)
        val xStep = max(1, w / 900)
        val candidates = mutableListOf<RectF>()
        var y = max(3, h / 20)
        while (y < h - max(6, h / 30)) {
            var runStart = -1; var x = 0
            while (x < w) {
                val c = bitmap.getPixel(x, y)
                val lum = (((c shr 16) and 255) * 30 + ((c shr 8) and 255) * 59 + (c and 255) * 11) / 100
                val dark = lum < 105
                if (dark && runStart < 0) runStart = x
                if ((!dark || x >= w - xStep) && runStart >= 0) {
                    val end = if (dark) x else x - xStep
                    if (end - runStart >= minRun) {
                        val top = (y - max(34, h / 28)).coerceAtLeast(0)
                        candidates += RectF(runStart.toFloat(), top.toFloat(), end.toFloat(), (y + 3).coerceAtMost(h).toFloat())
                    }
                    runStart = -1
                }
                x += xStep
            }
            y += yStep
        }
        val merged = mutableListOf<RectF>()
        candidates.sortedBy { it.top }.forEach { r ->
            val same = merged.indexOfFirst { m -> kotlin.math.abs(m.bottom - r.bottom) < max(8f, h * .012f) && kotlin.math.abs(m.left - r.left) < w * .08f }
            if (same < 0) merged += r else {
                val m = merged[same]; merged[same] = RectF(min(m.left,r.left), min(m.top,r.top), max(m.right,r.right), max(m.bottom,r.bottom))
            }
        }
        return merged.filter { it.width() >= minRun && it.height() in 20f..(h * .15f) }
            .take(24).mapIndexed { i, r -> Field(i+1, r.left, r.top, r.right, r.bottom, "__field__${i+1}") }
    }

    private suspend fun inferFromLabels(context: Context, uri: Uri, width: Int, height: Int): List<Field> {
        val layout = OcrProcessor.recognizeLayout(context, uri, OcrProcessor.Language.ARABIC_ENGLISH)
        val sx = width / layout.imageWidth.toFloat().coerceAtLeast(1f)
        val sy = height / layout.imageHeight.toFloat().coerceAtLeast(1f)
        val result = mutableListOf<Field>()
        layout.words.filter {
            it.text.endsWith(":") || it.text.endsWith("：") ||
                it.text.contains("الاسم") || it.text.contains("Name", true) || it.text.contains("Nom", true) ||
                it.text.contains("Date", true) || it.text.contains("التاريخ") ||
                it.text.contains("Signature", true) || it.text.contains("التوقيع") || it.text.contains("إمضاء") || it.text.contains("امضاء")
        }
            .take(12).forEachIndexed { i, word ->
                val center = word.centerX * sx
                val top = (word.top * sy - 6).coerceAtLeast(0f)
                val bottom = (word.bottom * sy + max(22f, (word.height * sy))).coerceAtMost(height.toFloat())
                val rect = if (center > width / 2f) RectF(width * .06f, top, (word.left * sx - 8).coerceAtLeast(width*.28f), bottom)
                           else RectF((word.right * sx + 8).coerceAtMost(width*.72f), top, width*.94f, bottom)
                if (rect.width() > width*.16f) result += Field(i+1, rect.left, rect.top, rect.right, rect.bottom, word.text.trim(':','：'))
            }
        return result
    }

    private fun overlaps(a: Field, b: Field): Boolean = RectF.intersects(RectF(a.left,a.top,a.right,a.bottom), RectF(b.left,b.top,b.right,b.bottom))

    private fun savePdf(bitmap: Bitmap, output: File) {
        val doc = PdfDocument()
        try {
            val info = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create()
            val page = doc.startPage(info); page.canvas.drawBitmap(bitmap, 0f, 0f, null); doc.finishPage(page)
            FileOutputStream(output).use { doc.writeTo(it) }
        } finally { doc.close() }
    }
}
