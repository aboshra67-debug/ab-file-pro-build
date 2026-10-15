package com.abfilepro.app.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.ContactsContract
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
import com.abfilepro.app.core.BusinessCardProcessor
import com.abfilepro.app.core.OcrProcessor
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
fun BusinessCardScreen(onBack: () -> Unit, initialUriText: String = "") {
    val context = LocalContext.current
    val appLanguage = LocalAppLanguage.current
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()

    var sourceFile by remember { mutableStateOf<File?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var language by remember { mutableStateOf(OcrProcessor.Language.ARABIC_ENGLISH) }
    var busy by remember { mutableStateOf(false) }
    var parsed by remember { mutableStateOf(BusinessCardProcessor.ParsedData()) }
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
                    val cached = BusinessCardProcessor.cacheSource(context, uri)
                    val fileUri = Uri.fromFile(cached)
                    val image = OcrProcessor.loadPreview(context, fileUri)
                    val text = OcrProcessor.recognize(context, fileUri, language)
                    Triple(cached, image, BusinessCardProcessor.parse(text))
                }
            }.onSuccess { (cached, image, data) ->
                recyclePreview()
                BusinessCardProcessor.cleanup(sourceFile)
                sourceFile = cached
                preview = image
                parsed = data
            }.onFailure {
                Toast.makeText(context, context.uiText(appLanguage, "b2_business_error_read"), Toast.LENGTH_LONG).show()
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
                    BusinessCardProcessor.parse(text)
                }
            }.onSuccess { parsed = it }
                .onFailure { Toast.makeText(context, context.uiText(appLanguage, "b2_business_error_ocr"), Toast.LENGTH_LONG).show() }
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
            Toast.makeText(context, context.uiText(appLanguage, "b2_business_error_camera"), Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        scanner.getStartScanIntent(activity)
            .addOnSuccessListener { sender -> scannerLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener {
                busy = false
                Toast.makeText(context, context.uiText(appLanguage, "b2_business_error_scanner"), Toast.LENGTH_LONG).show()
            }
    }

    fun addToContacts() {
        if (parsed.name.isBlank() && parsed.phone.isBlank() && parsed.email.isBlank()) {
            Toast.makeText(context, context.uiText(appLanguage, "b2_business_review_first"), Toast.LENGTH_SHORT).show()
            return
        }
        val notes = parsed.website.takeIf { it.isNotBlank() }?.let { context.uiText(appLanguage, "b2_export_pair", context.uiText(appLanguage, "b2_business_website"), it) }.orEmpty()
        val intent = Intent(Intent.ACTION_INSERT).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
            if (parsed.name.isNotBlank()) putExtra(ContactsContract.Intents.Insert.NAME, parsed.name)
            if (parsed.company.isNotBlank()) putExtra(ContactsContract.Intents.Insert.COMPANY, parsed.company)
            if (parsed.phone.isNotBlank()) putExtra(ContactsContract.Intents.Insert.PHONE, parsed.phone)
            if (parsed.email.isNotBlank()) putExtra(ContactsContract.Intents.Insert.EMAIL, parsed.email)
            if (notes.isNotBlank()) putExtra(ContactsContract.Intents.Insert.NOTES, notes)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, context.uiText(appLanguage, "b2_business_error_contacts"), Toast.LENGTH_LONG).show() }
    }

    fun copyData() {
        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText(context.uiText(appLanguage, "b2_business_clip_label"), BusinessCardProcessor.structuredText(context, appLanguage.code, parsed)))
        Toast.makeText(context, context.uiText(appLanguage, "b2_business_copied"), Toast.LENGTH_SHORT).show()
    }

    fun shareData() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, BusinessCardProcessor.structuredText(context, appLanguage.code, parsed))
        }
        runCatching { context.startActivity(Intent.createChooser(intent, context.uiText(appLanguage, "b2_business_share_title"))) }
            .onFailure { Toast.makeText(context, context.uiText(appLanguage, "b2_business_error_share"), Toast.LENGTH_SHORT).show() }
    }

    LaunchedEffect(initialUriText) {
        if (initialUriText.isNotBlank() && sourceFile == null) {
            runCatching { Uri.parse(initialUriText) }.getOrNull()?.let(::analyzeUri)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recyclePreview()
            BusinessCardProcessor.cleanup(sourceFile)
        }
    }

    val updateParsed: ((BusinessCardProcessor.ParsedData) -> BusinessCardProcessor.ParsedData) -> Unit = { updater -> parsed = updater(parsed) }

    Scaffold(topBar = { ScreenTopBar(context.uiText(appLanguage, "b2_business_title"), onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ContactPage, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(context.uiText(appLanguage, "b2_business_subtitle"), fontWeight = FontWeight.Bold)
                    }
                    Text(
                        context.uiText(appLanguage, "b2_business_desc"),
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
                    Icon(Icons.Default.DocumentScanner, null); Spacer(Modifier.width(5.dp)); Text(context.uiText(appLanguage, "b2_business_camera"))
                }
                OutlinedButton(onClick = { gallery.launch("image/*") }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(5.dp)); Text(context.uiText(appLanguage, "b2_business_pick"))
                }
            }

            preview?.let { image ->
                Card(Modifier.fillMaxWidth().height(230.dp)) {
                    Image(image.asImageBitmap(), context.uiText(appLanguage, "b2_business_preview"), Modifier.fillMaxSize().padding(6.dp), contentScale = ContentScale.Fit)
                }
            }

            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(context.uiText(appLanguage, "b2_business_processing"), style = MaterialTheme.typography.bodySmall)
            }

            if (sourceFile != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(context.uiText(appLanguage, "b2_business_contact_data"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    AssistChip(onClick = {}, label = { Text(context.uiText(appLanguage, "b2_business_conf", parsed.confidence)) })
                }

                OutlinedTextField(parsed.name, { value -> updateParsed { it.copy(name = value) } }, label = { Text(context.uiText(appLanguage, "b2_business_name")) }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
                OutlinedTextField(parsed.company, { value -> updateParsed { it.copy(company = value) } }, label = { Text(context.uiText(appLanguage, "b2_business_company")) }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
                OutlinedTextField(parsed.phone, { value -> updateParsed { it.copy(phone = value) } }, label = { Text(context.uiText(appLanguage, "b2_business_phone")) }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
                OutlinedTextField(parsed.email, { value -> updateParsed { it.copy(email = value) } }, label = { Text(context.uiText(appLanguage, "b2_business_email")) }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
                OutlinedTextField(parsed.website, { value -> updateParsed { it.copy(website = value) } }, label = { Text(context.uiText(appLanguage, "b2_business_website")) }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)

                Button(onClick = ::addToContacts, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(6.dp)); Text(context.uiText(appLanguage, "b2_business_add_contact"))
                }
                Text(
                    context.uiText(appLanguage, "b2_business_manual_save"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f)
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = ::copyData, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(4.dp)); Text(context.uiText(appLanguage, "b2_business_copy"))
                    }
                    OutlinedButton(onClick = ::shareData, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Share, null); Spacer(Modifier.width(4.dp)); Text(context.uiText(appLanguage, "b2_business_share"))
                    }
                }
                OutlinedButton(onClick = ::rerunOcr, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text(context.uiText(appLanguage, "b2_business_rerun"))
                }
                TextButton(onClick = { showRaw = !showRaw }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.TextSnippet, null); Spacer(Modifier.width(5.dp)); Text(if (showRaw) context.uiText(appLanguage, "b2_business_hide_text") else context.uiText(appLanguage, "b2_business_show_text"))
                }
                if (showRaw && parsed.rawText.isNotBlank()) {
                    Card(Modifier.fillMaxWidth()) {
                        Text(parsed.rawText, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Text(
                if (OcrProcessor.isModelReady(context, language))
                    context.uiText(appLanguage, "b2_business_model_ready")
                else
                    context.uiText(appLanguage, "b2_business_model_download"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .60f)
            )
        }
    }
}
