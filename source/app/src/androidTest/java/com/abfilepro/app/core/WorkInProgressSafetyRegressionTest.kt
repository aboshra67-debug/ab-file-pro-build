package com.abfilepro.app.core

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.abfilepro.app.assistant.VoiceRecordingController
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** P19: work in progress survives screen recreation, and scanner/recorder failures lose nothing. */
@RunWith(AndroidJUnit4::class)
class WorkInProgressSafetyRegressionTest {
    private lateinit var context: Context
    private lateinit var scratch: File
    private val created = mutableListOf<File>()

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scratch = File(context.cacheDir, "p19_${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After fun tearDown() {
        scratch.deleteRecursively()
        created.forEach { it.delete(); File(it.parentFile, ".${it.name}.json").delete() }
    }

    private fun scansBefore(): Set<String> = FileUtils.outputDir(context, "scans").listFiles().orEmpty().map { it.name }.toSet()

    @Test fun reviewPagesRoundTripThroughSavedState() {
        val image = File(scratch, "page.jpg").apply { writeBytes(ByteArray(64) { 1 }) }
        val page = ScanDraftPage(7, image, rotation = 90, filter = ScanFilter.BW, brightness = 12, contrast = 1.2f,
            cropLeft = .1f, perspective = ScanQuad.insetDefault(.05f),
            annotations = listOf(ScanTextAnnotation(id = 3, text = "سري", x = .2f, y = .3f, backgroundArgb = 0xFFFFFF00)))
        val restored = ScanDraftPageCodec.decode(ScanDraftPageCodec.encode(listOf(page)))
        assertEquals(listOf(page), restored)
    }

    @Test fun restoredReviewDropsPagesWhoseImageIsGone() {
        val kept = File(scratch, "kept.jpg").apply { writeBytes(ByteArray(8) { 1 }) }
        val gone = File(scratch, "gone.jpg")
        val restored = ScanDraftPageCodec.decode(ScanDraftPageCodec.encode(listOf(ScanDraftPage(1, kept), ScanDraftPage(2, gone))))
        assertEquals(listOf(1L), restored.map { it.id })
    }

    @Test fun scannerPdfIsPublishedOnlyAfterVerification() = runBlocking {
        val source = File(scratch, "scanner.pdf").apply { writeText("%PDF-1.7\n%test\n") }
        val before = scansBefore()
        val saved = ScanProcessor.importScannerPdf(context, Uri.fromFile(source), "Imported")
        created += saved
        assertTrue(saved.isFile && saved.readText() == source.readText())
        assertEquals(before + saved.name, scansBefore())
    }

    @Test fun invalidScannerResultFailsCleanlyWithoutLeavingFiles() = runBlocking {
        val source = File(scratch, "broken.pdf").apply { writeText("not a pdf") }
        val before = scansBefore()
        val result = runCatching { ScanProcessor.importScannerPdf(context, Uri.fromFile(source), "Broken") }
        result.getOrNull()?.let { created += it }
        assertTrue("A non-PDF scanner result was published", result.isFailure)
        assertEquals("Partial or hidden files were left behind", before, FileUtils.outputDir(context, "scans").listFiles().orEmpty().map { it.name }.toSet())
    }

    @Test fun recordingStoppedOutsideTheScreenIsRecovered() {
        val dir = VoiceRecordingController.recordingsDir(context)
        val orphan = File(dir, "AB_VOICE_${System.currentTimeMillis()}.aac").apply { writeBytes(ByteArray(2048) { 7 }) }
        created += orphan
        File(dir, ".${orphan.name}.json").writeText("""{"durationMs":4200,"quality":"high","finished":true}""")
        val referenced = File(dir, "AB_VOICE_1.aac").name
        val recovered = VoiceRecordingController.unsavedRecordings(context, setOf(referenced))
        val match = recovered.single { it.file.name == orphan.name }
        assertEquals(4200L, match.durationMs)
        assertEquals("high", match.quality)
        assertTrue("A referenced recording must not be offered again",
            VoiceRecordingController.unsavedRecordings(context, setOf(orphan.name)).none { it.file.name == orphan.name })
    }
}
