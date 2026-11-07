package com.abfilepro.app.core

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** P32: the translation language file did not arrive in time (the download keeps going in the background). */
class TranslationModelTimeoutException : Exception("translation_model_timeout")

/**
 * P32: ML Kit's language-file download has no timeout of its own. On a weak or queued connection the
 * "downloading" message used to stay on screen forever. This turns a stall into a clear, retryable
 * error. A real user cancel is NOT converted, so it still reads as "cancelled".
 */
object ModelDownloadGuard {
    /** Translating a scanned page: the person is waiting on a button, allow a slow first download. */
    const val MANUAL_TIMEOUT_MS = 90_000L

    /** Live camera translation retries on its own, so it gives up sooner. */
    const val LIVE_TIMEOUT_MS = 45_000L

    suspend fun <T> within(timeoutMs: Long, block: suspend () -> T): T =
        try {
            withTimeout(timeoutMs) { block() }
        } catch (e: TimeoutCancellationException) {
            throw TranslationModelTimeoutException()
        }
}
