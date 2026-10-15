package com.abfilepro.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ScanFilter {
    ORIGINAL, DOCUMENT, COLOR, GRAYSCALE, BW, PHOTO, SHADOW_REMOVE, FADED_TEXT
}

enum class ScanPdfPageSize { AUTO, A4, LETTER }


enum class ScanCaptureMode(
    val maxPages: Int,
    val defaultFilter: ScanFilter,
    val documentDetection: Boolean = true,
    val autoCaptureDefault: Boolean = true,
    val splitBookCapture: Boolean = false
) {
    DOCUMENT(1, ScanFilter.DOCUMENT),
    BATCH(10, ScanFilter.DOCUMENT),
    BOOK(50, ScanFilter.DOCUMENT, splitBookCapture = true),
    ID_CARD(2, ScanFilter.DOCUMENT),
    DUPLEX(2, ScanFilter.DOCUMENT),
    TRANSLATION(1, ScanFilter.DOCUMENT, autoCaptureDefault = false),
    PASSPORT(1, ScanFilter.DOCUMENT),
    RECEIPT(1, ScanFilter.DOCUMENT),
    WHITEBOARD(20, ScanFilter.COLOR),
    PHOTO(10, ScanFilter.ORIGINAL, documentDetection = false, autoCaptureDefault = false)
}

data class ScanPoint(val x: Float, val y: Float)

data class ScanQuad(
    val topLeft: ScanPoint,
    val topRight: ScanPoint,
    val bottomRight: ScanPoint,
    val bottomLeft: ScanPoint
) {
    companion object {
        fun insetDefault(inset: Float = .035f) = ScanQuad(
            ScanPoint(inset, inset),
            ScanPoint(1f - inset, inset),
            ScanPoint(1f - inset, 1f - inset),
            ScanPoint(inset, 1f - inset)
        )
    }

    fun clamped(): ScanQuad = ScanQuad(
        topLeft.clamped(), topRight.clamped(), bottomRight.clamped(), bottomLeft.clamped()
    )
}

private fun ScanPoint.clamped() = ScanPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))

data class ScanDraftPage(
    val id: Long,
    val file: File,
    val rotation: Int = 0,
    val filter: ScanFilter = ScanFilter.ORIGINAL,
    val brightness: Int = 0,
    val contrast: Float = 1f,
    val sharpness: Float = 0f,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 0f,
    val cropBottom: Float = 0f,
    val perspective: ScanQuad? = null,
    val annotations: List<ScanTextAnnotation> = emptyList()
)

data class ScanTextAnnotation(
    val id: Long = System.nanoTime(),
    val text: String,
    val x: Float = .50f,
    val y: Float = .50f,
    val sizeScale: Float = .055f,
    val colorArgb: Long = 0xFF111111,
    val backgroundArgb: Long? = null
)

object ScanProcessor {
    suspend fun cacheScannerPages(context: Context, uris: List<Uri>): List<ScanDraftPage> {
        require(uris.isNotEmpty()) { "no_scan_pages" }
        RuntimeSafety.ensureSafePageBatch(uris.size, maxPages = 50)
        RuntimeSafety.ensureStorage(context)
        val dir = File(context.cacheDir, "scan_review/${System.currentTimeMillis()}").apply { mkdirs() }
        val pages = mutableListOf<ScanDraftPage>()
        val failedImports = mutableListOf<String>()
        try {
            uris.forEachIndexed { index, uri ->
                currentCoroutineContext().ensureActive()
                val file = File(dir, "page_${index + 1}.jpg")
                try {
                    copyScannerUriToFile(context, uri, file)
                    currentCoroutineContext().ensureActive()
                    require(file.length() > 0L) { "scan_page_empty" }
                    // Imports are cached on the IO dispatcher. A nonempty file
                    // must actually decode before it can replace an approved page.
                    decodeScaled(file, 320).recycle()
                    normalizeCapturedOrientation(file)
                    pages += ScanDraftPage(id = System.nanoTime() + index, file = file)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    runCatching { file.delete() }
                    failedImports += "${index + 1}:${error.message ?: error::class.java.simpleName}"
                }
            }
            check(pages.isNotEmpty()) {
                "scan_page_import_failed:${failedImports.joinToString("|").ifBlank { "unknown" }}"
            }
            return pages
        } catch (error: Throwable) {
            dir.deleteRecursively()
            throw error
        }
    }

    private suspend fun copyScannerUriToFile(context: Context, uri: Uri, target: File) {
        var firstFailure: Throwable? = null
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "scan_page_stream_unavailable" }
                FileOutputStream(target).use { output -> RuntimeSafety.copyCancellable(input, output) }
            }
            if (target.length() > 0L) return
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            firstFailure = error
        }

        runCatching { target.delete() }
        try {
            context.contentResolver.openFileDescriptor(uri, "r").use { descriptor ->
                requireNotNull(descriptor) { "scan_page_descriptor_unavailable" }
                FileInputStream(descriptor.fileDescriptor).use { input ->
                    FileOutputStream(target).use { output -> RuntimeSafety.copyCancellable(input, output) }
                }
            }
            require(target.length() > 0L) { "scan_page_descriptor_empty" }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fallbackFailure: Throwable) {
            runCatching { target.delete() }
            throw IllegalStateException(
                "scan_page_read_failed:${fallbackFailure.message ?: firstFailure?.message ?: "unknown"}",
                fallbackFailure
            )
        }
    }


    fun prepareCameraCapture(
        file: File,
        mode: ScanCaptureMode,
        detectedRect: RectF? = null,
        detectedQuad: ScanQuad? = null
    ): List<ScanDraftPage> {
        require(file.exists() && file.length() > 0L) { "صورة الالتقاط غير صالحة" }
        decodeScaled(file, 320).recycle()
        normalizeCapturedOrientation(file)
        if (!mode.splitBookCapture) {
            return listOf(
                ScanDraftPage(
                    id = System.nanoTime(),
                    file = file,
                    filter = mode.defaultFilter,
                    perspective = if (mode.documentDetection) {
                        resolveDocumentPerspective(
                            detectedRect = detectedRect,
                            detectedQuad = detectedQuad
                        )
                    } else null
                )
            )
        }
        // Book mode is intentionally flattened before splitting so both resulting pages
        // inherit a stable rectangular source and do not share one perspective quad.
        if (mode.documentDetection && detectedQuad != null) {
            applyPerspectiveToFile(file, detectedQuad)
        } else if (mode.documentDetection && detectedRect != null) {
            cropCapturedToDocument(file, detectedRect)
        }

        val bitmap = decodeScaled(file, 3200)
        require(bitmap.width >= 2) { "تعذر تقسيم صورة الكتاب" }
        val middle = bitmap.width / 2
        val leftBitmap = Bitmap.createBitmap(bitmap, 0, 0, middle, bitmap.height)
        val rightBitmap = Bitmap.createBitmap(bitmap, middle, 0, bitmap.width - middle, bitmap.height)
        val dir = file.parentFile ?: error("تعذر تجهيز صفحات الكتاب")
        val leftFile = File(dir, "book_left_${System.nanoTime()}.jpg")
        val rightFile = File(dir, "book_right_${System.nanoTime()}.jpg")
        FileOutputStream(leftFile).use { leftBitmap.compress(Bitmap.CompressFormat.JPEG, 94, it) }
        FileOutputStream(rightFile).use { rightBitmap.compress(Bitmap.CompressFormat.JPEG, 94, it) }
        leftBitmap.recycle()
        rightBitmap.recycle()
        bitmap.recycle()
        runCatching { file.delete() }
        return listOf(
            ScanDraftPage(id = System.nanoTime(), file = leftFile, filter = mode.defaultFilter),
            ScanDraftPage(id = System.nanoTime() + 1, file = rightFile, filter = mode.defaultFilter)
        )
    }

    private fun resolveDocumentPerspective(
        detectedRect: RectF?,
        detectedQuad: ScanQuad?
    ): ScanQuad? = detectedQuad?.clamped() ?: detectedRect?.let { rect ->
        DocumentCropGeometry.fromRect(rect.left, rect.top, rect.right, rect.bottom)?.let { quad ->
            ScanQuad(
                topLeft = ScanPoint(quad.topLeftX, quad.topLeftY),
                topRight = ScanPoint(quad.topRightX, quad.topRightY),
                bottomRight = ScanPoint(quad.bottomRightX, quad.bottomRightY),
                bottomLeft = ScanPoint(quad.bottomLeftX, quad.bottomLeftY)
            )
        }
    }

    private fun cropCapturedToDocument(file: File, rect: RectF) {
        val leftN = (rect.left - .025f).coerceIn(0f, .90f)
        val topN = (rect.top - .025f).coerceIn(0f, .90f)
        val rightN = (rect.right + .025f).coerceIn(.10f, 1f)
        val bottomN = (rect.bottom + .025f).coerceIn(.10f, 1f)
        if (rightN - leftN < .16f || bottomN - topN < .16f) return

        val bitmap = decodeScaled(file, 3600)
        val x = (bitmap.width * leftN).roundToInt().coerceIn(0, bitmap.width - 1)
        val y = (bitmap.height * topN).roundToInt().coerceIn(0, bitmap.height - 1)
        val right = (bitmap.width * rightN).roundToInt().coerceIn(x + 1, bitmap.width)
        val bottom = (bitmap.height * bottomN).roundToInt().coerceIn(y + 1, bitmap.height)
        val cropped = Bitmap.createBitmap(bitmap, x, y, right - x, bottom - y)
        FileOutputStream(file, false).use { cropped.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        if (cropped !== bitmap) bitmap.recycle()
        cropped.recycle()
    }

    fun renderSourcePreview(page: ScanDraftPage, maxDimension: Int = 760): Bitmap =
        decodeScaled(page.file, maxDimension)

    /**
     * Local smart-clarity suggestion. It analyses a lightweight preview and only
     * adjusts non-destructive editor parameters; the source image is never changed.
     */
    fun suggestSmartClarity(page: ScanDraftPage, preview: Bitmap): ScanDraftPage {
        if (preview.width < 2 || preview.height < 2) return page
        val step = max(1, max(preview.width, preview.height) / 180)
        var count = 0L
        var sum = 0.0
        var sumSq = 0.0
        var dark = 0L
        var bright = 0L
        var chroma = 0.0
        var y = 0
        while (y < preview.height) {
            var x = 0
            while (x < preview.width) {
                val pixel = preview.getPixel(x, y)
                val r = Color.red(pixel).toDouble()
                val g = Color.green(pixel).toDouble()
                val b = Color.blue(pixel).toDouble()
                val luma = .2126 * r + .7152 * g + .0722 * b
                sum += luma
                sumSq += luma * luma
                if (luma < 72.0) dark++
                if (luma > 215.0) bright++
                chroma += max(r, max(g, b)) - min(r, min(g, b))
                count++
                x += step
            }
            y += step
        }
        if (count == 0L) return page
        val mean = sum / count
        val variance = (sumSq / count - mean * mean).coerceAtLeast(0.0)
        val std = kotlin.math.sqrt(variance)
        val darkRatio = dark.toDouble() / count
        val brightRatio = bright.toDouble() / count
        val avgChroma = chroma / count

        val suggestedFilter = when {
            darkRatio > .20 && brightRatio < .38 -> ScanFilter.SHADOW_REMOVE
            std < 28.0 && brightRatio > .34 -> ScanFilter.FADED_TEXT
            avgChroma > 46.0 && std > 48.0 -> ScanFilter.COLOR
            else -> ScanFilter.DOCUMENT
        }
        val suggestedBrightness = when {
            mean < 92.0 -> 14
            mean < 118.0 -> 9
            mean < 145.0 -> 5
            mean > 218.0 -> -7
            mean > 196.0 -> -3
            else -> 2
        }
        val suggestedContrast = when {
            std < 24.0 -> 1.30f
            std < 38.0 -> 1.20f
            std < 56.0 -> 1.12f
            std > 82.0 -> 1.03f
            else -> 1.08f
        }
        val suggestedSharpness = when {
            std < 30.0 -> .30f
            std < 50.0 -> .24f
            else -> .18f
        }
        return page.copy(
            filter = suggestedFilter,
            brightness = suggestedBrightness,
            contrast = suggestedContrast,
            sharpness = suggestedSharpness
        )
    }

    private fun applyPerspectiveToFile(file: File, quad: ScanQuad) {
        requireValidPerspective(quad)
        val bitmap = decodeScaled(file, 3600)
        val flattened = perspectiveCorrect(bitmap, quad)
        FileOutputStream(file, false).use { flattened.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        flattened.recycle()
    }

    private fun normalizeCapturedOrientation(file: File) {
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return
        val bitmap = decodeScaled(file, 3600)
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        FileOutputStream(file, false).use { rotated.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        if (rotated !== bitmap) bitmap.recycle()
        rotated.recycle()
    }

    fun renderPreview(page: ScanDraftPage, maxDimension: Int = 760): Bitmap {
        val bitmap = decodeScaled(page.file, maxDimension)
        return transform(bitmap, page)
    }

    /**
     * Commits the user-approved four-corner perspective into a new cache image.
     * The source file is left untouched until the replacement JPEG is fully written
     * and validated, then the old cache file is removed. The returned draft no longer
     * carries perspective metadata, so Review/Edit always opens the already flattened page.
     */
    suspend fun commitPerspectiveCrop(context: Context, page: ScanDraftPage): ScanDraftPage {
        val quad = page.perspective ?: return page
        requireValidPerspective(quad)
        currentCoroutineContext().ensureActive()
        val sourceFile = page.file
        require(sourceFile.isFile && sourceFile.length() > 0L) { "تعذر قراءة صورة الصفحة" }
        if (quad == ScanQuad.insetDefault(0f)) {
            // Unchanged bounds retire only the perspective metadata. Validate that
            // the source still decodes, then preserve its bytes and all editor edits.
            val readable = decodeScaled(sourceFile, 760)
            try {
                currentCoroutineContext().ensureActive()
                return page.copy(perspective = null)
            } finally {
                readable.recycle()
            }
        }
        RuntimeSafety.ensureStorage(context, estimatedOutputBytes = sourceFile.length().coerceAtLeast(2L * 1024L * 1024L))

        val dir = sourceFile.parentFile ?: error("تعذر تجهيز صورة الصفحة")
        val stamp = System.nanoTime()
        val pending = File(dir, "perspective_${stamp}.jpg.part")
        val committed = File(dir, "perspective_${stamp}.jpg")

        var flattened: Bitmap? = null
        try {
            val source = decodeScaled(sourceFile, exportMaxDimension(sourceFile))
            currentCoroutineContext().ensureActive()
            val outputBitmap = perspectiveCorrect(source, quad)
            flattened = outputBitmap
            require(outputBitmap.width >= 2 && outputBitmap.height >= 2) { "تعذر تصحيح منظور الصفحة" }

            FileOutputStream(pending, false).use { output ->
                check(outputBitmap.compress(Bitmap.CompressFormat.JPEG, 96, output)) { "تعذر حفظ الصورة المصححة" }
                output.flush()
                output.fd.sync()
            }
            currentCoroutineContext().ensureActive()
            require(pending.length() > 0L) { "الصورة المصححة فارغة" }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(pending.absolutePath, bounds)
            require(bounds.outWidth >= 2 && bounds.outHeight >= 2) { "تعذر التحقق من الصورة المصححة" }

            if (!pending.renameTo(committed)) {
                pending.copyTo(committed, overwrite = true)
                check(committed.length() == pending.length() && committed.length() > 0L) { "تعذر اعتماد الصورة المصححة" }
                pending.delete()
            }
            require(committed.isFile && committed.length() > 0L) { "تعذر اعتماد الصورة المصححة" }

            // Delete only the old temporary capture after the replacement is complete and valid.
            if (sourceFile.absolutePath != committed.absolutePath) runCatching { sourceFile.delete() }

            return page.copy(
                file = committed,
                perspective = null,
                cropLeft = 0f,
                cropTop = 0f,
                cropRight = 0f,
                cropBottom = 0f
            )
        } catch (error: Throwable) {
            runCatching { pending.delete() }
            runCatching { committed.delete() }
            throw error
        } finally {
            flattened?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    suspend fun createPdf(
        context: Context,
        pages: List<ScanDraftPage>,
        baseName: String,
        quality: Int,
        pageSize: ScanPdfPageSize = ScanPdfPageSize.AUTO,
        preserveSourceDetail: Boolean = false,
        destinationDir: File? = null
    ): File {
        require(pages.isNotEmpty()) { "لا توجد صفحات للحفظ" }
        RuntimeSafety.ensureSafePageBatch(pages.size, maxPages = 50)
        val estimatedBytes = pages.sumOf { it.file.length().coerceAtLeast(0L) }.coerceAtMost(768L * 1024L * 1024L)
        RuntimeSafety.ensureStorage(context, estimatedOutputBytes = estimatedBytes)
        val safe = baseName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "Scan" }
        val targetDir = destinationDir?.canonicalFile?.also {
            require(it.isDirectory && FileUtils.isInsideRoot(context, it)) { "مجلد حفظ غير صالح" }
        } ?: FileUtils.outputDir(context, "scans")
        require(targetDir.exists() || targetDir.mkdirs()) { "تعذر إنشاء مجلد الحفظ" }
        require(targetDir.isDirectory && targetDir.canWrite()) { "مجلد الحفظ غير قابل للكتابة" }
        val out = File(targetDir, "${safe}_${System.currentTimeMillis()}.pdf")
        val pending = File(targetDir, ".${out.name}.part")
        runCatching { pending.delete() }
        val pdf = PdfDocument()
        try {
            pages.forEachIndexed { index, draft ->
                currentCoroutineContext().ensureActive()
                // P9: preserve more source detail for a single Document PDF without returning
                // to Alpha37's unsafe 4608 px multi-bitmap allocation path. 3200 px is a
                // deliberate stability cap; other scanner modes keep the normal quality cap.
                val maxDimension = if (preserveSourceDetail) {
                    min(exportMaxDimension(draft.file), max(qualityToMaxDimension(quality), 3200))
                } else {
                    qualityToMaxDimension(quality)
                }
                val source = decodeScaled(draft.file, maxDimension)
                val bitmap = transform(source, draft)
                val (pageWidth, pageHeight) = resolvePageSize(bitmap, pageSize)
                val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                val pdfPage = pdf.startPage(info)
                val canvas = pdfPage.canvas
                canvas.drawColor(Color.WHITE)
                val target = fitRect(bitmap.width, bitmap.height, pageWidth, pageHeight)
                canvas.drawBitmap(bitmap, null, target, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                pdf.finishPage(pdfPage)
                bitmap.recycle()
            }
            FileOutputStream(pending, false).use { output ->
                pdf.writeTo(output)
                output.flush()
                output.fd.sync()
            }
        } finally {
            pdf.close()
        }
        require(pending.exists() && pending.length() > 0L) { "scan_save_output_invalid" }
        if (out.exists()) require(out.delete()) { "تعذر استبدال الملف المحفوظ" }
        require(pending.renameTo(out)) { "تعذر تثبيت الملف في مجلد الحفظ" }
        require(out.exists() && out.length() > 0L) { "scan_save_output_invalid" }
        return out
    }

    suspend fun exportEditedPageToCache(
        context: Context,
        page: ScanDraftPage,
        quality: Int
    ): File {
        RuntimeSafety.ensureStorage(context)
        currentCoroutineContext().ensureActive()
        val dir = File(context.cacheDir, "scan_export/${System.currentTimeMillis()}").apply { mkdirs() }
        val out = File(dir, "page_${page.id}.jpg")
        val source = decodeScaled(page.file, qualityToMaxDimension(quality))
        val bitmap = transform(source, page)
        try {
            FileOutputStream(out).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(55, 95), output)) {
                    "تعذر تجهيز الصورة المعدلة"
                }
            }
        } finally {
            bitmap.recycle()
        }
        require(out.length() > 0L) { "تعذر تجهيز الصورة المعدلة" }
        return out
    }

    suspend fun saveSinglePageJpeg(
        context: Context,
        page: ScanDraftPage,
        baseName: String,
        quality: Int,
        destinationDir: File? = null
    ): File {
        RuntimeSafety.ensureStorage(context, estimatedOutputBytes = page.file.length().coerceAtLeast(0L))
        currentCoroutineContext().ensureActive()
        val safe = baseName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "Scan" }
        val targetDir = destinationDir?.canonicalFile?.also {
            require(it.isDirectory && FileUtils.isInsideRoot(context, it)) { "مجلد حفظ غير صالح" }
        } ?: FileUtils.outputDir(context, "scans")
        require(targetDir.exists() || targetDir.mkdirs()) { "تعذر إنشاء مجلد الحفظ" }
        require(targetDir.isDirectory && targetDir.canWrite()) { "مجلد الحفظ غير قابل للكتابة" }
        val out = File(targetDir, "${safe}_${System.currentTimeMillis()}.jpg")
        val pending = File(targetDir, ".${out.name}.part")
        runCatching { pending.delete() }
        // Alpha41: write + fsync to a temporary file, then atomically publish the real JPG.
        // This prevents the UI from reporting success before bytes are durable on storage.
        return try {
            val source = decodeScaled(page.file, qualityToMaxDimension(quality))
            val bitmap = transform(source, page)
            try {
                FileOutputStream(pending, false).use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(70, 98), output)) {
                        "scan_save_output_invalid"
                    }
                    output.flush()
                    output.fd.sync()
                }
            } finally {
                bitmap.recycle()
            }
            require(pending.exists() && pending.length() > 0L) { "scan_save_output_invalid" }
            if (out.exists()) require(out.delete()) { "تعذر استبدال الملف المحفوظ" }
            require(pending.renameTo(out)) { "تعذر تثبيت الملف في مجلد الحفظ" }
            require(out.exists() && out.length() > 0L) { "scan_save_output_invalid" }
            out
        } catch (error: Throwable) {
            // P15 SCAN-04: the hidden .part file belongs to this attempt only; never leave it behind.
            runCatching { pending.delete() }
            throw error
        }
    }

    /** Identity of a JPG published by one Batch save attempt (P15 SCAN-04 ownership proof). */
    private data class PublishedBatchImage(val file: File, val length: Long, val lastModified: Long)

    /**
     * P15 SCAN-04: Batch → JPG is all-or-nothing for this attempt. Each page is still written and
     * fsynced through [saveSinglePageJpeg]; if any page fails or the save is cancelled, only the
     * images this attempt published, and that are still unchanged since it wrote them (same path,
     * size and modification time), are removed. Files that existed before the attempt, or that
     * were changed after it published them, are never touched. Review pages are not modified.
     */
    suspend fun saveBatchJpegs(
        context: Context,
        pages: List<ScanDraftPage>,
        baseName: String,
        quality: Int,
        destinationDir: File? = null,
        savePage: suspend (index: Int, page: ScanDraftPage, pageBaseName: String) -> File = { _, page, pageBaseName ->
            saveSinglePageJpeg(context, page, pageBaseName, quality, destinationDir)
        }
    ): List<File> {
        require(pages.isNotEmpty()) { "no_scan_pages" }
        val published = ArrayList<PublishedBatchImage>(pages.size)
        return try {
            pages.forEachIndexed { index, page ->
                currentCoroutineContext().ensureActive()
                val image = savePage(index, page, "${baseName}_${index + 1}")
                // Record ownership first so a page that fails verification is still rolled back.
                if (image.isFile) published += PublishedBatchImage(image, image.length(), image.lastModified())
                require(FileUtils.verifySavedFile(context, image)) { "scan_save_output_invalid" }
            }
            currentCoroutineContext().ensureActive()
            published.map { it.file }
        } catch (error: Throwable) {
            rollbackPublishedBatchImages(published)
            throw error
        }
    }

    private fun rollbackPublishedBatchImages(published: List<PublishedBatchImage>) {
        published.asReversed().forEach { owned ->
            runCatching {
                val file = owned.file
                if (file.isFile && file.length() == owned.length && file.lastModified() == owned.lastModified) {
                    file.delete()
                }
            }
        }
    }

    suspend fun savePageImages(
        context: Context,
        pages: List<ScanDraftPage>,
        quality: Int
    ): List<File> {
        require(pages.isNotEmpty()) { "no_scan_pages" }
        RuntimeSafety.ensureSafePageBatch(pages.size, maxPages = 50)
        val estimatedBytes = pages.sumOf { it.file.length().coerceAtLeast(0L) }.coerceAtMost(768L * 1024L * 1024L)
        RuntimeSafety.ensureStorage(context, estimatedOutputBytes = estimatedBytes)
        val dir = FileUtils.outputDir(context, "scan_images/${System.currentTimeMillis()}")
        val outputs = mutableListOf<File>()
        try {
            pages.forEachIndexed { index, draft ->
                currentCoroutineContext().ensureActive()
                val source = decodeScaled(draft.file, qualityToMaxDimension(quality))
                val bitmap = transform(source, draft)
                try {
                    val out = File(dir, "page_${index + 1}.jpg")
                    FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(55, 95), it) }
                    outputs += out
                } finally {
                    bitmap.recycle()
                }
            }
            return outputs
        } catch (error: Throwable) {
            dir.deleteRecursively()
            throw error
        }
    }

    fun cleanup(pages: List<ScanDraftPage>) {
        pages.map { it.file.parentFile }.distinct().forEach { dir ->
            runCatching { dir?.deleteRecursively() }
        }
    }

    private fun resolvePageSize(bitmap: Bitmap, size: ScanPdfPageSize): Pair<Int, Int> {
        if (size == ScanPdfPageSize.AUTO) return bitmap.width to bitmap.height
        val portrait = when (size) {
            ScanPdfPageSize.A4 -> 595 to 842
            ScanPdfPageSize.LETTER -> 612 to 792
            ScanPdfPageSize.AUTO -> bitmap.width to bitmap.height
        }
        return if (bitmap.width >= bitmap.height) portrait.second to portrait.first else portrait
    }

    private fun fitRect(bitmapWidth: Int, bitmapHeight: Int, pageWidth: Int, pageHeight: Int): RectF {
        val margin = when {
            min(pageWidth, pageHeight) < 700 -> 14f
            else -> 24f
        }
        val availableW = (pageWidth - margin * 2f).coerceAtLeast(1f)
        val availableH = (pageHeight - margin * 2f).coerceAtLeast(1f)
        val scale = min(availableW / bitmapWidth.toFloat(), availableH / bitmapHeight.toFloat())
        val drawW = bitmapWidth * scale
        val drawH = bitmapHeight * scale
        val left = (pageWidth - drawW) / 2f
        val top = (pageHeight - drawH) / 2f
        return RectF(left, top, left + drawW, top + drawH)
    }

    private fun qualityToMaxDimension(quality: Int): Int = when {
        quality >= 90 -> 2800
        quality >= 80 -> 2350
        quality >= 70 -> 1950
        else -> 1550
    }

    private fun exportMaxDimension(file: File): Int {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sourceMax = max(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        // Preserve full detail for common 12–16 MP captures while avoiding multi-bitmap OOM spikes on huge gallery images.
        return sourceMax.coerceAtMost(4608)
    }

    private fun decodeScaled(file: File, maxDimension: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "تعذر قراءة صورة الصفحة" }
        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > maxDimension * 2) sample *= 2
        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        ) ?: error("تعذر قراءة صورة الصفحة")
        val currentMax = max(bitmap.width, bitmap.height)
        if (currentMax <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / currentMax
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun transform(source: Bitmap, page: ScanDraftPage): Bitmap {
        var current = page.perspective?.let { perspectiveCorrect(source, it) } ?: source
        current = crop(current, page.cropLeft, page.cropTop, page.cropRight, page.cropBottom)
        val normalized = ((page.rotation % 360) + 360) % 360
        if (normalized != 0) {
            val before = current
            val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
            current = Bitmap.createBitmap(before, 0, 0, before.width, before.height, matrix, true)
            if (current !== before) before.recycle()
        }

        val needsColorPass = page.filter != ScanFilter.ORIGINAL || page.brightness != 0 || kotlin.math.abs(page.contrast - 1f) > .01f
        if (needsColorPass) {
            val before = current
            current = applyColorAdjustments(before, page.filter, page.brightness, page.contrast)
            if (current !== before) before.recycle()
        }

        val effectiveSharpness = max(page.sharpness, if (page.filter == ScanFilter.FADED_TEXT) .22f else 0f)
        if (effectiveSharpness > .02f) {
            val before = current
            current = sharpen(before, effectiveSharpness.coerceIn(0f, .65f))
            if (current !== before) before.recycle()
        }
        if (page.annotations.isNotEmpty()) {
            val before = current
            current = drawAnnotations(before, page.annotations)
            if (current !== before) before.recycle()
        }
        return current
    }

    private fun drawAnnotations(bitmap: Bitmap, annotations: List<ScanTextAnnotation>): Bitmap {
        if (annotations.isEmpty()) return bitmap
        val target = if (bitmap.isMutable) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, true)
            ?: error("scan_annotation_bitmap_copy_failed")
        val canvas = Canvas(target)
        val shortSide = min(bitmap.width, bitmap.height).coerceAtLeast(1).toFloat()
        annotations.forEach { item ->
            val text = item.text.trim()
            if (text.isEmpty()) return@forEach
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = item.colorArgb.toInt()
                textSize = (shortSide * item.sizeScale.coerceIn(.025f, .14f)).coerceAtLeast(14f)
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            }
            val x = (item.x.coerceIn(.02f, .98f) * bitmap.width).coerceAtMost(bitmap.width - 1f)
            val y = (item.y.coerceIn(.04f, .96f) * bitmap.height).coerceAtMost(bitmap.height - 1f)
            val width = paint.measureText(text)
            val metrics = paint.fontMetrics
            item.backgroundArgb?.let { background ->
                val pad = paint.textSize * .24f
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background.toInt() }
                canvas.drawRoundRect(
                    x - pad,
                    y + metrics.ascent - pad,
                    (x + width + pad).coerceAtMost(bitmap.width.toFloat()),
                    y + metrics.descent + pad,
                    pad,
                    pad,
                    bgPaint
                )
            }
            canvas.drawText(text, x, y, paint)
        }
        return target
    }

    private fun requireValidPerspective(quad: ScanQuad) {
        val points = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
        require(points.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f }) {
            "حدود الصفحة غير صالحة"
        }
        var twiceArea = 0.0
        points.indices.forEach { index ->
            val first = points[index]
            val second = points[(index + 1) % points.size]
            val third = points[(index + 2) % points.size]
            val turn = (second.x.toDouble() - first.x) * (third.y.toDouble() - second.y) -
                (second.y.toDouble() - first.y) * (third.x.toDouble() - second.x)
            // The named corners run clockwise in image coordinates (where y grows down).
            // Every strict positive turn excludes crossing, concave, reversed and collapsed quads.
            require(turn > 1e-6) { "حدود الصفحة غير صالحة" }
            twiceArea += first.x.toDouble() * second.y - first.y.toDouble() * second.x
        }
        // Reject slivers smaller than 0.1% of the captured frame.
        require(twiceArea >= .002) { "مساحة الصفحة صغيرة جدًا" }
    }

    private fun perspectiveCorrect(source: Bitmap, quadInput: ScanQuad): Bitmap {
        requireValidPerspective(quadInput)
        val quad = quadInput
        fun px(p: ScanPoint) = floatArrayOf(p.x * source.width, p.y * source.height)
        val tl = px(quad.topLeft)
        val tr = px(quad.topRight)
        val br = px(quad.bottomRight)
        val bl = px(quad.bottomLeft)

        fun distance(a: FloatArray, b: FloatArray): Float = hypot(a[0] - b[0], a[1] - b[1])
        val topW = distance(tl, tr)
        val bottomW = distance(bl, br)
        val leftH = distance(tl, bl)
        val rightH = distance(tr, br)
        var outW = max(topW, bottomW).roundToInt().coerceAtLeast(2)
        var outH = max(leftH, rightH).roundToInt().coerceAtLeast(2)

        // Guard against malformed corner edits creating enormous bitmaps.
        val maxSource = max(source.width, source.height).coerceAtLeast(1)
        val maxOutput = (maxSource * 1.08f).roundToInt().coerceAtLeast(2)
        if (outW > maxOutput || outH > maxOutput) {
            val scale = min(maxOutput / outW.toFloat(), maxOutput / outH.toFloat())
            outW = (outW * scale).roundToInt().coerceAtLeast(2)
            outH = (outH * scale).roundToInt().coerceAtLeast(2)
        }

        val src = floatArrayOf(
            tl[0], tl[1], tr[0], tr[1], br[0], br[1], bl[0], bl[1]
        )
        val dst = floatArrayOf(
            0f, 0f, outW.toFloat(), 0f, outW.toFloat(), outH.toFloat(), 0f, outH.toFloat()
        )
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) return source
        val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        if (output !== source) source.recycle()
        return output
    }

    private fun crop(source: Bitmap, leftF: Float, topF: Float, rightF: Float, bottomF: Float): Bitmap {
        val left = leftF.coerceIn(0f, .4f)
        val top = topF.coerceIn(0f, .4f)
        val right = rightF.coerceIn(0f, .4f)
        val bottom = bottomF.coerceIn(0f, .4f)
        if (left + right < .002f && top + bottom < .002f) return source
        val x = (source.width * left).roundToInt().coerceIn(0, source.width - 1)
        val y = (source.height * top).roundToInt().coerceIn(0, source.height - 1)
        val rightPx = (source.width * right).roundToInt()
        val bottomPx = (source.height * bottom).roundToInt()
        val width = (source.width - x - rightPx).coerceAtLeast(1)
        val height = (source.height - y - bottomPx).coerceAtLeast(1)
        val safeWidth = min(width, source.width - x)
        val safeHeight = min(height, source.height - y)
        val cropped = Bitmap.createBitmap(source, x, y, safeWidth, safeHeight)
        if (cropped !== source) source.recycle()
        return cropped
    }

    private fun applyColorAdjustments(source: Bitmap, filter: ScanFilter, brightness: Int, contrast: Float): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val sourceForFilter = if (filter == ScanFilter.SHADOW_REMOVE) flattenDocumentShadows(source) else source
        val base = when (filter) {
            ScanFilter.GRAYSCALE -> ColorMatrix().apply { setSaturation(0f) }
            ScanFilter.COLOR -> saturationContrastMatrix(1.22f, 1.08f, 2f)
            ScanFilter.DOCUMENT -> saturationContrastMatrix(0.28f, 1.34f, 16f)
            ScanFilter.BW -> saturationContrastMatrix(0f, 1.85f, 10f)
            ScanFilter.PHOTO -> saturationContrastMatrix(1.08f, 1.04f, 1f)
            ScanFilter.SHADOW_REMOVE -> saturationContrastMatrix(.18f, 1.18f, 10f)
            ScanFilter.FADED_TEXT -> saturationContrastMatrix(0f, 1.58f, 18f)
            ScanFilter.ORIGINAL -> ColorMatrix()
        }
        val userContrast = contrast.coerceIn(.65f, 1.55f)
        val userBrightness = brightness.coerceIn(-55, 55).toFloat()
        if (kotlin.math.abs(userContrast - 1f) > .01f || kotlin.math.abs(userBrightness) > .5f) {
            val translate = 128f * (1f - userContrast) + userBrightness
            base.postConcat(ColorMatrix(floatArrayOf(
                userContrast, 0f, 0f, 0f, translate,
                0f, userContrast, 0f, 0f, translate,
                0f, 0f, userContrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )))
        }
        paint.colorFilter = ColorMatrixColorFilter(base)
        canvas.drawBitmap(sourceForFilter, 0f, 0f, paint)
        if (sourceForFilter !== source) sourceForFilter.recycle()
        return output
    }

    private fun flattenDocumentShadows(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val block = max(24, min(width, height) / 24)
        val blocksX = (width + block - 1) / block
        val blocksY = (height + block - 1) / block
        val background = IntArray(blocksX * blocksY)

        for (by in 0 until blocksY) {
            for (bx in 0 until blocksX) {
                val startX = bx * block
                val startY = by * block
                val endX = min(width, startX + block)
                val endY = min(height, startY + block)
                var sum = 0L
                var count = 0
                var y = startY
                while (y < endY) {
                    var x = startX
                    while (x < endX) {
                        val c = source.getPixel(x, y)
                        sum += (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100
                        count++
                        x += 4
                    }
                    y += 4
                }
                background[by * blocksX + bx] = if (count == 0) 200 else (sum / count).toInt()
            }
        }

        val row = IntArray(width)
        for (y in 0 until height) {
            source.getPixels(row, 0, width, 0, y, width, 1)
            val by = (y / block).coerceIn(0, blocksY - 1)
            for (x in 0 until width) {
                val c = row[x]
                val bx = (x / block).coerceIn(0, blocksX - 1)
                val bg = background[by * blocksX + bx]
                val lift = ((228 - bg) * .72f).roundToInt().coerceIn(-22, 72)
                row[x] = Color.argb(
                    Color.alpha(c),
                    (Color.red(c) + lift).coerceIn(0, 255),
                    (Color.green(c) + lift).coerceIn(0, 255),
                    (Color.blue(c) + lift).coerceIn(0, 255)
                )
            }
            out.setPixels(row, 0, width, 0, y, width, 1)
        }
        return out
    }

    private fun sharpen(source: Bitmap, amount: Float): Bitmap {
        if (source.width < 3 || source.height < 3) return source
        val width = source.width
        val height = source.height
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val prev = IntArray(width)
        val curr = IntArray(width)
        val next = IntArray(width)
        val result = IntArray(width)
        source.getPixels(prev, 0, width, 0, 0, width, 1)
        source.getPixels(curr, 0, width, 0, 1, width, 1)
        out.setPixels(prev, 0, width, 0, 0, width, 1)
        val a = amount.coerceIn(0f, .65f)
        val centerWeight = 1f + 4f * a
        for (y in 1 until height - 1) {
            source.getPixels(next, 0, width, 0, y + 1, width, 1)
            result[0] = curr[0]
            result[width - 1] = curr[width - 1]
            for (x in 1 until width - 1) {
                val c = curr[x]
                val l = curr[x - 1]
                val r = curr[x + 1]
                val u = prev[x]
                val d = next[x]
                val alpha = Color.alpha(c)
                val red = clampChannel(Color.red(c) * centerWeight - a * (Color.red(l) + Color.red(r) + Color.red(u) + Color.red(d)))
                val green = clampChannel(Color.green(c) * centerWeight - a * (Color.green(l) + Color.green(r) + Color.green(u) + Color.green(d)))
                val blue = clampChannel(Color.blue(c) * centerWeight - a * (Color.blue(l) + Color.blue(r) + Color.blue(u) + Color.blue(d)))
                result[x] = Color.argb(alpha, red, green, blue)
            }
            out.setPixels(result, 0, width, 0, y, width, 1)
            System.arraycopy(curr, 0, prev, 0, width)
            System.arraycopy(next, 0, curr, 0, width)
        }
        source.getPixels(result, 0, width, 0, height - 1, width, 1)
        out.setPixels(result, 0, width, 0, height - 1, width, 1)
        return out
    }

    private fun clampChannel(value: Float): Int = value.roundToInt().coerceIn(0, 255)

    private fun saturationContrastMatrix(saturation: Float, contrast: Float, brightness: Float): ColorMatrix {
        val saturationMatrix = ColorMatrix().apply { setSaturation(saturation) }
        val translate = 128f * (1f - contrast) + brightness
        val contrastMatrix = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        ))
        saturationMatrix.postConcat(contrastMatrix)
        return saturationMatrix
    }
}
