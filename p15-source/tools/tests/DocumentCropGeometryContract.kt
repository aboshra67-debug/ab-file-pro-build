package com.abfilepro.app.core

private fun assertClose(actual: Float, expected: Float, epsilon: Float = 0.0001f) {
    check(kotlin.math.abs(actual - expected) <= epsilon) { "Expected $expected, got $actual" }
}

fun main() {
    val portrait = DocumentCropGeometry.fromRect(.12f, .08f, .88f, .92f)
        ?: error("valid portrait rectangle should produce a quad")
    assertClose(portrait.topLeftX, .095f)
    assertClose(portrait.topLeftY, .055f)
    assertClose(portrait.bottomRightX, .905f)
    assertClose(portrait.bottomRightY, .945f)

    val clamped = DocumentCropGeometry.fromRect(.01f, .01f, .99f, .99f)
        ?: error("near-full valid rectangle should produce a quad")
    assertClose(clamped.topLeftX, 0f)
    assertClose(clamped.topLeftY, 0f)
    assertClose(clamped.bottomRightX, 1f)
    assertClose(clamped.bottomRightY, 1f)

    check(DocumentCropGeometry.fromRect(.4f, .4f, .5f, .5f) == null) {
        "tiny detections must be rejected"
    }
    check(DocumentCropGeometry.fromRect(.8f, .2f, .3f, .9f) == null) {
        "inverted detections must be rejected"
    }
    check(DocumentCropGeometry.fromRect(Float.NaN, .1f, .8f, .9f) == null) {
        "non-finite detections must be rejected"
    }

    println("document crop geometry contract: PASS")
}
