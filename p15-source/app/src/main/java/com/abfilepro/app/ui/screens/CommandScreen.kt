package com.abfilepro.app.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.i18n.uiText
import com.abfilepro.app.ui.i18n.localizedOperationFailure
import com.abfilepro.app.core.CommandEngine
import com.abfilepro.app.core.FileUtils
import com.abfilepro.app.core.OperationHistoryStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandScreen(onBack: () -> Unit, onOpenPdf: (File) -> Unit) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    var uris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val commandExamples = remember(language) { CommandEngine.examples(context, language.code) }
    var command by remember(language) { mutableStateOf(commandExamples.first()) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var output by remember { mutableStateOf<File?>(null) }
    var historyId by remember { mutableStateOf<String?>(null) }
    var historyVersion by remember { mutableIntStateOf(0) }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        onDispose { activeJob?.cancel() }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { selected ->
        uris = selected.take(30)
        output = null
        historyId = null
    }
    val plan = remember(language, command, uris.size) { CommandEngine.plan(context, language.code, command, uris.size) }
    val recentHistory = remember(historyVersion) { OperationHistoryStore.list(context, 5).filter { it.tool == "AB Command" } }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("AB Command", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack, enabled = !busy) { Icon(Icons.Default.ArrowBack, context.uiText(language, "command_screen_001")) } }
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                        Text(context.uiText(language, "command_screen_002"), fontWeight = FontWeight.ExtraBold)
                    }
                    Text(context.uiText(language, "command_screen_003"), style = MaterialTheme.typography.bodySmall)
                    Text(context.uiText(language, "command_screen_004"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Text(context.uiText(language, "command_screen_005"), fontWeight = FontWeight.Bold)
            commandExamples.forEach { example ->
                SuggestionChip(
                    onClick = { command = example },
                    label = { Text(example, maxLines = 2) },
                    icon = { Icon(Icons.Default.Bolt, null) }
                )
            }

            OutlinedButton(onClick = { picker.launch(arrayOf("image/*", "application/pdf", "text/plain")) }, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                Icon(Icons.Default.AttachFile, null); Spacer(Modifier.width(8.dp)); Text(if (uris.isEmpty()) context.uiText(language, "command_screen_006") else context.uiText(language, "command_screen_007", uris.size))
            }

            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(context.uiText(language, "command_screen_008")) },
                minLines = 3,
                enabled = !busy
            )

            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(context.uiText(language, "command_screen_009"), fontWeight = FontWeight.Bold)
                    plan.steps.forEachIndexed { index, step -> Text("${index + 1}. $step", style = MaterialTheme.typography.bodySmall) }
                    plan.warning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium) }
                }
            }

            Button(
                onClick = {
                    busy = true; progress = context.uiText(language, "command_screen_010"); output = null; historyId = null
                    activeJob = scope.launch {
                        try {
                            val result = CommandEngine.execute(context, language.code, command, uris) { progress = it }
                            output = result.output
                            historyId = result.historyId
                            historyVersion++
                            Toast.makeText(context, context.uiText(language, "command_screen_011"), Toast.LENGTH_SHORT).show()
                        } catch (error: Throwable) {
                            Toast.makeText(context, context.localizedOperationFailure(language, error, "command_screen_012"), Toast.LENGTH_LONG).show()
                        } finally {
                            busy = false; progress = ""; activeJob = null
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(), enabled = !busy && plan.valid && uris.isNotEmpty()
            ) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(context.uiText(language, "command_screen_013")) }

            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(progress, style = MaterialTheme.typography.bodySmall)
                Text(context.uiText(language, "v15_processing_progress"), style = MaterialTheme.typography.labelSmall)
                OutlinedButton(onClick = { activeJob?.cancel() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Close, null); Spacer(Modifier.width(6.dp)); Text(context.uiText(language, "v15_cancel_operation"))
                }
            }

            output?.let { file ->
                Card(shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(context.uiText(language, "command_screen_014", file.name), fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { if (file.extension.equals("pdf", true)) onOpenPdf(file) else FileUtils.openFile(context, file) }, modifier = Modifier.weight(1f)) { Text(context.uiText(language, "command_screen_015")) }
                            OutlinedButton(onClick = { FileUtils.shareFileToWhatsApp(context, file) }, modifier = Modifier.weight(1f)) { Text(context.uiText(language, "command_screen_016")) }
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
                                                    output = null; historyId = null; historyVersion++
                                                    Toast.makeText(context, context.uiText(language, if (partial) "p12_undo_partial" else "command_screen_017"), Toast.LENGTH_SHORT).show()
                                                } else Toast.makeText(context, context.uiText(language, "p12_undo_protected"), Toast.LENGTH_LONG).show()
                                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) {
                                                Toast.makeText(context, context.uiText(language, "p12_undo_protected"), Toast.LENGTH_LONG).show()
                                            } finally { busy = false; activeJob = null }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Icon(Icons.Default.Undo, null); Spacer(Modifier.width(6.dp)); Text(context.uiText(language, "command_screen_019")) }
                        }
                    }
                }
            }

            if (recentHistory.isNotEmpty()) {
                Text(context.uiText(language, "command_screen_020"), fontWeight = FontWeight.Bold)
                recentHistory.forEach { entry ->
                    Card(
                        Modifier.fillMaxWidth().clickable(enabled = !busy && entry.canUndo(context)) {
                            if (!busy) {
                                busy = true
                                activeJob = scope.launch {
                                    try {
                                        if (OperationHistoryStore.undo(context, entry.id)) {
                                            val partial = OperationHistoryStore.hasPending(context, entry.id)
                                            historyVersion++
                                            if (historyId == entry.id) { output = null; historyId = null }
                                            Toast.makeText(context, context.uiText(language, if (partial) "p12_undo_partial" else "command_screen_021"), Toast.LENGTH_SHORT).show()
                                        } else Toast.makeText(context, context.uiText(language, "p12_undo_protected"), Toast.LENGTH_LONG).show()
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        Toast.makeText(context, context.uiText(language, "p12_undo_protected"), Toast.LENGTH_LONG).show()
                                    } finally { busy = false; activeJob = null }
                                }
                            }
                        }
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.History, null)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(entry.title, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                                Text(if (entry.canUndo(context)) context.uiText(language, "command_screen_022") else context.uiText(language, "command_screen_023"), style = MaterialTheme.typography.labelSmall)
                            }
                            if (entry.canUndo(context)) Icon(Icons.Default.Undo, null)
                        }
                    }
                }
            }
        }
    }
}
