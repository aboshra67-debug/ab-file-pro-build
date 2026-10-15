package com.abfilepro.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.abs

/** P23: the AB smart camera's live detector (OpenCV) and the one-shot / several-sheets pages. */
@RunWith(AndroidJUnit4::class)
class SmartCameraRegressionTest {
    private lateinit var context: Context
    private lateinit var dir: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dir = File(context.cacheDir, "p23_${UUID.randomUUID()}").apply { check(mkdirs()) }
    }

    @After fun tearDown() { dir.deleteRecursively() }

    /** Dark desk with white sheets; each sheet has a few dark "text" lines inside. */
    private fun scene(width: Int, height: Int, sheets: List<IntArray>): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(45, 42, 40))
        val paper = Paint().apply { color = Color.rgb(242, 242, 236) }
        val ink = Paint().apply { color = Color.rgb(60, 60, 60); strokeWidth = 2f }
        sheets.forEach { (l, t, r, b) ->
            canvas.drawRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat(), paper)
            var y = t + 18
            while (y < b - 12) { canvas.drawLine(l + 14f, y.toFloat(), r - 14f, y.toFloat(), ink); y += 16 }
        }
        return bitmap
    }

    private fun near(expected: Float, actual: Float, tolerance: Float = .035f) =
        assertTrue("expected $expected got $actual", abs(expected - actual) <= tolerance)

    @Test fun oneSheetIsOutlinedAtItsCorners() {
        val bitmap = scene(640, 480, listOf(intArrayOf(120, 60, 500, 420)))
        try {
            val detected = LiveDocumentDetector.detectBitmap(bitmap, 1)
            assertNotNull("OpenCV must load on the phone", detected)
            val result = detected!!
            val quad = result.best!!.quad
            near(120f / 640, quad.topLeft.x); near(60f / 480, quad.topLeft.y)
            near(500f / 640, quad.bottomRight.x); near(420f / 480, quad.bottomRight.y)
            assertTrue("sharp synthetic image", result.sharpness > 40.0)
        } finally { bitmap.recycle() }
    }

    @Test fun severalSheetsInOneShotAreFoundSeparatelyInReadingOrder() {
        val bitmap = scene(
            800, 600,
            listOf(intArrayOf(40, 40, 360, 260), intArrayOf(440, 40, 760, 260), intArrayOf(200, 330, 600, 560))
        )
        try {
            val result = LiveDocumentDetector.detectBitmap(bitmap, 6)!!
            assertEquals(3, result.candidates.size)
            // Top row right-to-left, then the bottom sheet.
            assertTrue(result.candidates[0].quad.topLeft.x > .5f)
            assertTrue(result.candidates[1].quad.topLeft.x < .5f)
            assertTrue(result.candidates[2].quad.topLeft.y > .5f)
            assertEquals(DocumentKind.MULTI, SmartCapturePolicy.classify(result.candidates, result.frameAspect))
        } finally { bitmap.recycle() }
    }

    @Test fun anEmptyDeskHasNoOutline() {
        val bitmap = scene(640, 480, emptyList())
        try {
            assertTrue(LiveDocumentDetector.detectBitmap(bitmap, 6)!!.candidates.isEmpty())
        } finally { bitmap.recycle() }
    }

    @Test fun cameraLuminanceIsRotatedToTheUprightScreen() {
        // Sensor frame 640x480 (landscape), phone held upright -> rotation 90.
        val bitmap = scene(640, 480, listOf(intArrayOf(100, 80, 300, 400)))
        val luminance = ByteArray(640 * 480)
        val pixels = IntArray(640 * 480)
        bitmap.getPixels(pixels, 0, 640, 0, 0, 640, 480)
        bitmap.recycle()
        pixels.forEachIndexed { i, p -> luminance[i] = ((Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000).toByte() }
        val result = LiveDocumentDetector.detectLuminance(luminance, 640, 480, 640, 90, 1)!!
        near(480f / 640f, result.frameAspect, .001f)
        val quad = result.best!!.quad
        // Sensor x in [100,300]/640, y in [80,400]/480 -> upright x in [1-400/480, 1-80/480], y in [100/640, 300/640].
        near(1f - 400f / 480, quad.topLeft.x); near(100f / 640, quad.topLeft.y)
        near(1f - 80f / 480, quad.bottomRight.x); near(300f / 640, quad.bottomRight.y)
    }

    @Test fun severalSheetsBecomeIndependentPagesThatCanBeRetiredOneByOne() {
        val shotDir = File(dir, "shot").apply { mkdirs() }
        val shot = File(shotDir, "shot.jpg")
        val bitmap = scene(800, 600, listOf(intArrayOf(40, 40, 360, 260), intArrayOf(440, 40, 760, 260)))
        FileOutputStream(shot).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bitmap.recycle()
        val quads = listOf(ScanQuad.insetDefault(.1f), ScanQuad.insetDefault(.2f), ScanQuad.insetDefault(.3f))
        val pages = SmartCaptureFiles.pagesFor(shot, quads, ScanCaptureMode.BATCH)
        assertEquals(3, pages.size)
        assertEquals(3, pages.map { it.file.absolutePath }.toSet().size)
        assertEquals(quads, pages.map { it.perspective })
        assertEquals(3, pages.map { it.id }.toSet().size)

        ScanPageFiles.retire(listOf(pages[1]), listOf(pages[0], pages[2]))
        assertTrue(!pages[1].file.exists())
        assertTrue(pages[0].file.isFile && pages[2].file.isFile)
        ScanPageFiles.retire(listOf(pages[0], pages[2]), emptyList())
        assertTrue("an emptied scan folder is removed", !shotDir.exists())
    }
}
