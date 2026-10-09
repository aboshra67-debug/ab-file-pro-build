package com.abfilepro.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Exercises the public processor with real PNGs, PDFs, and Android storage. */
@RunWith(AndroidJUnit4::class)
class CleanShareSafetyRegressionTest {
    private lateinit var context: Context
    private lateinit var fixtures: File
    private lateinit var shareFolder: File
    private var previousSaveRelative: String? = null
    private val plain = CleanShareProcessor.Options(false, true, false, false, false)

    @Before fun createFixtures() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("ab_file_pro_files", 0)
        previousSaveRelative = prefs.getString("default_save_relative", null)
        fixtures = File(FileUtils.rootDir(context), "P13_TEST_${UUID.randomUUID()}")
        check(fixtures.mkdirs())
        prefs.edit().putString("default_save_relative", fixtures.name).commit()
        shareFolder = FileUtils.outputDir(context, "AB Clean Share")
        check(shareFolder.isDirectory)
    }

    @After fun removeOnlyFixtures() {
        val edit = context.getSharedPreferences("ab_file_pro_files", 0).edit()
        if (previousSaveRelative == null) edit.remove("default_save_relative")
        else edit.putString("default_save_relative", previousSaveRelative)
        edit.commit()
        fixtures.deleteRecursively()
    }

    private fun image(parent: File, name: String, seed: Int = 1): File {
        val bitmap = Bitmap.createBitmap(96, 80, Bitmap.Config.ARGB_8888)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            bitmap.setPixel(x, y, Color.rgb((x * 29 + seed) % 256, (y * 31 + seed) % 256, (x * y + seed * 17) % 256))
        }
        return File(parent, name).also { out ->
            try { FileOutputStream(out).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
        }
    }

    private fun pdf(parent: File, name: String, blank: List<Boolean> = listOf(false, false), size: Int = 180): File {
        val doc = PdfDocument()
        try {
            blank.forEachIndexed { index, empty ->
                val page = doc.startPage(PdfDocument.PageInfo.Builder(size, size, index + 1).create())
                page.canvas.drawColor(Color.WHITE)
                if (!empty) {
                    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    for (y in 0 until size) for (x in 0 until size) {
                        bitmap.setPixel(x, y, Color.rgb((x * 47 + index) % 256, (y * 67 + index) % 256, (x * y * 11 + index * 19) % 256))
                    }
                    page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                    bitmap.recycle()
                }
                doc.finishPage(page)
            }
            return File(parent, name).also { FileOutputStream(it).use(doc::writeTo) }
        } finally { doc.close() }
    }

    private fun pageCount(file: File): Int = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { it.pageCount }
    }

    private fun cacheFiles(): Set<String> = File(context.cacheDir, "clean_share").walkTopDown()
        .filter { it.isFile }.map { it.relativeTo(context.cacheDir).path }.toSet()

    @Test fun imageChosenFromShareFolderNeverReplacesItsSource() = runBlocking {
        val source = image(shareFolder, "original.png")
        val before = source.readBytes()
        val result = CleanShareProcessor.clean(context, FileUtils.contentUri(context, source), plain)
        assertNotEquals("Clean Share wrote to its source path", source.canonicalPath, result.file.canonicalPath)
        assertArrayEquals(before, source.readBytes())
        assertTrue(FileUtils.verifySavedFile(context, result.file))
    }

    @Test fun pdfChosenFromShareFolderNeverReplacesItsSource() = runBlocking {
        val source = pdf(shareFolder, "original.pdf")
        val before = source.readBytes()
        val result = CleanShareProcessor.clean(context, FileUtils.contentUri(context, source), plain)
        assertNotEquals("Clean Share wrote to its source path", source.canonicalPath, result.file.canonicalPath)
        assertArrayEquals(before, source.readBytes())
        assertEquals(2, pageCount(result.file))
    }

    @Test fun existingImageWithSameNameIsPreserved() = runBlocking {
        val source = image(fixtures, "same.png")
        val existing = File(shareFolder, source.name).apply { writeText("USER FILE MUST STAY") }
        val result = CleanShareProcessor.clean(context, FileUtils.contentUri(context, source), plain)
        assertArrayEquals("USER FILE MUST STAY".toByteArray(), existing.readBytes())
        assertNotEquals(existing.canonicalPath, result.file.canonicalPath)
    }

    @Test fun existingPdfWithSameNameIsPreserved() = runBlocking {
        val source = pdf(fixtures, "same.pdf")
        val existing = File(shareFolder, source.name).apply { writeText("USER PDF MUST STAY") }
        val result = CleanShareProcessor.clean(context, FileUtils.contentUri(context, source), plain)
        assertArrayEquals("USER PDF MUST STAY".toByteArray(), existing.readBytes())
        assertNotEquals(existing.canonicalPath, result.file.canonicalPath)
    }

    @Test fun parallelImagesWithSameNameHaveIndependentOutputs() = runBlocking {
        val left = File(fixtures, "left").apply { mkdir() }
        val right = File(fixtures, "right").apply { mkdir() }
        val a = image(left, "same.png", 1)
        val b = image(right, "same.png", 97)
        val originalA = a.readBytes(); val originalB = b.readBytes()
        val outputs = coroutineScope {
            listOf(a, b).map { source -> async { CleanShareProcessor.clean(context, FileUtils.contentUri(context, source), plain) } }.map { it.await() }
        }
        assertEquals("Parallel operations shared a destination", 2, outputs.map { it.file.canonicalPath }.toSet().size)
        assertFalse(outputs[0].file.readBytes().contentEquals(outputs[1].file.readBytes()))
        assertArrayEquals(originalA, a.readBytes()); assertArrayEquals(originalB, b.readBytes())
    }

    @Test fun malformedPdfLeavesNoAttemptFiles() = runBlocking {
        val source = File(fixtures, "broken.pdf").apply { writeText("not a PDF") }
        val before = cacheFiles()
        assertTrue(runCatching { CleanShareProcessor.clean(context, FileUtils.contentUri(context, source), plain) }.isFailure)
        assertEquals("A failed PDF left its source copy in cache", before, cacheFiles())
        assertEquals("not a PDF", source.readText())
        assertTrue(shareFolder.listFiles()!!.isEmpty())
    }

    @Test fun largerCompressionCandidateIsNotReportedAsCompressed() = runBlocking {
        val source = pdf(fixtures, "small.pdf", listOf(false), 32)
        val input = FileUtils.contentUri(context, source)
        val normal = CleanShareProcessor.clean(context, input, plain.copy(cleanFileName = true))
        val requested = CleanShareProcessor.clean(context, input, plain.copy(compress = true, cleanFileName = true))
        assertTrue("Compression was reported even though the file grew: ${normal.file.length()} -> ${requested.file.length()}",
            !requested.compressed || requested.file.length() < normal.file.length())
        assertEquals(1, pageCount(requested.file))
    }

    @Test fun normalImageProducesReadableCopyWithoutChangingSource() = runBlocking {
        val source = image(fixtures, "outside.png")
        val before = source.readBytes()
        val result = CleanShareProcessor.clean(context, FileUtils.contentUri(context, source), plain)
        assertArrayEquals(before, source.readBytes())
        assertTrue(result.file.isFile && result.file.length() > 0)
        assertTrue(FileUtils.verifySavedFile(context, result.file))
        assertFalse(result.isPdf)
    }

    @Test fun normalPdfPreservesAllRequestedPages() = runBlocking {
        val source = pdf(fixtures, "outside.pdf", listOf(false, true, false))
        val before = source.readBytes()
        val result = CleanShareProcessor.clean(context, FileUtils.contentUri(context, source), plain)
        assertArrayEquals(before, source.readBytes())
        assertEquals(3, pageCount(result.file))
        assertEquals(0, result.blankPagesRemoved)
        assertTrue(result.isPdf)
    }

    @Test fun blankPageRemovalStillKeepsTheContentPage() = runBlocking {
        val source = pdf(fixtures, "with_blank.pdf", listOf(true, false))
        val result = CleanShareProcessor.clean(context, FileUtils.contentUri(context, source), plain.copy(removeBlankPages = true))
        assertEquals(1, result.blankPagesRemoved)
        assertEquals(1, pageCount(result.file))
        assertEquals(2, pageCount(source))
    }

    @Test fun allBlankPagesAreRejectedWithoutChangingSource() = runBlocking {
        val source = pdf(fixtures, "all_blank.pdf", listOf(true, true))
        val before = source.readBytes()
        assertTrue(runCatching { CleanShareProcessor.clean(context, FileUtils.contentUri(context, source), plain.copy(removeBlankPages = true)) }.isFailure)
        assertArrayEquals(before, source.readBytes())
        assertTrue(shareFolder.listFiles()!!.isEmpty())
    }
}
