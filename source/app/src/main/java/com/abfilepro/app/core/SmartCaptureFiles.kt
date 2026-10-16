package com.abfilepro.app.core

import java.io.File

/**
 * P23: turns one camera shot into review pages. One sheet → one page with its corners.
 * Several sheets in one shot → one page per sheet; each page gets its own copy of the photo
 * so deleting or retaking one page never touches the others.
 */
object SmartCaptureFiles {
    fun pagesFor(shot: File, quads: List<ScanQuad>, mode: ScanCaptureMode): List<ScanDraftPage> {
        val prepared = ScanProcessor.prepareCameraCapture(shot, mode, detectedQuad = quads.firstOrNull())
        // P24: book mode returns two pages (left/right) from one shot.
        if (quads.size <= 1 || prepared.size != 1) return prepared
        val first = prepared.first()
        val parent = shot.parentFile ?: return listOf(first)
        val pages = mutableListOf(first)
        try {
            quads.drop(1).forEachIndexed { index, quad ->
                val copy = File(parent, "sheet_${index + 2}.jpg")
                shot.copyTo(copy, overwrite = true)
                require(copy.length() == shot.length()) { "scan_page_copy_failed" }
                pages += first.copy(id = first.id + index + 1, file = copy, perspective = quad.clamped())
            }
        } catch (error: Throwable) {
            pages.drop(1).forEach { runCatching { it.file.delete() } }
            throw error
        }
        return pages
    }
}
