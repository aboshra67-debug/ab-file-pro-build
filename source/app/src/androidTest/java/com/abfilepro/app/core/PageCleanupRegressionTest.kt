package com.abfilepro.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.sqrt

/** P24: finger removal, screen-photo cleanup and book pages (on-device image processing). */
@RunWith(AndroidJUnit4::class)
class PageCleanupRegressionTest {
    private lateinit var context: Context
    private lateinit var dir: File
    private val skin = Color.rgb(224, 172, 140)

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dir = File(context.cacheDir, "p24_${UUID.randomUUID()}").apply { check(mkdirs()) }
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun page(withFingerAtEdge: Boolean, withSkinInside: Boolean = false): Bitmap {
        val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val ink = Paint().apply { color = Color.rgb(20, 20, 20); strokeWidth = 4f }
        var y = 120f
        while (y < 700f) { canvas.drawLine(150f, y, 450f, y, ink); y += 30f }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = skin }
        if (withFingerAtEdge) canvas.drawRoundRect(RectF(-30f, 330f, 70f, 430f), 40f, 40f, fill)
        if (withSkinInside) canvas.drawOval(RectF(230f, 300f, 370f, 470f), fill)
        return bitmap
    }

    private fun isNearWhite(c: Int) = Color.red(c) > 200 && Color.green(c) > 200 && Color.blue(c) > 200

    @Test fun aFingerOnThePageEdgeIsPaintedOverAndTheTextStays() {
        val source = page(withFingerAtEdge = true)
        val (cleaned, repainted) = PageCleanup.removeFingers(source)
        try {
            assertTrue("finger pixels repainted", repainted > 0)
            assertTrue("finger area now paper: ${Integer.toHexString(cleaned.getPixel(25, 380))}", isNearWhite(cleaned.getPixel(25, 380)))
            val text = cleaned.getPixel(300, 120)
            assertTrue("text kept", Color.red(text) < 90)
        } finally { source.recycle(); cleaned.recycle() }
    }

    @Test fun skinInsideThePageLikeAnIdPhotoIsLeftAlone() {
        val source = page(withFingerAtEdge = false, withSkinInside = true)
        val (cleaned, repainted) = PageCleanup.removeFingers(source)
        try {
            assertEquals(0, repainted)
            assertEquals(skin, cleaned.getPixel(300, 385))
        } finally { source.recycle(); cleaned.recycle() }
    }

    @Test fun screenPhotoCleanupFlattensMoireStripes() {
        val source = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        for (y in 0 until 300) for (x in 0 until 400) {
            val v = if ((x + y) % 3 == 0) 90 else 200
            source.setPixel(x, y, Color.rgb(v, v, v))
        }
        fun stdDev(b: Bitmap): Double {
            var sum = 0.0; var sq = 0.0; var n = 0
            for (y in 100 until 200) for (x in 100 until 300) { val v = Color.red(b.getPixel(x, y)).toDouble(); sum += v; sq += v * v; n++ }
            val mean = sum / n
            return sqrt(sq / n - mean * mean)
        }
        val cleaned = PageCleanup.cleanScreenPhoto(source)
        try {
            assertTrue("stripes reduced: before=${stdDev(source)} after=${stdDev(cleaned)}", stdDev(cleaned) < stdDev(source) * .5)
        } finally { source.recycle(); cleaned.recycle() }
    }

    @Test fun cleaningAPageKeepsItsOriginalImageAndPointsAtTheNewOne() = runBlocking {
        val original = File(dir, "page.jpg")
        val bitmap = page(withFingerAtEdge = true)
        FileOutputStream(original).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bitmap.recycle()
        val draft = ScanDraftPage(id = 7L, file = original, rotation = 90, perspective = ScanQuad.insetDefault(.05f))
        val outcome = PageCleanup.apply(context, draft, PageCleanup.Operation.REMOVE_FINGERS)
        assertTrue(outcome.changed)
        assertEquals(7L, outcome.page.id)
        assertNotEquals(original.absolutePath, outcome.page.file.absolutePath)
        assertTrue(original.isFile && outcome.page.file.isFile)
        assertEquals(0, outcome.page.rotation)
        assertNull(outcome.page.perspective)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(outcome.page.file.path, bounds)
        assertTrue("rotation baked in (landscape now)", bounds.outWidth > bounds.outHeight)
    }

    @Test fun aBookShotBecomesTwoPagesRightPageFirstForArabic() {
        val shotDir = File(dir, "book").apply { mkdirs() }
        val shot = File(shotDir, "shot.jpg")
        val spread = Bitmap.createBitmap(1000, 700, Bitmap.Config.ARGB_8888)
        Canvas(spread).apply {
            drawColor(Color.WHITE)
            drawRect(0f, 0f, 500f, 700f, Paint().apply { color = Color.rgb(250, 230, 230) })  // left page tinted red
            drawRect(500f, 0f, 1000f, 700f, Paint().apply { color = Color.rgb(230, 230, 250) }) // right page tinted blue
        }
        FileOutputStream(shot).use { spread.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        spread.recycle()
        val pages = SmartCaptureFiles.pagesFor(shot, emptyList(), ScanCaptureMode.BOOK)
        assertEquals(2, pages.size)
        val ordered = SmartCapturePolicy.bookReadingOrder(pages, rightToLeft = true)
        val first = BitmapFactory.decodeFile(ordered[0].file.path)
        val second = BitmapFactory.decodeFile(ordered[1].file.path)
        try {
            assertTrue("right (blue) page first", Color.blue(first.getPixel(first.width / 2, first.height / 2)) > Color.red(first.getPixel(first.width / 2, first.height / 2)))
            assertTrue("left (red) page second", Color.red(second.getPixel(second.width / 2, second.height / 2)) > Color.blue(second.getPixel(second.width / 2, second.height / 2)))
        } finally { first.recycle(); second.recycle() }
    }
}
