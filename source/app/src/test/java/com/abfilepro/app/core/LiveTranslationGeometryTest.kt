package com.abfilepro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** P24: live translation geometry/targets and book page order. */
class LiveTranslationGeometryTest {
    @Test fun blocksAreNormalizedToTheUprightFrameAndClamped() {
        val block = LiveTranslationGeometry.normalize("Hello  world", 48, 100, 432, 160, 480, 640)!!
        assertEquals("Hello world", block.text)
        assertEquals(.1f, block.left, 1e-4f); assertEquals(100f / 640, block.top, 1e-4f)
        assertEquals(.9f, block.right, 1e-4f); assertEquals(.25f, block.bottom, 1e-4f)
        val clamped = LiveTranslationGeometry.normalize("Edge text", -20, -5, 600, 30, 480, 640)!!
        assertEquals(0f, clamped.left, 0f); assertEquals(1f, clamped.right, 0f)
    }

    @Test fun noiseBlocksAreDropped() {
        assertNull(LiveTranslationGeometry.normalize("a", 0, 0, 200, 50, 480, 640))
        assertNull(LiveTranslationGeometry.normalize("12 34", 0, 0, 200, 50, 480, 640))
        assertNull(LiveTranslationGeometry.normalize("tiny", 0, 0, 3, 3, 480, 640))
        assertNull(LiveTranslationGeometry.normalize("Hello", 0, 0, 10, 10, 0, 640))
    }

    @Test fun cameraFramesAreTurnedUpright() {
        assertEquals(480 to 640, LiveTranslationGeometry.uprightSize(640, 480, 90))
        assertEquals(480 to 640, LiveTranslationGeometry.uprightSize(640, 480, 270))
        assertEquals(640 to 480, LiveTranslationGeometry.uprightSize(640, 480, 0))
    }

    @Test fun foreignTextGoesToArabicAndArabicTextGoesToEnglish() {
        assertEquals("ar", LiveTranslationGeometry.targetFor("en", "ar"))
        assertEquals("ar", LiveTranslationGeometry.targetFor("fr", "ar"))
        assertEquals("en", LiveTranslationGeometry.targetFor("ar", "ar"))
        assertEquals("fr", LiveTranslationGeometry.targetFor("ar", "fr"))
        assertEquals("en", LiveTranslationGeometry.targetFor("fr", "en"))
        assertEquals("ar", LiveTranslationGeometry.targetFor("en", "en"))
    }

    @Test fun arabicBooksAreReadRightPageFirst() {
        assertEquals(listOf("right", "left"), SmartCapturePolicy.bookReadingOrder(listOf("left", "right"), rightToLeft = true))
        assertEquals(listOf("left", "right"), SmartCapturePolicy.bookReadingOrder(listOf("left", "right"), rightToLeft = false))
        assertEquals(SmartCaptureMode.BOOK, SmartCaptureMode.from(ScanCaptureMode.BOOK))
    }
}
