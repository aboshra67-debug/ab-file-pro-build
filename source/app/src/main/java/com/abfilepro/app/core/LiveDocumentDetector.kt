package com.abfilepro.app.core

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * P23: live sheet detection for the AB smart camera (OpenCV, on-device, no network).
 * Works on the camera's luminance plane at ~480 px, finds every separate sheet in view
 * (several receipts/cards in one shot) and returns their corners normalized to the
 * upright frame. Also reports sharpness (variance of Laplacian) and brightness.
 */
object LiveDocumentDetector {
    data class Result(
        val candidates: List<DocumentCandidate>,
        val sharpness: Double,
        val brightness: Double,
        /** Upright frame width / height. */
        val frameAspect: Float,
    ) {
        val best: DocumentCandidate? get() = candidates.maxByOrNull { it.areaRatio * .6f + it.score * .4f }
    }

    private const val WORK_SIZE = 480.0
    private const val MIN_AREA = SmartCapturePolicy.MIN_MULTI_AREA
    private const val MAX_AREA = .97f

    @Volatile private var loaded: Boolean? = null

    fun ensureLoaded(): Boolean = loaded ?: runCatching { OpenCVLoader.initLocal() }.getOrDefault(false).also { loaded = it }

    /** Camera luminance (Y) plane, as delivered by ImageAnalysis. */
    fun detectLuminance(
        luminance: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        rotationDegrees: Int,
        maxDocs: Int,
    ): Result? {
        if (!ensureLoaded() || width < 80 || height < 80) return null
        val gray = Mat(height, width, CvType.CV_8UC1)
        try {
            if (rowStride == width) {
                gray.put(0, 0, luminance.copyOf(width * height))
            } else {
                val row = ByteArray(width)
                for (y in 0 until height) {
                    System.arraycopy(luminance, y * rowStride, row, 0, width)
                    gray.put(y, 0, row)
                }
            }
            return detectGray(gray, rotationDegrees, maxDocs)
        } finally {
            gray.release()
        }
    }

    /** Upright bitmap (tests, imported photos). */
    fun detectBitmap(bitmap: Bitmap, maxDocs: Int): Result? {
        if (!ensureLoaded()) return null
        val rgba = Mat()
        val gray = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            return detectGray(gray, 0, maxDocs)
        } finally {
            rgba.release(); gray.release()
        }
    }

    private fun detectGray(source: Mat, rotationDegrees: Int, maxDocs: Int): Result {
        val small = Mat()
        val blurred = Mat()
        val edges = Mat()
        val binary = Mat()
        val laplacian = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val contours = mutableListOf<MatOfPoint>()
        try {
            val scale = WORK_SIZE / max(source.cols(), source.rows()).toDouble()
            if (scale < 1.0) Imgproc.resize(source, small, Size(source.cols() * scale, source.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
            else source.copyTo(small)
            val w = small.cols().toDouble()
            val h = small.rows().toDouble()
            val imageArea = w * h

            val brightness = Core.mean(small).`val`[0]
            Imgproc.Laplacian(small, laplacian, CvType.CV_64F)
            val mean = MatOfDouble()
            val std = MatOfDouble()
            Core.meanStdDev(laplacian, mean, std)
            val sharpness = std.toArray().firstOrNull()?.let { it * it } ?: 0.0
            mean.release(); std.release()

            Imgproc.GaussianBlur(small, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, 30.0, 110.0)
            Imgproc.dilate(edges, edges, kernel, Point(-1.0, -1.0), 2)
            collect(edges, contours)
            Imgproc.threshold(blurred, binary, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_OPEN, kernel)
            collect(binary, contours)

            val found = mutableListOf<DocumentCandidate>()
            for (contour in contours.sortedByDescending { abs(Imgproc.contourArea(it)) }.take(60)) {
                val contourArea = abs(Imgproc.contourArea(contour))
                val areaRatio = (contourArea / imageArea).toFloat()
                if (areaRatio < MIN_AREA || areaRatio > MAX_AREA) continue
                val corners = quadCorners(contour, contourArea) ?: continue
                val ordered = order(corners) ?: continue
                val polygonArea = area(ordered)
                val quadRatio = (polygonArea / imageArea).toFloat()
                if (quadRatio < MIN_AREA || quadRatio > MAX_AREA) continue
                val fill = (contourArea / polygonArea.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f)
                val angles = rightAngleScore(ordered)
                if (angles < .45f) continue
                val score = fill * .4f + angles * .4f + (quadRatio / .3f).coerceAtMost(1f) * .2f
                val quad = ScanQuad(
                    ScanPoint((ordered[0].x / w).toFloat(), (ordered[0].y / h).toFloat()),
                    ScanPoint((ordered[1].x / w).toFloat(), (ordered[1].y / h).toFloat()),
                    ScanPoint((ordered[2].x / w).toFloat(), (ordered[2].y / h).toFloat()),
                    ScanPoint((ordered[3].x / w).toFloat(), (ordered[3].y / h).toFloat()),
                ).clamped()
                found += DocumentCandidate(rotate(quad, rotationDegrees), quadRatio, score)
            }
            val selected = SmartCapturePolicy.selectSeparate(found.filter { it.score >= .5f }, maxDocs.coerceAtLeast(1))
            val upright = if (rotationDegrees % 180 != 0) h / w else w / h
            return Result(selected, sharpness, brightness, upright.toFloat())
        } finally {
            contours.forEach { it.release() }
            small.release(); blurred.release(); edges.release(); binary.release(); laplacian.release(); kernel.release()
        }
    }

    private fun collect(image: Mat, into: MutableList<MatOfPoint>) {
        val hierarchy = Mat()
        val copy = image.clone()
        try {
            Imgproc.findContours(copy, into, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        } finally {
            hierarchy.release(); copy.release()
        }
    }

    /** Four corners: polygon approximation first, then the rotated bounding box of a solid shape (rounded card corners). */
    private fun quadCorners(contour: MatOfPoint, contourArea: Double): Array<Point>? {
        val curve = MatOfPoint2f(*contour.toArray())
        val approx = MatOfPoint2f()
        try {
            val perimeter = Imgproc.arcLength(curve, true)
            if (perimeter <= 0.0) return null
            for (epsilon in doubleArrayOf(.02, .03, .04, .05)) {
                Imgproc.approxPolyDP(curve, approx, perimeter * epsilon, true)
                if (approx.total() == 4L) {
                    val polygon = MatOfPoint(*approx.toArray())
                    val convex = try { Imgproc.isContourConvex(polygon) } finally { polygon.release() }
                    if (convex) return approx.toArray()
                }
            }
            val hullIdx = MatOfInt()
            try {
                Imgproc.convexHull(contour, hullIdx)
                val points = contour.toArray()
                val hull = hullIdx.toArray().map { points[it] }
                val hullArea = area(hull.toTypedArray())
                if (hullArea <= 0.0 || contourArea / hullArea < .9) return null
            } finally {
                hullIdx.release()
            }
            val box = Imgproc.minAreaRect(curve)
            val corners = arrayOfNulls<Point>(4)
            box.points(corners)
            return corners.requireNoNulls()
        } finally {
            curve.release(); approx.release()
        }
    }

    private fun order(points: Array<Point>): Array<Point>? {
        if (points.size != 4) return null
        val tl = points.minByOrNull { it.x + it.y }!!
        val br = points.maxByOrNull { it.x + it.y }!!
        val tr = points.maxByOrNull { it.x - it.y }!!
        val bl = points.minByOrNull { it.x - it.y }!!
        val ordered = arrayOf(tl, tr, br, bl)
        return if (ordered.toSet().size == 4) ordered else null
    }

    private fun area(points: Array<Point>): Double {
        var sum = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2.0
    }

    private fun rightAngleScore(p: Array<Point>): Float {
        var worst = 1f
        for (i in 0 until 4) {
            val a = p[(i + 3) % 4]; val b = p[i]; val c = p[(i + 1) % 4]
            val v1x = a.x - b.x; val v1y = a.y - b.y
            val v2x = c.x - b.x; val v2y = c.y - b.y
            val len = sqrt(v1x * v1x + v1y * v1y) * sqrt(v2x * v2x + v2y * v2y)
            if (len <= 0.0) return 0f
            val angle = Math.toDegrees(acos(((v1x * v2x + v1y * v2y) / len).coerceIn(-1.0, 1.0)))
            worst = min(worst, (1.0 - abs(angle - 90.0) / 45.0).toFloat().coerceIn(0f, 1f))
        }
        return worst
    }

    /** Sensor-frame normalized point → upright (display) frame after rotating [degrees] clockwise. */
    fun rotate(quad: ScanQuad, degrees: Int): ScanQuad {
        fun r(p: ScanPoint) = when (((degrees % 360) + 360) % 360) {
            90 -> ScanPoint(1f - p.y, p.x)
            180 -> ScanPoint(1f - p.x, 1f - p.y)
            270 -> ScanPoint(p.y, 1f - p.x)
            else -> p
        }
        if (((degrees % 360) + 360) % 360 == 0) return quad
        val pts = listOf(r(quad.topLeft), r(quad.topRight), r(quad.bottomRight), r(quad.bottomLeft))
        // Re-order so the corners stay top-left, top-right, bottom-right, bottom-left upright.
        val tl = pts.minBy { it.x + it.y }
        val br = pts.maxBy { it.x + it.y }
        val tr = pts.maxBy { it.x - it.y }
        val bl = pts.minBy { it.x - it.y }
        return ScanQuad(tl, tr, br, bl)
    }
}
