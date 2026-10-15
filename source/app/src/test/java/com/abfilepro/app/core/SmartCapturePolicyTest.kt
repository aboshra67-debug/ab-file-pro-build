package com.abfilepro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P23: AB smart camera rules (what is in view, which sheets, when to auto-capture). */
class SmartCapturePolicyTest {
    private fun rect(l: Float, t: Float, r: Float, b: Float, score: Float = .9f) = DocumentCandidate(
        ScanQuad(ScanPoint(l, t), ScanPoint(r, t), ScanPoint(r, b), ScanPoint(l, b)),
        areaRatio = (r - l) * (b - t),
        score = score,
    )

    // Upright portrait frame 3:4 (width / height = 0.75).
    private val portrait = .75f

    @Test fun aLargeA4SheetIsADocument() {
        // 0.8 x 0.85 of a 3:4 frame -> 0.6 : 0.85 real -> ratio ~1.42 but large -> document
        assertEquals(DocumentKind.DOCUMENT, SmartCapturePolicy.classify(listOf(rect(.1f, .08f, .9f, .93f)), portrait))
    }

    @Test fun aSmallCardShapedSheetIsACard() {
        // width .6 of frame (=.45 real), height .38 -> ratio 1.18? use landscape card: w .64 -> .48, h .30 -> 1.6
        val card = rect(.18f, .35f, .82f, .65f)
        assertEquals(DocumentKind.ID_CARD, SmartCapturePolicy.classify(listOf(card), portrait))
    }

    @Test fun aLongNarrowSheetIsAReceipt() {
        // w .3 of frame (=.225 real), h .8 -> ratio 3.6
        assertEquals(DocumentKind.RECEIPT, SmartCapturePolicy.classify(listOf(rect(.35f, .1f, .65f, .9f)), portrait))
    }

    @Test fun twoSeparateSheetsAreSeveralSheetsAndNothingIsNone() {
        val two = listOf(rect(.05f, .1f, .45f, .4f), rect(.55f, .5f, .95f, .8f))
        assertEquals(DocumentKind.MULTI, SmartCapturePolicy.classify(two, portrait))
        assertEquals(DocumentKind.NONE, SmartCapturePolicy.classify(emptyList(), portrait))
    }

    @Test fun duplicateOutlinesOfOneSheetCollapseToOne() {
        val a = rect(.1f, .1f, .5f, .5f, score = .95f)
        val sameSheetSlightlyBigger = rect(.09f, .09f, .51f, .51f, score = .7f)
        val other = rect(.6f, .6f, .9f, .9f)
        val kept = SmartCapturePolicy.selectSeparate(listOf(sameSheetSlightlyBigger, other, a), maxDocs = 6)
        assertEquals(2, kept.size)
        assertTrue(kept.any { it.score == .95f })
    }

    @Test fun selectionRespectsTheMaximumNumberOfSheets() {
        val sheets = (0 until 5).map { i -> rect(.02f + i * .19f, .4f, .18f + i * .19f, .6f) }
        assertEquals(3, SmartCapturePolicy.selectSeparate(sheets, maxDocs = 3).size)
    }

    @Test fun sheetsAreOrderedTopToBottomThenRightToLeft() {
        val topLeft = rect(.05f, .05f, .45f, .35f)
        val topRight = rect(.55f, .06f, .95f, .36f)
        val bottom = rect(.2f, .6f, .8f, .9f)
        val ordered = SmartCapturePolicy.readingOrder(listOf(bottom, topLeft, topRight))
        assertEquals(listOf(topRight, topLeft, bottom), ordered)
    }

    @Test fun autoCaptureFiresOnlyAfterTheSheetIsHeldStill() {
        val tracker = AutoCaptureTracker(holdMs = 900, maxMovement = .03f, cooldownMs = 1200)
        val q = rect(.1f, .1f, .9f, .9f).quad
        assertEquals(0f, tracker.update(q, sharp = true, nowMs = 0), 0f)
        assertTrue(tracker.update(q, true, 450) in .4f..0.6f)
        assertEquals(1f, tracker.update(q, true, 900), 0f)
    }

    @Test fun movementBlurOrLosingTheSheetRestartsTheCount() {
        val tracker = AutoCaptureTracker(holdMs = 900, maxMovement = .03f, cooldownMs = 1200)
        val q = rect(.1f, .1f, .9f, .9f).quad
        val moved = rect(.2f, .1f, .95f, .9f).quad
        tracker.update(q, true, 0)
        tracker.update(q, true, 600)
        assertEquals(0f, tracker.update(moved, true, 700), 0f)
        assertTrue(tracker.update(moved, true, 1000) < 1f)
        assertEquals(0f, tracker.update(moved, false, 1700), 0f)
        assertEquals(0f, tracker.update(null, true, 1800), 0f)
    }

    @Test fun theSameSheetIsNotCapturedTwiceButTheNextOneIs() {
        val tracker = AutoCaptureTracker(holdMs = 900, maxMovement = .03f, cooldownMs = 1200)
        val first = rect(.1f, .1f, .9f, .9f).quad
        tracker.update(first, true, 0)
        assertEquals(1f, tracker.update(first, true, 900), 0f)
        tracker.captured(first, 900)
        assertEquals(0f, tracker.update(first, true, 1500), 0f)
        assertEquals(0f, tracker.update(first, true, 5000), 0f)
        val next = rect(.15f, .3f, .85f, .95f).quad
        assertEquals(0f, tracker.update(next, true, 5100), 0f)
        assertEquals(1f, tracker.update(next, true, 6000), 0f)
    }

    @Test fun modesMapToReviewModesAndTheSmartEngineCoversDocumentModes() {
        assertEquals(SmartCaptureMode.ID_CARD, SmartCaptureMode.from(ScanCaptureMode.ID_CARD))
        assertEquals(SmartCaptureMode.BATCH, SmartCaptureMode.from(ScanCaptureMode.BATCH))
        assertEquals(ScanCaptureMode.BATCH, SmartCaptureMode.MULTI.captureMode)
        assertTrue(ScannerEnginePolicy.usesSmart(ScanCaptureMode.RECEIPT, ScannerEngine.SMART))
        assertFalse(ScannerEnginePolicy.usesSmart(ScanCaptureMode.RECEIPT, ScannerEngine.GOOGLE))
        assertFalse(ScannerEnginePolicy.usesGoogle(ScanCaptureMode.RECEIPT, ScannerEngine.SMART))
    }
}
