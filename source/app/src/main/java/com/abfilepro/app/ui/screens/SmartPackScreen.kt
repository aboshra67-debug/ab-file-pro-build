package com.abfilepro.app.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abfilepro.app.core.FileUtils
import com.abfilepro.app.core.OperationHistoryStore
import com.abfilepro.app.core.SmartPackProcessor
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.i18n.uiText
import com.abfilepro.app.ui.i18n.localizedOperationFailure
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartPackScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val appLanguage = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    var uris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf<SmartPackProcessor.Plan?>(null) }
    var result by remember { mutableStateOf<SmartPackProcessor.Result?>(null) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { activeJob?.cancel() } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { selected ->
        uris = selected.take(50)
        plan = null
        result = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AB Smart Pack", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack, enabled = !busy) { Icon(Icons.Default.ArrowBack, context.uiText(appLanguage, "b2_pack_back")) } }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(context.uiText(appLanguage, "b2_pack_subtitle"), fontWeight = FontWeight.ExtraBold)
                    Text(context.uiText(appLanguage, "b2_pack_desc"), style = MaterialTheme.typography.bodySmall)
                    Text(context.uiText(appLanguage, "b2_pack_original"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Button(
                onClick = { picker.launch(arrayOf("image/*", "application/pdf", "text/*", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) },
                modifier = Modifier.fillMaxWidth(), enabled = !busy
            ) {
                Icon(Icons.Default.FolderCopy, null); Spacer(Modifier.width(8.dp));
                Text(if (uris.isEmpty()) context.uiText(appLanguage, "b2_pack_pick") else context.uiText(appLanguage, "b2_pack_selected", uris.size))
            }

            if (uris.isNotEmpty() && plan == null && result == null) {
                Button(
                    onClick = {
                        busy = true; progress = context.uiText(appLanguage, "b2_pack_starting")
                        activeJob = scope.launch {
                            try {
                                plan = SmartPackProcessor.analyze(context, uris, appLanguage.code) { progress = it }
                            } catch (error: Throwable) {
                                Toast.makeText(context, context.localizedOperationFailure(appLanguage, error, "b2_pack_error_analysis"), Toast.LENGTH_LONG).show()
                            } finally { busy = false; progress = ""; activeJob = null }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy
                ) { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text(context.uiText(appLanguage, "b2_pack_analyze")) }
            }

            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth()); Text(progress, style = MaterialTheme.typography.bodySmall)
                Text(context.uiText(appLanguage, "v15_processing_progress"), style = MaterialTheme.typography.labelSmall)
                OutlinedButton(onClick = { activeJob?.cancel() }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Close, null); Spacer(Modifier.width(6.dp)); Text(context.uiText(appLanguage, "v15_cancel_operation")) }
            }

            plan?.let { p ->
                Card(shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(context.uiText(appLanguage, "b2_pack_preview"), fontWeight = FontWeight.ExtraBold)
                        Text(context.uiText(appLanguage, "b2_pack_stats", p.items.size, p.duplicates, p.similarFiles), style = MaterialTheme.typography.bodySmall)
                        p.groups.take(8).forEach { group ->
                            Text("${group.category} / ${group.cluster}: ${group.count}", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                p.items.take(20).forEach { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.originalName, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text("→ ${item.category}/${item.cluster}/${item.proposedBaseName}", style = MaterialTheme.typography.bodySmall)
                            item.duplicateOf?.let { Text(context.uiText(appLanguage, "b2_pack_duplicate_of", it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
                            item.similarTo?.let { Text(context.uiText(appLanguage, "b2_pack_similar_to", it), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
                if (p.items.size > 20) Text(context.uiText(appLanguage, "b2_pack_more", p.items.size - 20), style = MaterialTheme.typography.labelSmall)

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { plan = null }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Close, null); Spacer(Modifier.width(5.dp)); Text(context.uiText(appLanguage, "b2_pack_cancel"))
                    }
                    Button(
                        onClick = {
                            busy = true; progress = context.uiText(appLanguage, "b2_pack_organizing")
                            activeJob = scope.launch {
                                try {
                                    val applied = SmartPackProcessor.apply(context, p, appLanguage.code) { progress = it }
                                    result = applied
                                    Toast.makeText(context, context.uiText(appLanguage, "b2_pack_organized", applied.filesCopied), Toast.LENGTH_SHORT).show()
                                } catch (error: Throwable) {
                                    Toast.makeText(context, context.localizedOperationFailure(appLanguage, error, "b2_pack_error_organize"), Toast.LENGTH_LONG).show()
                                } finally { busy = false; progress = ""; activeJob = null }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) { Icon(Icons.Default.Check, null); Spacer(Modifier.width(5.dp)); Text(context.uiText(appLanguage, "b2_pack_approve")) }
                }
            }

            result?.let { r ->
                Card(shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(context.uiText(appLanguage, "b2_pack_created"), fontWeight = FontWeight.ExtraBold)
                        Text(context.uiText(appLanguage, "b2_pack_result_stats", r.filesCopied, r.duplicates), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { FileUtils.openFile(context, r.report) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Description, null); Spacer(Modifier.width(6.dp)); Text(context.uiText(appLanguage, "b2_pack_open_report"))
                        }
                        OutlinedButton(
                            onClick = {
                                if (!busy) {
                                    busy = true
                                    activeJob = scope.launch {
                                        try {
                                            if (OperationHistoryStore.undo(context, r.historyId)) {
                                                val partial = OperationHistoryStore.hasPending(context, r.historyId)
                                                result = null; plan = null
                                                Toast.makeText(context, context.uiText(appLanguage, if (partial) "p12_undo_partial" else "b2_pack_undone"), Toast.LENGTH_SHORT).show()
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
                        ) { Icon(Icons.Default.Undo, null); Spacer(Modifier.width(6.dp)); Text(context.uiText(appLanguage, "b2_pack_undo")) }
                    }
                }
            }
        }
    }
}
