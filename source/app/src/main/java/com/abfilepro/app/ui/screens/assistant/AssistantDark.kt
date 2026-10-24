package com.abfilepro.app.ui.screens.assistant

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * P25: the assistant screens were drawn with fixed light colours, so in dark mode they stayed white
 * (white cards with white text). Every light surface now goes through [adaptSurface] and every dark
 * ink through [adaptInk]; in light mode both return the colour unchanged.
 *
 * [on] is snapshot state, so a screen that reads it recomposes when the theme changes.
 */
internal object AssistantDark {
    var on by mutableStateOf(false)
}

private val DarkCard = Color(0xFF111827)

/** Cards, screen backgrounds, borders and soft chips: light colours become dark tinted ones. */
internal fun adaptSurface(color: Color): Color {
    if (!AssistantDark.on || color.alpha < 0.05f) return color
    return if (color.luminance() > 0.55f) lerp(color, DarkCard, 0.86f).copy(alpha = color.alpha) else color
}

/** Text and icons: dark inks become light ones, accents stay recognisable. */
internal fun adaptInk(color: Color): Color {
    if (!AssistantDark.on) return color
    val l = color.luminance()
    // Saturated accents (red, green, blue) stay as they are; only neutral dark inks are lightened.
    val chroma = maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue)
    return when {
        l < 0.08f -> lerp(color, Color.White, 0.88f).copy(alpha = color.alpha)
        l < 0.22f && chroma < 0.35f -> lerp(color, Color.White, 0.55f).copy(alpha = color.alpha)
        else -> color
    }
}
