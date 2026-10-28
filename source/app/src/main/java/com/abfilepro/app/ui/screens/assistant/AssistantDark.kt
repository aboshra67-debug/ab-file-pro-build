package com.abfilepro.app.ui.screens.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import androidx.compose.runtime.Composable
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

private val DarkAssistantBlue = Color(0xFF4F8CFF)
private val StockBlue = Color(0xFF1E66F5)

/**
 * P30: the stock blue (1E66F5) has weak contrast as text on the dark background, which is what outlined
 * buttons use. Inside the assistant, in dark mode only, that one colour is replaced by the lighter blue
 * the "navy" theme already uses. Every other theme colour and the light mode are left untouched.
 */
@Composable
internal fun AssistantThemeBridge(content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    if (AssistantDark.on && scheme.primary == StockBlue) {
        MaterialTheme(
            colorScheme = scheme.copy(primary = DarkAssistantBlue),
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
            content = content,
        )
    } else {
        content()
    }
}

/**
 * P30: several operational screens were laid out for Arabic with fixed "End" alignment, which put the
 * English/French headings on the right. For Arabic nothing changes; for the other languages the same
 * places now align to the start (left), and the header bars are mirrored so the back button is on the left.
 */
internal val isLtrUi: Boolean
    @Composable get() = LocalAppLanguage.current.code != "ar"

internal val endAlign: Alignment.Horizontal
    @Composable get() = if (isLtrUi) Alignment.Start else Alignment.End

internal val endText: TextAlign
    @Composable get() = if (isLtrUi) TextAlign.Start else TextAlign.End

internal val endArrange: Arrangement.Horizontal
    @Composable get() = if (isLtrUi) Arrangement.Start else Arrangement.End

@Composable
internal fun HeaderMirror(content: @Composable () -> Unit) {
    if (isLtrUi) CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, content = content) else content()
}
