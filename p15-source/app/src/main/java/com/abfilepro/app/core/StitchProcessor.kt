package com.abfilepro.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.abfilepro.app.ui.i18n.uiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object StitchProcessor {
    private const val MAX_IMAGES = 30
    private const val ANALYSIS_WIDTH = 56
    private const val ANALYSIS_MAX_HEIGHT = 420
    private const val MAX_OUTPUT_WIDTH = 1440
    private const val MIN_OUTPUT_WIDTH = 240
    private const val PIXEL_BUDGET = 18_000_000.0
    private const val MATCH_THRESHOLD = 0.855

    data class Result(
        val imageFile: File,
        val pdfFile: File,
        val sourceCount: Int,
        val confidentLinks: Int,
        val outputWidth: Int,
        val outputHeight: Int,
        val smartOrderUsed: Boolean,
        val isDraft: Boolean = false
    )

    private data class SourceMeta(
        val uri: Uri,
        val originalIndex: Int,
        val width: Int,
        val height: Int,
        val fingerprint: Fingerprint
    )

    private data class Fingerprint(
        val width: Int,
        val height: Int,
        val gray: ByteArray
    ) {
        fun value(x: Int, y: Int): Int = gray[y * width + x].toInt() and 0xFF
    }

    private data class Match(
        val score: Double,
        val trimFraction: Double
    )

    suspend fun stitch(
        context: Context,
        uris: List<Uri>,
        smartOrder: Boolean = true,
        languageCode: String = "ar",
        draftMode: Boolean = false,
        onProgress: suspend (label: String) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        RuntimeSafety.ensureStorage(context)
        require(uris.size >= 2) { context.uiText(languageCode, "b2_stitch_error_min") }
        require(uris.size <= MAX_IMAGES) { context.uiText(languageCode, "b2_stitch_error_max", MAX_IMAGES) }

        onProgress(context.uiText(languageCode, "b2_stitch_progress_analyze"))
        val sources = buildList {
            uris.forEachIndexed { index, uri ->
                currentCoroutineContext().ensureActive()
                add(readMeta(context, uri, index))
            }
        }
        require(sources.all { it.width > 0 && it.height > 0 }) { context.uiText(languageCode, "b2_stitch_error_read_one") }

        val matchCache = HashMap<Long, Match>()
        fun match(a: SourceMeta, b: SourceMeta): Match {
            val key = (a.originalIndex.toLong() shl 32) or (b.originalIndex.toLong() and 0xffffffffL)
            return matchCache.getOrPut(key) { findOverlap(a.fingerprint, b.fingerprint) }
        }

        val ordered = if (smartOrder && sources.size > 2) {
            onProgress(context.uiText(languageCode, "b2_stitch_progress_order"))
            smartOrder(sources, ::match)
        } else {
            sources
        }

        onProgress(context.uiText(languageCode, "b2_stitch_progress_overlap"))
        val trims = IntArray(ordered.size)
        var confidentLinks = 0
        for (i in 1 until ordered.size) {
            currentCoroutineContext().ensureActive()
            val link = match(ordered[i - 1], ordered[i])
            if (link.score >= MATCH_THRESHOLD) {
                trims[i] = (ordered[i].height * link.trimFraction).roundToInt().coerceIn(0, ordered[i].height - 1)
                confidentLinks++
            }
        }

        val targetWidth = chooseOutputWidth(ordered, trims)
        onProgress(context.uiText(languageCode, "b2_stitch_progress_merge", ordered.size))
        var stitched: Bitmap? = null
        try {
            val scaledHeights = ordered.map { source ->
                max(1, (source.height * (targetWidth / source.width.toDouble())).roundToInt())
            }
            val scaledTrims = IntArray(ordered.size)
            for (i in 1 until ordered.size) {
                currentCoroutineContext().ensureActive()
                val metaHeight = ordered[i].height.toDouble().coerceAtLeast(1.0)
                scaledTrims[i] = (scaledHeights[i] * (trims[i] / metaHeight)).roundToInt().coerceIn(0, scaledHeights[i] - 1)
            }
            val totalHeight = ordered.indices.sumOf { index ->
                if (index == 0) scaledHeights[index] else scaledHeights[index] - scaledTrims[index]
            }.coerceAtLeast(1)

            stitched = Bitmap.createBitmap(targetWidth, totalHeight, Bitmap.Config.RGB_565)
            val canvas = Canvas(stitched)
            var y = 0
            ordered.forEachIndexed { index, source ->
                currentCoroutineContext().ensureActive()
                val bitmap = decodeScaled(context, source.uri, targetWidth)
                try {
                    val srcTop = if (index == 0) 0 else scaledTrims[index].coerceAtMost(bitmap.height - 1)
                    val src = Rect(0, srcTop, bitmap.width, bitmap.height)
                    val h = bitmap.height - srcTop
                    val dst = Rect(0, y, targetWidth, y + h)
                    canvas.drawBitmap(bitmap, src, dst, null)
                    y += h
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }

            val stamp = System.currentTimeMillis()
            val outDir = if (draftMode) {
                File(context.cacheDir, "stitch_drafts/$stamp").apply { mkdirs() }
            } else {
                FileUtils.outputDir(context, "AB Stitch")
            }
            val imageFile = File(outDir, "AB_Stitch_$stamp.jpg")
            FileOutputStream(imageFile).use { output ->
                check(stitched.compress(Bitmap.CompressFormat.JPEG, 93, output)) { context.uiText(languageCode, "b2_stitch_error_save_image") }
            }

            onProgress(context.uiText(languageCode, "b2_stitch_progress_pdf"))
            val pdfFile = File(outDir, "AB_Stitch_$stamp.pdf")
            savePdf(stitched, pdfFile)

            Result(
                imageFile = imageFile,
                pdfFile = pdfFile,
                sourceCount = ordered.size,
                confidentLinks = confidentLinks,
                outputWidth = stitched.width,
                outputHeight = stitched.height,
                smartOrderUsed = smartOrder,
                isDraft = draftMode
            )
        } catch (error: Throwable) {
            if (draftMode) {
                File(context.cacheDir, "stitch_drafts").listFiles().orEmpty()
                    .filter { System.currentTimeMillis() - it.lastModified() < 5 * 60 * 1000L }
                    .forEach { runCatching { it.deleteRecursively() } }
            }
            throw error
        } finally {
            stitched?.let { runCatching { if (!it.isRecycled) it.recycle() } }
            sources.forEach { source -> source.fingerprint.gray.fill(0) }
        }
    }

    suspend fun commitDraft(context: Context, result: Result, cropTop: Float = 0f, cropBottom: Float = 0f, languageCode: String = "ar"): Result = withContext(Dispatchers.IO) {
        RuntimeSafety.ensureStorage(context)
        currentCoroutineContext().ensureActive()
        require(result.isDraft) { context.uiText(languageCode, "b2_stitch_error_already_saved") }
        require(result.imageFile.exists()) { context.uiText(languageCode, "b2_stitch_error_draft_missing") }
        val top = cropTop.coerceIn(0f, .35f)
        val bottom = cropBottom.coerceIn(0f, .35f)
        require(top + bottom < .7f) { context.uiText(languageCode, "b2_stitch_error_crop") }

        val source = BitmapFactory.decodeFile(result.imageFile.absolutePath) ?: error(context.uiText(languageCode, "b2_stitch_error_open_draft"))
        val y = (source.height * top).roundToInt().coerceIn(0, source.height - 1)
        val end = (source.height * (1f - bottom)).roundToInt().coerceIn(y + 1, source.height)
        val cropped = if (y == 0 && end == source.height) source else Bitmap.createBitmap(source, 0, y, source.width, end - y)
        var image: File? = null
        var pdf: File? = null
        try {
            currentCoroutineContext().ensureActive()
            val stamp = System.currentTimeMillis()
            val outDir = FileUtils.outputDir(context, "AB Stitch")
            val committedImage = File(outDir, "AB_Stitch_$stamp.jpg")
            image = committedImage
            FileOutputStream(committedImage).use { output ->
                check(cropped.compress(Bitmap.CompressFormat.JPEG, 93, output)) { context.uiText(languageCode, "b2_stitch_error_save_image") }
            }
            currentCoroutineContext().ensureActive()
            val committedPdf = File(outDir, "AB_Stitch_$stamp.pdf")
            pdf = committedPdf
            savePdf(cropped, committedPdf)
            currentCoroutineContext().ensureActive()
            runCatching { result.imageFile.parentFile?.deleteRecursively() }
            return@withContext result.copy(
                imageFile = committedImage,
                pdfFile = committedPdf,
                outputWidth = cropped.width,
                outputHeight = cropped.height,
                isDraft = false
            )
        } catch (error: Throwable) {
            image?.delete(); pdf?.delete()
            throw error
        } finally {
            if (cropped !== source && !cropped.isRecycled) cropped.recycle()
            if (!source.isRecycled) source.recycle()
        }
    }

    fun discardDraft(result: Result) {
        if (!result.isDraft) return
        runCatching { result.imageFile.parentFile?.deleteRecursively() }
    }

    private fun readMeta(context: Context, uri: Uri, index: Int): SourceMeta {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "تعذر قراءة الصورة" }
            BitmapFactory.decodeStream(input, null, bounds)
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "ملف صورة غير صالح" }
        val analysis = decodeForAnalysis(context, uri, bounds.outWidth, bounds.outHeight)
        return SourceMeta(uri, index, bounds.outWidth, bounds.outHeight, analysis)
    }

    private fun decodeForAnalysis(context: Context, uri: Uri, width: Int, height: Int): Fingerprint {
        var sample = 1
        while (width / (sample * 2) >= ANALYSIS_WIDTH) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "تعذر قراءة الصورة" }
            requireNotNull(BitmapFactory.decodeStream(input, null, options)) { "تعذر تحليل الصورة" }
        }
        try {
            val targetHeight = min(ANALYSIS_MAX_HEIGHT, max(20, (decoded.height * (ANALYSIS_WIDTH / decoded.width.toDouble())).roundToInt()))
            val scaled = Bitmap.createScaledBitmap(decoded, ANALYSIS_WIDTH, targetHeight, true)
            try {
                val pixels = IntArray(scaled.width * scaled.height)
                scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
                val gray = ByteArray(pixels.size)
                for (i in pixels.indices) {
                    val color = pixels[i]
                    val r = (color shr 16) and 0xFF
                    val g = (color shr 8) and 0xFF
                    val b = color and 0xFF
                    gray[i] = ((r * 30 + g * 59 + b * 11) / 100).toByte()
                }
                return Fingerprint(scaled.width, scaled.height, gray)
            } finally {
                if (scaled !== decoded) scaled.recycle()
            }
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    private fun findOverlap(a: Fingerprint, b: Fingerprint): Match {
        val minOverlap = max(12, min(a.height, b.height) / 12)
        val maxOverlap = min((a.height * 0.72).roundToInt(), (b.height * 0.72).roundToInt())
        if (maxOverlap <= minOverlap) return Match(0.0, 0.0)

        val maxSkip = min((b.height * 0.24).roundToInt(), max(0, b.height - minOverlap - 1))
        val overlapStep = max(2, (maxOverlap - minOverlap) / 28)
        val skipStep = max(1, maxSkip / 12)
        var bestScore = 0.0
        var bestTrim = 0.0

        var skip = 0
        while (skip <= maxSkip) {
            var overlap = maxOverlap
            while (overlap >= minOverlap && skip + overlap <= b.height) {
                val aStart = a.height - overlap
                val yStep = max(1, overlap / 24)
                var diff = 0L
                var samples = 0
                var texture = 0L
                var y = 0
                while (y < overlap) {
                    var x = 0
                    while (x < a.width) {
                        val av = a.value(x, aStart + y)
                        val bv = b.value(x, skip + y)
                        diff += kotlin.math.abs(av - bv)
                        if (x >= 2) texture += kotlin.math.abs(av - a.value(x - 2, aStart + y))
                        samples++
                        x += 2
                    }
                    y += yStep
                }
                if (samples > 0) {
                    val similarity = 1.0 - (diff.toDouble() / samples / 255.0)
                    val textureMean = texture.toDouble() / samples
                    val textureFactor = when {
                        textureMean >= 7.0 -> 1.0
                        textureMean >= 3.0 -> 0.97
                        else -> 0.90
                    }
                    val sizePreference = 0.94 + 0.06 * (overlap.toDouble() / maxOverlap)
                    val score = similarity * textureFactor * sizePreference
                    if (score > bestScore) {
                        bestScore = score
                        bestTrim = (skip + overlap).toDouble() / b.height
                    }
                }
                overlap -= overlapStep
            }
            skip += skipStep
        }
        return Match(bestScore.coerceIn(0.0, 1.0), bestTrim.coerceIn(0.0, 0.95))
    }

    private fun smartOrder(sources: List<SourceMeta>, matcher: (SourceMeta, SourceMeta) -> Match): List<SourceMeta> {
        if (sources.size <= 2) return sources
        val incoming = DoubleArray(sources.size)
        val outgoing = DoubleArray(sources.size)
        for (i in sources.indices) {
            for (j in sources.indices) {
                if (i == j) continue
                val score = matcher(sources[i], sources[j]).score
                outgoing[i] = max(outgoing[i], score)
                incoming[j] = max(incoming[j], score)
            }
        }
        val start = sources.indices
            .filter { outgoing[it] >= MATCH_THRESHOLD }
            .minByOrNull { incoming[it] }
            ?: 0

        val result = ArrayList<SourceMeta>(sources.size)
        val used = BooleanArray(sources.size)
        var current = start
        while (result.size < sources.size) {
            result += sources[current]
            used[current] = true
            if (result.size == sources.size) break

            val best = sources.indices
                .filterNot { used[it] }
                .map { it to matcher(sources[current], sources[it]).score }
                .maxByOrNull { it.second }

            current = if (best != null && best.second >= MATCH_THRESHOLD) {
                best.first
            } else {
                sources.indices.firstOrNull { !used[it] && it > current }
                    ?: sources.indices.first { !used[it] }
            }
        }
        return result
    }

    private fun chooseOutputWidth(sources: List<SourceMeta>, trims: IntArray): Int {
        val minSourceWidth = sources.minOf { it.width }.coerceAtLeast(1)
        val naturalWidth = min(MAX_OUTPUT_WIDTH, minSourceWidth)
        var totalAspect = 0.0
        sources.forEachIndexed { index, source ->
            val visibleHeight = if (index == 0) source.height else source.height - trims[index]
            totalAspect += visibleHeight.coerceAtLeast(1).toDouble() / source.width.coerceAtLeast(1)
        }
        val budgetWidth = sqrt(PIXEL_BUDGET / totalAspect.coerceAtLeast(0.1)).toInt()
        require(budgetWidth >= MIN_OUTPUT_WIDTH) { "الصور طويلة جدًا لعملية واحدة. قسّمها إلى مجموعتين للحفاظ على ثبات الهاتف" }
        return min(naturalWidth, budgetWidth).coerceAtLeast(MIN_OUTPUT_WIDTH)
    }

    private fun decodeScaled(context: Context, uri: Uri, targetWidth: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "تعذر قراءة الصورة" }
            BitmapFactory.decodeStream(input, null, bounds)
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "صورة غير صالحة" }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "تعذر قراءة الصورة" }
            requireNotNull(BitmapFactory.decodeStream(input, null, options)) { "تعذر تحميل الصورة" }
        }
        val targetHeight = max(1, (decoded.height * (targetWidth / decoded.width.toDouble())).roundToInt())
        if (decoded.width == targetWidth) return decoded
        val scaled = Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
        decoded.recycle()
        return scaled
    }

    private fun savePdf(bitmap: Bitmap, output: File) {
        val document = PdfDocument()
        try {
            val pageWidth = 1240
            val pageHeight = 1754
            val margin = 28
            val contentWidth = pageWidth - margin * 2
            val contentHeight = pageHeight - margin * 2
            val sourceSliceHeight = max(1, (contentHeight * (bitmap.width / contentWidth.toDouble())).roundToInt())
            var sourceTop = 0
            var pageNumber = 1
            while (sourceTop < bitmap.height) {
                val sourceBottom = min(bitmap.height, sourceTop + sourceSliceHeight)
                val actualHeight = sourceBottom - sourceTop
                val drawHeight = (actualHeight * (contentWidth / bitmap.width.toDouble())).roundToInt().coerceAtMost(contentHeight)
                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                val page = document.startPage(pageInfo)
                val src = Rect(0, sourceTop, bitmap.width, sourceBottom)
                val dst = RectF(margin.toFloat(), margin.toFloat(), (margin + contentWidth).toFloat(), (margin + drawHeight).toFloat())
                page.canvas.drawBitmap(bitmap, src, dst, null)
                document.finishPage(page)
                sourceTop = sourceBottom
                pageNumber++
            }
            FileOutputStream(output).use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }
}
