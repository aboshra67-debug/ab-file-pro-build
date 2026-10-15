package com.abfilepro.app.core

import android.content.Context
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.ParcelFileDescriptor
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.FileOutputStream

/**
 * P21: on the Android 8 test images the system PdfRenderer refuses every PDF, even the rewritten
 * copy. The app then refuses file-producing PDF work with a clear message (tested in
 * PdfCompatibilityRegressionTest). Tests that need real page rendering are skipped there only, and
 * the reason is recorded; on Android 9+ the system renderer is required, so they always run.
 */
object PdfTestSupport {
    @Volatile private var cached: Boolean? = null

    fun systemPdfRenderingAvailable(context: Context): Boolean = cached ?: run {
        val dir = File(context.cacheDir, "pdf_probe").apply { mkdirs() }
        val file = File(dir, "probe.pdf")
        val doc = PdfDocument()
        try {
            doc.startPage(PdfDocument.PageInfo.Builder(100, 100, 1).create()).also { page ->
                page.canvas.drawColor(Color.WHITE)
                doc.finishPage(page)
            }
            FileOutputStream(file).use(doc::writeTo)
        } finally { doc.close() }
        val available = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                SafePdfRenderer.open(fd, context).use { it.mode != SafePdfRenderer.Mode.PDFBOX }
            }
        } finally { dir.deleteRecursively() }
        if (Build.VERSION.SDK_INT >= 28) assertTrue("the system PDF renderer must work on Android 9+", available)
        cached = available
        available
    }

    fun assumeSystemPdfRendering(context: Context) {
        assumeTrue("System PdfRenderer cannot open PDFs on API ${Build.VERSION.SDK_INT}; the app refuses safely instead",
            systemPdfRenderingAvailable(context))
    }
}
