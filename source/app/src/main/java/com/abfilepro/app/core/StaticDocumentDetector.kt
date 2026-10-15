package com.abfilepro.app.core

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * On-device static-image document detector used by AB Magic Flow.
 * It mirrors the hardened CameraX detector but works on an already captured bitmap.
 * A low-confidence result returns no quad; callers must keep the original image.
 */
object StaticDocumentDetector {
    data class Detection(
        val quad: ScanQuad?,
        val confidence: Float,
        val warning: String?,
        val openCvLoaded: Boolean = false,
        val contourCount: Int = 0,
        val candidateCount: Int = 0,
        val bestScore: Float = 0f,
        val source: String = "unknown",
        val reason: String? = warning
    )
    private data class LineFit(val slope: Float, val intercept: Float, val coverage: Float, val fitScore: Float)

    fun detect(bitmap: Bitmap): Detection {
        val initResult = runCatching { OpenCVLoader.initLocal() }
        val openCvLoaded = initResult.getOrDefault(false)
        val openCvResult = if (openCvLoaded) {
            runCatching { detectWithOpenCv(bitmap) }.getOrElse { error ->
                Detection(
                    quad = null,
                    confidence = 0f,
                    warning = "OpenCV exception: ${error.javaClass.simpleName}",
                    openCvLoaded = true,
                    source = "opencv",
                    reason = error.message ?: error.javaClass.simpleName
                )
            }
        } else {
            Detection(
                quad = null,
                confidence = 0f,
                warning = "OpenCV init failed",
                openCvLoaded = false,
                source = "opencv",
                reason = initResult.exceptionOrNull()?.message ?: "initLocal=false"
            )
        }
        if (openCvResult.quad != null) return openCvResult

        // Safe fallback keeps the previous pure-Kotlin detector available on devices
        // where OpenCV cannot be initialized or where no reliable 4-corner contour exists.
        val legacy = legacyDetect(bitmap)
        return if (legacy.quad != null) {
            legacy.copy(
                openCvLoaded = openCvLoaded,
                contourCount = openCvResult.contourCount,
                candidateCount = openCvResult.candidateCount,
                bestScore = openCvResult.bestScore,
                source = "legacy-fallback",
                reason = openCvResult.reason
            )
        } else {
            openCvResult.copy(
                warning = openCvResult.warning ?: legacy.warning,
                reason = openCvResult.reason ?: legacy.warning
            )
        }
    }

    private fun detectWithOpenCv(bitmap: Bitmap): Detection {
        if (bitmap.width < 120 || bitmap.height < 120) return Detection(null, 0f, "الصورة صغيرة")

        val rgba = Mat()
        val gray = Mat()
        val blurred = Mat()
        val edges = Mat()
        val binary = Mat()
        val hierarchy = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val contours = mutableListOf<MatOfPoint>()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)

            // Pass 1: strong document border edges.
            Imgproc.Canny(blurred, edges, 45.0, 145.0)
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

            // Pass 2: bright-paper segmentation catches weak borders on dark desks/backgrounds.
            Imgproc.threshold(blurred, binary, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel)
            val binaryContours = mutableListOf<MatOfPoint>()
            val binaryHierarchy = Mat()
            try {
                Imgproc.findContours(binary, binaryContours, binaryHierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
                contours.addAll(binaryContours)
            } finally {
                binaryHierarchy.release()
            }

            val imageArea = bitmap.width.toDouble() * bitmap.height.toDouble()
            var bestQuad: ScanQuad? = null
            var bestScore = 0f
            var candidateCount = 0

            contours.sortedByDescending { kotlin.math.abs(Imgproc.contourArea(it)) }.take(40).forEach { contour ->
                val contourArea = kotlin.math.abs(Imgproc.contourArea(contour))
                val areaRatio = (contourArea / imageArea).toFloat()
                if (areaRatio !in .10f..0.985f) return@forEach

                val curve = MatOfPoint2f(*contour.toArray())
                val approx = MatOfPoint2f()
                try {
                    val perimeter = Imgproc.arcLength(curve, true)
                    if (perimeter <= 0.0) return@forEach

                    var points: Array<Point>? = null
                    for (epsilon in doubleArrayOf(.015, .020, .025, .030, .040, .050)) {
                        Imgproc.approxPolyDP(curve, approx, perimeter * epsilon, true)
                        if (approx.total() == 4L) {
                            val polygon = MatOfPoint(*approx.toArray())
                            val convex = try { Imgproc.isContourConvex(polygon) } finally { polygon.release() }
                            if (convex) { points = approx.toArray(); break }
                        }
                    }
                    val rawPoints = points ?: return@forEach
                    val ordered = orderQuad(rawPoints) ?: return@forEach
                    val normalized = ScanQuad(
                        topLeft = ScanPoint((ordered[0].x / bitmap.width).toFloat(), (ordered[0].y / bitmap.height).toFloat()),
                        topRight = ScanPoint((ordered[1].x / bitmap.width).toFloat(), (ordered[1].y / bitmap.height).toFloat()),
                        bottomRight = ScanPoint((ordered[2].x / bitmap.width).toFloat(), (ordered[2].y / bitmap.height).toFloat()),
                        bottomLeft = ScanPoint((ordered[3].x / bitmap.width).toFloat(), (ordered[3].y / bitmap.height).toFloat())
                    ).clamped()
                    if (!isSane(normalized)) return@forEach

                    val quadAreaRatio = polygonArea(ordered).toFloat() / imageArea.toFloat()
                    if (quadAreaRatio !in .10f..0.985f) return@forEach
                    val fill = (contourArea / polygonArea(ordered).coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f)
                    val angleScore = rightAngleScore(ordered)
                    val minSide = minOf(
                        distance(ordered[0], ordered[1]), distance(ordered[1], ordered[2]),
                        distance(ordered[2], ordered[3]), distance(ordered[3], ordered[0])
                    )
                    val sideScore = (minSide / min(bitmap.width, bitmap.height).toDouble() / .24).toFloat().coerceIn(0f, 1f)
                    val areaScore = ((quadAreaRatio - .10f) / .55f).coerceIn(0f, 1f)
                    val score = areaScore * .48f + fill * .22f + angleScore * .20f + sideScore * .10f
                    candidateCount += 1
                    if (score > bestScore) {
                        bestScore = score
                        bestQuad = normalized
                    }
                } finally {
                    curve.release()
                    approx.release()
                }
            }

            val accepted = bestQuad != null && bestScore >= .46f
            val reason = when {
                contours.isEmpty() -> "no-contours"
                candidateCount == 0 -> "no-4-corner-candidates"
                bestQuad == null -> "no-sane-quad"
                bestScore < .46f -> "score-below-threshold"
                else -> null
            }
            return Detection(
                quad = if (accepted) bestQuad else null,
                confidence = bestScore.coerceIn(0f, 1f),
                warning = if (accepted) null else "لم يتم العثور على أربع زوايا موثوقة",
                openCvLoaded = true,
                contourCount = contours.size,
                candidateCount = candidateCount,
                bestScore = bestScore.coerceIn(0f, 1f),
                source = "opencv",
                reason = reason
            )
        } finally {
            contours.forEach { it.release() }
            rgba.release(); gray.release(); blurred.release(); edges.release(); binary.release(); hierarchy.release(); kernel.release()
        }
    }

    private fun orderQuad(points: Array<Point>): Array<Point>? {
        if (points.size != 4) return null
        val tl = points.minByOrNull { it.x + it.y } ?: return null
        val br = points.maxByOrNull { it.x + it.y } ?: return null
        val tr = points.maxByOrNull { it.x - it.y } ?: return null
        val bl = points.minByOrNull { it.x - it.y } ?: return null
        val unique = listOf(tl, tr, br, bl).map { "${it.x.toInt()}:${it.y.toInt()}" }.toSet()
        return if (unique.size == 4) arrayOf(tl, tr, br, bl) else null
    }

    private fun polygonArea(points: Array<Point>): Double {
        var sum = 0.0
        for (i in points.indices) {
            val j = (i + 1) % points.size
            sum += points[i].x * points[j].y - points[j].x * points[i].y
        }
        return kotlin.math.abs(sum) * .5
    }

    private fun distance(a: Point, b: Point): Double = kotlin.math.hypot(a.x - b.x, a.y - b.y)

    private fun rightAngleScore(points: Array<Point>): Float {
        fun corner(a: Point, b: Point, c: Point): Float {
            val abx = a.x - b.x; val aby = a.y - b.y
            val cbx = c.x - b.x; val cby = c.y - b.y
            val denom = kotlin.math.hypot(abx, aby) * kotlin.math.hypot(cbx, cby)
            if (denom < 1e-6) return 0f
            val cosine = kotlin.math.abs((abx * cbx + aby * cby) / denom).toFloat()
            // Perspective can make corners non-90°, so reject only extreme/non-document angles.
            return (1f - cosine / .72f).coerceIn(0f, 1f)
        }
        return listOf(
            corner(points[3], points[0], points[1]), corner(points[0], points[1], points[2]),
            corner(points[1], points[2], points[3]), corner(points[2], points[3], points[0])
        ).average().toFloat()
    }

    private fun legacyDetect(bitmap: Bitmap): Detection {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 120 || height < 120) return Detection(null, 0f, "الصورة صغيرة")

        val step = max(4, min(width, height) / 120)
        val gridW = max(3, width / step)
        val gridH = max(3, height / step)
        val xHits = IntArray(gridW)
        val yHits = IntArray(gridH)
        val edgeGrid = IntArray(gridW * gridH)
        var brightnessSum = 0L
        var brightnessCount = 0
        var edgeSum = 0L
        var edgeCount = 0

        fun lum(x: Int, y: Int): Int {
            val c = bitmap.getPixel(x.coerceIn(0, width - 1), y.coerceIn(0, height - 1))
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            return (r * 30 + g * 59 + b * 11) / 100
        }

        for (gy in 1 until gridH - 1) {
            val y = gy * step
            for (gx in 1 until gridW - 1) {
                val x = gx * step
                val c = lum(x, y)
                brightnessSum += c
                brightnessCount++
                val dx = abs(lum(x + step, y) - lum(x - step, y))
                val dy = abs(lum(x, y + step) - lum(x, y - step))
                val edge = dx + dy
                edgeGrid[gy * gridW + gx] = edge
                edgeSum += edge
                edgeCount++
                if (edge >= 54) { xHits[gx]++; yHits[gy]++ }
            }
        }

        val brightness = if (brightnessCount == 0) 0 else (brightnessSum / brightnessCount).toInt()
        val edgeEnergy = if (edgeCount == 0) 0f else edgeSum.toFloat() / edgeCount

        fun peak(
            array: IntArray,
            fromRatio: Float,
            toRatio: Float,
            preferOuter: Boolean,
            preferLow: Boolean
        ): Pair<Int, Float> {
            val from = (array.size * fromRatio).toInt().coerceIn(1, array.lastIndex)
            val to = (array.size * toRatio).toInt().coerceIn(from, array.lastIndex - 1)
            val normalizer = if (array === xHits) gridH.toFloat() else gridW.toFloat()
            val band = max(1, array.size / 60)
            val smoothed = FloatArray(array.size)
            for (i in from..to) {
                var sum = 0
                var count = 0
                for (j in (i - band).coerceAtLeast(0)..(i + band).coerceAtMost(array.lastIndex)) {
                    sum += array[j]
                    count++
                }
                smoothed[i] = if (count == 0) 0f else sum.toFloat() / count
            }

            var strongest = 0f
            for (i in from..to) strongest = max(strongest, smoothed[i])
            val threshold = max(normalizer * .050f, strongest * .34f)
            val ordered = if (preferLow) (from..to) else (to downTo from)
            if (preferOuter) {
                for (i in ordered) {
                    if (smoothed[i] >= threshold) {
                        return i to (smoothed[i] / normalizer).coerceIn(0f, 1f)
                    }
                }
            }

            var bestIndex = from
            var bestValue = -1f
            for (i in from..to) {
                if (smoothed[i] > bestValue) {
                    bestValue = smoothed[i]
                    bestIndex = i
                }
            }
            return bestIndex to (bestValue.coerceAtLeast(0f) / normalizer).coerceIn(0f, 1f)
        }

        val (leftI, leftStrength) = peak(xHits, .02f, .48f, preferOuter = true, preferLow = true)
        val (rightI, rightStrength) = peak(xHits, .52f, .98f, preferOuter = true, preferLow = false)
        val (topI, topStrength) = peak(yHits, .02f, .48f, preferOuter = true, preferLow = true)
        val (bottomI, bottomStrength) = peak(yHits, .52f, .98f, preferOuter = true, preferLow = false)
        if (rightI <= leftI || bottomI <= topI) return Detection(null, 0f, "لم يتم العثور على حدود مستند")

        val left = leftI.toFloat() / gridW
        val right = rightI.toFloat() / gridW
        val top = topI.toFloat() / gridH
        val bottom = bottomI.toFloat() / gridH
        val rectW = right - left
        val rectH = bottom - top
        val area = rectW * rectH
        val ratio = if (rectH > 0f) rectW / rectH else 0f
        val minEdge = min(min(leftStrength, rightStrength), min(topStrength, bottomStrength))
        val avgEdge = (leftStrength + rightStrength + topStrength + bottomStrength) / 4f

        val areaScore = when {
            area < .08f || area > .92f -> 0f
            area in .16f..0.82f -> 1f
            else -> .58f
        }
        val ratioScore = when {
            ratio in .42f..1.70f -> 1f
            ratio in .28f..2.20f -> .62f
            else -> 0f
        }
        val edgeScore = ((avgEdge - .08f) / .24f).coerceIn(0f, 1f)
        val minEdgeScore = ((minEdge - .055f) / .16f).coerceIn(0f, 1f)
        val focusScore = ((edgeEnergy - 12f) / 28f).coerceIn(0f, 1f)

        val xBand = max(2, gridW / 16)
        val yBand = max(2, gridH / 16)
        val leftFit = fitXBoundary(edgeGrid, gridW, gridH, leftI, xBand, topI, bottomI)
        val rightFit = fitXBoundary(edgeGrid, gridW, gridH, rightI, xBand, topI, bottomI)
        val topFit = fitYBoundary(edgeGrid, gridW, gridH, topI, yBand, leftI, rightI)
        val bottomFit = fitYBoundary(edgeGrid, gridW, gridH, bottomI, yBand, leftI, rightI)
        val continuity = listOf(leftFit, rightFit, topFit, bottomFit).map { it.coverage }.average().toFloat().coerceIn(0f, 1f)
        val fitScore = listOf(leftFit, rightFit, topFit, bottomFit).map { it.fitScore }.average().toFloat().coerceIn(0f, 1f)

        val confidence = (
            edgeScore * .25f + minEdgeScore * .17f + areaScore * .15f + ratioScore * .10f +
                focusScore * .10f + continuity * .15f + fitScore * .08f
            ).coerceIn(0f, 1f)

        val raw = ScanQuad(
            intersect(leftFit, topFit), intersect(rightFit, topFit),
            intersect(rightFit, bottomFit), intersect(leftFit, bottomFit)
        )
        val quad = if (isSane(raw)) raw.clamped() else ScanQuad(
            ScanPoint(left, top), ScanPoint(right, top), ScanPoint(right, bottom), ScanPoint(left, bottom)
        )
        val marginOk = points(quad).all { it.x > .005f && it.y > .005f && it.x < .995f && it.y < .995f }
        // Post-capture detection is intentionally a little more permissive than auto-capture.
        // The user always sees and can correct the four points before the perspective crop is applied.
        val accepted = confidence >= .48f && areaScore > 0f && ratioScore > 0f && continuity >= .18f && fitScore >= .16f && marginOk
        val warning = when {
            brightness < 45 -> "الإضاءة منخفضة"
            brightness > 235 -> "الإضاءة شديدة"
            edgeEnergy < 11.5f -> "الصورة غير واضحة"
            !accepted -> "الحدود غير موثوقة؛ سيتم الاحتفاظ بالصورة كاملة"
            else -> null
        }
        return Detection(if (accepted) quad else null, confidence, warning)
    }

    private fun fitXBoundary(edgeGrid: IntArray, gridW: Int, gridH: Int, centerX: Int, band: Int, fromY: Int, toY: Int): LineFit {
        val independent = mutableListOf<Float>()
        val dependent = mutableListOf<Float>()
        val startY = fromY.coerceIn(1, gridH - 2)
        val endY = toY.coerceIn(startY, gridH - 2)
        for (gy in startY..endY) {
            var bestX = -1; var bestEdge = 0
            for (gx in (centerX - band).coerceIn(1, gridW - 2)..(centerX + band).coerceIn(1, gridW - 2)) {
                val edge = edgeGrid[gy * gridW + gx]
                if (edge > bestEdge) { bestEdge = edge; bestX = gx }
            }
            if (bestX >= 0 && bestEdge >= 48) { independent += gy.toFloat() / gridH; dependent += bestX.toFloat() / gridW }
        }
        return regress(independent, dependent, dependent.size.toFloat() / (endY - startY + 1).coerceAtLeast(1))
    }

    private fun fitYBoundary(edgeGrid: IntArray, gridW: Int, gridH: Int, centerY: Int, band: Int, fromX: Int, toX: Int): LineFit {
        val independent = mutableListOf<Float>()
        val dependent = mutableListOf<Float>()
        val startX = fromX.coerceIn(1, gridW - 2)
        val endX = toX.coerceIn(startX, gridW - 2)
        for (gx in startX..endX) {
            var bestY = -1; var bestEdge = 0
            for (gy in (centerY - band).coerceIn(1, gridH - 2)..(centerY + band).coerceIn(1, gridH - 2)) {
                val edge = edgeGrid[gy * gridW + gx]
                if (edge > bestEdge) { bestEdge = edge; bestY = gy }
            }
            if (bestY >= 0 && bestEdge >= 48) { independent += gx.toFloat() / gridW; dependent += bestY.toFloat() / gridH }
        }
        return regress(independent, dependent, dependent.size.toFloat() / (endX - startX + 1).coerceAtLeast(1))
    }

    private fun regress(x: List<Float>, y: List<Float>, coverage: Float): LineFit {
        if (x.size < 2 || x.size != y.size) return LineFit(0f, y.firstOrNull() ?: 0f, coverage.coerceIn(0f, 1f), 0f)
        val mx = x.average().toFloat(); val my = y.average().toFloat()
        var num = 0f; var den = 0f
        x.indices.forEach { i -> val dx = x[i] - mx; num += dx * (y[i] - my); den += dx * dx }
        val slope = if (den < 1e-6f) 0f else num / den
        val intercept = my - slope * mx
        var residual = 0f
        y.indices.forEach { i -> residual += abs(y[i] - (slope * x[i] + intercept)) }
        residual /= y.size.coerceAtLeast(1)
        return LineFit(slope, intercept, coverage.coerceIn(0f, 1f), (1f - residual / .055f).coerceIn(0f, 1f))
    }

    private fun intersect(xLine: LineFit, yLine: LineFit): ScanPoint {
        val denom = 1f - xLine.slope * yLine.slope
        if (abs(denom) < 1e-5f) return ScanPoint(xLine.intercept, yLine.intercept)
        val x = (xLine.slope * yLine.intercept + xLine.intercept) / denom
        return ScanPoint(x, yLine.slope * x + yLine.intercept)
    }

    private fun isSane(q: ScanQuad): Boolean {
        val pts = points(q)
        if (pts.any { it.x < -.08f || it.x > 1.08f || it.y < -.08f || it.y > 1.08f }) return false
        fun cross(a: ScanPoint, b: ScanPoint, c: ScanPoint): Float = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        val crosses = listOf(cross(pts[0], pts[1], pts[2]), cross(pts[1], pts[2], pts[3]), cross(pts[2], pts[3], pts[0]), cross(pts[3], pts[0], pts[1]))
        val same = crosses.all { it > .0005f } || crosses.all { it < -.0005f }
        if (!same) return false
        var area = 0f
        for (i in pts.indices) { val j = (i + 1) % pts.size; area += pts[i].x * pts[j].y - pts[j].x * pts[i].y }
        return abs(area) * .5f in .10f..0.82f
    }

    private fun points(q: ScanQuad) = listOf(q.topLeft, q.topRight, q.bottomRight, q.bottomLeft)
}
