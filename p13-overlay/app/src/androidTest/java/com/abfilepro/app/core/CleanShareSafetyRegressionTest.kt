package com.abfilepro.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

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

    private fun cacheFiles(): Set<String> = context.cacheDir.listFiles().orEmpty()
        .filter { it.name == "clean_share" || it.name.startsWith("clean_share_") }
        .flatMap { folder -> folder.walkTopDown().filter { it.isFile }.map { it.relativeTo(context.cacheDir).path }.toList() }.toSet()

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

    @Test fun failedWriterRemovesOnlyItsPartialDraft() = runBlocking {
        val existing = File(shareFolder, "keep.txt").apply { writeText("USER DATA") }
        val outcome = runCatching {
            CleanShareOutput.create({ shareFolder }, context.cacheDir) { draft ->
                draft.file("partial.png").writeText("partial bytes")
                throw IOException("controlled writer failure")
            }
        }
        assertTrue(outcome.exceptionOrNull() is IOException)
        assertEquals("USER DATA", existing.readText())
        assertEquals(listOf("keep.txt"), shareFolder.listFiles()!!.map { it.name })
    }

    @Test fun cancellationBeforePublicationRemovesPartialDraft() = runBlocking {
        val task = async {
            CleanShareOutput.create({ shareFolder }, context.cacheDir) { draft ->
                val staged = draft.file("ready.png").apply { writeText("ready bytes") }
                currentCoroutineContext().cancel()
                draft.publish(staged, "result", "png")
            }
        }
        assertTrue(runCatching { task.await() }.exceptionOrNull() is CancellationException)
        assertTrue(shareFolder.listFiles()!!.isEmpty())
    }

    @Test fun failureAfterPublicationRemovesUnchangedOutput() = runBlocking {
        val outcome = runCatching {
            CleanShareOutput.create({ shareFolder }, context.cacheDir) { draft ->
                draft.publish(draft.file("ready.txt").apply { writeText("ready bytes") }, "result", "txt")
                throw IOException("controlled handoff failure")
            }
        }
        assertTrue(outcome.exceptionOrNull() is IOException)
        assertTrue(shareFolder.listFiles()!!.isEmpty())
    }

    @Test fun failedHandoffPreservesOutputEditedAfterPublication() = runBlocking {
        var published: File? = null
        val outcome = runCatching {
            CleanShareOutput.create({ shareFolder }, context.cacheDir) { draft ->
                published = draft.publish(draft.file("ready.txt").apply { writeText("ready bytes") }, "result", "txt")
                published!!.writeText("USER EDIT AFTER PUBLICATION")
                throw IOException("controlled handoff failure")
            }
        }
        assertTrue(outcome.exceptionOrNull() is IOException)
        assertEquals("USER EDIT AFTER PUBLICATION", published!!.readText())
        assertEquals(1, shareFolder.listFiles()!!.size)
    }

    @Test fun failedCompressionKeepsValidCleanedPdf() = runBlocking {
        val result = CleanShareOutput.create({ shareFolder }, context.cacheDir) { draft ->
            val original = pdf(draft.directory, "cleaned.pdf")
            val candidate = draft.file("compressed.pdf")
            val selected = draft.preferSmallerPdf(original, candidate, 2) {
                candidate.writeText("partial compressed PDF")
                throw IOException("controlled compression failure")
            }
            assertFalse(selected.compressed)
            assertEquals(original, selected.file)
            draft.publish(selected.file, "result", "pdf")
        }
        assertEquals(2, pageCount(result))
        assertEquals(listOf(result.name), shareFolder.listFiles()!!.map { it.name })
    }

    @Test fun invalidCompressionCandidateKeepsValidCleanedPdf() = runBlocking {
        val result = CleanShareOutput.create({ shareFolder }, context.cacheDir) { draft ->
            val original = pdf(draft.directory, "cleaned.pdf")
            val candidate = draft.file("compressed.pdf")
            val selected = draft.preferSmallerPdf(original, candidate, 2) { candidate.writeText("%PDF-broken") }
            assertFalse(selected.compressed)
            draft.publish(selected.file, "result", "pdf")
        }
        assertEquals(2, pageCount(result))
    }

    @Test fun compressionCandidateMissingPagesIsRejected() = runBlocking {
        val result = CleanShareOutput.create({ shareFolder }, context.cacheDir) { draft ->
            val original = pdf(draft.directory, "cleaned.pdf")
            val candidate = draft.file("compressed.pdf")
            val selected = draft.preferSmallerPdf(original, candidate, 2) {
                pdf(draft.directory, candidate.name, listOf(true), 32)
            }
            assertFalse(selected.compressed)
            draft.publish(selected.file, "result", "pdf")
        }
        assertEquals(2, pageCount(result))
    }

    @Test fun jpegOutputStillPreservesTheOriginalPng() = runBlocking {
        val source = image(fixtures, "original.png")
        val before = source.readBytes()
        val result = CleanShareProcessor.clean(context, FileUtils.contentUri(context, source), plain.copy(compress = true))
        assertArrayEquals(before, source.readBytes())
        assertEquals("jpg", result.file.extension)
        assertTrue(FileUtils.verifySavedFile(context, result.file))
    }

    @Test fun smallerPdfCompressionIsAcceptedWithoutSharedFolderResidue() = runBlocking {
        val source = pdf(fixtures, "large.pdf", listOf(false, false), 384)
        val before = source.readBytes()
        val normal = CleanShareProcessor.clean(context, FileUtils.contentUri(context, source), plain.copy(cleanFileName = true))
        val normalSize = normal.file.length()
        val result = CleanShareProcessor.clean(context, FileUtils.contentUri(context, source), plain.copy(compress = true, cleanFileName = true))
        assertTrue("A smaller valid candidate should be used", result.compressed)
        assertTrue(result.file.length() < normalSize)
        assertEquals(2, pageCount(result.file))
        assertArrayEquals(before, source.readBytes())
        assertFalse("Clean Share published an intermediate to the general PDF folder", File(fixtures, "pdf").exists())
    }

    private class PausedDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun pending(): Boolean = queue.isNotEmpty()
        fun runNext() { checkNotNull(queue.poll()).run() }
        fun drain() { while (pending()) runNext() }
    }

    private suspend fun cancelDuringReturnHandoff(replaceWithIdenticalBytes: Boolean) = coroutineScope {
        val caller = PausedDispatcher()
        val ready = CompletableDeferred<File>()
        val operation = async(caller) {
            CleanShareOutput.create({ shareFolder }, context.cacheDir) { draft ->
                val file = draft.publish(draft.file("ready.txt").apply { writeText("published bytes") }, "result", "txt")
                ready.complete(file)
                file
            }
        }
        caller.runNext()
        val output = withTimeout(10_000) { ready.await() }
        withTimeout(10_000) { while (!caller.pending()) delay(5) }
        assertTrue("The result must be complete before cancelling its queued return", output.isFile)
        if (replaceWithIdenticalBytes) {
            val bytes = output.readBytes()
            val modified = output.lastModified()
            val identity = Files.readAttributes(output.toPath(), BasicFileAttributes::class.java).fileKey()
            val replacement = File(fixtures, "replacement.txt").apply { writeBytes(bytes) }
            check(replacement.setLastModified(modified))
            Files.move(replacement.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
            assertEquals(modified, output.lastModified())
            assertNotEquals("Fixture must replace file identity", identity,
                Files.readAttributes(output.toPath(), BasicFileAttributes::class.java).fileKey())
        }
        operation.cancel()
        withTimeout(10_000) {
            while (!operation.isCompleted) { caller.drain(); delay(5) }
        }
        assertTrue(runCatching { operation.await() }.exceptionOrNull() is CancellationException)
        if (replaceWithIdenticalBytes) {
            assertEquals("published bytes", output.readText())
            assertEquals(listOf(output.name), shareFolder.listFiles()!!.map { it.name })
        } else assertTrue("Cancelled handoff left its unchanged output", shareFolder.listFiles()!!.isEmpty())
    }

    @Test fun cancelledReturnHandoffRemovesUnchangedPublication() = runBlocking {
        cancelDuringReturnHandoff(false)
    }

    @Test fun cancelledReturnHandoffPreservesIdenticalReplacement() = runBlocking {
        cancelDuringReturnHandoff(true)
    }

    @Test fun inputAndOcrDraftsRemainInAppPrivateCache() = runBlocking {
        val before = cacheFiles()
        CleanShareOutput.create({ shareFolder }, context.cacheDir) { draft ->
            val sensitive = draft.file("source.pdf").apply { writeText("UNREDACTED INPUT FIXTURE") }
            assertTrue(draft.directory.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
            assertEquals("UNREDACTED INPUT FIXTURE", sensitive.readText())
            assertTrue("An input draft reached the user's output folder", shareFolder.listFiles()!!.isEmpty())
        }
        assertEquals(before, cacheFiles())
    }

    @Test fun qrRedactionStillWorksWithPrivateOcrDrafts() = runBlocking {
        val bitmap = QrGenerator.create("https://example.com/AB-P13-SAFETY", 600)
        val source = File(fixtures, "qr.png")
        try { FileOutputStream(source).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        val before = source.readBytes()
        val cacheBefore = cacheFiles()
        val result = CleanShareProcessor.clean(context, FileUtils.contentUri(context, source), plain.copy(redactSensitive = true))
        assertTrue("Existing QR detection/redaction stopped working", result.qrItems >= 1 && result.totalRedactions >= 1)
        val cleaned = checkNotNull(BitmapFactory.decodeFile(result.file.path))
        val scanner = BarcodeScanning.getClient()
        try { assertTrue(scanner.process(InputImage.fromBitmap(cleaned, 0)).await().isEmpty()) }
        finally { scanner.close(); cleaned.recycle() }
        assertArrayEquals(before, source.readBytes())
        assertEquals(cacheBefore, cacheFiles())
    }
}
