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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abfilepro.app.core.FileUtils
import com.abfilepro.app.core.OperationHistoryStore
import com.abfilepro.app.core.SmartFormProcessor
import com.abfilepro.app.core.SecureValueStore
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.i18n.uiText
import com.abfilepro.app.ui.i18n.localizedOperationFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartFormScreen(onBack: () -> Unit, onOpenPdf: (File) -> Unit) {
    val context = LocalContext.current
    val appLanguage = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var template by remember { mutableStateOf<SmartFormProcessor.Template?>(null) }
    var filled by remember { mutableStateOf<SmartFormProcessor.FilledResult?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var templatesVersion by remember { mutableIntStateOf(0) }
    var rememberValues by remember { mutableStateOf(false) }
    var signatureText by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf("") }
    var historyId by remember { mutableStateOf<String?>(null) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    val values = remember { mutableStateMapOf<Int, String>() }
    val savedTemplates = remember(templatesVersion) { SmartFormProcessor.listTemplates(context, 5) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { selected ->
        uri = selected
        template = null
        filled = null
        historyId = null
        values.clear()
        signatureText = ""
        dateText = ""
    }

    LaunchedEffect(template?.id, rememberValues) {
        if (!rememberValues) return@LaunchedEffect
        template?.fields?.forEach { field ->
            val saved = SecureValueStore.get(context, formValueKey(field.hint), legacyPrefsName = "ab_smart_form_values").orEmpty()
            if (saved.isNotBlank() && values[field.id].isNullOrBlank()) values[field.id] = saved
        }
    }

    LaunchedEffect(template?.sourceImage?.absolutePath, filled?.imageFile?.absolutePath) {
        preview?.let { if (!it.isRecycled) it.recycle() }
        preview = null
        val file = filled?.imageFile ?: template?.sourceImage ?: return@LaunchedEffect
        preview = withContext(Dispatchers.IO) { runCatching { FileUtils.loadImagePreview(file, 1200) }.getOrNull() }
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
                title = { Text("AB Smart Form", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack, enabled = !busy) { Icon(Icons.Default.ArrowBack, context.uiText(appLanguage, "b2_form_back")) } }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(context.uiText(appLanguage, "b2_form_subtitle"), fontWeight = FontWeight.ExtraBold)
                    Text(context.uiText(appLanguage, "b2_form_desc"), style = MaterialTheme.typography.bodySmall)
                    Text(context.uiText(appLanguage, "b2_form_privacy"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Button(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.fillMaxWidth(), enabled = !busy
            ) { Icon(Icons.Default.DocumentScanner, null); Spacer(Modifier.width(8.dp)); Text(context.uiText(appLanguage, "b2_form_pick")) }

            if (uri != null && template == null) {
                Button(
                    onClick = {
                        busy = true
                        activeJob = scope.launch {
                            try {
                                val analyzed = SmartFormProcessor.analyze(context, uri!!)
                                template = analyzed
                                Toast.makeText(context, context.uiText(appLanguage, "v15_form_detected", analyzed.fields.size), Toast.LENGTH_SHORT).show()
                            } catch (error: Throwable) {
                                Toast.makeText(context, context.localizedOperationFailure(appLanguage, error, "b2_form_error_analyze"), Toast.LENGTH_LONG).show()
                            } finally { busy = false; activeJob = null }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy
                ) { Text(context.uiText(appLanguage, "b2_form_detect")) }
            }

            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(context.uiText(appLanguage, "v15_processing_progress"), style = MaterialTheme.typography.labelSmall)
                OutlinedButton(onClick = { activeJob?.cancel() }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Close, null); Spacer(Modifier.width(6.dp)); Text(context.uiText(appLanguage, "v15_cancel_operation")) }
            }

            if (template == null && savedTemplates.isNotEmpty()) {
                Text(context.uiText(appLanguage, "b2_form_saved_templates"), fontWeight = FontWeight.Bold)
                savedTemplates.forEachIndexed { index, saved ->
                    OutlinedButton(
                        onClick = { template = saved; values.clear(); filled = null; historyId = null },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(context.uiText(appLanguage, "b2_form_template_no", index + 1, saved.fields.size)) }
                }
            }

            preview?.let { bitmap ->
                Card(shape = RoundedCornerShape(16.dp)) {
                    Image(bitmap.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 300.dp), contentScale = ContentScale.Fit)
                }
            }

            template?.let { current ->
                val templateIsPersisted = runCatching { FileUtils.isInsideRoot(context, current.metaFile) }.getOrDefault(false)
                Text(context.uiText(appLanguage, "b2_form_detected", current.fields.size), fontWeight = FontWeight.Bold)
                if (!templateIsPersisted) {
                    OutlinedButton(
                        onClick = {
                            busy = true
                            activeJob = scope.launch {
                                try {
                                    template = SmartFormProcessor.persistTemplate(context, current)
                                    templatesVersion++
                                    Toast.makeText(context, context.uiText(appLanguage, "v15_form_template_saved"), Toast.LENGTH_SHORT).show()
                                } catch (error: Throwable) {
                                    Toast.makeText(context, context.localizedOperationFailure(appLanguage, error, "b2_form_error_create"), Toast.LENGTH_LONG).show()
                                } finally { busy = false; activeJob = null }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(6.dp)); Text(context.uiText(appLanguage, "v15_form_save_template")) }
                }
                current.fields.forEach { field ->
                    OutlinedTextField(
                        value = values[field.id].orEmpty(),
                        onValueChange = { values[field.id] = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (SmartFormProcessor.isGenericFieldHint(field.hint)) context.uiText(appLanguage, "b2_form_field", field.id) else field.hint) },
                        enabled = !busy
                    )
                }

                OutlinedTextField(
                    value = signatureText,
                    onValueChange = { signatureText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(context.uiText(appLanguage, "b2_form_signature")) },
                    leadingIcon = { Icon(Icons.Default.Draw, null) },
                    enabled = !busy
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = dateText,
                        onValueChange = { dateText = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(context.uiText(appLanguage, "b2_form_date")) },
                        enabled = !busy
                    )
                    Button(
                        onClick = {
                            dateText = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                            current.fields.filter { SmartFormProcessor.isDateHint(it.hint) }
                                .forEach { values[it.id] = dateText }
                        },
                        enabled = !busy,
                        modifier = Modifier.padding(top = 8.dp)
                    ) { Text(context.uiText(appLanguage, "b2_form_today")) }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(context.uiText(appLanguage, "b2_form_remember"), fontWeight = FontWeight.Bold)
                        Text(context.uiText(appLanguage, "b2_form_consent"), style = MaterialTheme.typography.labelSmall)
                        if (rememberValues) Text(context.uiText(appLanguage, "v15_secure_values"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = rememberValues, onCheckedChange = { rememberValues = it }, enabled = !busy)
                }

                Button(
                    onClick = {
                        busy = true
                        activeJob = scope.launch {
                            try {
                                val output = SmartFormProcessor.fill(context, current, values, signatureText, dateText)
                                filled = output
                                val history = OperationHistoryStore.record(context, "AB Smart Form", context.uiText(appLanguage, "b2_form_filled"), listOf(output.imageFile, output.pdfFile))
                                historyId = history.id
                                if (rememberValues) {
                                    current.fields.forEach { field ->
                                        val key = formValueKey(field.hint)
                                        val value = values[field.id].orEmpty().trim()
                                        if (value.isNotBlank()) SecureValueStore.put(context, key, value)
                                        else SecureValueStore.remove(context, key)
                                    }
                                }
                                Toast.makeText(context, context.uiText(appLanguage, "b2_form_created"), Toast.LENGTH_SHORT).show()
                            } catch (error: Throwable) {
                                Toast.makeText(context, context.localizedOperationFailure(appLanguage, error, "b2_form_error_create"), Toast.LENGTH_LONG).show()
                            } finally { busy = false; activeJob = null }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy
                ) { Icon(Icons.Default.Check, null); Spacer(Modifier.width(6.dp)); Text(context.uiText(appLanguage, "b2_form_create")) }
            }

            filled?.let { output ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onOpenPdf(output.pdfFile) }, modifier = Modifier.weight(1f)) { Text(context.uiText(appLanguage, "b2_form_open_pdf")) }
                    OutlinedButton(onClick = { FileUtils.shareFileToWhatsApp(context, output.pdfFile) }, modifier = Modifier.weight(1f)) { Text(context.uiText(appLanguage, "b2_form_whatsapp")) }
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
                                            filled = null; historyId = null
                                            Toast.makeText(context, context.uiText(appLanguage, if (partial) "p12_undo_partial" else "b2_form_undone"), Toast.LENGTH_SHORT).show()
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
                    ) { Icon(Icons.Default.Undo, null); Spacer(Modifier.width(6.dp)); Text(context.uiText(appLanguage, "b2_form_undo")) }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

private fun formValueKey(hint: String): String = "field_" + hint
    .lowercase()
    .replace(Regex("[^\\p{L}\\p{N}]+"), "_")
    .take(50)
