package com.abfilepro.app.core

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** P21: PDF pages stay readable on Android 8, where the system renderer rejects some valid files. */
@RunWith(AndroidJUnit4::class)
class PdfCompatibilityRegressionTest {
    private lateinit var context: Context
    private lateinit var dir: File
    private val outputs = mutableListOf<File>()

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SafePdfRenderer.init(context)
        dir = File(context.cacheDir, "p21_pdf_${UUID.randomUUID()}").apply { check(mkdirs()) }
    }

    @After fun tearDown() {
        SafePdfRenderer.skipNativeOriginalForTest = false
        SafePdfRenderer.skipNativeRewriteForTest = false
        outputs.forEach { it.delete() }
        dir.deleteRecursively()
    }

    /** Page 1 is 300x400 with a red top half, page 2 is 500x250 and white. */
    private fun androidPdf(name: String = "sample.pdf"): File {
        val doc = PdfDocument()
        try {
            doc.startPage(PdfDocument.PageInfo.Builder(300, 400, 1).create()).also { page ->
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawRect(0f, 0f, 300f, 200f, Paint().apply { color = Color.RED })
                doc.finishPage(page)
            }
            doc.startPage(PdfDocument.PageInfo.Builder(500, 250, 2).create()).also { page ->
                page.canvas.drawColor(Color.WHITE)
                doc.finishPage(page)
            }
            return File(dir, name).also { FileOutputStream(it).use(doc::writeTo) }
        } finally { doc.close() }
    }

    private fun <T> open(file: File, block: (SafePdfRenderer) -> T): T =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> SafePdfRenderer.open(fd, context).use(block) }

    private fun redTopWhiteBottom(bitmap: Bitmap) {
        val top = bitmap.getPixel(bitmap.width / 2, bitmap.height / 5)
        val bottom = bitmap.getPixel(bitmap.width / 2, bitmap.height * 17 / 20)
        assertTrue("top half should be red, was ${Integer.toHexString(top)}", Color.red(top) > 200 && Color.green(top) < 80 && Color.blue(top) < 80)
        assertTrue("bottom half should stay white, was ${Integer.toHexString(bottom)}", Color.red(bottom) > 200 && Color.green(bottom) > 200 && Color.blue(bottom) > 200)
    }

    private fun renderFirstPage(renderer: SafePdfRenderer): Bitmap {
        val bitmap = Bitmap.createBitmap(150, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        renderer.openPage(0).use { it.render(bitmap, null) }
        return bitmap
    }

    private fun compatLeftovers(): List<String> =
        File(context.cacheDir, "pdf_compat").listFiles()?.map { it.name }.orEmpty()

    @Test fun systemRendererStaysThePrimaryPathOnCurrentAndroid() {
        val file = androidPdf()
        open(file) { renderer ->
            assertEquals(2, renderer.pageCount)
            if (Build.VERSION.SDK_INT >= 28) assertEquals(SafePdfRenderer.Mode.NATIVE, renderer.mode)
        }
    }

    @Test fun androidWrittenPdfRendersItsRealContentOnThisAndroidVersion() {
        val file = androidPdf()
        open(file) { renderer ->
            assertTrue("pages must be rendered by the system renderer, mode=${renderer.mode}", renderer.mode != SafePdfRenderer.Mode.PDFBOX)
            val bitmap = renderFirstPage(renderer)
            try { redTopWhiteBottom(bitmap) } finally { bitmap.recycle() }
        }
    }

    @Test fun rewrittenCopyKeepsEveryPageAndIsRemovedAfterwards() {
        val file = androidPdf()
        val before = file.readBytes()
        SafePdfRenderer.skipNativeOriginalForTest = true
        open(file) { renderer ->
            assertEquals(SafePdfRenderer.Mode.NATIVE_REWRITTEN, renderer.mode)
            assertEquals(2, renderer.pageCount)
            renderer.openPage(0).use { assertEquals(300 to 400, it.width to it.height) }
            renderer.openPage(1).use { assertEquals(500 to 250, it.width to it.height) }
            val bitmap = renderFirstPage(renderer)
            try { redTopWhiteBottom(bitmap) } finally { bitmap.recycle() }
        }
        assertTrue("the user's PDF must not change", before.contentEquals(file.readBytes()))
        assertEquals(emptyList<String>(), compatLeftovers())
    }

    @Test fun pdfboxOnlyModeNeverProducesFilesFromItsRendering() = runBlocking {
        val file = androidPdf()
        SafePdfRenderer.skipNativeOriginalForTest = true
        SafePdfRenderer.skipNativeRewriteForTest = true
        open(file) { renderer ->
            assertEquals(SafePdfRenderer.Mode.PDFBOX, renderer.mode)
            assertEquals(2, renderer.pageCount)
            renderer.openPage(1).use { assertEquals(500 to 250, it.width to it.height) }
            val bitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
            try {
                renderer.openPage(0).use { it.render(bitmap, null) }
                fail("rendering for a saved file must be refused in PDFBox-only mode")
            } catch (_: IOException) {
            } finally { bitmap.recycle() }
        }
        try {
            outputs += PdfTools.pdfToLongImage(context, Uri.fromFile(file), preferredWidth = 600)
            fail("long image must not be produced from PDFBox-only rendering")
        } catch (_: IOException) {
        }
        CleanShareOutput.verifyPdf(file, 2)
        val preview = PdfViewerUtils.renderPage(file, 0, 300, forPreview = true)
        try { assertEquals(300, preview.width) } finally { preview.recycle() }
    }

    @Test fun brokenPdfIsStillRejectedWhenTheFallbackIsUsed() {
        val broken = File(dir, "broken.pdf").apply { writeText("%PDF-broken") }
        SafePdfRenderer.skipNativeOriginalForTest = true
        try {
            open(broken) { it.pageCount }
            fail("a broken PDF must not open")
        } catch (_: IOException) {
        }
        try {
            CleanShareOutput.verifyPdf(broken, 1)
            fail("a broken PDF must not verify")
        } catch (_: Exception) {
        }
    }

    @Test fun longImageIsProducedThroughTheRewrittenCopy() = runBlocking {
        val file = androidPdf()
        SafePdfRenderer.skipNativeOriginalForTest = true
        val result = PdfTools.pdfToLongImage(context, Uri.fromFile(file), preferredWidth = 600)
        outputs += result
        val image = requireNotNull(BitmapFactory.decodeFile(result.path))
        try {
            assertEquals(600, image.width)
            assertTrue(image.height > 800)
            val top = image.getPixel(300, 100)
            assertTrue("first page content must be in the long image, was ${Integer.toHexString(top)}", Color.red(top) > 200 && Color.green(top) < 80)
        } finally { image.recycle() }
    }
}
