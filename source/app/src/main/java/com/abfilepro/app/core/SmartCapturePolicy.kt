package com.abfilepro.app.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** P23: modes of the AB smart camera (mode bar under the viewfinder). */
enum class SmartCaptureMode(val captureMode: ScanCaptureMode) {
    AUTO(ScanCaptureMode.BATCH),
    DOCUMENT(ScanCaptureMode.DOCUMENT),
    BATCH(ScanCaptureMode.BATCH),
    ID_CARD(ScanCaptureMode.ID_CARD),
    RECEIPT(ScanCaptureMode.RECEIPT),
    MULTI(ScanCaptureMode.BATCH),
    TRANSLATION(ScanCaptureMode.TRANSLATION),
    /** P24: an open book; each shot becomes two pages (right page first for Arabic books). */
    BOOK(ScanCaptureMode.BOOK);

    companion object {
        fun from(mode: ScanCaptureMode): SmartCaptureMode = when (mode) {
            ScanCaptureMode.DOCUMENT, ScanCaptureMode.PASSPORT -> DOCUMENT
            ScanCaptureMode.BATCH, ScanCaptureMode.WHITEBOARD, ScanCaptureMode.PHOTO -> BATCH
            ScanCaptureMode.BOOK -> BOOK
            ScanCaptureMode.ID_CARD, ScanCaptureMode.DUPLEX -> ID_CARD
            ScanCaptureMode.RECEIPT -> RECEIPT
            ScanCaptureMode.TRANSLATION -> TRANSLATION
        }
    }
}

/** What the camera thinks is in front of it (used by AUTO). */
enum class DocumentKind { NONE, DOCUMENT, ID_CARD, RECEIPT, MULTI }

data class DocumentCandidate(val quad: ScanQuad, val areaRatio: Float, val score: Float)

object SmartCapturePolicy {
    /**
     * Width/height ratio of a quad on the real frame (long side / short side).
     * [frameAspect] = frame width / frame height of the upright frame the quad is normalized to.
     */
    fun longToShortRatio(quad: ScanQuad, frameAspect: Float): Float {
        fun len(a: ScanPoint, b: ScanPoint) = hypot(((a.x - b.x) * frameAspect).toDouble(), (a.y - b.y).toDouble()).toFloat()
        val top = len(quad.topLeft, quad.topRight)
        val bottom = len(quad.bottomLeft, quad.bottomRight)
        val left = len(quad.topLeft, quad.bottomLeft)
        val right = len(quad.topRight, quad.bottomRight)
        val w = (top + bottom) / 2f
        val h = (left + right) / 2f
        val shortSide = min(w, h).coerceAtLeast(1e-4f)
        return max(w, h) / shortSide
    }

    /**
     * Classifies what is in view. Cards are ~1.586:1 and usually small in frame, receipts
     * are long and narrow, two or more separate sheets mean "several sheets".
     */
    fun classify(candidates: List<DocumentCandidate>, frameAspect: Float): DocumentKind {
        val usable = candidates.filter { it.areaRatio >= MIN_MULTI_AREA }
        if (usable.size >= 2) return DocumentKind.MULTI
        val best = candidates.maxByOrNull { it.areaRatio } ?: return DocumentKind.NONE
        val ratio = longToShortRatio(best.quad, frameAspect)
        return when {
            ratio >= 2.15f -> DocumentKind.RECEIPT
            ratio in 1.48f..1.72f && best.areaRatio <= .42f -> DocumentKind.ID_CARD
            else -> DocumentKind.DOCUMENT
        }
    }

    /**
     * Keeps non-overlapping candidates, best first, then orders them for reading:
     * rows from top to bottom, and inside a row from right to left (Arabic reading order).
     */
    fun selectSeparate(candidates: List<DocumentCandidate>, maxDocs: Int): List<DocumentCandidate> {
        val kept = mutableListOf<DocumentCandidate>()
        for (candidate in candidates.sortedByDescending { it.score * .6f + it.areaRatio * .4f }) {
            if (kept.size >= maxDocs) break
            val box = bounds(candidate.quad)
            if (kept.none { overlap(bounds(it.quad), box) > .25f }) kept += candidate
        }
        return readingOrder(kept)
    }

    fun readingOrder(candidates: List<DocumentCandidate>): List<DocumentCandidate> {
        if (candidates.size <= 1) return candidates
        val withCenters = candidates.map { it to center(it.quad) }
        val rows = mutableListOf<MutableList<Pair<DocumentCandidate, ScanPoint>>>()
        for (item in withCenters.sortedBy { it.second.y }) {
            val height = bounds(item.first.quad).let { it[3] - it[1] }
            val row = rows.lastOrNull()
            if (row != null && abs(row.first().second.y - item.second.y) < height * .5f) row += item else rows += mutableListOf(item)
        }
        return rows.flatMap { row -> row.sortedByDescending { it.second.x }.map { it.first } }
    }

    private fun center(q: ScanQuad) = ScanPoint(
        (q.topLeft.x + q.topRight.x + q.bottomRight.x + q.bottomLeft.x) / 4f,
        (q.topLeft.y + q.topRight.y + q.bottomRight.y + q.bottomLeft.y) / 4f
    )

    /** [left, top, right, bottom] */
    private fun bounds(q: ScanQuad): FloatArray {
        val xs = listOf(q.topLeft.x, q.topRight.x, q.bottomRight.x, q.bottomLeft.x)
        val ys = listOf(q.topLeft.y, q.topRight.y, q.bottomRight.y, q.bottomLeft.y)
        return floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
    }

    /** Intersection over the smaller box (a small sheet inside a big one counts as overlapping). */
    private fun overlap(a: FloatArray, b: FloatArray): Float {
        val w = (min(a[2], b[2]) - max(a[0], b[0])).coerceAtLeast(0f)
        val h = (min(a[3], b[3]) - max(a[1], b[1])).coerceAtLeast(0f)
        val inter = w * h
        val smaller = min((a[2] - a[0]) * (a[3] - a[1]), (b[2] - b[0]) * (b[3] - b[1])).coerceAtLeast(1e-6f)
        return inter / smaller
    }

    /** Largest corner movement between two quads (normalized units). */
    fun movement(a: ScanQuad, b: ScanQuad): Float = listOf(
        hypot((a.topLeft.x - b.topLeft.x).toDouble(), (a.topLeft.y - b.topLeft.y).toDouble()),
        hypot((a.topRight.x - b.topRight.x).toDouble(), (a.topRight.y - b.topRight.y).toDouble()),
        hypot((a.bottomRight.x - b.bottomRight.x).toDouble(), (a.bottomRight.y - b.bottomRight.y).toDouble()),
        hypot((a.bottomLeft.x - b.bottomLeft.x).toDouble(), (a.bottomLeft.y - b.bottomLeft.y).toDouble()),
    ).max().toFloat()

    const val MIN_MULTI_AREA = .03f

    /** P24: a book shot yields [left, right]; Arabic books are read right page first. */
    fun <T> bookReadingOrder(leftThenRight: List<T>, rightToLeft: Boolean): List<T> =
        if (rightToLeft && leftThenRight.size == 2) leftThenRight.reversed() else leftThenRight
}

/**
 * P23: automatic capture. The frame must stay on the same sheet (corners moving less than
 * [maxMovement]) and be sharp for [holdMs]; progress feeds the ring around the shutter.
 * Any jump, blur or loss of the sheet restarts the count. After a capture it waits
 * [cooldownMs] and requires the sheet to change before counting again (no double shots).
 */
class AutoCaptureTracker(
    private val holdMs: Long = 900L,
    private val maxMovement: Float = .03f,
    private val cooldownMs: Long = 1200L,
) {
    private var anchor: ScanQuad? = null
    private var stableSince = -1L
    private var lastCaptureAt = -1L
    private var capturedQuad: ScanQuad? = null

    /** Returns progress 0..1; 1 means "capture now". */
    fun update(quad: ScanQuad?, sharp: Boolean, nowMs: Long): Float {
        if (quad == null || !sharp) {
            reset()
            if (quad == null) capturedQuad = null
            return 0f
        }
        if (lastCaptureAt >= 0 && nowMs - lastCaptureAt < cooldownMs) return 0f
        val previous = capturedQuad
        if (previous != null) {
            // Same sheet still in place after a capture: wait until it changes or leaves.
            if (SmartCapturePolicy.movement(previous, quad) < maxMovement * 3) return 0f
            capturedQuad = null
        }
        val current = anchor
        if (current == null || SmartCapturePolicy.movement(current, quad) > maxMovement) {
            anchor = quad
            stableSince = nowMs
            return 0f
        }
        return ((nowMs - stableSince).toFloat() / holdMs).coerceIn(0f, 1f)
    }

    fun captured(quad: ScanQuad?, nowMs: Long) {
        lastCaptureAt = nowMs
        capturedQuad = quad
        reset()
    }

    fun reset() {
        anchor = null
        stableSince = -1L
    }
}
