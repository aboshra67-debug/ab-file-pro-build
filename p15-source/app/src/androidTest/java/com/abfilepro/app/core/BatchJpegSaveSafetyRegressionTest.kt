package com.abfilepro.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Comparator
import java.util.UUID

/**
 * P15 SCAN-04: Batch → JPG save must be all-or-nothing for one attempt. These tests drive the real
 * [ScanProcessor.saveBatchJpegs] / [ScanProcessor.saveSinglePageJpeg] against real files inside the
 * app's save root; only the deliberate failure or cancellation point is injected.
 */
@RunWith(AndroidJUnit4::class)
class BatchJpegSaveSafetyRegressionTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var pagesDir: File
    private lateinit var destination: File

    @Before fun fixtures() {
        context = ApplicationProvider.getApplicationContext()
        root = File(FileUtils.rootDir(context), "P15_TEST_${UUID.randomUUID()}").apply { check(mkdirs()) }
        pagesDir = File(root, "pages").apply { check(mkdirs()) }
        destination = File(root, "out").apply { check(mkdirs()) }
    }

    @After fun cleanupFixturesOnly() {
        if (root.exists()) Files.walk(root.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun sha(file: File): ByteArray = MessageDigest.getInstance("SHA-256").digest(file.readBytes())

    private fun page(id: Long, color: Int): ScanDraftPage {
        val file = File(pagesDir, "page_$id.png")
        val bitmap = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        return ScanDraftPage(id, file)
    }

    private fun unreadablePage(id: Long): ScanDraftPage =
        ScanDraftPage(id, File(pagesDir, "broken_$id.png").apply { writeText("NONEMPTY_INVALID_IMAGE") })

    private fun preExistingImage(name: String): File {
        val file = File(destination, name)
        val bitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.MAGENTA)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        } finally { bitmap.recycle() }
        return file
    }

    private fun destinationNames(): Set<String> = destination.listFiles().orEmpty().map { it.name }.toSet()

    private fun assertReadableJpeg(file: File) {
        val decoded = BitmapFactory.decodeFile(file.path)
        try { assertTrue("Saved page is not a readable image: ${file.name}", decoded != null && decoded.width > 0) }
        finally { decoded?.recycle() }
    }

    private suspend fun save(pages: List<ScanDraftPage>, base: String = "Batch") =
        ScanProcessor.saveBatchJpegs(context, pages, base, quality = 85, destinationDir = destination)

    @Test fun successfulBatchPublishesEveryPageInOrder() = runBlocking {
        val pages = listOf(page(1, Color.RED), page(2, Color.GREEN), page(3, Color.BLUE))
        val saved = save(pages)
        assertEquals(3, saved.size)
        saved.forEachIndexed { index, file ->
            assertTrue(file.name, file.name.startsWith("Batch_${index + 1}_") && file.name.endsWith(".jpg"))
            assertEquals(destination.canonicalFile, file.parentFile!!.canonicalFile)
            assertReadableJpeg(file)
        }
        assertEquals(saved.map { it.name }.toSet(), destinationNames())
    }

    @Test fun secondPageFailureRemovesOnlyThisAttemptsImages() = runBlocking {
        val existing = preExistingImage("Batch_1_existing.jpg")
        val existingSha = sha(existing)
        val pages = listOf(page(1, Color.RED), unreadablePage(2), page(3, Color.BLUE))
        val reviewShas = pages.map { sha(it.file) }
        val result = runCatching { save(pages) }
        assertTrue("A Batch with an unreadable page was reported as saved", result.isFailure)
        assertEquals("Partial images or .part files were left in the save folder", setOf(existing.name), destinationNames())
        assertArrayEquals("A file that existed before the attempt was changed", existingSha, sha(existing))
        pages.forEachIndexed { index, reviewPage ->
            assertTrue("Review page ${index + 1} was removed", reviewPage.file.isFile)
            assertArrayEquals("Review page ${index + 1} was modified", reviewShas[index], sha(reviewPage.file))
        }
    }

    @Test fun retryAfterFailureDoesNotDuplicatePages() = runBlocking {
        val first = page(1, Color.RED)
        val third = page(3, Color.BLUE)
        assertTrue(runCatching { save(listOf(first, unreadablePage(2), third)) }.isFailure)
        assertTrue("Failed attempt left images behind", destinationNames().isEmpty())
        val retried = save(listOf(first, page(2, Color.GREEN), third))
        assertEquals(3, retried.size)
        assertEquals("Retry produced duplicate or missing pages", retried.map { it.name }.toSet(), destinationNames())
        retried.forEach(::assertReadableJpeg)
    }

    @Test fun cancellationDuringSaveRemovesPublishedImages() = runBlocking {
        val existing = preExistingImage("keep.jpg")
        val pages = listOf(page(1, Color.RED), page(2, Color.GREEN), page(3, Color.BLUE))
        val firstPublished = CompletableDeferred<File>()
        val job = CoroutineScope(Dispatchers.IO).async {
            ScanProcessor.saveBatchJpegs(context, pages, "Batch", 85, destination) { index, scanPage, name ->
                if (index == 0) {
                    ScanProcessor.saveSinglePageJpeg(context, scanPage, name, 85, destination).also { firstPublished.complete(it) }
                } else {
                    awaitCancellation()
                }
            }
        }
        val published = withTimeout(30_000) { firstPublished.await() }
        assertTrue("Page 1 was not published before cancelling", published.isFile)
        job.cancel()
        val outcome = runCatching { job.await() }
        assertTrue("Cancelled save completed", outcome.exceptionOrNull() is CancellationException)
        assertEquals("Cancelled save left images behind", setOf(existing.name), destinationNames())
    }

    @Test fun imageChangedAfterPublishIsKeptOnRollback() = runBlocking {
        val pages = listOf(page(1, Color.RED), page(2, Color.GREEN))
        var changed: File? = null
        val result = runCatching {
            ScanProcessor.saveBatchJpegs(context, pages, "Batch", 85, destination) { index, scanPage, name ->
                if (index == 0) {
                    ScanProcessor.saveSinglePageJpeg(context, scanPage, name, 85, destination).also { changed = it }
                } else {
                    // Page 1 is already published and recorded; someone else edits it before page 2 fails.
                    requireNotNull(changed).appendBytes(ByteArray(64) { 7 })
                    error("deliberate_page_2_failure")
                }
            }
        }
        assertEquals("deliberate_page_2_failure", result.exceptionOrNull()?.message)
        val edited = requireNotNull(changed)
        assertTrue("Rollback deleted a file that was changed after this attempt wrote it", edited.isFile)
        assertEquals(setOf(edited.name), destinationNames())
    }
}
