package com.abfilepro.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abfilepro.app.ui.components.ScreenTopBar
import com.abfilepro.app.ui.i18n.AppLanguage
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.i18n.uiText

private data class ThemeChoice(val id: String, val labelKey: String, val color: Color)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    darkMode: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
    appLanguage: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val prefs = remember(context) { context.getSharedPreferences("ab_file_pro", Context.MODE_PRIVATE) }
    var languageDialog by remember { mutableStateOf(false) }
    var helpDialog by remember { mutableStateOf(false) }
    var selectedTheme by remember { mutableStateOf(prefs.getString("theme_preset", "blue").orEmpty().ifBlank { "blue" }) }
    var showIntro by remember { mutableStateOf(!prefs.getBoolean("intro_disabled", false)) }
    var showAiHelp by remember { mutableStateOf(prefs.getBoolean("show_ai_help", true)) }

    val versionName = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull().orEmpty().ifBlank { context.uiText(language, "b2_settings_unknown") }
    }

    val themes = remember {
        listOf(
            ThemeChoice("blue", "settings_theme_blue", Color(0xFF2563EB)),
            ThemeChoice("red", "settings_theme_red", Color(0xFFD32F2F)),
            ThemeChoice("amber", "settings_theme_amber", Color(0xFFF5A000)),
            ThemeChoice("green", "settings_theme_green", Color(0xFF0F9D72)),
            ThemeChoice("purple", "settings_theme_purple", Color(0xFF7C3AED)),
            ThemeChoice("navy", "settings_theme_navy", Color(0xFF12325B)),
            ThemeChoice("light", "settings_theme_light", Color(0xFFE2E8F0)),
            ThemeChoice("graphite", "settings_theme_graphite", Color(0xFF475569)),
            ThemeChoice("amoled", "settings_theme_amoled", Color(0xFF050505))
        )
    }

    if (languageDialog) {
        AlertDialog(
            onDismissRequest = { languageDialog = false },
            icon = { Icon(Icons.Default.Language, null) },
            title = { Text(context.uiText(language, "b2_settings_lang_title"), fontWeight = FontWeight.ExtraBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppLanguage.entries.forEach { option ->
                        ListItem(
                            headlineContent = { Text(context.uiText(language, option.nativeNameKey), fontWeight = FontWeight.SemiBold) },
                            supportingContent = {
                                Text(when (option) {
                                    AppLanguage.ARABIC -> context.uiText(language, "b2_settings_arabic_dir")
                                    AppLanguage.ENGLISH -> context.uiText(language, "b2_settings_english_dir")
                                    AppLanguage.FRENCH -> context.uiText(language, "b2_settings_french_dir")
                                })
                            },
                            leadingContent = { RadioButton(selected = appLanguage == option, onClick = null) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onLanguageChange(option)
                                    languageDialog = false
                                }
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { languageDialog = false }) { Text(context.uiText(language, "b2_settings_close")) } }
        )
    }

    if (helpDialog) {
        AlertDialog(
            onDismissRequest = { helpDialog = false },
            icon = { Icon(Icons.Default.SmartToy, null) },
            title = { Text(context.uiText(language, "settings_help_title"), fontWeight = FontWeight.ExtraBold) },
            text = { Text(context.uiText(language, "settings_help_desc"), lineHeight = 21.sp) },
            confirmButton = {
                TextButton(onClick = { helpDialog = false }) { Text(context.uiText(language, "home_close")) }
            }
        )
    }

    Scaffold(
        topBar = { ScreenTopBar(context.uiText(language, "b2_settings_title"), onBack) },
        floatingActionButton = {
            if (showAiHelp) {
                SmallFloatingActionButton(
                    onClick = { helpDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.SmartToy, context.uiText(language, "home_ai_help"), modifier = Modifier.size(22.dp))
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(2.dp))

            SettingsSectionTitle(Icons.Default.Language, context.uiText(language, "settings_section_language"))
            PremiumSettingsCard(
                icon = Icons.Default.Language,
                title = context.uiText(language, "b2_settings_language"),
                subtitle = context.uiText(language, appLanguage.nativeNameKey),
                onClick = { languageDialog = true }
            )

            SettingsSectionTitle(Icons.Default.Palette, context.uiText(language, "settings_section_appearance"))
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Default.DarkMode, null, tint = MaterialTheme.colorScheme.primary)
                            Column {
                                Text(context.uiText(language, "b2_settings_dark"), fontWeight = FontWeight.Bold)
                                Text(
                                    context.uiText(language, "settings_dark_desc"),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .56f),
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Switch(checked = darkMode, onCheckedChange = onDarkModeChange)
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
                    Text(context.uiText(language, "settings_theme_title"), fontWeight = FontWeight.ExtraBold)

                    themes.chunked(3).forEach { rowThemes ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowThemes.forEach { theme ->
                                ThemeChip(
                                    modifier = Modifier.weight(1f),
                                    theme = theme,
                                    selected = selectedTheme == theme.id,
                                    label = context.uiText(language, theme.labelKey),
                                    onClick = {
                                        selectedTheme = theme.id
                                        prefs.edit().putString("theme_preset", theme.id).apply()
                                        context.findSettingsActivity()?.recreate()
                                    }
                                )
                            }
                            repeat(3 - rowThemes.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            SettingsSectionTitle(Icons.Default.Tune, context.uiText(language, "settings_section_experience"))
            SettingsToggleCard(
                icon = Icons.Default.MovieFilter,
                title = context.uiText(language, "settings_intro_title"),
                subtitle = context.uiText(language, "settings_intro_desc"),
                checked = showIntro,
                onCheckedChange = {
                    showIntro = it
                    prefs.edit().putBoolean("intro_disabled", !it).apply()
                }
            )
            SettingsToggleCard(
                icon = Icons.Default.SmartToy,
                title = context.uiText(language, "settings_robot_title"),
                subtitle = context.uiText(language, "settings_robot_desc"),
                checked = showAiHelp,
                onCheckedChange = {
                    showAiHelp = it
                    prefs.edit().putBoolean("show_ai_help", it).apply()
                }
            )

            SettingsSectionTitle(Icons.Default.Info, context.uiText(language, "settings_section_about"))
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("AB File Pro", fontWeight = FontWeight.Black, fontSize = 16.sp)
                        Text("v$versionName", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f), fontSize = 12.sp)
                    }
                    Icon(Icons.Default.WorkspacePremium, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun SettingsSectionTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
    }
}

@Composable
private fun PremiumSettingsCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .10f)) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(10.dp).size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.ExtraBold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .56f), fontSize = 12.sp)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .30f))
        }
    }
}

@Composable
private fun SettingsToggleCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .10f)) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(10.dp).size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.ExtraBold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .56f), fontSize = 11.sp, lineHeight = 15.sp)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun ThemeChip(
    modifier: Modifier,
    theme: ThemeChoice,
    selected: Boolean,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .09f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f),
        border = BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)
        )
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(modifier = Modifier.size(24.dp), shape = CircleShape, color = theme.color) {}
            Text(label, fontSize = 9.5.sp, fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold, maxLines = 1)
        }
    }
}

private tailrec fun Context.findSettingsActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findSettingsActivity()
    else -> null
}
