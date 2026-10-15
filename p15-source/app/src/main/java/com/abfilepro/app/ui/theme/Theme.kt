package com.abfilepro.app.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = RoyalBlue,
    secondary = Teal,
    tertiary = Color(0xFF7C3AED),
    error = AccentRed,
    background = LightBg,
    surface = LightSurface,
    onPrimary = Color.White,
    onBackground = Navy,
    onSurface = Navy
)

private val DarkColors = darkColorScheme(
    primary = RoyalBlue,
    secondary = Teal,
    tertiary = Color(0xFFA78BFA),
    error = AccentRed,
    background = DarkBg,
    surface = DarkSurface,
    onPrimary = Color.White,
    onBackground = Color(0xFFF8FAFC),
    onSurface = Color(0xFFF8FAFC)
)

private val PremiumShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

private fun premiumLight(primary: Color, secondary: Color = Teal) = lightColorScheme(
    primary = primary,
    secondary = secondary,
    tertiary = Color(0xFF7C3AED),
    error = AccentRed,
    background = Color(0xFFF7F8FC),
    surface = Color.White,
    surfaceVariant = Color(0xFFF0F2F7),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF111827),
    onSurface = Color(0xFF111827),
    outline = Color(0xFFCBD2DE),
    outlineVariant = Color(0xFFE4E7EE)
)

private fun premiumDark(primary: Color, secondary: Color = Teal, amoled: Boolean = false) = darkColorScheme(
    primary = primary,
    secondary = secondary,
    tertiary = Color(0xFFC4B5FD),
    error = Color(0xFFFF6B6B),
    background = if (amoled) Color.Black else Color(0xFF0B1220),
    surface = if (amoled) Color(0xFF080808) else Color(0xFF111827),
    surfaceVariant = if (amoled) Color(0xFF121212) else Color(0xFF1B2434),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFFF8FAFC),
    onSurface = Color(0xFFF8FAFC),
    outline = Color(0xFF465269),
    outlineVariant = Color(0xFF273246)
)

private fun themeScheme(preset: String, darkTheme: Boolean) = when (preset) {
    "red" -> if (darkTheme) premiumDark(Color(0xFFFF5252), Color(0xFFFFB74D)) else premiumLight(Color(0xFFD32F2F), Color(0xFFF59E0B))
    "amber" -> if (darkTheme) premiumDark(Color(0xFFFFC107), Color(0xFFFF8F00)) else premiumLight(Color(0xFFE59B00), Color(0xFF2563EB))
    "green" -> if (darkTheme) premiumDark(Color(0xFF34D399), Color(0xFF22C55E)) else premiumLight(Color(0xFF0F9D72), Color(0xFF2563EB))
    "purple" -> if (darkTheme) premiumDark(Color(0xFFA78BFA), Color(0xFFEC4899)) else premiumLight(Color(0xFF7C3AED), Color(0xFFDB2777))
    "navy" -> premiumDark(Color(0xFF4F8CFF), Color(0xFF38BDF8))
    "graphite" -> premiumDark(Color(0xFF94A3B8), Color(0xFF60A5FA))
    "amoled" -> premiumDark(Color(0xFF5B8CFF), Color(0xFF2DD4BF), amoled = true)
    "light" -> premiumLight(Color(0xFF334155), Color(0xFF2563EB))
    "blue" -> if (darkTheme) DarkColors else LightColors
    else -> if (darkTheme) DarkColors else LightColors
}

@Composable
fun ABFileProTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preset = context.getSharedPreferences("ab_file_pro", Context.MODE_PRIVATE)
        .getString("theme_preset", "blue")
        .orEmpty()
        .ifBlank { "blue" }

    MaterialTheme(
        colorScheme = themeScheme(preset, darkTheme),
        shapes = PremiumShapes,
        content = content
    )
}
