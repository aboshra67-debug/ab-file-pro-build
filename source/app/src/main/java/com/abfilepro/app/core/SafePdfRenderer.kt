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
import java.io.IOException
import kotlin.math.max
import kotlin.math.sqrt

/**
 * P21 (Android 8 compatibility): the system PdfRenderer on Android 8.0/8.1 rejects some valid PDFs
 * (including ones written by Android's own PdfDocument) with "file not in PDF format or corrupted".
 * This wrapper keeps the system renderer as the first and normal path, and only when it refuses the
 * file opens the same PDF with PDFBox (already bundled) so the page count and the rendered page are
 * still available. Same call shape as PdfRenderer: pageCount, openPage(i).use { width/height/render }.
 */
class SafePdfRenderer private constructor(
    private val native: PdfRenderer?,
    private val box: PDDocument?,
) : AutoCloseable {
    private val boxRenderer: PDFRenderer? = box?.let(::PDFRenderer)

    val pageCount: Int get() = native?.pageCount ?: box!!.numberOfPages

    /** True when the PDF was opened through the PDFBox fallback. */
    val usesFallback: Boolean get() = native == null

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
            val pageToBitmap = transform ?: Matrix().apply {
                setScale(destination.width.toFloat() / width, destination.height.toFloat() / height)
            }
            val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat()).also(pageToBitmap::mapRect)
            var scale = max(bounds.width() / width, bounds.height() / height).coerceAtLeast(0.05f)
            // Never rasterize far beyond what the destination can show (memory safety).
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
        native?.close()
        box?.close()
    }

    companion object {
        @Volatile private var appContext: Context? = null

        /** Test hook: skip the system renderer so the PDFBox path is exercised on every API level. */
        @Volatile internal var forceFallbackForTest: Boolean = false

        fun init(context: Context) { appContext = context.applicationContext }

        /**
         * Opens [descriptor]. The caller keeps owning [descriptor] exactly as with PdfRenderer
         * (closing it afterwards stays safe). [context] is only used to prepare PDFBox resources.
         */
        @Throws(IOException::class)
        fun open(descriptor: ParcelFileDescriptor, context: Context? = null): SafePdfRenderer {
            val spare = descriptor.dup()
            try {
                if (forceFallbackForTest) throw IOException("forced PDFBox fallback (test)")
                return SafePdfRenderer(PdfRenderer(descriptor), null)
            } catch (nativeError: IOException) {
                val document = try {
                    (context?.applicationContext ?: appContext)?.let { if (!PDFBoxResourceLoader.isReady()) PDFBoxResourceLoader.init(it) }
                    ParcelFileDescriptor.AutoCloseInputStream(spare).use { input ->
                        input.channel.position(0)
                        PDDocument.load(input)
                    }
                } catch (fallbackError: Throwable) {
                    nativeError.addSuppressed(fallbackError)
                    throw nativeError
                }
                if (document.numberOfPages <= 0) {
                    document.close()
                    throw nativeError
                }
                return SafePdfRenderer(null, document)
            } finally {
                runCatching { spare.close() }
            }
        }
    }
}
