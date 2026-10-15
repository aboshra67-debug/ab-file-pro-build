package com.abfilepro.app.core

enum class ScanFlowStage {
    HUB,
    CAMERA,
    REVIEW,
}

enum class ScanBackTarget {
    EXIT_SCAN,
    HUB,
    CAMERA,
    REVIEW,
}

object ScanInteractionRules {
    fun backTarget(stage: ScanFlowStage, hasPages: Boolean): ScanBackTarget = when (stage) {
        ScanFlowStage.HUB -> ScanBackTarget.EXIT_SCAN
        ScanFlowStage.CAMERA -> if (hasPages) ScanBackTarget.REVIEW else ScanBackTarget.HUB
        ScanFlowStage.REVIEW -> ScanBackTarget.HUB
    }

    fun nextZoomRatio(
        currentRatio: Float,
        scaleFactor: Float,
        minRatio: Float,
        maxRatio: Float,
    ): Float {
        val safeMin = minOf(minRatio, maxRatio)
        val safeMax = maxOf(minRatio, maxRatio)
        return (currentRatio * scaleFactor).coerceIn(safeMin, safeMax)
    }
}
