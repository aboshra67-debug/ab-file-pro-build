package com.abfilepro.app.core

/**
 * Pure normalized geometry used by scanner capture preparation.
 * Keeps document-edge fallback logic independent from Android graphics classes
 * so it can be verified without an Android runtime.
 */
object DocumentCropGeometry {
    data class QuadCoordinates(
        val topLeftX: Float,
        val topLeftY: Float,
        val topRightX: Float,
        val topRightY: Float,
        val bottomRightX: Float,
        val bottomRightY: Float,
        val bottomLeftX: Float,
        val bottomLeftY: Float
    )

    fun fromRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        margin: Float = .025f
    ): QuadCoordinates? {
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return null
        if (right <= left || bottom <= top) return null
        if (right - left < .16f || bottom - top < .16f) return null

        val safeMargin = margin.coerceIn(0f, .08f)
        val l = (left - safeMargin).coerceIn(0f, 1f)
        val t = (top - safeMargin).coerceIn(0f, 1f)
        val r = (right + safeMargin).coerceIn(0f, 1f)
        val b = (bottom + safeMargin).coerceIn(0f, 1f)
        if (r - l < .16f || b - t < .16f) return null

        return QuadCoordinates(
            topLeftX = l,
            topLeftY = t,
            topRightX = r,
            topRightY = t,
            bottomRightX = r,
            bottomRightY = b,
            bottomLeftX = l,
            bottomLeftY = b
        )
    }
}
