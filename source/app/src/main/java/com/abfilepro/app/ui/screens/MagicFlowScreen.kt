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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.i18n.uiText
import com.abfilepro.app.ui.i18n.localizedOperationFailure
import com.abfilepro.app.core.FileUtils
import com.abfilepro.app.core.MagicFlowProcessor
import com.abfilepro.app.core.OcrProcessor
import com.abfilepro.app.core.OperationHistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MagicFlowScreen(onBack: () -> Unit, onOpenPdf: (File) -> Unit) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    var uris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var captureFile by remember { mutableStateOf<File?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var proposal by remember { mutableStateOf<MagicFlowProcessor.Proposal?>(null) }
    var result by remember { mutableStateOf<MagicFlowProcessor.Result?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var editMode by remember { mutableStateOf(false) }
    var finalName by remember { mutableStateOf("") }
    var finalFolder by remember { mutableStateOf("") }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    fun resetPlan() {
        proposal = null
        result = null
        editMode = false
        finalName = ""
        finalFolder = ""
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { selected ->
        uris = selected.take(30)
        resetPlan()
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = captureFile
        if (ok && file != null && file.length() > 0L) {
            uris = listOf(FileUtils.contentUri(context, file))
            resetPlan()
        } else {
            runCatching { file?.delete() }
        }
    }

    LaunchedEffect(uris.firstOrNull()?.toString()) {
        preview?.let { if (!it.isRecycled) it.recycle() }
        preview = null
        uris.firstOrNull()?.let { uri ->
            preview = withContext(Dispatchers.IO) { runCatching { OcrProcessor.loadPreview(context, uri) }.getOrNull() }
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
                title = { Text("AB Magic Flow", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack, enabled = !busy) { Icon(Icons.Default.ArrowBack, context.uiText(language, "magic_flow_screen_001")) } }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                        Text(context.uiText(language, "magic_flow_screen_002"), fontWeight = FontWeight.ExtraBold)
                    }
                    Text(
                        context.uiText(language, "magic_flow_screen_003"),
                        style = MaterialTheme.typography.bodySmall
                    )
                    AssistChip(onClick = {}, label = { Text(context.uiText(language, "magic_flow_screen_004")) }, leadingIcon = { Icon(Icons.Default.Security, null) })
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val file = File(FileUtils.outputDir(context, "AB Magic Flow/Captures"), "Original_${System.currentTimeMillis()}.jpg")
                        if (runCatching { file.createNewFile() }.getOrDefault(false)) {
                            captureFile = file
                            camera.launch(FileUtils.contentUri(context, file))
                        } else Toast.makeText(context, context.uiText(language, "magic_flow_screen_005"), Toast.LENGTH_LONG).show()
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) { Icon(Icons.Default.PhotoCamera, null); Spacer(Modifier.width(6.dp)); Text(context.uiText(language, "magic_flow_screen_006")) }
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("image/*")) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) { Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(6.dp)); Text(context.uiText(language, "magic_flow_screen_007")) }
            }

            if (uris.isNotEmpty()) {
                Text(context.uiText(language, "magic_flow_screen_008", uris.size), style = MaterialTheme.typography.labelMedium)
                preview?.let { bitmap ->
                    Card(shape = RoundedCornerShape(16.dp)) {
                        Image(
                            bitmap.asImageBitmap(),
                            context.uiText(language, "magic_flow_screen_009"),
                            Modifier.fillMaxWidth().heightIn(max = 300.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }

            if (uris.isNotEmpty() && proposal == null && result == null) {
                Button(
                    onClick = {
                        busy = true; progress = context.uiText(language, "magic_flow_screen_010")
                        activeJob = scope.launch {
                            try {
                                val p = MagicFlowProcessor.analyze(context, uris, language.code) { progress = it }
                                proposal = p
                                finalName = p.suggestedName
                                finalFolder = p.suggestedFolder
                            } catch (error: Throwable) {
                                Toast.makeText(context, context.localizedOperationFailure(language, error, "magic_flow_screen_011"), Toast.LENGTH_LONG).show()
                            } finally {
                                busy = false; progress = ""; activeJob = null
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Icon(Icons.Default.AutoFixHigh, null); Spacer(Modifier.width(8.dp)); Text(context.uiText(language, "magic_flow_screen_012")) }
            }

            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(progress, style = MaterialTheme.typography.bodySmall)
                Text(context.uiText(language, "v15_processing_progress"), style = MaterialTheme.typography.labelSmall)
                OutlinedButton(onClick = { activeJob?.cancel() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Close, null); Spacer(Modifier.width(6.dp)); Text(context.uiText(language, "v15_cancel_operation"))
                }
            }

            proposal?.let { p ->
                Card(shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(context.uiText(language, "magic_flow_screen_013"), fontWeight = FontWeight.ExtraBold)
                        Text(context.uiText(language, "magic_flow_screen_014", p.typeLabel, p.confidence))
                        if (editMode) {
                            OutlinedTextField(finalName, { finalName = it }, Modifier.fillMaxWidth(), label = { Text(context.uiText(language, "magic_flow_screen_015")) }, enabled = !busy)
                            OutlinedTextField(finalFolder, { finalFolder = it }, Modifier.fillMaxWidth(), label = { Text(context.uiText(language, "magic_flow_screen_016")) }, enabled = !busy)
                        } else {
                            Text(context.uiText(language, "magic_flow_screen_017", finalName))
                            Text(context.uiText(language, "magic_flow_screen_018", finalFolder))
                        }
                        Text(context.uiText(language, "magic_flow_screen_019", p.action), style = MaterialTheme.typography.bodySmall)
                        if (p.textPreview.isNotBlank()) {
                            Text(context.uiText(language, "magic_flow_screen_020"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            Text(p.textPreview, maxLines = 5, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { editMode = !editMode }, enabled = !busy, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Edit, null); Spacer(Modifier.width(4.dp)); Text(if (editMode) context.uiText(language, "magic_flow_screen_021") else context.uiText(language, "magic_flow_screen_022"))
                            }
                            Button(
                                onClick = {
                                    busy = true; progress = context.uiText(language, "magic_flow_screen_023")
                                    activeJob = scope.launch {
                                        try {
                                            result = MagicFlowProcessor.execute(context, uris, language.code, p, finalName, finalFolder) { progress = it }
                                            Toast.makeText(context, context.uiText(language, "magic_flow_screen_024"), Toast.LENGTH_SHORT).show()
                                        } catch (error: Throwable) {
                                            Toast.makeText(context, context.localizedOperationFailure(language, error, "magic_flow_screen_025"), Toast.LENGTH_LONG).show()
                                        } finally {
                                            busy = false; progress = ""; activeJob = null
                                        }
                                    }
                                },
                                enabled = !busy && finalName.isNotBlank() && finalFolder.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) { Icon(Icons.Default.Check, null); Spacer(Modifier.width(4.dp)); Text(context.uiText(language, "magic_flow_screen_026")) }
                        }
                        TextButton(onClick = { proposal = null; editMode = false }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Undo, null); Spacer(Modifier.width(5.dp)); Text(context.uiText(language, "magic_flow_screen_027"))
                        }
                    }
                }
            }

            result?.let { r ->
                Card(shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(context.uiText(language, "magic_flow_screen_028"), fontWeight = FontWeight.ExtraBold)
                        Text(context.uiText(language, "magic_flow_screen_029", r.pageCount, r.correctedPages, r.folder), style = MaterialTheme.typography.bodySmall)
                        Text(r.pdf.name, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onOpenPdf(r.pdf) }, modifier = Modifier.weight(1f)) { Text(context.uiText(language, "magic_flow_screen_030")) }
                            OutlinedButton(onClick = { FileUtils.shareFile(context, r.pdf) }, modifier = Modifier.weight(1f)) { Text(context.uiText(language, "magic_flow_screen_031")) }
                        }
                        OutlinedButton(
                            onClick = {
                                if (!busy) {
                                    busy = true
                                    activeJob = scope.launch {
                                        try {
                                            if (OperationHistoryStore.undo(context, r.historyId)) {
                                                val partial = OperationHistoryStore.hasPending(context, r.historyId)
                                                result = null; proposal = null
                                                Toast.makeText(context, context.uiText(language, if (partial) "p12_undo_partial" else "magic_flow_screen_032"), Toast.LENGTH_SHORT).show()
                                            } else Toast.makeText(context, context.uiText(language, "p12_undo_protected"), Toast.LENGTH_LONG).show()
                                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            Toast.makeText(context, context.uiText(language, "p12_undo_protected"), Toast.LENGTH_LONG).show()
                                        } finally { busy = false; activeJob = null }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy
                        ) { Icon(Icons.Default.Undo, null); Spacer(Modifier.width(6.dp)); Text(context.uiText(language, "magic_flow_screen_034")) }
                    }
                }
            }
        }
    }
}
