package com.abfilepro.app.core

import android.content.Context
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class MaintenanceSafetyRegressionTest {
    private lateinit var context: Context
    private lateinit var directRoot: File
    private lateinit var workspaceRoot: File
    private val outputs = mutableListOf<File>()
    private val authority = "com.abfilepro.app.maintenance.test.documents"

    @Before fun fixtures() {
        context = ApplicationProvider.getApplicationContext()
        directRoot = File(context.getExternalFilesDir(null), "p14_test_${UUID.randomUUID()}").apply { check(mkdirs()) }
        workspaceRoot = File(FileUtils.rootDir(context), "P14_TEST_${UUID.randomUUID()}").apply { check(mkdirs()) }
        FileUndoStore.clear(context)
    }

    @After fun cleanupFixturesOnly() {
        outputs.forEach { it.delete() }
        for (root in listOf(directRoot, workspaceRoot)) if (root.exists()) Files.walk(root.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
        FileUndoStore.clear(context)
    }

    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    private fun text(parent: File, name: String, value: String) = File(parent, name).apply { writeText(value) }
    private fun image(name: String, color: Int = Color.RED): File {
        val file = File(workspaceRoot, name)
        val bitmap = Bitmap.createBitmap(160, 240, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        return file
    }

    private fun pdf(name: String, colors: List<Int>): File {
        val file = File(workspaceRoot, name)
        val document = PdfDocument()
        try {
            colors.forEachIndexed { index, color ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(100, 150, index + 1).create())
                page.canvas.drawColor(color)
                document.finishPage(page)
            }
            file.outputStream().use { document.writeTo(it) }
        } finally { document.close() }
        return file
    }

    @Test fun arabicDecimalAmountAndTaxStayComplete() {
        val result = ReceiptInvoiceProcessor.parse(context, "ar", "فاتورة\nالإجمالي ١٠٠٫٥٠\nالضريبة ١٤٫٠٧")
        assertEquals("100.50", result.total)
        assertEquals("14.07", result.tax)
    }

    @Test fun arabicThousandsAndDecimalStayComplete() {
        assertEquals("1,000.50", ReceiptInvoiceProcessor.parse(context, "ar", "الإجمالي ١٬٠٠٠٫٥٠").total)
    }

    @Test fun persianDigitsAndArabicSeparatorsStayComplete() {
        assertEquals("2,345.67", ReceiptInvoiceProcessor.parse(context, "ar", "الإجمالي ۲٬۳۴۵٫۶۷").total)
    }

    @Test fun westernAmountsRemainSupported() {
        assertEquals("1,000.50", ReceiptInvoiceProcessor.parse(context, "en", "Grand total 1,000.50").total)
        assertEquals("100.50", ReceiptInvoiceProcessor.parse(context, "en", "Total 100.50").total)
    }

    @Test fun businessNameSurvivesMissingEmail() {
        assertEquals("Ahmed Ali", BusinessCardProcessor.parse("Ahmed Ali\nExample Studio\nwww.example.com\n+20 100 123 4567").name)
    }

    @Test fun businessNameSurvivesMissingWebsite() {
        assertEquals("Ahmed Ali", BusinessCardProcessor.parse("Ahmed Ali\nExample Studio\nahmed@example.com\n+20 100 123 4567").name)
    }

    @Test fun businessNameSurvivesMissingPhone() {
        assertEquals("Ahmed Ali", BusinessCardProcessor.parse("Ahmed Ali\nExample Studio\nahmed@example.com\nwww.example.com").name)
    }

    @Test fun arabicNameSurvivesAllContactFieldsMissing() {
        val result = BusinessCardProcessor.parse("أحمد علي\nشركة النور")
        assertEquals("أحمد علي", result.name)
        assertEquals("شركة النور", result.company)
    }

    @Test fun fullBusinessContactFieldsStillParse() {
        val result = BusinessCardProcessor.parse("Ahmed Ali\nExample Studio\nahmed@example.com\nwww.example.com\n+20 100 123 4567")
        assertEquals("Ahmed Ali", result.name)
        assertEquals("ahmed@example.com", result.email)
        assertEquals("www.example.com", result.website)
        assertTrue(result.phone.isNotBlank())
    }

    @Test fun singlePdfPageRendersAsLongImage() = runBlocking {
        val source = pdf("one.pdf", listOf(Color.RED))
        val original = sha(source)
        val result = PdfTools.pdfToLongImage(context, Uri.fromFile(source), 480).also { outputs += it }
        val bitmap = requireNotNull(BitmapFactory.decodeFile(result.path))
        try {
            assertEquals(480, bitmap.width)
            assertEquals(720, bitmap.height)
            assertTrue(Color.red(bitmap.getPixel(240, 360)) > 230)
        } finally { bitmap.recycle() }
        assertEquals(original, sha(source))
    }

    @Test fun multiplePdfPagesRenderInOrderAsLongImage() = runBlocking {
        val source = pdf("two.pdf", listOf(Color.RED, Color.BLUE))
        val original = sha(source)
        val result = PdfTools.pdfToLongImage(context, Uri.fromFile(source), 480).also { outputs += it }
        val bitmap = requireNotNull(BitmapFactory.decodeFile(result.path))
        try {
            assertEquals(480, bitmap.width)
            assertEquals(1448, bitmap.height)
            assertTrue(Color.red(bitmap.getPixel(240, 360)) > 230)
            assertTrue(Color.blue(bitmap.getPixel(240, 1088)) > 230)
        } finally { bitmap.recycle() }
        assertEquals(original, sha(source))
    }

    @Test fun unreadableImageFailsEntirePdfWithoutPublishingMissingPages() = runBlocking {
        val good = image("good.png")
        val bad = text(workspaceRoot, "bad.png", "not an image")
        val folder = FileUtils.outputDir(context, "pdf")
        val before = folder.listFiles().orEmpty().toSet()
        val result = runCatching { PdfTools.imagesToPdf(context, listOf(Uri.fromFile(good), Uri.fromFile(bad))) }
        val created = folder.listFiles().orEmpty().toSet() - before
        outputs += created
        assertTrue("A selected page was silently omitted", result.isFailure)
        assertTrue("A partial PDF was published", created.isEmpty())
        assertEquals("not an image", bad.readText())
    }

    @Test fun validImagesProduceEverySelectedPdfPage() = runBlocking {
        val first = image("first.png", Color.RED)
        val second = image("second.png", Color.BLUE)
        val result = PdfTools.imagesToPdf(context, listOf(Uri.fromFile(first), Uri.fromFile(second))).also { outputs += it }
        ParcelFileDescriptor.open(result, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer -> assertEquals(2, renderer.pageCount) }
        }
        assertTrue(first.isFile && second.isFile)
    }

    private class ControlledFile(
        original: File,
        private val failedListing: Boolean = false,
        private val failedRename: Boolean = false,
        private val failedDelete: Boolean = false,
        private val onModified: (() -> Unit)? = null
    ) : File(original.path) {
        override fun listFiles(): Array<File>? = if (failedListing) null else super.listFiles()
        override fun renameTo(dest: File): Boolean = if (failedRename) false else super.renameTo(dest)
        override fun delete(): Boolean = if (failedDelete) false else super.delete()
        override fun lastModified(): Long { onModified?.invoke(); return super.lastModified() }
    }

    @Test fun failedZipEnumerationCannotSucceedWithEmptyDirectory() = runBlocking {
        val source = File(directRoot, "source").apply { check(mkdir()) }
        val seed = text(source, "keep.txt", "KEEP")
        val result = runCatching {
            DeviceStorageManager.createZip(listOf(DeviceStorageManager.Entry(ControlledFile(source, failedListing = true))), directRoot, "archive.zip")
        }
        assertTrue("Unreadable directory became a successful empty archive", result.isFailure)
        assertEquals("KEEP", seed.readText())
        assertEquals(listOf("source"), directRoot.listFiles()!!.map { it.name }.sorted())
    }

    @Test fun corruptZipRemovesPartialFileAndImplicitDirectories() = runBlocking {
        val data = ByteArray(300_000) { (it % 251).toByte() }
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            val entry = ZipEntry("implicit/nested/file.bin").apply {
                method = ZipEntry.STORED
                size = data.size.toLong(); compressedSize = size
                crc = CRC32().apply { update(data) }.value
            }
            zip.putNextEntry(entry); zip.write(data); zip.closeEntry()
        }
        val corrupt = bytes.toByteArray()
        val offset = 30 + "implicit/nested/file.bin".toByteArray().size
        corrupt[offset + 200_000] = (corrupt[offset + 200_000].toInt() xor 1).toByte()
        val archive = File(directRoot, "bad.zip").apply { writeBytes(corrupt) }
        val destination = File(directRoot, "destination").apply { check(mkdir()) }
        val existing = text(destination, "keep.txt", "EXISTING")
        assertTrue(runCatching { DeviceStorageManager.extractZip(DeviceStorageManager.Entry(archive), destination, false) }.isFailure)
        assertEquals("EXISTING", existing.readText())
        assertEquals("Partial extraction and implicit folders survived", listOf("keep.txt"), destination.listFiles()!!.map { it.name }.sorted())
    }

    @Test fun renameUndoNeverMovesAReplacementFile() = runBlocking {
        val old = File(workspaceRoot, "old.txt")
        val current = text(workspaceRoot, "renamed.txt", "ORIGINAL")
        FileUndoStore.recordRename(context, old, current)
        check(current.delete())
        current.writeText("REPLACEMENT_FILE")
        assertTrue(FileUndoStore.undo(context).isFailure)
        assertEquals("REPLACEMENT_FILE", current.readText())
        assertFalse(old.exists())
        assertTrue("Conflicted undo was discarded", FileUndoStore.canUndo(context))
    }

    @Test fun moveUndoNeverMovesAReplacementFile() = runBlocking {
        val old = File(workspaceRoot, "old.txt")
        val current = text(workspaceRoot, "moved.txt", "ORIGINAL")
        FileUndoStore.recordMoves(context, mapOf(old.path to current.path))
        check(current.delete())
        current.writeText("REPLACEMENT_FILE")
        assertTrue(FileUndoStore.undo(context).isFailure)
        assertEquals("REPLACEMENT_FILE", current.readText())
        assertFalse(old.exists())
        assertTrue(FileUndoStore.canUndo(context))
    }

    @Test fun unchangedRenameUndoStillRestoresOriginalName() = runBlocking {
        val old = File(workspaceRoot, "old.txt")
        val current = text(workspaceRoot, "renamed.txt", "ORIGINAL")
        FileUndoStore.recordRename(context, old, current)
        assertEquals(1, FileUndoStore.undo(context).getOrThrow())
        assertEquals("ORIGINAL", old.readText())
        assertFalse(current.exists())
    }

    @Test fun legacyFallbackDeleteFailureKeepsCompletedCopy() {
        val source = text(workspaceRoot, "source.txt", "COMPLETE_SOURCE")
        val destination = File(workspaceRoot, "destination").apply { check(mkdir()) }
        val controlled = ControlledFile(source, failedRename = true, failedDelete = true)
        assertTrue(runCatching { FileUtils.moveFile(context, controlled, destination) }.isFailure)
        assertEquals("COMPLETE_SOURCE", source.readText())
        assertEquals("The complete destination was deleted after failed source deletion", "COMPLETE_SOURCE", File(destination, "source.txt").readText())
    }

    @Test fun legacyFallbackNeverDeletesSourceModifiedAfterCopy() {
        val source = text(workspaceRoot, "source.txt", "ORIGINAL")
        val destination = File(workspaceRoot, "destination").apply { check(mkdir()) }
        var mutated = false
        val controlled = ControlledFile(source, failedRename = true, onModified = {
            if (!mutated) { mutated = true; source.writeText("UPDATED_SOURCE") }
        })
        val result = runCatching { FileUtils.moveFile(context, controlled, destination) }
        assertTrue("Changed source was accepted as safely moved", result.isFailure)
        assertEquals("UPDATED_SOURCE", source.readText())
        assertEquals("ORIGINAL", File(destination, "source.txt").readText())
    }

    private fun safContext(): Context = context
    private fun resetProvider(fault: String = "") {
        val granted = CountDownLatch(1)
        var grantResult = Activity.RESULT_CANCELED
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                grantResult = resultCode
                granted.countDown()
            }
        }
        val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
        val grant = Intent().setComponent(ComponentName(testPackage, "com.abfilepro.app.core.MaintenanceGrantReceiver"))
            .putExtra("target_package", context.packageName)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
        context.sendOrderedBroadcast(grant, null, receiver, Handler(Looper.getMainLooper()), Activity.RESULT_CANCELED, null, null)
        assertTrue("Test provider grant receiver did not respond", granted.await(10, TimeUnit.SECONDS))
        assertEquals(Activity.RESULT_OK, grantResult)
        val ready = safContext().contentResolver.call(Uri.parse("content://$authority"), "reset_fixtures", fault, null)
        assertTrue(ready?.getBoolean("ready") == true)
    }
    private fun uri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(DocumentsContract.buildTreeDocumentUri(authority, "root"), id)
    private fun entry(id: String): SafStorageManager.Entry = requireNotNull(SafStorageManager.queryEntry(safContext(), uri(id), uri("root")))
    private fun content(id: String): String = safContext().contentResolver.openInputStream(uri(id))!!.bufferedReader().use { it.readText() }

    @Test fun nullSafCursorIsAnErrorRatherThanEmptyFolder() {
        resetProvider("null_source_listing")
        assertTrue("Provider failure was confused with an empty folder", runCatching { SafStorageManager.listChildren(safContext(), uri("source")) }.isFailure)
    }

    @Test fun failedSafListingCannotMoveAndDeleteUncopiedFolder() = runBlocking {
        resetProvider("null_source_listing")
        assertTrue(runCatching { SafStorageManager.move(safContext(), entry("source"), entry("destination")) }.isFailure)
        assertEquals("ORIGINAL_CHILD", content("source/seed.txt"))
        assertTrue(SafStorageManager.listChildren(safContext(), uri("destination")).isEmpty())
    }

    @Test fun safMoveKeepsFileModifiedAfterCopy() = runBlocking {
        resetProvider("mutate_input_after_copy")
        val result = runCatching { SafStorageManager.move(safContext(), entry("input.txt"), entry("destination")) }
        assertTrue("Newer source bytes were deleted", result.isFailure)
        assertEquals("UPDATED_INPUT", content("input.txt"))
        assertEquals("ORIGINAL_INPUT", content("destination/input.txt"))
    }

    @Test fun safMoveKeepsChildAddedAfterCopy() = runBlocking {
        resetProvider("add_child_after_copy")
        assertTrue(runCatching { SafStorageManager.move(safContext(), entry("source"), entry("destination")) }.isFailure)
        assertEquals("LATE_CHILD", content("source/late.txt"))
        assertEquals("ORIGINAL_CHILD", content("source/seed.txt"))
        assertEquals("ORIGINAL_CHILD", content("destination/source/seed.txt"))
    }

    @Test fun safMoveRejectsIncompleteDestinationBeforeDeletingOriginal() = runBlocking {
        resetProvider("corrupt_destination")
        assertTrue(runCatching { SafStorageManager.move(safContext(), entry("input.txt"), entry("destination")) }.isFailure)
        assertEquals("ORIGINAL_INPUT", content("input.txt"))
    }

    @Test fun normalSafFallbackMovePreservesEveryChild() = runBlocking {
        resetProvider()
        val moved = SafStorageManager.move(safContext(), entry("source"), entry("destination"))
        assertEquals("source", moved.name)
        assertEquals("ORIGINAL_CHILD", content("destination/source/seed.txt"))
        assertNull(SafStorageManager.queryEntry(safContext(), uri("source")))
    }

    @Test fun concaveCropIsRejectedBeforeChangingCapturedImage() = runBlocking {
        val source = image("concave.png")
        val before = sha(source)
        val quad = ScanQuad(ScanPoint(.05f,.05f), ScanPoint(.95f,.05f), ScanPoint(.45f,.4f), ScanPoint(.05f,.95f))
        val result = runCatching { ScanProcessor.commitPerspectiveCrop(context, ScanDraftPage(1, source, perspective = quad)) }
        result.getOrNull()?.file?.let { outputs += it }
        assertTrue("Concave geometry was flattened and accepted", result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(before, sha(source))
    }

    @Test fun crossingCropIsRejectedBeforeChangingCapturedImage() = runBlocking {
        val source = image("crossed.png")
        val before = sha(source)
        val quad = ScanQuad(ScanPoint(.05f,.05f), ScanPoint(.95f,.95f), ScanPoint(.95f,.05f), ScanPoint(.05f,.95f))
        val result = runCatching { ScanProcessor.commitPerspectiveCrop(context, ScanDraftPage(1, source, perspective = quad)) }
        result.getOrNull()?.file?.let { outputs += it }
        assertTrue("Crossing geometry was accepted", result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(before, sha(source))
    }

    @Test fun validPerspectiveCropStillProducesReadableImage() = runBlocking {
        val source = image("valid.png")
        val result = ScanProcessor.commitPerspectiveCrop(context, ScanDraftPage(1, source, perspective = ScanQuad.insetDefault(.05f)))
        outputs += result.file
        val decoded = requireNotNull(BitmapFactory.decodeFile(result.file.path))
        try { assertTrue(decoded.width > 100 && decoded.height > 150) } finally { decoded.recycle() }
    }
}
