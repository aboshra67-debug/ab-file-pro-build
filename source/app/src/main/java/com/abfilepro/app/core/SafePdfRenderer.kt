package com.abfilepro.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.sqrt

/**
 * P21 (Android 8 compatibility): the system PdfRenderer on Android 8.0/8.1 rejects some valid PDFs
 * (including ones written by Android's own PdfDocument) with "file not in PDF format or corrupted".
 *
 * Order of attempts, same call shape as PdfRenderer (pageCount, openPage(i).use { width/height/render }):
 *  1. [Mode.NATIVE]: the system renderer on the original file (every normal case, unchanged).
 *  2. [Mode.NATIVE_REWRITTEN]: PDFBox re-saves a private temporary copy in the classic PDF layout and
 *     the system renderer opens that copy. The user's file is never modified.
 *  3. [Mode.PDFBOX]: page count and page sizes from PDFBox. Rasterizing in this mode can miss content
 *     PDFBox does not support, so it is allowed only for on-screen previews (allowPreviewOnlyRendering);
 *     anything that produces a file refuses instead of saving blank pages.
 */
class SafePdfRenderer private constructor(
    private val native: PdfRenderer?,
    private val box: PDDocument?,
    private val rewrittenCopy: File?,
    private val allowPreviewOnlyRendering: Boolean,
) : AutoCloseable {
    enum class Mode { NATIVE, NATIVE_REWRITTEN, PDFBOX }

    private val boxRenderer: PDFRenderer? = box?.let(::PDFRenderer)

    val mode: Mode get() = when {
        native == null -> Mode.PDFBOX
        rewrittenCopy != null -> Mode.NATIVE_REWRITTEN
        else -> Mode.NATIVE
    }

    /** True when the original file was not opened directly by the system renderer. */
    val usesFallback: Boolean get() = mode != Mode.NATIVE

    val pageCount: Int get() = native?.pageCount ?: box!!.numberOfPages

    fun openPage(index: Int): Page {
        native?.let { return Page(it.openPage(index), index) }
        val document = box!!
        require(index in 0 until document.numberOfPages) { "رقم الصفحة غير صالح" }
        val page = document.getPage(index)
        val crop = page.cropBox
        val rotated = (page.rotation % 180 + 180) % 180 == 90
        val w = (if (rotated) crop.height else crop.width).toInt().coerceAtLeast(1)
        val h = (if (rotated) crop.width else crop.height).toInt().coerceAtLeast(1)
        return Page(null, index, w, h)
    }

    inner class Page internal constructor(
        private val nativePage: PdfRenderer.Page?,
        private val index: Int,
        private val boxWidth: Int = 0,
        private val boxHeight: Int = 0,
    ) : AutoCloseable {
        val width: Int get() = nativePage?.width ?: boxWidth
        val height: Int get() = nativePage?.height ?: boxHeight

        /** Like PdfRenderer.Page.render(..., RENDER_MODE_FOR_DISPLAY); a null matrix fills the bitmap. */
        fun render(destination: Bitmap, transform: Matrix?) {
            nativePage?.let {
                it.render(destination, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return
            }
            if (!allowPreviewOnlyRendering) throw IOException(UNSUPPORTED_MESSAGE)
            val pageToBitmap = transform ?: Matrix().apply {
                setScale(destination.width.toFloat() / width, destination.height.toFloat() / height)
            }
            val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat()).also(pageToBitmap::mapRect)
            var scale = max(bounds.width() / width, bounds.height() / height).coerceAtLeast(0.05f)
            val maxPixels = max(destination.width.toLong() * destination.height * 2L, 1_000_000L)
            val pixels = width.toLong() * height * scale * scale
            if (pixels > maxPixels) scale *= sqrt(maxPixels.toDouble() / pixels).toFloat()
            val image = boxRenderer!!.renderImage(index, scale)
            try {
                Canvas(destination).apply {
                    concat(pageToBitmap)
                    scale(width.toFloat() / image.width, height.toFloat() / image.height)
                    drawBitmap(image, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                }
            } finally {
                image.recycle()
            }
        }

        override fun close() { nativePage?.close() }
    }

    override fun close() {
        runCatching { native?.close() }
        runCatching { box?.close() }
        rewrittenCopy?.delete()
    }

    companion object {
        const val UNSUPPORTED_MESSAGE = "تعذر قراءة صفحات هذا الملف بأمان على إصدار Android ده؛ لم يتم حفظ أي ملف ناقص"

        @Volatile private var appContext: Context? = null

        /** Test hooks: skip earlier attempts so every path is exercised on every API level. */
        @Volatile internal var skipNativeOriginalForTest: Boolean = false
        @Volatile internal var skipNativeRewriteForTest: Boolean = false

        fun init(context: Context) { appContext = context.applicationContext }

        /**
         * Opens [descriptor]. The caller keeps owning [descriptor] exactly as with PdfRenderer
         * (closing it afterwards stays safe). [context] prepares PDFBox and the private temp folder.
         * [allowPreviewOnlyRendering] = true only for on-screen previews (see class docs).
         */
        @Throws(IOException::class)
        fun open(
            descriptor: ParcelFileDescriptor,
            context: Context? = null,
            allowPreviewOnlyRendering: Boolean = false,
        ): SafePdfRenderer {
            val spare = descriptor.dup()
            try {
                if (skipNativeOriginalForTest) throw IOException("system renderer skipped (test)")
                return SafePdfRenderer(PdfRenderer(descriptor), null, null, allowPreviewOnlyRendering)
            } catch (nativeError: IOException) {
                val ctx = context?.applicationContext ?: appContext
                val document = try {
                    ctx?.let { if (!PDFBoxResourceLoader.isReady()) PDFBoxResourceLoader.init(it) }
                    ParcelFileDescriptor.AutoCloseInputStream(spare).use { input ->
                        input.channel.position(0)
                        PDDocument.load(input)
                    }
                } catch (fallbackError: Throwable) {
                    nativeError.addSuppressed(fallbackError)
                    throw nativeError
                }
                try {
                    if (document.numberOfPages <= 0) throw nativeError
                    if (!skipNativeRewriteForTest) {
                        rewriteForSystemRenderer(document, ctx, document.numberOfPages)?.let { (renderer, copy) ->
                            document.close()
                            return SafePdfRenderer(renderer, null, copy, allowPreviewOnlyRendering)
                        }
                    }
                    return SafePdfRenderer(null, document, null, allowPreviewOnlyRendering)
                } catch (error: Throwable) {
                    runCatching { document.close() }
                    throw error
                }
            } finally {
                runCatching { spare.close() }
            }
        }

        private fun rewriteForSystemRenderer(document: PDDocument, ctx: Context?, pages: Int): Pair<PdfRenderer, File>? {
            val folder = File(ctx?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: return null), "pdf_compat")
            if (!folder.isDirectory && !folder.mkdirs()) return null
            val copy = File.createTempFile("compat_", ".pdf", folder)
            var renderer: PdfRenderer? = null
            return try {
                document.save(copy)
                renderer = PdfRenderer(ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY))
                if (renderer.pageCount != pages) {
                    renderer.close()
                    copy.delete()
                    null
                } else renderer to copy
            } catch (_: Throwable) {
                runCatching { renderer?.close() }
                copy.delete()
                null
            }
        }
    }
}
