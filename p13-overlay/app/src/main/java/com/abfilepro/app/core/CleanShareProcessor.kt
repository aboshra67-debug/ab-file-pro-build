package com.abfilepro.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

object CleanShareProcessor {
    data class Options(
        val redactSensitive: Boolean = true,
        val removeMetadata: Boolean = true,
        val removeBlankPages: Boolean = true,
        val compress: Boolean = true,
        val cleanFileName: Boolean = true
    )

    data class Result(
        val file: File,
        val sensitiveItems: Int,
        val qrItems: Int,
        val totalRedactions: Int,
        val blankPagesRemoved: Int,
        val metadataStripped: Boolean,
        val compressed: Boolean,
        val isPdf: Boolean
    )

    private data class RedactionResult(val bitmap: Bitmap, val sensitive: Int, val qr: Int, val total: Int)

    private val email = Regex("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", RegexOption.IGNORE_CASE)
    private val iban = Regex("^[A-Z]{2}\\d{2}[A-Z0-9]{10,30}$", RegexOption.IGNORE_CASE)

    suspend fun clean(context: Context, uri: Uri, options: Options = Options()): Result = CleanShareOutput.create({
        RuntimeSafety.ensureStorage(context)
        FileUtils.outputDir(context, "AB Clean Share")
    }, context.cacheDir) { draft ->
        val type = context.contentResolver.getType(uri).orEmpty().lowercase()
        when {
            type == "application/pdf" || type.endsWith("/pdf") -> cleanPdf(context, uri, options, draft)
            type.startsWith("image/") || type.isBlank() -> cleanImage(context, uri, options, draft)
            else -> error("AB Clean Share يدعم الصور وPDF في هذه النسخة")
        }
    }

    private suspend fun cleanImage(context: Context, uri: Uri, options: Options, draft: CleanShareOutput.Draft): Result {
        val source = loadBitmap(context, uri, 2600)
        var redaction: RedactionResult? = null
        try {
            val working = if (options.redactSensitive) {
                redactBitmap(context, source, draft).also { redaction = it }.bitmap
            } else source.copy(Bitmap.Config.ARGB_8888, true)
            try {
                val ext = if (options.compress) "jpg" else "png"
                val base = if (options.cleanFileName) "Shared_${System.currentTimeMillis()}" else safeBaseName(displayName(context, uri)).ifBlank { "Shared_${System.currentTimeMillis()}" }
                val staged = draft.file("image.$ext")
                FileOutputStream(staged).use { stream ->
                    val ok = if (options.compress) working.compress(Bitmap.CompressFormat.JPEG, 82, stream)
                    else working.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    check(ok) { "تعذر حفظ نسخة المشاركة" }
                    stream.fd.sync()
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(staged.path, bounds)
                require(bounds.outWidth == working.width && bounds.outHeight == working.height) { "تعذر التحقق من صورة المشاركة" }
                val out = draft.publish(staged, base, ext)
                val r = redaction
                return Result(
                    file = out,
                    sensitiveItems = r?.sensitive ?: 0,
                    qrItems = r?.qr ?: 0,
                    totalRedactions = r?.total ?: 0,
                    blankPagesRemoved = 0,
                    metadataStripped = options.removeMetadata || options.redactSensitive || options.compress,
                    compressed = options.compress,
                    isPdf = false
                )
            } finally {
                if (!working.isRecycled) working.recycle()
            }
        } finally {
            if (!source.isRecycled) source.recycle()
        }
    }

    private suspend fun cleanPdf(context: Context, uri: Uri, options: Options, draft: CleanShareOutput.Draft): Result {
        val tempSource = draft.file("source.pdf")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "clean_share_pdf_open_failed" }
            FileOutputStream(tempSource).use { output ->
                RuntimeSafety.copyCancellable(input, output)
                output.fd.sync()
            }
        }
        val staged = draft.file("cleaned.pdf")
        val base = if (options.cleanFileName) "Shared_${System.currentTimeMillis()}" else safeBaseName(displayName(context, uri)).ifBlank { "Shared" }
        var sensitive = 0
        var qr = 0
        var redactions = 0
        var blankRemoved = 0
        var outputPages = 0

        ParcelFileDescriptor.open(tempSource, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val pdf = PdfDocument()
                try {
                    require(renderer.pageCount > 0) { "PDF لا يحتوي على صفحات" }
                    require(renderer.pageCount <= 60) { "AB Clean Share يدعم حتى 60 صفحة في العملية الواحدة" }
                    for (index in 0 until renderer.pageCount) {
                        currentCoroutineContext().ensureActive()
                        val bitmap = renderer.openPage(index).use { page ->
                            val scale = minOf(2f, 1800f / max(page.width, page.height).coerceAtLeast(1))
                            val width = max(1, (page.width * scale).toInt())
                            val height = max(1, (page.height * scale).toInt())
                            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                                try {
                                    bitmap.eraseColor(Color.WHITE)
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                } catch (error: Throwable) {
                                    bitmap.recycle()
                                    throw error
                                }
                            }
                        }
                        try {
                            if (options.removeBlankPages && isBlankPage(bitmap)) {
                                blankRemoved++
                                continue
                            }
                            val processed = if (options.redactSensitive) {
                                val r = redactBitmap(context, bitmap, draft)
                                sensitive += r.sensitive; qr += r.qr; redactions += r.total
                                r.bitmap
                            } else bitmap
                            try {
                                val info = PdfDocument.PageInfo.Builder(processed.width, processed.height, outputPages + 1).create()
                                val outputPage = pdf.startPage(info)
                                try {
                                    outputPage.canvas.drawColor(Color.WHITE)
                                    outputPage.canvas.drawBitmap(processed, 0f, 0f, null)
                                } finally { pdf.finishPage(outputPage) }
                                outputPages++
                            } finally {
                                if (processed !== bitmap && !processed.isRecycled) processed.recycle()
                            }
                        } finally { if (!bitmap.isRecycled) bitmap.recycle() }
                    }
                    require(outputPages > 0) { "تم اعتبار جميع الصفحات فارغة؛ ألغِ خيار إزالة الصفحات الفارغة وحاول مجددًا" }
                    currentCoroutineContext().ensureActive()
                    FileOutputStream(staged).use { output ->
                        pdf.writeTo(output)
                        output.fd.sync()
                    }
                } finally { pdf.close() }
            }
        }
        CleanShareOutput.verifyPdf(staged, outputPages)
        val selected = if (options.compress) {
            val candidate = draft.file("compressed.pdf")
            draft.preferSmallerPdf(staged, candidate, outputPages) {
                PdfTools.compressPdf(context, FileProvider.getUriForFile(context, "${context.packageName}.files", staged), .55f, outputFile = candidate)
            }
        } else CleanShareOutput.Selection(staged, false)
        val finalFile = draft.publish(selected.file, base, "pdf")
        return Result(
            file = finalFile,
            sensitiveItems = sensitive,
            qrItems = qr,
            totalRedactions = redactions,
            blankPagesRemoved = blankRemoved,
            metadataStripped = true,
            compressed = selected.compressed,
            isPdf = true
        )
    }

    private suspend fun redactBitmap(context: Context, source: Bitmap, draft: CleanShareOutput.Draft): RedactionResult {
        val temp = draft.file("ocr_${java.util.UUID.randomUUID()}.jpg")
        FileOutputStream(temp).use { check(source.compress(Bitmap.CompressFormat.JPEG, 94, it)) { "تعذر تجهيز صورة الحجب" } }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", temp)
        val language = when {
            OcrProcessor.isModelReady(context, OcrProcessor.Language.ALL) -> OcrProcessor.Language.ALL
            OcrProcessor.isModelReady(context, OcrProcessor.Language.ARABIC_ENGLISH) -> OcrProcessor.Language.ARABIC_ENGLISH
            else -> OcrProcessor.Language.ALL
        }
        val layout = try {
            OcrProcessor.recognizeLayout(context, uri, language)
        } finally {
            temp.delete()
        }
        val scaleX = source.width.toFloat() / layout.imageWidth.coerceAtLeast(1)
        val scaleY = source.height.toFloat() / layout.imageHeight.coerceAtLeast(1)
        val sensitiveRects = layout.words.mapNotNull { word ->
            if (!isSensitive(word.text)) null else RectF(
                word.left * scaleX, word.top * scaleY, word.right * scaleX, word.bottom * scaleY
            ).expand(source.width, source.height)
        }

        val scanner = BarcodeScanning.getClient()
        val barcodes = try {
            scanner.process(InputImage.fromBitmap(source, 0)).await()
        } finally {
            scanner.close()
        }
        val qrRects = barcodes.mapNotNull { code -> code.boundingBox?.let { RectF(it).expand(source.width, source.height, 10f) } }
        val all = mergeRects(sensitiveRects + qrRects)
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        all.forEach { canvas.drawRoundRect(it, 6f, 6f, paint) }
        return RedactionResult(output, sensitiveRects.size, qrRects.size, all.size)
    }

    private fun isBlankPage(bitmap: Bitmap): Boolean {
        val step = max(1, max(bitmap.width, bitmap.height) / 220)
        var samples = 0
        var nonWhite = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val c = bitmap.getPixel(x, y)
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                if (r < 238 || g < 238 || b < 238) nonWhite++
                samples++
                x += step
            }
            y += step
        }
        return samples > 0 && nonWhite.toDouble() / samples.toDouble() < 0.0018
    }

    private fun isSensitive(raw: String): Boolean {
        val token = raw.trim().trim(',', '.', ':', ';', '(', ')', '[', ']', '{', '}')
        if (token.isBlank()) return false
        if (email.matches(token)) return true
        val compact = token.replace(Regex("[^A-Za-z0-9+]"), "")
        if (iban.matches(compact)) return true
        val digits = token.filter(Char::isDigit)
        if (digits.length in 12..19) return true
        if (digits.length in 9..15 && (token.startsWith("+") || token.startsWith("0"))) return true
        return false
    }

    private fun RectF.expand(width: Int, height: Int, padding: Float = 7f): RectF = RectF(
        (left - padding).coerceAtLeast(0f),
        (top - padding).coerceAtLeast(0f),
        (right + padding).coerceAtMost(width.toFloat()),
        (bottom + padding).coerceAtMost(height.toFloat())
    )

    private fun mergeRects(input: List<RectF>): List<RectF> {
        val result = mutableListOf<RectF>()
        input.sortedBy { it.top }.forEach { rect ->
            val overlapping = result.indexOfFirst { existing ->
                RectF.intersects(existing, rect) || (kotlin.math.abs(existing.top - rect.top) < 14f && rect.left <= existing.right + 14f)
            }
            if (overlapping >= 0) {
                val old = result[overlapping]
                result[overlapping] = RectF(
                    minOf(old.left, rect.left), minOf(old.top, rect.top),
                    maxOf(old.right, rect.right), maxOf(old.bottom, rect.bottom)
                )
            } else result += RectF(rect)
        }
        return result
    }

    private fun loadBitmap(context: Context, uri: Uri, maxDimension: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "image_unavailable" }
            BitmapFactory.decodeStream(input, null, bounds)
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "image_decode_failed" }
        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > maxDimension * 2) sample *= 2
        val original = context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "image_unavailable" }
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply {
                inSampleSize = sample.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } ?: error("image_decode_failed")
        val maxSide = max(original.width, original.height)
        if (maxSide <= maxDimension) return original
        val scale = maxDimension.toFloat() / maxSide
        val resized = Bitmap.createScaledBitmap(original, max(1, (original.width * scale).toInt()), max(1, (original.height * scale).toInt()), true)
        original.recycle()
        return resized
    }

    private fun displayName(context: Context, uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        } ?: "Shared"

    private fun safeBaseName(name: String): String = name.substringBeforeLast('.', name)
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim().take(60)
}
