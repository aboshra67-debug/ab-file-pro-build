package com.abfilepro.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * P24: one-tap page cleanups in the review screen (on-device, OpenCV).
 *  - removeFingers: skin-coloured areas that enter the page from its edges (fingers holding
 *    the sheet) are painted over from the surrounding paper. Skin areas fully inside the page
 *    (a photo on an ID card) are left alone.
 *  - cleanScreenPhoto: softens the moiré / scan lines of a photo taken of a monitor or phone
 *    screen, then restores text edges.
 * The result is written as a new image for that page only; its earlier edits are baked in.
 */
object PageCleanup {
    enum class Operation { REMOVE_FINGERS, CLEAN_SCREEN }

    data class Outcome(val page: ScanDraftPage, val changed: Boolean)

    /** Returns the cleaned bitmap and how many pixels were repainted. */
    fun removeFingers(source: Bitmap): Pair<Bitmap, Int> {
        require(LiveDocumentDetector.ensureLoaded()) { "opencv_unavailable" }
        val rgba = Mat(); val rgb = Mat(); val ycrcb = Mat(); val mask = Mat(); val labels = Mat()
        val stats = Mat(); val centroids = Mat(); val keep = Mat(); val out = Mat()
        try {
            Utils.bitmapToMat(source, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, ycrcb, Imgproc.COLOR_RGB2YCrCb)
            Core.inRange(ycrcb, Scalar(45.0, 136.0, 78.0), Scalar(250.0, 175.0, 126.0), mask)
            val k5 = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, k5)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, k5)
            k5.release()

            val count = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids, 8, CvType.CV_32S)
            val w = source.width; val h = source.height
            val minArea = w.toLong() * h / 500 // 0.2% of the page
            val edge = max(3, min(w, h) / 100)
            keep.create(h, w, CvType.CV_8UC1); keep.setTo(Scalar(0.0))
            var kept = 0
            for (label in 1 until count) {
                val x = stats.get(label, Imgproc.CC_STAT_LEFT)[0].toInt()
                val y = stats.get(label, Imgproc.CC_STAT_TOP)[0].toInt()
                val bw = stats.get(label, Imgproc.CC_STAT_WIDTH)[0].toInt()
                val bh = stats.get(label, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                val area = stats.get(label, Imgproc.CC_STAT_AREA)[0].toLong()
                if (area < minArea) continue
                val touchesEdge = x <= edge || y <= edge || x + bw >= w - edge || y + bh >= h - edge
                // A finger reaches in from the border; it does not cover the middle of the page.
                val staysNearEdge = bw <= w * .45 || bh <= h * .45
                if (!touchesEdge || !staysNearEdge) continue
                val component = Mat()
                Core.compare(labels, Scalar(label.toDouble()), component, Core.CMP_EQ)
                Core.bitwise_or(keep, component, keep)
                component.release()
                kept++
            }
            if (kept == 0) return source.copy(Bitmap.Config.ARGB_8888, false) to 0
            val grow = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(15.0, 15.0))
            Imgproc.dilate(keep, keep, grow)
            grow.release()
            val repainted = Core.countNonZero(keep)
            Photo.inpaint(rgb, keep, out, 9.0, Photo.INPAINT_TELEA)
            val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Imgproc.cvtColor(out, rgba, Imgproc.COLOR_RGB2RGBA)
            Utils.matToBitmap(rgba, result)
            return result to repainted
        } finally {
            rgba.release(); rgb.release(); ycrcb.release(); mask.release(); labels.release()
            stats.release(); centroids.release(); keep.release(); out.release()
        }
    }

    fun cleanScreenPhoto(source: Bitmap): Bitmap {
        require(LiveDocumentDetector.ensureLoaded()) { "opencv_unavailable" }
        val rgba = Mat(); val rgb = Mat(); val smooth = Mat(); val soft = Mat(); val sharp = Mat()
        try {
            Utils.bitmapToMat(source, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            // Moiré is a fine periodic pattern: a median + Gaussian pass removes it,
            // then a gentle unsharp mask brings back the letter edges.
            Imgproc.medianBlur(rgb, smooth, 3)
            val radius = if (max(source.width, source.height) > 2000) 7.0 else 5.0
            Imgproc.GaussianBlur(smooth, soft, Size(radius, radius), 0.0)
            val halo = Mat()
            try {
                Imgproc.GaussianBlur(soft, halo, Size(0.0, 0.0), 3.0)
                Core.addWeighted(soft, 1.45, halo, -0.45, 0.0, sharp)
            } finally {
                halo.release()
            }
            Imgproc.cvtColor(sharp, rgba, Imgproc.COLOR_RGB2RGBA)
            val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgba, result)
            return result
        } finally {
            rgba.release(); rgb.release(); smooth.release(); soft.release(); sharp.release()
        }
    }

    /** Applies [op] to the page as currently edited and returns the page pointing at the new image. */
    suspend fun apply(context: Context, page: ScanDraftPage, op: Operation): Outcome = withContext(Dispatchers.IO) {
        val exported = ScanProcessor.exportEditedPageToCache(context, page, 95)
        try {
            val bitmap = BitmapFactory.decodeFile(exported.path) ?: error("scan_page_unreadable")
            val (cleaned, changed) = try {
                when (op) {
                    Operation.REMOVE_FINGERS -> removeFingers(bitmap).let { it.first to (it.second > 0) }
                    Operation.CLEAN_SCREEN -> cleanScreenPhoto(bitmap) to true
                }
            } finally {
                bitmap.recycle()
            }
            if (!changed) {
                cleaned.recycle()
                return@withContext Outcome(page, false)
            }
            val dir = page.file.parentFile ?: error("scan_page_folder_missing")
            val target = File(dir, "clean_${System.nanoTime()}.jpg")
            try {
                FileOutputStream(target).use { out ->
                    check(cleaned.compress(Bitmap.CompressFormat.JPEG, 94, out)) { "scan_page_write_failed" }
                    out.fd.sync()
                }
            } catch (error: Throwable) {
                target.delete()
                throw error
            } finally {
                cleaned.recycle()
            }
            // The earlier image stays in the scan's cache folder (removed with the session),
            // so nothing else that still points at it can break.
            val updated = page.copy(
                file = target, rotation = 0, filter = ScanFilter.ORIGINAL, brightness = 0, contrast = 1f, sharpness = 0f,
                cropLeft = 0f, cropTop = 0f, cropRight = 0f, cropBottom = 0f, perspective = null, annotations = emptyList()
            )
            Outcome(updated, true)
        } finally {
            runCatching { exported.parentFile?.deleteRecursively() }
        }
    }
}
