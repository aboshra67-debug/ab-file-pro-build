package com.abfilepro.app.core

import android.graphics.RectF
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight, fully on-device document candidate analyzer.
 *
 * Stage C hardening adds:
 * - continuous-edge scoring instead of trusting four isolated edge peaks;
 * - a perspective quadrilateral estimated from four fitted boundary lines;
 * - stricter stability requirements before auto capture;
 * - near-full-frame rejection to reduce walls/doors/furniture false positives.
 *
 * Frames are never uploaded or persisted by this analyzer.
 */
class DocumentFrameAnalyzer(
    private val mode: ScanCaptureMode,
    private val liveDocumentPreview: Boolean = false,
    private val onResult: (FrameResult) -> Unit
) : ImageAnalysis.Analyzer {

    enum class Warning { LOW_LIGHT, GLARE, BLUR_OR_SHAKE, NO_DOCUMENT, MOVE_CLOSER, HOLD_STILL }

    data class FrameResult(
        val rect: RectF?,
        val quad: ScanQuad?,
        val confidence: Float,
        val brightness: Int,
        val edgeEnergy: Float,
        val stableFrames: Int,
        val warning: Warning?,
        val previewAspectRatio: Float
    ) {
        val captureReady: Boolean
            get() = quad != null && confidence >= 0.68f && stableFrames >= 10 && warning == null
    }

    private data class LineFit(
        val slope: Float,
        val intercept: Float,
        val coverage: Float,
        val fitScore: Float
    )

    private var lastQuad: ScanQuad? = null
    private var displayQuadState: ScanQuad? = null
    private var missedCandidateFrames = 0
    private var stableFrames = 0
    private var frameCounter = 0

    override fun analyze(image: ImageProxy) {
        try {
            frameCounter++
            // Alpha47: the single-document AUTO camera needs a responsive live outline.
            // Other scanner modes keep the existing every-other-frame analyzer cadence.
            if (!liveDocumentPreview && frameCounter % 2 != 0) return

            val plane = image.planes.firstOrNull() ?: return
            val width = image.width
            val height = image.height
            if (width < 80 || height < 80) return

            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val step = max(4, min(width, height) / 120)
            val gridW = max(3, width / step)
            val gridH = max(3, height / step)
            val xHits = IntArray(gridW)
            val yHits = IntArray(gridH)
            val edgeGrid = IntArray(gridW * gridH)

            var luminanceSum = 0L
            var luminanceCount = 0
            var edgeSum = 0L
            var edgeCount = 0

            fun yAt(x: Int, y: Int): Int {
                val safeX = x.coerceIn(0, width - 1)
                val safeY = y.coerceIn(0, height - 1)
                val index = safeY * rowStride + safeX * pixelStride
                return if (index in 0 until buffer.limit()) buffer.get(index).toInt() and 0xFF else 0
            }

            var gy = 1
            while (gy < gridH - 1) {
                val y = gy * step
                var gx = 1
                while (gx < gridW - 1) {
                    val x = gx * step
                    val c = yAt(x, y)
                    luminanceSum += c
                    luminanceCount++
                    val dx = abs(yAt(x + step, y) - yAt(x - step, y))
                    val dy = abs(yAt(x, y + step) - yAt(x, y - step))
                    val edge = dx + dy
                    edgeGrid[gy * gridW + gx] = edge
                    edgeSum += edge
                    edgeCount++
                    if (edge >= 54) {
                        xHits[gx]++
                        yHits[gy]++
                    }
                    gx++
                }
                gy++
            }

            val brightness = if (luminanceCount == 0) 0 else (luminanceSum / luminanceCount).toInt()
            val edgeEnergy = if (edgeCount == 0) 0f else edgeSum.toFloat() / edgeCount.toFloat()

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
                val smoothedHits = FloatArray(array.size)
                for (i in from..to) {
                    var sum = 0
                    var count = 0
                    for (j in (i - band).coerceAtLeast(0)..(i + band).coerceAtMost(array.lastIndex)) {
                        sum += array[j]
                        count++
                    }
                    smoothedHits[i] = if (count == 0) 0f else sum.toFloat() / count.toFloat()
                }

                var strongest = 0f
                for (i in from..to) strongest = max(strongest, smoothedHits[i])
                val threshold = max(normalizer * .055f, strongest * .38f)
                val ordered = if (preferLow) (from..to) else (to downTo from)
                if (preferOuter) {
                    for (i in ordered) {
                        if (smoothedHits[i] >= threshold) {
                            return i to (smoothedHits[i] / normalizer).coerceIn(0f, 1f)
                        }
                    }
                }

                var bestIndex = from
                var bestValue = -1f
                for (i in from..to) {
                    if (smoothedHits[i] > bestValue) {
                        bestValue = smoothedHits[i]
                        bestIndex = i
                    }
                }
                return bestIndex to (bestValue.coerceAtLeast(0f) / normalizer).coerceIn(0f, 1f)
            }

            val (leftI, leftStrength) = peak(xHits, .04f, .46f, preferOuter = true, preferLow = true)
            val (rightI, rightStrength) = peak(xHits, .54f, .96f, preferOuter = true, preferLow = false)
            val (topI, topStrength) = peak(yHits, .04f, .46f, preferOuter = true, preferLow = true)
            val (bottomI, bottomStrength) = peak(yHits, .54f, .96f, preferOuter = true, preferLow = false)

            var candidateRect: RectF? = null
            var candidateQuad: ScanQuad? = null
            var confidence = 0f

            if (rightI > leftI && bottomI > topI) {
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

                val minAreaForMode = when (mode) {
                    ScanCaptureMode.DOCUMENT, ScanCaptureMode.BATCH, ScanCaptureMode.DUPLEX,
                    ScanCaptureMode.TRANSLATION, ScanCaptureMode.PASSPORT -> .22f
                    ScanCaptureMode.BOOK, ScanCaptureMode.WHITEBOARD -> .18f
                    ScanCaptureMode.ID_CARD -> .11f
                    ScanCaptureMode.RECEIPT -> .08f
                    ScanCaptureMode.PHOTO -> 1f
                }
                val areaScore = when {
                    area < minAreaForMode || area > .82f -> 0f
                    area in max(minAreaForMode + .06f, .24f)..0.70f -> 1f
                    else -> .56f
                }
                val ratioScore = ratioScoreForMode(ratio)
                val edgeScore = ((avgEdge - .08f) / .24f).coerceIn(0f, 1f)
                val minEdgeScore = ((minEdge - .055f) / .16f).coerceIn(0f, 1f)
                val focusScore = ((edgeEnergy - 12f) / 28f).coerceIn(0f, 1f)

                val xBand = max(2, gridW / 16)
                val yBand = max(2, gridH / 16)
                val leftFit = fitXBoundary(edgeGrid, gridW, gridH, leftI, xBand, topI, bottomI)
                val rightFit = fitXBoundary(edgeGrid, gridW, gridH, rightI, xBand, topI, bottomI)
                val topFit = fitYBoundary(edgeGrid, gridW, gridH, topI, yBand, leftI, rightI)
                val bottomFit = fitYBoundary(edgeGrid, gridW, gridH, bottomI, yBand, leftI, rightI)

                val continuity = listOf(leftFit, rightFit, topFit, bottomFit)
                    .map { it.coverage }
                    .average().toFloat().coerceIn(0f, 1f)
                val lineFitScore = listOf(leftFit, rightFit, topFit, bottomFit)
                    .map { it.fitScore }
                    .average().toFloat().coerceIn(0f, 1f)

                confidence = (
                    edgeScore * .25f +
                        minEdgeScore * .17f +
                        areaScore * .15f +
                        ratioScore * .10f +
                        focusScore * .10f +
                        continuity * .15f +
                        lineFitScore * .08f
                    ).coerceIn(0f, 1f)

                val rawQuad = ScanQuad(
                    intersectXwithY(leftFit, topFit),
                    intersectXwithY(rightFit, topFit),
                    intersectXwithY(rightFit, bottomFit),
                    intersectXwithY(leftFit, bottomFit)
                )
                val saneQuad = if (isSaneQuad(rawQuad)) rawQuad.clamped() else ScanQuad(
                    ScanPoint(left, top),
                    ScanPoint(right, top),
                    ScanPoint(right, bottom),
                    ScanPoint(left, bottom)
                )

                val marginOk = saneQuad.points().all { it.x > .02f && it.y > .02f && it.x < .98f && it.y < .98f }
                val continuousEnough = continuity >= .30f
                val fitEnough = lineFitScore >= .28f
                val candidateConfidenceFloor = if (liveDocumentPreview) .42f else .52f
                val liveContinuityOk = if (liveDocumentPreview) continuity >= .22f else continuousEnough
                val liveFitOk = if (liveDocumentPreview) lineFitScore >= .20f else fitEnough
                if (
                    confidence >= candidateConfidenceFloor && areaScore > 0f && ratioScore > 0f &&
                    marginOk && liveContinuityOk && liveFitOk
                ) {
                    val rotatedQuad = rotateNormalizedQuad(saneQuad, image.imageInfo.rotationDegrees)
                    candidateQuad = rotatedQuad
                    candidateRect = boundingRect(rotatedQuad)
                }
            }

            val displayConfidenceFloor = if (liveDocumentPreview) .44f else .62f
            val stableMovementLimit = if (liveDocumentPreview) .030f else .022f
            if (candidateQuad != null && confidence >= displayConfidenceFloor) {
                val previous = lastQuad
                val movement = if (previous == null) 1f else quadMovement(previous, candidateQuad)
                stableFrames = if (previous != null && movement < stableMovementLimit) {
                    (stableFrames + 1).coerceAtMost(36)
                } else {
                    1
                }
                lastQuad = candidateQuad
                missedCandidateFrames = 0

                val currentDisplay = displayQuadState
                val displayMovement = if (currentDisplay == null) 0f else quadMovement(currentDisplay, candidateQuad)
                val mayAdoptCandidate = currentDisplay == null || displayMovement < .075f || stableFrames >= (if (liveDocumentPreview) 2 else 4)
                if (stableFrames >= (if (liveDocumentPreview) 1 else 2) && mayAdoptCandidate) {
                    val alpha = if (liveDocumentPreview) {
                        if (displayMovement < .040f) .52f else .36f
                    } else {
                        if (displayMovement < .035f) .42f else .28f
                    }
                    displayQuadState = smoothQuad(currentDisplay, candidateQuad, alpha)
                }
            } else {
                stableFrames = 0
                lastQuad = null
                missedCandidateFrames = (missedCandidateFrames + 1).coerceAtMost(8)
                if (missedCandidateFrames > if (liveDocumentPreview) 6 else 4) displayQuadState = null
            }

            // Keep the last trustworthy outline visible through very short detector dropouts.
            // Capture readiness still depends on the CURRENT confidence/stability/warning values,
            // so this visual hysteresis cannot trigger an unsafe auto-capture.
            val displayHoldFrames = if (liveDocumentPreview) 6 else 4
            val displayQuad = if (candidateQuad != null || missedCandidateFrames <= displayHoldFrames) displayQuadState else null
            val displayRect = displayQuad?.let { boundingRect(it) }

            val warning = when {
                brightness < 48 -> Warning.LOW_LIGHT
                brightness > 232 -> Warning.GLARE
                edgeEnergy < 11.5f -> Warning.BLUR_OR_SHAKE
                candidateQuad == null -> Warning.NO_DOCUMENT
                liveDocumentPreview && confidence < .54f -> Warning.MOVE_CLOSER
                liveDocumentPreview && stableFrames < 4 -> Warning.HOLD_STILL
                !liveDocumentPreview && confidence < .68f -> Warning.MOVE_CLOSER
                !liveDocumentPreview && stableFrames < 10 -> Warning.HOLD_STILL
                else -> null
            }

            val rotation = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
            val displayWidth = if (rotation == 90 || rotation == 270) height else width
            val displayHeight = if (rotation == 90 || rotation == 270) width else height
            val previewAspectRatio = displayWidth.toFloat() / displayHeight.toFloat().coerceAtLeast(1f)

            onResult(
                FrameResult(
                    rect = displayRect,
                    quad = displayQuad,
                    confidence = confidence,
                    brightness = brightness,
                    edgeEnergy = edgeEnergy,
                    stableFrames = stableFrames,
                    warning = warning,
                    previewAspectRatio = previewAspectRatio
                )
            )
        } finally {
            image.close()
        }
    }

    private fun smoothQuad(previous: ScanQuad?, current: ScanQuad, alpha: Float): ScanQuad {
        if (previous == null) return current
        val a = alpha.coerceIn(.12f, .65f)
        fun blend(p: ScanPoint, c: ScanPoint) = ScanPoint(
            x = p.x + (c.x - p.x) * a,
            y = p.y + (c.y - p.y) * a
        )
        return ScanQuad(
            topLeft = blend(previous.topLeft, current.topLeft),
            topRight = blend(previous.topRight, current.topRight),
            bottomRight = blend(previous.bottomRight, current.bottomRight),
            bottomLeft = blend(previous.bottomLeft, current.bottomLeft)
        ).clamped()
    }

    private fun fitXBoundary(
        edgeGrid: IntArray,
        gridW: Int,
        gridH: Int,
        centerX: Int,
        band: Int,
        fromY: Int,
        toY: Int
    ): LineFit {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val startY = fromY.coerceIn(1, gridH - 2)
        val endY = toY.coerceIn(startY, gridH - 2)
        for (gy in startY..endY) {
            var bestX = -1
            var bestEdge = 0
            val startX = (centerX - band).coerceIn(1, gridW - 2)
            val endX = (centerX + band).coerceIn(startX, gridW - 2)
            for (gx in startX..endX) {
                val edge = edgeGrid[gy * gridW + gx]
                if (edge > bestEdge) {
                    bestEdge = edge
                    bestX = gx
                }
            }
            if (bestX >= 0 && bestEdge >= 48) {
                xs += bestX.toFloat() / gridW
                ys += gy.toFloat() / gridH
            }
        }
        val expected = (endY - startY + 1).coerceAtLeast(1)
        return regress(independent = ys, dependent = xs, coverage = xs.size.toFloat() / expected)
    }

    private fun fitYBoundary(
        edgeGrid: IntArray,
        gridW: Int,
        gridH: Int,
        centerY: Int,
        band: Int,
        fromX: Int,
        toX: Int
    ): LineFit {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val startX = fromX.coerceIn(1, gridW - 2)
        val endX = toX.coerceIn(startX, gridW - 2)
        for (gx in startX..endX) {
            var bestY = -1
            var bestEdge = 0
            val startY = (centerY - band).coerceIn(1, gridH - 2)
            val endY = (centerY + band).coerceIn(startY, gridH - 2)
            for (gy in startY..endY) {
                val edge = edgeGrid[gy * gridW + gx]
                if (edge > bestEdge) {
                    bestEdge = edge
                    bestY = gy
                }
            }
            if (bestY >= 0 && bestEdge >= 48) {
                xs += gx.toFloat() / gridW
                ys += bestY.toFloat() / gridH
            }
        }
        val expected = (endX - startX + 1).coerceAtLeast(1)
        return regress(independent = xs, dependent = ys, coverage = xs.size.toFloat() / expected)
    }

    private fun regress(independent: List<Float>, dependent: List<Float>, coverage: Float): LineFit {
        if (independent.size < 2 || independent.size != dependent.size) {
            return LineFit(0f, dependent.firstOrNull() ?: 0f, coverage.coerceIn(0f, 1f), 0f)
        }
        val meanX = independent.average().toFloat()
        val meanY = dependent.average().toFloat()
        var numerator = 0f
        var denominator = 0f
        independent.indices.forEach { i ->
            val dx = independent[i] - meanX
            numerator += dx * (dependent[i] - meanY)
            denominator += dx * dx
        }
        val slope = if (denominator < 1e-6f) 0f else numerator / denominator
        val intercept = meanY - slope * meanX
        var residual = 0f
        dependent.indices.forEach { i ->
            residual += abs(dependent[i] - (slope * independent[i] + intercept))
        }
        residual /= dependent.size.coerceAtLeast(1)
        val fitScore = (1f - residual / .055f).coerceIn(0f, 1f)
        return LineFit(slope, intercept, coverage.coerceIn(0f, 1f), fitScore)
    }

    /** x = xLine.slope*y + xLine.intercept, y = yLine.slope*x + yLine.intercept */
    private fun intersectXwithY(xLine: LineFit, yLine: LineFit): ScanPoint {
        val denom = 1f - xLine.slope * yLine.slope
        if (abs(denom) < 1e-5f) return ScanPoint(xLine.intercept, yLine.intercept)
        val x = (xLine.slope * yLine.intercept + xLine.intercept) / denom
        val y = yLine.slope * x + yLine.intercept
        return ScanPoint(x, y)
    }

    private fun isSaneQuad(q: ScanQuad): Boolean {
        val pts = q.points()
        if (pts.any { it.x < -.08f || it.x > 1.08f || it.y < -.08f || it.y > 1.08f }) return false
        fun cross(a: ScanPoint, b: ScanPoint, c: ScanPoint): Float =
            (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        val crosses = listOf(
            cross(pts[0], pts[1], pts[2]),
            cross(pts[1], pts[2], pts[3]),
            cross(pts[2], pts[3], pts[0]),
            cross(pts[3], pts[0], pts[1])
        )
        val sameDirection = crosses.all { it > .0005f } || crosses.all { it < -.0005f }
        if (!sameDirection) return false
        val area = polygonArea(pts)
        return area in .10f..0.82f
    }

    private fun polygonArea(points: List<ScanPoint>): Float {
        var sum = 0f
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) * .5f
    }

    private fun quadMovement(a: ScanQuad, b: ScanQuad): Float {
        val ap = a.points()
        val bp = b.points()
        var total = 0f
        ap.indices.forEach { i ->
            total += abs(ap[i].x - bp[i].x) + abs(ap[i].y - bp[i].y)
        }
        return total / 8f
    }

    private fun boundingRect(quad: ScanQuad): RectF {
        val pts = quad.points()
        return RectF(
            pts.minOf { it.x }, pts.minOf { it.y },
            pts.maxOf { it.x }, pts.maxOf { it.y }
        )
    }

    private fun ScanQuad.points() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    private fun ratioScoreForMode(ratio: Float): Float = when (mode) {
        ScanCaptureMode.DOCUMENT, ScanCaptureMode.BATCH, ScanCaptureMode.DUPLEX, ScanCaptureMode.TRANSLATION -> when {
            ratio in .56f..0.96f || ratio in 1.04f..1.82f -> 1f
            ratio in .48f..2.0f -> .45f
            else -> 0f
        }
        ScanCaptureMode.BOOK -> when {
            ratio in 1.08f..2.25f -> 1f
            ratio in .92f..2.4f -> .45f
            else -> 0f
        }
        ScanCaptureMode.ID_CARD -> when {
            ratio in 1.28f..1.82f || ratio in .55f..0.78f -> 1f
            ratio in .48f..2.0f -> .42f
            else -> 0f
        }
        ScanCaptureMode.PASSPORT -> when {
            ratio in .62f..0.88f || ratio in 1.12f..1.62f -> 1f
            ratio in .52f..1.8f -> .45f
            else -> 0f
        }
        ScanCaptureMode.RECEIPT -> when {
            ratio in .28f..0.86f -> 1f
            ratio in .22f..1.0f -> .5f
            else -> 0f
        }
        ScanCaptureMode.WHITEBOARD -> when {
            ratio in .60f..2.20f -> 1f
            ratio in .50f..2.35f -> .5f
            else -> 0f
        }
        ScanCaptureMode.PHOTO -> 0f
    }

    private fun rotateNormalizedQuad(quad: ScanQuad, degrees: Int): ScanQuad = ScanQuad(
        rotateNormalizedPoint(quad.topLeft, degrees),
        rotateNormalizedPoint(quad.topRight, degrees),
        rotateNormalizedPoint(quad.bottomRight, degrees),
        rotateNormalizedPoint(quad.bottomLeft, degrees)
    ).let { rotated ->
        // Rotation can change semantic corner order. Sort into visual TL/TR/BR/BL.
        val pts = rotated.points()
        val tl = pts.minBy { it.x + it.y }
        val br = pts.maxBy { it.x + it.y }
        val tr = pts.maxBy { it.x - it.y }
        val bl = pts.minBy { it.x - it.y }
        ScanQuad(tl, tr, br, bl)
    }

    private fun rotateNormalizedPoint(point: ScanPoint, degrees: Int): ScanPoint = when ((degrees % 360 + 360) % 360) {
        90 -> ScanPoint(1f - point.y, point.x)
        180 -> ScanPoint(1f - point.x, 1f - point.y)
        270 -> ScanPoint(point.y, 1f - point.x)
        else -> point
    }
}
