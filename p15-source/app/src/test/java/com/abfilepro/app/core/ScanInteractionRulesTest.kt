package com.abfilepro.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanInteractionRulesTest {
    @Test
    fun reviewBackReturnsToHub() {
        assertEquals(
            ScanBackTarget.HUB,
            ScanInteractionRules.backTarget(ScanFlowStage.REVIEW, hasPages = true)
        )
    }

    @Test
    fun cameraBackReturnsToReviewWhenPagesExist() {
        assertEquals(
            ScanBackTarget.REVIEW,
            ScanInteractionRules.backTarget(ScanFlowStage.CAMERA, hasPages = true)
        )
    }

    @Test
    fun cameraBackReturnsToHubWhenNoPagesExist() {
        assertEquals(
            ScanBackTarget.HUB,
            ScanInteractionRules.backTarget(ScanFlowStage.CAMERA, hasPages = false)
        )
    }

    @Test
    fun pinchZoomIsClampedToCameraRange() {
        assertEquals(2.0f, ScanInteractionRules.nextZoomRatio(1.0f, 2.0f, 1.0f, 4.0f))
        assertEquals(4.0f, ScanInteractionRules.nextZoomRatio(3.0f, 2.0f, 1.0f, 4.0f))
        assertEquals(1.0f, ScanInteractionRules.nextZoomRatio(1.2f, 0.1f, 1.0f, 4.0f))
    }
}
