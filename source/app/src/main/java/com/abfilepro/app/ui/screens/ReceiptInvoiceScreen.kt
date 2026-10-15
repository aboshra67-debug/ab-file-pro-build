package com.abfilepro.app.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
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
import com.abfilepro.app.core.OcrProcessor
import com.abfilepro.app.core.ReceiptInvoiceProcessor
import com.abfilepro.app.core.findActivity
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.i18n.localizedLabel
import com.abfilepro.app.ui.i18n.uiText
import com.abfilepro.app.ui.components.ScreenTopBar
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptInvoiceScreen(onBack: () -> Unit, initialUriText: String = "") {
    val context = LocalContext.current
    val appLanguage = LocalAppLanguage.current
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()

    var sourceFile by remember { mutableStateOf<File?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var language by remember { mutableStateOf(OcrProcessor.Language.ARABIC_ENGLISH) }
    var busy by remember { mutableStateOf(false) }
    var parsed by remember { mutableStateOf(ReceiptInvoiceProcessor.ParsedData()) }
    var baseName by remember { mutableStateOf(context.uiText(appLanguage, "b2_receipt_default_name")) }
    var lastSaved by remember { mutableStateOf<File?>(null) }
    var showRaw by remember { mutableStateOf(false) }

    val scannerOptions = remember {
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(1)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
    }
    val scanner = remember { GmsDocumentScanning.getClient(scannerOptions) }

    fun recyclePreview() {
        preview?.let { if (!it.isRecycled) it.recycle() }
        preview = null
    }

    fun analyzeUri(uri: Uri) {
        scope.launch {
            busy = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val cached = ReceiptInvoiceProcessor.cacheSource(context, uri)
                    val fileUri = Uri.fromFile(cached)
                    val image = OcrProcessor.loadPreview(context, fileUri)
                    val text = OcrProcessor.recognize(context, fileUri, language)
                    Triple(cached, image, ReceiptInvoiceProcessor.parse(context, appLanguage.code, text))
                }
            }.onSuccess { (cached, image, data) ->
                recyclePreview()
                ReceiptInvoiceProcessor.cleanup(sourceFile)
                sourceFile = cached
                preview = image
                parsed = data
            }.onFailure {
                Toast.makeText(context, context.uiText(appLanguage, "b2_receipt_error_read"), Toast.LENGTH_LONG).show()
            }
            busy = false
        }
    }

    fun rerunOcr() {
        val file = sourceFile ?: return
        scope.launch {
            busy = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val text = OcrProcessor.recognize(context, Uri.fromFile(file), language)
                    ReceiptInvoiceProcessor.parse(context, appLanguage.code, text)
                }
            }.onSuccess { parsed = it }
                .onFailure { Toast.makeText(context, context.uiText(appLanguage, "b2_receipt_error_ocr"), Toast.LENGTH_LONG).show() }
            busy = false
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) analyzeUri(uri)
    }

    val scannerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result: ActivityResult ->
        busy = false
        if (result.resultCode == Activity.RESULT_OK) {
            GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages?.firstOrNull()?.imageUri?.let(::analyzeUri)
        }
    }

    fun launchScanner() {
        if (activity == null) {
            Toast.makeText(context, context.uiText(appLanguage, "b2_receipt_error_camera"), Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        scanner.getStartScanIntent(activity)
            .addOnSuccessListener { sender -> scannerLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener {
                busy = false
                Toast.makeText(context, context.uiText(appLanguage, "b2_receipt_error_scanner"), Toast.LENGTH_LONG).show()
            }
    }

    LaunchedEffect(initialUriText) {
        if (initialUriText.isNotBlank() && sourceFile == null) {
            runCatching { Uri.parse(initialUriText) }.getOrNull()?.let(::analyzeUri)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recyclePreview()
            ReceiptInvoiceProcessor.cleanup(sourceFile)
        }
    }

    val updateParsed: ((ReceiptInvoiceProcessor.ParsedData) -> ReceiptInvoiceProcessor.ParsedData) -> Unit = { updater -> parsed = updater(parsed) }

    Scaffold(topBar = { ScreenTopBar(context.uiText(appLanguage, "b2_receipt_title"), onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ReceiptLong, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(context.uiText(appLanguage, "b2_receipt_subtitle"), fontWeight = FontWeight.Bold)
                    }
                    Text(
                        context.uiText(appLanguage, "b2_receipt_desc"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f)
                    )
                }
            }

            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OcrProcessor.Language.entries.forEach { option ->
                    FilterChip(
                        selected = language == option,
                        onClick = { language = option },
                        label = { Text(context.localizedLabel(appLanguage, option)) },
                        enabled = !busy
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::launchScanner, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.DocumentScanner, null); Spacer(Modifier.width(5.dp)); Text(context.uiText(appLanguage, "b2_receipt_camera"))
                }
                OutlinedButton(onClick = { gallery.launch("image/*") }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(5.dp)); Text(context.uiText(appLanguage, "b2_receipt_pick"))
                }
            }

            preview?.let { image ->
                Card(Modifier.fillMaxWidth().height(240.dp)) {
                    Image(image.asImageBitmap(), context.uiText(appLanguage, "b2_receipt_preview"), Modifier.fillMaxSize().padding(6.dp), contentScale = ContentScale.Fit)
                }
            }

            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(context.uiText(appLanguage, "b2_receipt_processing"), style = MaterialTheme.typography.bodySmall)
            }

            if (sourceFile != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(context.uiText(appLanguage, "b2_receipt_data"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    AssistChip(onClick = {}, label = { Text(context.uiText(appLanguage, "b2_receipt_conf", parsed.confidence)) })
                }

                OutlinedTextField(
                    value = parsed.documentType,
                    onValueChange = { value -> updateParsed { it.copy(documentType = value) } },
                    label = { Text(context.uiText(appLanguage, "b2_receipt_type")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !busy
                )
                OutlinedTextField(
                    value = parsed.vendor,
                    onValueChange = { value -> updateParsed { it.copy(vendor = value) } },
                    label = { Text(context.uiText(appLanguage, "b2_receipt_vendor")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !busy
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = parsed.date,
                        onValueChange = { value -> updateParsed { it.copy(date = value) } },
                        label = { Text(context.uiText(appLanguage, "b2_receipt_date")) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        enabled = !busy
                    )
                    OutlinedTextField(
                        value = parsed.invoiceNumber,
                        onValueChange = { value -> updateParsed { it.copy(invoiceNumber = value) } },
                        label = { Text(context.uiText(appLanguage, "b2_receipt_number")) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        enabled = !busy
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = parsed.total,
                        onValueChange = { value -> updateParsed { it.copy(total = value) } },
                        label = { Text(context.uiText(appLanguage, "b2_receipt_total")) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        enabled = !busy
                    )
                    OutlinedTextField(
                        value = parsed.tax,
                        onValueChange = { value -> updateParsed { it.copy(tax = value) } },
                        label = { Text(context.uiText(appLanguage, "b2_receipt_tax")) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        enabled = !busy
                    )
                }

                OutlinedButton(onClick = ::rerunOcr, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text(context.uiText(appLanguage, "b2_receipt_rerun"))
                }

                TextButton(onClick = { showRaw = !showRaw }, modifier = Modifier.fillMaxWidth()) {
                    Icon(if (showRaw) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                    Spacer(Modifier.width(5.dp)); Text(if (showRaw) context.uiText(appLanguage, "b2_receipt_hide_text") else context.uiText(appLanguage, "b2_receipt_show_text"))
                }
                if (showRaw) {
                    OutlinedTextField(
                        value = parsed.rawText,
                        onValueChange = { value -> updateParsed { it.copy(rawText = value) } },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                        label = { Text(context.uiText(appLanguage, "b2_receipt_original_text")) },
                        enabled = !busy
                    )
                }

                OutlinedTextField(
                    value = baseName,
                    onValueChange = { baseName = it },
                    label = { Text(context.uiText(appLanguage, "b2_receipt_filename")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !busy
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText(context.uiText(appLanguage, "b2_receipt_clip_label"), ReceiptInvoiceProcessor.structuredText(context, appLanguage.code, parsed)))
                            Toast.makeText(context, context.uiText(appLanguage, "b2_receipt_copied"), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(4.dp)); Text(context.uiText(appLanguage, "b2_receipt_copy")) }
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, ReceiptInvoiceProcessor.structuredText(context, appLanguage.code, parsed))
                            }
                            context.startActivity(Intent.createChooser(intent, context.uiText(appLanguage, "b2_receipt_share_title")))
                        },
                        modifier = Modifier.weight(1f)
                    ) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(4.dp)); Text(context.uiText(appLanguage, "b2_receipt_share")) }
                    Button(
                        onClick = {
                            runCatching { ReceiptInvoiceProcessor.saveResult(context, appLanguage.code, parsed, baseName) }
                                .onSuccess {
                                    lastSaved = it
                                    Toast.makeText(context, context.uiText(appLanguage, "b2_receipt_saved", it.name), Toast.LENGTH_SHORT).show()
                                }
                                .onFailure { Toast.makeText(context, context.uiText(appLanguage, "b2_receipt_error_save"), Toast.LENGTH_LONG).show() }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(4.dp)); Text(context.uiText(appLanguage, "b2_receipt_save")) }
                }
                lastSaved?.let { Text(context.uiText(appLanguage, "b2_receipt_last", it.name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }

            Text(
                if (OcrProcessor.isModelReady(context, language))
                    context.uiText(appLanguage, "b2_receipt_model_ready")
                else
                    context.uiText(appLanguage, "b2_receipt_model_download"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .60f)
            )
        }
    }
}
