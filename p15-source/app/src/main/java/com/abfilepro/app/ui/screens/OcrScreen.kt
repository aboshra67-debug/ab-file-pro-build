package com.abfilepro.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import com.abfilepro.app.ui.i18n.localizedLabel
import com.abfilepro.app.ui.i18n.localizedOcrCategory
import com.abfilepro.app.ui.i18n.localizedOperationFailure
import com.abfilepro.app.core.FileUtils
import com.abfilepro.app.core.OcrProcessor
import com.abfilepro.app.ui.components.ScreenTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun OcrScreen(onBack: () -> Unit, onOpenPdf: (File) -> Unit) {
    val context = LocalContext.current
    val appLanguage = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var ocrLanguage by remember { mutableStateOf(OcrProcessor.Language.ARABIC_ENGLISH) }
    var selectedImage by remember { mutableStateOf<Uri?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var crop by remember { mutableStateOf(OcrProcessor.CropSpec()) }
    var showCrop by remember { mutableStateOf(false) }
    var pdfProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var lastPdf by remember { mutableStateOf<File?>(null) }
    var lastTextFile by remember { mutableStateOf<File?>(null) }
    var showModels by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var suggestedName by remember { mutableStateOf("OCR") }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    fun launchCancellable(errorKey: String, block: suspend () -> Unit) {
        if (busy) return
        activeJob = scope.launch {
            busy = true
            try {
                block()
            } catch (error: CancellationException) {
                Toast.makeText(context, context.uiText(appLanguage, "v15_cancelled"), Toast.LENGTH_SHORT).show()
            } catch (error: Throwable) {
                Toast.makeText(context, context.localizedOperationFailure(appLanguage, error, errorKey), Toast.LENGTH_LONG).show()
            } finally {
                pdfProgress = null
                busy = false
                activeJob = null
            }
        }
    }

    fun refreshPreview(uri: Uri, newCrop: OcrProcessor.CropSpec = crop) {
        scope.launch {
            val bmp = runCatching { OcrProcessor.loadPreview(context, uri, newCrop) }.getOrNull()
            preview?.recycle()
            preview = bmp
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedImage = uri
            crop = OcrProcessor.CropSpec()
            refreshPreview(uri, OcrProcessor.CropSpec())
        }
    }

    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) launchCancellable("ocr_screen_003") {
            pdfProgress = 0 to 0
            val result = OcrProcessor.createSearchablePdf(context, uri, ocrLanguage) { page, total ->
                withContext(Dispatchers.Main) { pdfProgress = page to total }
            }
            lastPdf = result.pdf
            lastTextFile = result.textFile
            text = result.text.ifBlank { context.uiText(appLanguage, "ocr_screen_001") }
            suggestedName = OcrProcessor.suggestBaseName(result.text)
            Toast.makeText(context, context.uiText(appLanguage, "ocr_screen_002", result.pageCount), Toast.LENGTH_LONG).show()
        }
    }

    val searchableImagesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) launchCancellable("ocr_screen_006") {
            pdfProgress = 0 to 0
            val result = OcrProcessor.createSearchablePdfFromImages(context, uris, ocrLanguage) { page, total ->
                withContext(Dispatchers.Main) { pdfProgress = page to total }
            }
            lastPdf = result.pdf
            lastTextFile = result.textFile
            text = result.text.ifBlank { context.uiText(appLanguage, "ocr_screen_004") }
            suggestedName = OcrProcessor.suggestBaseName(result.text)
            Toast.makeText(context, context.uiText(appLanguage, "ocr_screen_005", result.pageCount), Toast.LENGTH_LONG).show()
        }
    }

    fun runImageOcr() {
        val uri = selectedImage ?: return
        launchCancellable("ocr_screen_008") {
            val result = OcrProcessor.recognize(context, uri, ocrLanguage, crop)
            text = result.ifBlank { context.uiText(appLanguage, "ocr_screen_007") }
            suggestedName = OcrProcessor.suggestBaseName(result)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activeJob?.cancel()
            preview?.recycle()
        }
    }

    Scaffold(topBar = { ScreenTopBar(context.uiText(appLanguage, "ocr_screen_009"), onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(context.uiText(appLanguage, "ocr_screen_010"), fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OcrProcessor.Language.entries.forEach { option ->
                    FilterChip(
                        selected = ocrLanguage == option,
                        onClick = { ocrLanguage = option },
                        label = { Text(context.localizedLabel(appLanguage, option)) },
                        enabled = !busy
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ImageSearch, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(context.uiText(appLanguage, "ocr_screen_011"), fontWeight = FontWeight.Bold)
                            Text(context.uiText(appLanguage, "ocr_screen_012"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Button(onClick = { imagePicker.launch("image/*") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(6.dp)); Text(context.uiText(appLanguage, "ocr_screen_013"))
                    }

                    preview?.let { bmp ->
                        Surface(Modifier.fillMaxWidth().height(230.dp), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                            Image(bmp.asImageBitmap(), context.uiText(appLanguage, "ocr_screen_014"), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { showCrop = !showCrop }, enabled = !busy, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Crop, null); Spacer(Modifier.width(5.dp)); Text(if (showCrop) context.uiText(appLanguage, "ocr_screen_015") else context.uiText(appLanguage, "ocr_screen_016"))
                            }
                            Button(onClick = ::runImageOcr, enabled = !busy, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.TextSnippet, null); Spacer(Modifier.width(5.dp)); Text(context.uiText(appLanguage, "ocr_screen_017"))
                            }
                        }

                        if (showCrop) {
                            CropControls(
                                crop = crop,
                                enabled = !busy,
                                onChange = { newCrop ->
                                    crop = newCrop
                                    selectedImage?.let { refreshPreview(it, newCrop) }
                                },
                                onReset = {
                                    crop = OcrProcessor.CropSpec()
                                    selectedImage?.let { refreshPreview(it, crop) }
                                }
                            )
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FindInPage, null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(context.uiText(appLanguage, "ocr_screen_018"), fontWeight = FontWeight.Bold)
                            Text(context.uiText(appLanguage, "ocr_screen_019"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Button(
                        onClick = { pdfPicker.launch(arrayOf("application/pdf")) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(6.dp)); Text(context.uiText(appLanguage, "ocr_screen_020"))
                    }
                    OutlinedButton(
                        onClick = { searchableImagesPicker.launch(arrayOf("image/*")) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Collections, null); Spacer(Modifier.width(6.dp)); Text(context.uiText(appLanguage, "ocr_screen_021"))
                    }
                    pdfProgress?.let { (page, total) ->
                        if (total > 0) {
                            LinearProgressIndicator(progress = { page / total.toFloat() }, modifier = Modifier.fillMaxWidth())
                            Text(context.uiText(appLanguage, "ocr_screen_022", page, total), style = MaterialTheme.typography.bodySmall)
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(context.uiText(appLanguage, "ocr_screen_023"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    lastPdf?.let { file ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onOpenPdf(file) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Visibility, null); Spacer(Modifier.width(4.dp)); Text(context.uiText(appLanguage, "ocr_screen_024"))
                            }
                            OutlinedButton(onClick = { FileUtils.shareFile(context, file) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Share, null); Spacer(Modifier.width(4.dp)); Text(context.uiText(appLanguage, "ocr_screen_025"))
                            }
                        }
                    }
                }
            }

            if (busy && pdfProgress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (busy && activeJob != null) {
                OutlinedButton(
                    onClick = { activeJob?.cancel() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Close, null)
                    Spacer(Modifier.width(6.dp))
                    Text(context.uiText(appLanguage, "v15_cancel_operation"))
                }
                Text(
                    context.uiText(appLanguage, "v15_processing_progress"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(context.uiText(appLanguage, "ocr_screen_026"), fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                placeholder = { Text(context.uiText(appLanguage, "ocr_screen_027")) },
                enabled = !busy
            )

            if (text.isNotBlank()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    label = { Text(context.uiText(appLanguage, "ocr_screen_028")) },
                    supportingText = {
                        if (searchQuery.isNotBlank()) Text(context.uiText(appLanguage, "ocr_screen_029", OcrProcessor.findMatches(text, searchQuery)))
                    },
                    enabled = !busy
                )
                OutlinedTextField(
                    value = suggestedName,
                    onValueChange = { suggestedName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                    label = { Text(context.uiText(appLanguage, "ocr_screen_030")) },
                    supportingText = { Text(context.uiText(appLanguage, "ocr_screen_031")) },
                    enabled = !busy
                )
                AssistChip(
                    onClick = {},
                    label = { Text(context.uiText(appLanguage, "ocr_screen_032", context.localizedOcrCategory(appLanguage, OcrProcessor.suggestCategory(text)))) },
                    leadingIcon = { Icon(Icons.Default.FolderSpecial, null) },
                    enabled = false
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("OCR", text))
                        Toast.makeText(context, context.uiText(appLanguage, "ocr_screen_033"), Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f), enabled = text.isNotBlank()
                ) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(4.dp)); Text(context.uiText(appLanguage, "ocr_screen_034")) }
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
                        context.startActivity(Intent.createChooser(intent, context.uiText(appLanguage, "ocr_screen_035")))
                    },
                    modifier = Modifier.weight(1f), enabled = text.isNotBlank()
                ) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(4.dp)); Text(context.uiText(appLanguage, "ocr_screen_025")) }
                Button(
                    onClick = {
                        runCatching { OcrProcessor.saveText(context, text, suggestedName) }
                            .onSuccess { file ->
                                lastTextFile = file
                                Toast.makeText(context, context.uiText(appLanguage, "ocr_screen_036", file.name), Toast.LENGTH_SHORT).show()
                            }
                            .onFailure { Toast.makeText(context, context.uiText(appLanguage, "ocr_screen_037"), Toast.LENGTH_LONG).show() }
                    },
                    modifier = Modifier.weight(1f), enabled = text.isNotBlank()
                ) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(4.dp)); Text(context.uiText(appLanguage, "ocr_screen_038")) }
            }
            lastTextFile?.let { file ->
                Text(context.uiText(appLanguage, "ocr_screen_039", file.name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            OutlinedButton(onClick = { showModels = !showModels }, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                Icon(Icons.Default.Language, null); Spacer(Modifier.width(6.dp)); Text(context.uiText(appLanguage, "ocr_screen_040"))
            }
            if (showModels) OcrModelManager(context, busy) { busy = it }

            Text(
                if (OcrProcessor.isModelReady(context, ocrLanguage))
                    context.uiText(appLanguage, "ocr_screen_041")
                else
                    context.uiText(appLanguage, "ocr_screen_042"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .60f)
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun CropControls(
    crop: OcrProcessor.CropSpec,
    enabled: Boolean,
    onChange: (OcrProcessor.CropSpec) -> Unit,
    onReset: () -> Unit
) {
    val context = LocalContext.current
    val appLanguage = LocalAppLanguage.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f))) {
        Column(Modifier.padding(12.dp)) {
            Text(context.uiText(appLanguage, "ocr_screen_043"), fontWeight = FontWeight.Bold)
            Text(context.uiText(appLanguage, "ocr_screen_044"), style = MaterialTheme.typography.bodySmall)
            CropSlider(context.uiText(appLanguage, "ocr_screen_045"), crop.left, enabled) { onChange(crop.copy(left = it)) }
            CropSlider(context.uiText(appLanguage, "ocr_screen_046"), crop.right, enabled) { onChange(crop.copy(right = it)) }
            CropSlider(context.uiText(appLanguage, "ocr_screen_047"), crop.top, enabled) { onChange(crop.copy(top = it)) }
            CropSlider(context.uiText(appLanguage, "ocr_screen_048"), crop.bottom, enabled) { onChange(crop.copy(bottom = it)) }
            TextButton(onClick = onReset, enabled = enabled) {
                Icon(Icons.Default.RestartAlt, null); Spacer(Modifier.width(5.dp)); Text(context.uiText(appLanguage, "ocr_screen_049"))
            }
        }
    }
}

@Composable
private fun CropSlider(title: String, value: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodySmall)
            Text("${(value * 100).toInt()}%", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
        Slider(value = value, onValueChange = onChange, valueRange = 0f..0.4f, enabled = enabled)
    }
}

@Composable
private fun OcrModelManager(context: Context, globalBusy: Boolean, onBusy: (Boolean) -> Unit) {
    val appLanguage = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OcrProcessor.Model.entries.forEach { model ->
                val ready = remember(refresh) { OcrProcessor.isModelReady(context, model) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(context.localizedLabel(appLanguage, model), fontWeight = FontWeight.SemiBold)
                        Text(if (ready) context.uiText(appLanguage, "ocr_screen_050") else context.uiText(appLanguage, "ocr_screen_051"), style = MaterialTheme.typography.bodySmall)
                    }
                    if (!ready) {
                        Button(onClick = {
                            scope.launch {
                                onBusy(true)
                                runCatching { OcrProcessor.downloadModel(context, model) }
                                    .onSuccess { Toast.makeText(context, context.uiText(appLanguage, "ocr_screen_052", context.localizedLabel(appLanguage, model)), Toast.LENGTH_SHORT).show() }
                                    .onFailure { Toast.makeText(context, context.uiText(appLanguage, "ocr_screen_053"), Toast.LENGTH_LONG).show() }
                                refresh++
                                onBusy(false)
                            }
                        }, enabled = !globalBusy) { Text(context.uiText(appLanguage, "ocr_screen_054")) }
                    } else {
                        TextButton(onClick = {
                            if (OcrProcessor.removeModel(context, model)) {
                                refresh++
                                Toast.makeText(context, context.uiText(appLanguage, "ocr_screen_055", context.localizedLabel(appLanguage, model)), Toast.LENGTH_SHORT).show()
                            }
                        }, enabled = !globalBusy) { Text(context.uiText(appLanguage, "ocr_screen_056")) }
                    }
                }
                if (model != OcrProcessor.Model.entries.last()) HorizontalDivider()
            }
        }
    }
}
