package com.abfilepro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P22: which modes use Google's scanner and how its pages join the review list. */
class ScannerEnginePolicyTest {
    @Test fun documentModesUseGoogleOnlyWhenItIsTheChosenEngine() {
        for (mode in listOf(ScanCaptureMode.DOCUMENT, ScanCaptureMode.BATCH, ScanCaptureMode.ID_CARD, ScanCaptureMode.RECEIPT, ScanCaptureMode.TRANSLATION)) {
            assertTrue(mode.name, ScannerEnginePolicy.usesGoogle(mode, ScannerEngine.GOOGLE))
            assertFalse(mode.name, ScannerEnginePolicy.usesGoogle(mode, ScannerEngine.BUILT_IN))
        }
    }

    @Test fun bookSplitAndImagesToPdfStayOnTheAppCamera() {
        assertFalse(ScannerEnginePolicy.usesGoogle(ScanCaptureMode.BOOK, ScannerEngine.GOOGLE))
        assertFalse(ScannerEnginePolicy.usesGoogle(ScanCaptureMode.PHOTO, ScannerEngine.GOOGLE))
    }

    @Test fun pageLimitFollowsTheModeAndWhatIsAlreadyScanned() {
        assertEquals(1, ScannerEnginePolicy.pageLimit(ScanCaptureMode.DOCUMENT, 0, retake = false))
        assertEquals(2, ScannerEnginePolicy.pageLimit(ScanCaptureMode.ID_CARD, 0, retake = false))
        assertEquals(1, ScannerEnginePolicy.pageLimit(ScanCaptureMode.ID_CARD, 1, retake = false))
        assertEquals(10, ScannerEnginePolicy.pageLimit(ScanCaptureMode.BATCH, 0, retake = false))
        assertEquals(3, ScannerEnginePolicy.pageLimit(ScanCaptureMode.BATCH, 7, retake = false))
        assertEquals(1, ScannerEnginePolicy.pageLimit(ScanCaptureMode.BATCH, 7, retake = true))
        assertFalse(ScannerEnginePolicy.canAdd(ScanCaptureMode.BATCH, 10))
        assertTrue(ScannerEnginePolicy.canAdd(ScanCaptureMode.BATCH, 9))
    }

    @Test fun retakeReplacesOnlyItsSlotAndKeepsTheOrder() {
        val (merged, retired) = ScannerEnginePolicy.merge(ScanCaptureMode.BATCH, listOf("a", "b", "c"), listOf("B2"), retakeIndex = 1, append = false)
        assertEquals(listOf("a", "B2", "c"), merged)
        assertEquals(listOf("b"), retired)
    }

    @Test fun addingPagesAppendsUpToTheLimitAndRetiresTheOverflow() {
        val existing = (1..9).map { "p$it" }
        val (merged, retired) = ScannerEnginePolicy.merge(ScanCaptureMode.BATCH, existing, listOf("x", "y"), retakeIndex = null, append = true)
        assertEquals(existing + "x", merged)
        assertEquals(listOf("y"), retired)
    }

    @Test fun aNewScanReplacesThePreviousReviewList() {
        val (merged, retired) = ScannerEnginePolicy.merge(ScanCaptureMode.DOCUMENT, listOf("old"), listOf("new"), retakeIndex = null, append = false)
        assertEquals(listOf("new"), merged)
        assertEquals(listOf("old"), retired)
    }

    @Test fun anEmptyResultChangesNothing() {
        val (merged, retired) = ScannerEnginePolicy.merge(ScanCaptureMode.BATCH, listOf("a"), emptyList<String>(), retakeIndex = 0, append = false)
        assertEquals(listOf("a"), merged)
        assertEquals(emptyList<String>(), retired)
    }

    @Test fun aStaleRetakeSlotAddsThePageInsteadOfWipingTheList() {
        val (merged, retired) = ScannerEnginePolicy.merge(ScanCaptureMode.BATCH, listOf("a", "b"), listOf("n"), retakeIndex = 5, append = false)
        assertEquals(listOf("a", "b", "n"), merged)
        assertEquals(emptyList<String>(), retired)
    }
}
