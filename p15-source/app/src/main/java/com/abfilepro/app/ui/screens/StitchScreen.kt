package com.abfilepro.app.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import com.abfilepro.app.core.FileUtils
import com.abfilepro.app.core.OperationHistoryStore
import com.abfilepro.app.core.StitchProcessor
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
fun StitchScreen(onBack: () -> Unit, onOpenPdf: (File) -> Unit) {
    val context = LocalContext.current
    val appLanguage = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var smartOrder by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<StitchProcessor.Result?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var cropTop by remember { mutableFloatStateOf(0f) }
    var cropBottom by remember { mutableFloatStateOf(0f) }
    var historyId by remember { mutableStateOf<String?>(null) }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        if (uris.isNotEmpty()) {
            result?.let { StitchProcessor.discardDraft(it) }
            selectedUris = uris
            result = null
            historyId = null
            cropTop = 0f
            cropBottom = 0f
        }
    }

    LaunchedEffect(result?.imageFile?.absolutePath, cropTop, cropBottom) {
        preview?.let { if (!it.isRecycled) it.recycle() }
        preview = null
        val file = result?.imageFile ?: return@LaunchedEffect
        val base = withContext(Dispatchers.IO) { runCatching { FileUtils.loadImagePreview(file, 1400) }.getOrNull() } ?: return@LaunchedEffect
        val topPx = (base.height * cropTop).toInt().coerceIn(0, base.height - 1)
        val endPx = (base.height * (1f - cropBottom)).toInt().coerceIn(topPx + 1, base.height)
        preview = if (topPx == 0 && endPx == base.height) base else {
            val cropped = Bitmap.createBitmap(base, 0, topPx, base.width, endPx - topPx)
            if (cropped !== base && !base.isRecycled) base.recycle()
            cropped
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            activeJob?.cancel()
            preview?.let { if (!it.isRecycled) it.recycle() }
            result?.let { if (it.isDraft) StitchProcessor.discardDraft(it) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AB Stitch", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack, enabled = !busy) { Icon(Icons.Default.ArrowBack, context.uiText(appLanguage, "b2_stitch_back")) } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.ViewAgenda, null, modifier = Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text(context.uiText(appLanguage, "b2_stitch_subtitle"), fontWeight = FontWeight.ExtraBold)
                            Text(context.uiText(appLanguage, "b2_stitch_desc"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(context.uiText(appLanguage, "b2_stitch_limit"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Button(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.fillMaxWidth(), enabled = !busy
            ) {
                Icon(Icons.Default.Collections, null); Spacer(Modifier.width(8.dp))
                Text(if (selectedUris.isEmpty()) context.uiText(appLanguage, "b2_stitch_pick") else context.uiText(appLanguage, "b2_stitch_change", selectedUris.size))
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(context.uiText(appLanguage, "b2_stitch_smart_order"), fontWeight = FontWeight.Bold)
                    Text(if (smartOrder) context.uiText(appLanguage, "b2_stitch_smart_on") else context.uiText(appLanguage, "b2_stitch_smart_off"), style = MaterialTheme.typography.labelSmall)
                }
                Switch(checked = smartOrder, onCheckedChange = { smartOrder = it }, enabled = !busy && result == null)
            }

            if (selectedUris.size >= 2 && result == null) {
                Text(context.uiText(appLanguage, "b2_stitch_manual_order"), fontWeight = FontWeight.Bold)
                selectedUris.forEachIndexed { index, _ ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(context.uiText(appLanguage, "b2_stitch_image_no", index + 1), Modifier.weight(1f))
                            IconButton(
                                onClick = {
                                    val list = selectedUris.toMutableList()
                                    val previous = list[index - 1]; list[index - 1] = list[index]; list[index] = previous
                                    selectedUris = list; smartOrder = false
                                },
                                enabled = !busy && index > 0
                            ) { Icon(Icons.Default.KeyboardArrowUp, context.uiText(appLanguage, "b2_stitch_up")) }
                            IconButton(
                                onClick = {
                                    val list = selectedUris.toMutableList()
                                    val next = list[index + 1]; list[index + 1] = list[index]; list[index] = next
                                    selectedUris = list; smartOrder = false
                                },
                                enabled = !busy && index < selectedUris.lastIndex
                            ) { Icon(Icons.Default.KeyboardArrowDown, context.uiText(appLanguage, "b2_stitch_down")) }
                        }
                    }
                }

                Button(
                    onClick = {
                        busy = true; progress = context.uiText(appLanguage, "b2_stitch_starting")
                        activeJob = scope.launch {
                            try {
                                val stitched = StitchProcessor.stitch(context, selectedUris, smartOrder = smartOrder, languageCode = appLanguage.code, draftMode = true) { label -> progress = label }
                                result = stitched
                                cropTop = 0f; cropBottom = 0f
                                Toast.makeText(context, context.uiText(appLanguage, "b2_stitch_review"), Toast.LENGTH_SHORT).show()
                            } catch (error: Throwable) {
                                Toast.makeText(context, context.localizedOperationFailure(appLanguage, error, "b2_stitch_error_join"), Toast.LENGTH_LONG).show()
                            } finally { busy = false; progress = ""; activeJob = null }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy
                ) { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text(context.uiText(appLanguage, "b2_stitch_make_preview")) }
            }

            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(progress, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterHorizontally))
                Text(context.uiText(appLanguage, "v15_processing_progress"), style = MaterialTheme.typography.labelSmall)
                OutlinedButton(onClick = { activeJob?.cancel() }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Close, null); Spacer(Modifier.width(6.dp)); Text(context.uiText(appLanguage, "v15_cancel_operation")) }
            }

            result?.let { stitched ->
                Text(if (stitched.isDraft) context.uiText(appLanguage, "b2_stitch_preview") else context.uiText(appLanguage, "b2_stitch_saved_result"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                preview?.let { bitmap ->
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                        Image(bitmap.asImageBitmap(), context.uiText(appLanguage, "b2_stitch_stitched_image"), Modifier.fillMaxWidth().heightIn(max = 360.dp), contentScale = ContentScale.Fit)
                    }
                }

                if (stitched.isDraft) {
                    Text(context.uiText(appLanguage, "b2_stitch_crop_top", (cropTop * 100).toInt()), style = MaterialTheme.typography.labelMedium)
                    Slider(value = cropTop, onValueChange = { cropTop = it.coerceAtMost(0.30f - cropBottom) }, valueRange = 0f..0.25f, enabled = !busy)
                    Text(context.uiText(appLanguage, "b2_stitch_crop_bottom", (cropBottom * 100).toInt()), style = MaterialTheme.typography.labelMedium)
                    Slider(value = cropBottom, onValueChange = { cropBottom = it.coerceAtMost(0.30f - cropTop) }, valueRange = 0f..0.25f, enabled = !busy)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { StitchProcessor.discardDraft(stitched); result = null; cropTop = 0f; cropBottom = 0f },
                            modifier = Modifier.weight(1f), enabled = !busy
                        ) { Text(context.uiText(appLanguage, "b2_stitch_cancel")) }
                        Button(
                            onClick = {
                                busy = true; progress = context.uiText(appLanguage, "b2_stitch_saving")
                                activeJob = scope.launch {
                                    try {
                                        val saved = withContext(Dispatchers.IO) { StitchProcessor.commitDraft(context, stitched, cropTop, cropBottom, appLanguage.code) }
                                        result = saved
                                        val history = OperationHistoryStore.record(context, "AB Stitch", context.uiText(appLanguage, "b2_stitch_joined_count", saved.sourceCount), listOf(saved.imageFile, saved.pdfFile))
                                        historyId = history.id
                                        cropTop = 0f; cropBottom = 0f
                                    } catch (error: Throwable) {
                                        Toast.makeText(context, context.localizedOperationFailure(appLanguage, error, "b2_stitch_error_save"), Toast.LENGTH_LONG).show()
                                    } finally { busy = false; progress = ""; activeJob = null }
                                }
                            },
                            modifier = Modifier.weight(1f), enabled = !busy
                        ) { Icon(Icons.Default.Check, null); Spacer(Modifier.width(5.dp)); Text(context.uiText(appLanguage, "b2_stitch_approve_save")) }
                    }
                } else {
                    Text(context.uiText(appLanguage, "b2_stitch_stats", stitched.sourceCount, stitched.outputWidth, stitched.outputHeight, stitched.confidentLinks, (stitched.sourceCount - 1).coerceAtLeast(0)), style = MaterialTheme.typography.labelSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { FileUtils.shareImageToWhatsApp(context, stitched.imageFile) }, modifier = Modifier.weight(1f)) { Text(context.uiText(appLanguage, "b2_stitch_whatsapp")) }
                        OutlinedButton(onClick = { FileUtils.shareFile(context, stitched.imageFile) }, modifier = Modifier.weight(1f)) { Text(context.uiText(appLanguage, "b2_stitch_share_image")) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onOpenPdf(stitched.pdfFile) }, modifier = Modifier.weight(1f)) { Text(context.uiText(appLanguage, "b2_stitch_open_pdf")) }
                        OutlinedButton(onClick = { FileUtils.shareFile(context, stitched.pdfFile) }, modifier = Modifier.weight(1f)) { Text(context.uiText(appLanguage, "b2_stitch_share_pdf")) }
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
                                                result = null; historyId = null; selectedUris = emptyList()
                                                Toast.makeText(context, context.uiText(appLanguage, if (partial) "p12_undo_partial" else "b2_stitch_undone"), Toast.LENGTH_SHORT).show()
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
                        ) { Icon(Icons.Default.Undo, null); Spacer(Modifier.width(6.dp)); Text(context.uiText(appLanguage, "b2_stitch_undo")) }
                    }
                }
            }
        }
    }
}
