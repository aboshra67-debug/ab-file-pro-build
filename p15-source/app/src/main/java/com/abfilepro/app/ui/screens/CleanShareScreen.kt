package com.abfilepro.app.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abfilepro.app.core.CleanShareProcessor
import com.abfilepro.app.core.FileUtils
import com.abfilepro.app.core.OperationHistoryStore
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.i18n.uiText
import com.abfilepro.app.ui.i18n.localizedOperationFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanShareScreen(onBack: () -> Unit, onOpenPdf: (File) -> Unit, initialUriText: String = "") {
    val context = LocalContext.current
    val appLanguage = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    var uri by remember(initialUriText) { mutableStateOf(initialUriText.takeIf { it.isNotBlank() }?.let(Uri::parse)) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<CleanShareProcessor.Result?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var historyId by remember { mutableStateOf<String?>(null) }
    var redactSensitive by remember { mutableStateOf(true) }
    var removeMetadata by remember { mutableStateOf(true) }
    var removeBlankPages by remember { mutableStateOf(true) }
    var compress by remember { mutableStateOf(true) }
    var cleanFileName by remember { mutableStateOf(true) }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        uri = selected
        result = null
        historyId = null
    }

    LaunchedEffect(result?.file?.absolutePath) {
        preview?.let { if (!it.isRecycled) it.recycle() }
        preview = null
        val output = result ?: return@LaunchedEffect
        if (!output.isPdf) {
            preview = withContext(Dispatchers.IO) { runCatching { FileUtils.loadImagePreview(output.file, 1400) }.getOrNull() }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            activeJob?.cancel()
            preview?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AB Clean Share", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack, enabled = !busy) { Icon(Icons.Default.ArrowBack, context.uiText(appLanguage, "b2_clean_back")) } }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(context.uiText(appLanguage, "b2_clean_subtitle"), fontWeight = FontWeight.ExtraBold)
                    Text(context.uiText(appLanguage, "b2_clean_desc"), style = MaterialTheme.typography.bodySmall)
                    Text(context.uiText(appLanguage, "b2_clean_privacy"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Button(
                onClick = { picker.launch(arrayOf("image/*", "application/pdf")) },
                modifier = Modifier.fillMaxWidth(), enabled = !busy
            ) {
                Icon(Icons.Default.AttachFile, null); Spacer(Modifier.width(8.dp)); Text(if (uri == null) context.uiText(appLanguage, "b2_clean_pick") else context.uiText(appLanguage, "b2_clean_change"))
            }

            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(context.uiText(appLanguage, "b2_clean_options"), fontWeight = FontWeight.Bold)
                    CleanOption(context.uiText(appLanguage, "b2_clean_redact"), redactSensitive) { redactSensitive = it }
                    CleanOption(context.uiText(appLanguage, "b2_clean_metadata"), removeMetadata) { removeMetadata = it }
                    CleanOption(context.uiText(appLanguage, "b2_clean_blank"), removeBlankPages) { removeBlankPages = it }
                    CleanOption(context.uiText(appLanguage, "b2_clean_compress"), compress) { compress = it }
                    CleanOption(context.uiText(appLanguage, "b2_clean_clean_name"), cleanFileName) { cleanFileName = it }
                }
            }

            if (uri != null && result == null) {
                Button(
                    onClick = {
                        busy = true
                        activeJob = scope.launch {
                            val options = CleanShareProcessor.Options(
                                redactSensitive = redactSensitive,
                                removeMetadata = removeMetadata,
                                removeBlankPages = removeBlankPages,
                                compress = compress,
                                cleanFileName = cleanFileName
                            )
                            try {
                                val cleaned = CleanShareProcessor.clean(context, uri!!, options)
                                result = cleaned
                                val history = OperationHistoryStore.record(context, "AB Clean Share", context.uiText(appLanguage, "b2_clean_safe_copy"), listOf(cleaned.file))
                                historyId = history.id
                                Toast.makeText(context, context.uiText(appLanguage, "b2_clean_created"), Toast.LENGTH_SHORT).show()
                            } catch (error: Throwable) {
                                Toast.makeText(context, context.localizedOperationFailure(appLanguage, error, "b2_clean_error_create"), Toast.LENGTH_LONG).show()
                            } finally { busy = false; activeJob = null }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy
                ) {
                    Icon(Icons.Default.Security, null); Spacer(Modifier.width(8.dp)); Text(context.uiText(appLanguage, "b2_clean_approve"))
                }
            }

            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(context.uiText(appLanguage, "b2_clean_processing"), style = MaterialTheme.typography.bodySmall)
                Text(context.uiText(appLanguage, "v15_processing_progress"), style = MaterialTheme.typography.labelSmall)
                OutlinedButton(onClick = { activeJob?.cancel() }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Close, null); Spacer(Modifier.width(6.dp)); Text(context.uiText(appLanguage, "v15_cancel_operation")) }
            }

            result?.let { cleaned ->
                preview?.let { bitmap ->
                    Card(shape = RoundedCornerShape(16.dp)) {
                        Image(bitmap.asImageBitmap(), context.uiText(appLanguage, "b2_clean_result"), Modifier.fillMaxWidth().heightIn(max = 340.dp), contentScale = ContentScale.Fit)
                    }
                }
                Card(shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(cleaned.file.name, fontWeight = FontWeight.Bold)
                        Text(context.uiText(appLanguage, "b2_clean_stats", cleaned.totalRedactions, cleaned.sensitiveItems, cleaned.qrItems), style = MaterialTheme.typography.labelMedium)
                        if (cleaned.isPdf) Text(context.uiText(appLanguage, "b2_clean_blank_count", cleaned.blankPagesRemoved), style = MaterialTheme.typography.labelMedium)
                        Text(context.uiText(appLanguage, "b2_clean_meta_compression", if (cleaned.metadataStripped) context.uiText(appLanguage, "b2_clean_removed") else context.uiText(appLanguage, "b2_clean_not_requested"), if (cleaned.compressed) context.uiText(appLanguage, "b2_clean_enabled") else context.uiText(appLanguage, "b2_clean_disabled")), style = MaterialTheme.typography.labelSmall)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { if (cleaned.isPdf) onOpenPdf(cleaned.file) else FileUtils.openFile(context, cleaned.file) },
                        modifier = Modifier.weight(1f)
                    ) { Text(context.uiText(appLanguage, "b2_clean_open")) }
                    OutlinedButton(onClick = { FileUtils.shareFile(context, cleaned.file) }, modifier = Modifier.weight(1f)) { Text(context.uiText(appLanguage, "b2_clean_share")) }
                }
                historyId?.let { id ->
                    OutlinedButton(
                        onClick = {
                            if (!busy) {
                                busy = true
                                activeJob = scope.launch {
                                    try {
                                        if (OperationHistoryStore.undo(context, id)) {
                                            val partial = OperationHistoryStore.hasPending(context, id)
                                            result = null; historyId = null
                                            Toast.makeText(context, context.uiText(appLanguage, if (partial) "p12_undo_partial" else "b2_clean_undone"), Toast.LENGTH_SHORT).show()
                                        } else Toast.makeText(context, context.uiText(appLanguage, "p12_undo_protected"), Toast.LENGTH_LONG).show()
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        Toast.makeText(context, context.uiText(appLanguage, "p12_undo_protected"), Toast.LENGTH_LONG).show()
                                    } finally { busy = false; activeJob = null }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Icon(Icons.Default.Undo, null); Spacer(Modifier.width(6.dp)); Text(context.uiText(appLanguage, "b2_clean_undo")) }
                }
            }
        }
    }
}

@Composable
private fun CleanOption(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
