package com.abfilepro.app.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModelDownloadGuardTest {
    @Test fun fastDownloadReturnsItsValue() = runBlocking {
        assertEquals("ok", ModelDownloadGuard.within(1_000) { "ok" })
    }

    @Test fun stalledDownloadBecomesARetryableError() {
        try {
            runBlocking { ModelDownloadGuard.within(50) { delay(5_000) } }
            fail("expected a timeout")
        } catch (e: TranslationModelTimeoutException) {
            // The error is a normal exception, NOT a CancellationException, so the scanner does not
            // show it as "cancelled by the user".
            val asThrowable: Throwable = e
            assertFalse(asThrowable is CancellationException)
        }
    }

    @Test fun realCancelIsNotTurnedIntoATimeout() = runBlocking {
        var converted = false
        val job = launch {
            try {
                ModelDownloadGuard.within(10_000) { delay(10_000) }
            } catch (e: TranslationModelTimeoutException) {
                converted = true
            }
        }
        delay(50)
        job.cancelAndJoin()
        assertFalse(converted)
        assertTrue(job.isCancelled)
    }

    @Test fun otherErrorsPassThroughUnchanged() {
        try {
            runBlocking { ModelDownloadGuard.within(1_000) { throw IllegalStateException("no network") } }
            fail("expected the original error")
        } catch (e: IllegalStateException) {
            assertEquals("no network", e.message)
        }
    }

    @Test fun liveGivesUpSoonerThanTheManualButton() {
        assertTrue(ModelDownloadGuard.LIVE_TIMEOUT_MS < ModelDownloadGuard.MANUAL_TIMEOUT_MS)
        assertTrue(ModelDownloadGuard.MANUAL_TIMEOUT_MS in 30_000L..180_000L)
    }
}
