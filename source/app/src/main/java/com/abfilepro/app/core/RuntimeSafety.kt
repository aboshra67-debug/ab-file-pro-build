package com.abfilepro.app.core

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.io.OutputStream
import java.io.File

/**
 * Runtime guardrails for long document operations.
 *
 * The implementation deliberately avoids reading document content. It only checks storage,
 * bounds batch sizes, and cleans AB-owned temporary files that are old enough to be abandoned.
 */
object RuntimeSafety {
    const val SAFE_SCAN_BATCH_PAGES = 30
    private const val MIN_FREE_BYTES = 96L * 1024L * 1024L
    private const val TEMP_MAX_AGE_MS = 24L * 60L * 60L * 1000L

    class InsufficientStorageException(
        val availableBytes: Long,
        val requiredBytes: Long
    ) : IllegalStateException("insufficient_storage")

    fun ensureStorage(context: Context, estimatedOutputBytes: Long = 0L) {
        val root = FileUtils.rootDir(context)
        val stats = StatFs(root.absolutePath)
        val available = stats.availableBytes.coerceAtLeast(0L)
        val required = (estimatedOutputBytes.coerceAtLeast(0L) + MIN_FREE_BYTES).coerceAtLeast(MIN_FREE_BYTES)
        if (available < required) throw InsufficientStorageException(available, required)
    }

    fun ensureSafePageBatch(count: Int, maxPages: Int = SAFE_SCAN_BATCH_PAGES) {
        require(count in 1..maxPages) { "unsafe_page_batch" }
    }

    /** Removes only old AB-owned cache folders/files. User workspace files are never touched. */
    fun cleanupTemporaryFiles(context: Context) {
        ConversionDraftManager.cleanup(context)
        val cutoff = System.currentTimeMillis() - TEMP_MAX_AGE_MS
        val cache = context.cacheDir
        val ownedPrefixes = listOf(
            "card_scan",
            "business_card_scan",
            "receipt_scan",
            "scan_review",
            "scanner_pro",
            "pdf_editor_patches",
            "pdf_editor_work",
            "pdf_merge",
            "clean_share",
            "stitch_drafts",
            "smart_form_drafts"
        )
        cache.listFiles().orEmpty().forEach { entry ->
            val isOwned = ownedPrefixes.any { prefix -> entry.name == prefix || entry.name.startsWith("${prefix}_") } ||
                entry.name.startsWith("ocr_pdf_")
            if (!isOwned) return@forEach
            val modified = newestTimestamp(entry)
            if (modified in 1 until cutoff) runCatching { entry.deleteRecursively() }
        }
    }


    suspend fun copyCancellable(input: InputStream, output: OutputStream, bufferSize: Int = 64 * 1024): Long {
        val buffer = ByteArray(bufferSize.coerceIn(8 * 1024, 256 * 1024))
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            total += read
        }
        return total
    }

    fun isCancellation(error: Throwable): Boolean = error is CancellationException

    private fun newestTimestamp(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.lastModified()
        var newest = file.lastModified()
        file.listFiles().orEmpty().forEach { child -> newest = maxOf(newest, newestTimestamp(child)) }
        return newest
    }
}
