package com.abfilepro.app.core

import android.content.Context
import java.io.File

/**
 * P22: which camera captures the pages.
 *  - GOOGLE: Google's document scanner (ML Kit, Play services): automatic capture, edge detection,
 *    crop and cleanup. Our own review, editing and saving run on its result.
 *  - BUILT_IN: the app's own camera (Scanner Pro). Used when the user picks it, when Google's
 *    scanner is unavailable on the phone (for example without Google services), and for modes
 *    Google's scanner does not cover (book split, images to PDF).
 */
enum class ScannerEngine { GOOGLE, SMART, BUILT_IN }

/** P23: a request to open the AB smart camera (new scan, retake of one page, or add pages). */
data class SmartCameraRequest(val mode: SmartCaptureMode, val retakeIndex: Int? = null, val append: Boolean = false)

/** Pure rules (JVM-testable). */
object ScannerEnginePolicy {
    private val googleModes = setOf(
        ScanCaptureMode.DOCUMENT,
        ScanCaptureMode.BATCH,
        ScanCaptureMode.ID_CARD,
        ScanCaptureMode.DUPLEX,
        ScanCaptureMode.RECEIPT,
        ScanCaptureMode.TRANSLATION,
        ScanCaptureMode.PASSPORT,
        ScanCaptureMode.WHITEBOARD,
    )

    fun usesGoogle(mode: ScanCaptureMode, engine: ScannerEngine): Boolean =
        engine == ScannerEngine.GOOGLE && mode in googleModes

    /** P23: the AB smart camera covers the same modes as Google's scanner. */
    fun usesSmart(mode: ScanCaptureMode, engine: ScannerEngine): Boolean =
        engine == ScannerEngine.SMART && mode in googleModes

    /**
     * How many pages Google's scanner may return for this request.
     * A retake always asks for exactly one page; adding pages never exceeds the mode's limit.
     */
    fun pageLimit(mode: ScanCaptureMode, existingPages: Int, retake: Boolean): Int {
        if (retake) return 1
        val remaining = (mode.maxPages - existingPages.coerceAtLeast(0)).coerceAtLeast(0)
        return remaining.coerceIn(1, mode.maxPages)
    }

    /** True when there is still room to add pages for this mode. */
    fun canAdd(mode: ScanCaptureMode, existingPages: Int): Boolean = existingPages < mode.maxPages

    /**
     * Merges pages returned by Google's scanner into the current review list.
     *  - retakeIndex: the first returned page replaces that slot, order kept.
     *  - append: new pages go after the existing ones, capped at the mode limit.
     *  - otherwise: the returned pages start a new review list.
     * Returns the new list and the pages that are no longer referenced (safe to delete).
     */
    fun <T> merge(
        mode: ScanCaptureMode,
        existing: List<T>,
        returned: List<T>,
        retakeIndex: Int?,
        append: Boolean,
    ): Pair<List<T>, List<T>> {
        if (returned.isEmpty()) return existing to emptyList()
        if (retakeIndex != null && retakeIndex in existing.indices) {
            val updated = existing.toMutableList()
            val replaced = updated[retakeIndex]
            updated[retakeIndex] = returned.first()
            return updated to (listOf(replaced) + returned.drop(1))
        }
        // A stale retake slot never wipes the review list; the page is added instead.
        if (append || retakeIndex != null) {
            val room = (mode.maxPages - existing.size).coerceAtLeast(0)
            val kept = returned.take(room)
            return (existing + kept) to returned.drop(room)
        }
        val kept = returned.take(mode.maxPages)
        return kept to (existing + returned.drop(mode.maxPages))
    }
}

/**
 * P23: pages retired from a review list (retake, overflow) are removed file by file.
 * Pages of one scan share a cache folder, so deleting a page's folder would erase its
 * siblings (P22 bug on retake). The folder goes only once it is empty.
 */
object ScanPageFiles {
    fun retire(retired: List<ScanDraftPage>, stillUsed: List<ScanDraftPage>) {
        val live = stillUsed.map { it.file.absolutePath }.toSet()
        retired.filter { it.file.absolutePath !in live }.forEach { page ->
            runCatching { page.file.delete() }
            val parent = page.file.parentFile
            if (parent != null && parent.isDirectory && parent.listFiles().isNullOrEmpty()) runCatching { parent.delete() }
        }
    }
}

object ScannerEngineStore {
    private const val PREFS = "ab_file_pro_scanner_v22"
    private const val KEY_ENGINE = "engine"
    private const val KEY_SAVE_RELATIVE = "save_relative"
    const val DEFAULT_FOLDER = "scans"

    fun engine(context: Context): ScannerEngine =
        runCatching {
            ScannerEngine.valueOf(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ENGINE, ScannerEngine.GOOGLE.name).orEmpty()
            )
        }.getOrDefault(ScannerEngine.GOOGLE)

    fun setEngine(context: Context, engine: ScannerEngine) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ENGINE, engine.name).apply()
    }

    /**
     * P22: one place for every scanner save (single page, batch, card, receipt, translation).
     * Default: "AB File Pro/scans". A folder the user picks from the scanner is remembered here.
     */
    fun saveDirectory(context: Context): File {
        val root = FileUtils.rootDir(context)
        val relative = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SAVE_RELATIVE, null)
        if (relative != null) {
            val candidate = if (relative.isBlank()) root else File(root, relative)
            if (runCatching { FileUtils.isInsideRoot(context, candidate) }.getOrDefault(false)) {
                candidate.mkdirs()
                if (candidate.isDirectory) return candidate
            }
        }
        return FileUtils.outputDir(context, DEFAULT_FOLDER)
    }

    fun setSaveDirectory(context: Context, directory: File) {
        require(FileUtils.isInsideRoot(context, directory) && directory.isDirectory) { "مجلد حفظ غير صالح" }
        val root = FileUtils.rootDir(context).canonicalFile
        val target = directory.canonicalFile
        val relative = if (target == root) "" else target.path.removePrefix(root.path).trimStart(File.separatorChar)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SAVE_RELATIVE, relative).apply()
    }
}
