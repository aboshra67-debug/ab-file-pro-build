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
        SafePdfRenderer.forceFallbackForTest = false
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

    @Test fun systemRendererStaysThePrimaryPathOnCurrentAndroid() {
        val file = androidPdf()
        open(file) { renderer ->
            assertEquals(2, renderer.pageCount)
            if (Build.VERSION.SDK_INT >= 28) assertFalse(renderer.usesFallback)
        }
    }

    @Test fun fallbackReportsEveryPageWithItsSize() {
        val file = androidPdf()
        SafePdfRenderer.forceFallbackForTest = true
        open(file) { renderer ->
            assertTrue(renderer.usesFallback)
            assertEquals(2, renderer.pageCount)
            renderer.openPage(0).use { assertEquals(300 to 400, it.width to it.height) }
            renderer.openPage(1).use { assertEquals(500 to 250, it.width to it.height) }
        }
    }

    @Test fun fallbackRendersThePageContentIntoTheRequestedBitmap() {
        val file = androidPdf()
        SafePdfRenderer.forceFallbackForTest = true
        val bitmap = Bitmap.createBitmap(150, 200, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            open(file) { renderer -> renderer.openPage(0).use { it.render(bitmap, null) } }
            val top = bitmap.getPixel(75, 40)
            val bottom = bitmap.getPixel(75, 170)
            assertTrue("top half should be red, was ${Integer.toHexString(top)}", Color.red(top) > 200 && Color.green(top) < 80 && Color.blue(top) < 80)
            assertTrue("bottom half should stay white, was ${Integer.toHexString(bottom)}", Color.red(bottom) > 200 && Color.green(bottom) > 200 && Color.blue(bottom) > 200)
        } finally { bitmap.recycle() }
    }

    @Test fun brokenPdfIsStillRejectedWhenTheFallbackIsUsed() {
        val broken = File(dir, "broken.pdf").apply { writeText("%PDF-broken") }
        SafePdfRenderer.forceFallbackForTest = true
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

    @Test fun longImageIsProducedThroughTheFallback() = runBlocking {
        val file = androidPdf()
        SafePdfRenderer.forceFallbackForTest = true
        val result = PdfTools.pdfToLongImage(context, Uri.fromFile(file), preferredWidth = 600)
        outputs += result
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(result.path, bounds)
        assertEquals(600, bounds.outWidth)
        assertTrue(bounds.outHeight > 800)
    }
}
