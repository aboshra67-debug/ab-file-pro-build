package com.abfilepro.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abfilepro.app.core.FileUtils
import com.abfilepro.app.core.ScanTranslationResult
import com.abfilepro.app.core.TranslationTarget
import com.abfilepro.app.ui.components.ScreenTopBar
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.i18n.uiText

@Composable
fun ScanTranslationResultScreen(
    result: ScanTranslationResult,
    busy: Boolean,
    onTargetChange: (TranslationTarget) -> Unit,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    Scaffold(topBar = { ScreenTopBar(context.uiText(language, "alpha11_translation_title"), onDone) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(14.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(context.uiText(language, "alpha11_translation_saved"), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TranslationTarget.entries.forEach { target ->
                    FilterChip(
                        selected = result.target == target,
                        onClick = { if (!busy && result.target != target) onTargetChange(target) },
                        enabled = !busy,
                        label = { Text(targetLabel(target)) }
                    )
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            TranslationTextCard(context.uiText(language, "alpha11_translation_source"), result.sourceText)
            TranslationTextCard(context.uiText(language, "alpha11_translation_result"), result.translatedText)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("translation", result.translatedText))
                        Toast.makeText(context, context.uiText(language, "alpha11_translation_copy"), Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.ContentCopy, null)
                    Spacer(Modifier.width(5.dp))
                    Text(context.uiText(language, "alpha11_translation_copy"))
                }
                Button(onClick = { FileUtils.shareFile(context, result.file) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Share, null)
                    Spacer(Modifier.width(5.dp))
                    Text(context.uiText(language, "alpha11_translation_share"))
                }
            }
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                Text(context.uiText(language, "alpha11_translation_done"))
            }
        }
    }
}

@Composable
private fun TranslationTextCard(title: String, text: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun targetLabel(target: TranslationTarget): String = when (target) {
    TranslationTarget.ARABIC -> "العربية"
    TranslationTarget.ENGLISH -> "English"
    TranslationTarget.FRENCH -> "Français"
}
