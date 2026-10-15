package com.abfilepro.app.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.abfilepro.app.core.FileUtils
import com.abfilepro.app.core.QrGenerator
import com.abfilepro.app.core.QrHistoryItem
import com.abfilepro.app.core.QrHistoryKind
import com.abfilepro.app.core.QrHistoryStore
import com.abfilepro.app.ui.components.ScreenTopBar
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.i18n.uiText
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

private class BarcodeAnalyzer(
    private val scanner: BarcodeScanner,
    private val onValue: (String) -> Unit
) : ImageAnalysis.Analyzer {
    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { codes ->
                codes.firstOrNull()?.rawValue?.takeIf { it.isNotBlank() }?.let(onValue)
            }
            .addOnCompleteListener { imageProxy.close() }
    }
}

private enum class QrType { TEXT, URL, PHONE, EMAIL, WIFI, CONTACT, LOCATION }

@Composable
fun BarcodeScreen(readerOnly: Boolean = false, onBack: () -> Unit) {
    val context = LocalContext.current
    val appLanguage = LocalAppLanguage.current
    var tab by remember { mutableIntStateOf(0) }
    var historyRevision by remember { mutableIntStateOf(0) }
    var generatorPrefill by remember { mutableStateOf<String?>(null) }

    if (readerOnly) {
        Scaffold(topBar = { ScreenTopBar(context.uiText(appLanguage, "b2_qr_tab_scan"), onBack) }) { padding ->
            BarcodeReaderContent(
                modifier = Modifier.fillMaxSize().padding(padding),
                onHistoryChanged = { historyRevision++ }
            )
        }
        return
    }

    Scaffold(topBar = { ScreenTopBar(context.uiText(appLanguage, "b2_qr_title"), onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(context.uiText(appLanguage, "b2_qr_tab_scan")) }, icon = { Icon(Icons.Default.QrCodeScanner, null) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(context.uiText(appLanguage, "b2_qr_tab_create")) }, icon = { Icon(Icons.Default.QrCode2, null) })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(context.uiText(appLanguage, "b2_qr_tab_history")) }, icon = { Icon(Icons.Default.History, null) })
            }
            when (tab) {
                0 -> BarcodeReaderContent(
                    modifier = Modifier.fillMaxSize(),
                    onHistoryChanged = { historyRevision++ }
                )
                1 -> QrGeneratorContent(
                    modifier = Modifier.fillMaxSize(),
                    prefillPayload = generatorPrefill,
                    onPrefillConsumed = { generatorPrefill = null },
                    onHistoryChanged = { historyRevision++ }
                )
                else -> QrHistoryContent(
                    modifier = Modifier.fillMaxSize(),
                    revision = historyRevision,
                    onGenerateAgain = { value ->
                        generatorPrefill = value
                        tab = 1
                    }
                )
            }
        }
    }
}

@Composable
private fun BarcodeReaderContent(
    modifier: Modifier = Modifier,
    onHistoryChanged: () -> Unit
) {
    val context = LocalContext.current
    val appLanguage = LocalAppLanguage.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var result by remember { mutableStateOf("") }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (!granted) Toast.makeText(context, context.uiText(appLanguage, "b2_qr_permission_error"), Toast.LENGTH_LONG).show()
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permission.launch(Manifest.permission.CAMERA)
    }

    Column(modifier.padding(16.dp)) {
        if (hasPermission) {
            BarcodeCamera(
                modifier = Modifier.fillMaxWidth().weight(1f),
                onValue = { value ->
                    if (value != result) {
                        result = value
                        QrHistoryStore.add(context, value, QrHistoryKind.SCANNED)
                        onHistoryChanged()
                    }
                }
            )
        } else {
            Card(Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.size(90.dp))
                    Spacer(Modifier.height(18.dp))
                    Text(context.uiText(appLanguage, "b2_qr_permission_desc"))
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text(context.uiText(appLanguage, "b2_qr_permission_button")) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(context.uiText(appLanguage, "b2_qr_scan_result"), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(result.ifBlank { context.uiText(appLanguage, "b2_qr_scan_hint") })
                if (result.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { copyText(context, result, "Barcode", context.uiText(appLanguage, "b2_qr_copied")) }) {
                            Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text(context.uiText(appLanguage, "b2_qr_copy"))
                        }
                        Button(onClick = { shareText(context, result, context.uiText(appLanguage, "b2_qr_share_code")) }) {
                            Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text(context.uiText(appLanguage, "b2_qr_share"))
                        }
                        if (result.startsWith("https://", ignoreCase = true) || result.startsWith("http://", ignoreCase = true)) {
                            OutlinedButton(onClick = {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result))) }
                            }) {
                                Icon(Icons.Default.OpenInNew, null); Spacer(Modifier.width(6.dp)); Text("فتح")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QrGeneratorContent(
    modifier: Modifier = Modifier,
    prefillPayload: String?,
    onPrefillConsumed: () -> Unit,
    onHistoryChanged: () -> Unit
) {
    val context = LocalContext.current
    val appLanguage = LocalAppLanguage.current
    var type by remember { mutableStateOf(QrType.TEXT) }
    var value by remember { mutableStateOf("") }
    var extra1 by remember { mutableStateOf("") }
    var extra2 by remember { mutableStateOf("") }
    var extra3 by remember { mutableStateOf("") }
    var wifiWpa by remember { mutableStateOf(true) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var payload by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        onDispose { bitmap?.let { if (!it.isRecycled) it.recycle() } }
    }

    LaunchedEffect(prefillPayload) {
        val content = prefillPayload?.trim().orEmpty()
        if (content.isNotBlank()) {
            type = QrType.TEXT
            value = content
            extra1 = ""; extra2 = ""; extra3 = ""
            runCatching { QrGenerator.create(content) }
                .onSuccess { newBitmap ->
                    bitmap?.let { if (!it.isRecycled) it.recycle() }
                    bitmap = newBitmap
                    payload = content
                    QrHistoryStore.add(context, content, QrHistoryKind.GENERATED)
                    onHistoryChanged()
                }
                .onFailure { Toast.makeText(context, context.uiText(appLanguage, "b2_qr_error_create"), Toast.LENGTH_LONG).show() }
            onPrefillConsumed()
        }
    }

    fun buildPayload(): String = when (type) {
        QrType.TEXT -> value.trim()
        QrType.URL -> value.trim().let { if (it.isBlank() || it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
        QrType.PHONE -> value.trim().let { if (it.isBlank()) "" else "tel:$it" }
        QrType.EMAIL -> {
            val email = value.trim()
            if (email.isBlank()) "" else {
                val subject = enc(extra1.trim())
                val body = enc(extra2.trim())
                buildString {
                    append("mailto:").append(email)
                    val parts = mutableListOf<String>()
                    if (subject.isNotBlank()) parts += "subject=$subject"
                    if (body.isNotBlank()) parts += "body=$body"
                    if (parts.isNotEmpty()) append('?').append(parts.joinToString("&"))
                }
            }
        }
        QrType.WIFI -> {
            val ssid = escapeWifi(value.trim())
            val pass = escapeWifi(extra1)
            if (ssid.isBlank()) "" else "WIFI:T:${if (wifiWpa) "WPA" else "nopass"};S:$ssid;P:$pass;;"
        }
        QrType.CONTACT -> {
            val name = value.trim()
            val phone = extra1.trim()
            val email = extra2.trim()
            val org = extra3.trim()
            if (name.isBlank() && phone.isBlank() && email.isBlank()) "" else buildString {
                append("MECARD:")
                if (name.isNotBlank()) append("N:").append(name).append(';')
                if (phone.isNotBlank()) append("TEL:").append(phone).append(';')
                if (email.isNotBlank()) append("EMAIL:").append(email).append(';')
                if (org.isNotBlank()) append("ORG:").append(org).append(';')
                append(';')
            }
        }
        QrType.LOCATION -> {
            val lat = value.trim(); val lon = extra1.trim(); val label = extra2.trim()
            if (lat.isBlank() || lon.isBlank()) "" else "geo:$lat,$lon${if (label.isNotBlank()) "?q=${enc("$lat,$lon ($label)")}" else ""}"
        }
    }

    fun regenerate() {
        val content = buildPayload()
        if (content.isBlank()) {
            Toast.makeText(context, context.uiText(appLanguage, "b2_qr_need_data"), Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { QrGenerator.create(content) }
            .onSuccess { newBitmap ->
                bitmap?.let { if (!it.isRecycled) it.recycle() }
                bitmap = newBitmap
                payload = content
                QrHistoryStore.add(context, content, QrHistoryKind.GENERATED)
                onHistoryChanged()
            }
            .onFailure { Toast.makeText(context, context.uiText(appLanguage, "b2_qr_error_create"), Toast.LENGTH_LONG).show() }
    }

    Column(modifier.padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QrType.entries.forEach { item ->
                FilterChip(
                    selected = type == item,
                    onClick = {
                        type = item
                        value = ""; extra1 = ""; extra2 = ""; extra3 = ""
                        bitmap?.let { if (!it.isRecycled) it.recycle() }
                        bitmap = null; payload = ""
                    },
                    label = { Text(context.uiText(appLanguage, when (item) {
                        QrType.TEXT -> "b2_qr_type_text"
                        QrType.URL -> "b2_qr_type_url"
                        QrType.PHONE -> "b2_qr_type_phone"
                        QrType.EMAIL -> "b2_qr_type_email"
                        QrType.WIFI -> "b2_qr_type_wifi"
                        QrType.CONTACT -> "b2_qr_type_contact"
                        QrType.LOCATION -> "b2_qr_type_location"
                    })) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        when (type) {
            QrType.TEXT -> QrField(value, { value = it }, context.uiText(appLanguage, "b2_qr_field_text"), singleLine = false)
            QrType.URL -> QrField(value, { value = it }, context.uiText(appLanguage, "b2_qr_field_url"), "example.com")
            QrType.PHONE -> QrField(value, { value = it }, context.uiText(appLanguage, "b2_qr_field_phone"))
            QrType.EMAIL -> {
                QrField(value, { value = it }, context.uiText(appLanguage, "b2_qr_field_email"))
                QrField(extra1, { extra1 = it }, context.uiText(appLanguage, "b2_qr_field_subject"))
                QrField(extra2, { extra2 = it }, context.uiText(appLanguage, "b2_qr_field_message"), singleLine = false)
            }
            QrType.WIFI -> {
                QrField(value, { value = it }, context.uiText(appLanguage, "b2_qr_field_wifi"))
                if (wifiWpa) QrField(extra1, { extra1 = it }, context.uiText(appLanguage, "b2_qr_field_password"))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = wifiWpa, onCheckedChange = { wifiWpa = it })
                    Spacer(Modifier.width(8.dp)); Text(if (wifiWpa) context.uiText(appLanguage, "b2_qr_wifi_secure") else context.uiText(appLanguage, "b2_qr_wifi_open"))
                }
            }
            QrType.CONTACT -> {
                QrField(value, { value = it }, context.uiText(appLanguage, "b2_qr_field_name"))
                QrField(extra1, { extra1 = it }, context.uiText(appLanguage, "b2_qr_field_contact_phone"))
                QrField(extra2, { extra2 = it }, "Email")
                QrField(extra3, { extra3 = it }, context.uiText(appLanguage, "b2_qr_field_company"))
            }
            QrType.LOCATION -> {
                QrField(value, { value = it }, "Latitude")
                QrField(extra1, { extra1 = it }, "Longitude")
                QrField(extra2, { extra2 = it }, context.uiText(appLanguage, "b2_qr_field_location"))
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(onClick = ::regenerate, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.QrCode2, null); Spacer(Modifier.width(8.dp)); Text(context.uiText(appLanguage, "b2_qr_tab_create"))
        }
        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth().weight(1f)) {
            if (bitmap == null) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.QrCode2, null, modifier = Modifier.size(82.dp))
                    Spacer(Modifier.height(8.dp)); Text(context.uiText(appLanguage, "b2_qr_qr_placeholder"))
                }
            } else {
                Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = "QR", modifier = Modifier.fillMaxWidth().weight(1f))
                    Spacer(Modifier.height(8.dp))
                    Text(payload, maxLines = 2, style = MaterialTheme.typography.bodySmall, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        if (bitmap != null) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copyText(context, payload, "QR", context.uiText(appLanguage, "b2_qr_copied")) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(5.dp)); Text(context.uiText(appLanguage, "b2_qr_copy"))
                }
                OutlinedButton(onClick = {
                    runCatching { saveQr(context, bitmap!!) }
                        .onSuccess { Toast.makeText(context, context.uiText(appLanguage, "b2_qr_saved", it.name), Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(context, context.uiText(appLanguage, "b2_qr_error_save"), Toast.LENGTH_LONG).show() }
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Save, null); Spacer(Modifier.width(5.dp)); Text(context.uiText(appLanguage, "b2_qr_save"))
                }
                Button(onClick = {
                    runCatching { saveQr(context, bitmap!!).also { FileUtils.shareFile(context, it) } }
                        .onFailure { Toast.makeText(context, context.uiText(appLanguage, "b2_qr_error_share"), Toast.LENGTH_LONG).show() }
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Share, null); Spacer(Modifier.width(5.dp)); Text(context.uiText(appLanguage, "b2_qr_share"))
                }
            }
        }
    }
}

@Composable
private fun QrHistoryContent(
    modifier: Modifier = Modifier,
    revision: Int,
    onGenerateAgain: (String) -> Unit
) {
    val context = LocalContext.current
    val appLanguage = LocalAppLanguage.current
    var history by remember { mutableStateOf(QrHistoryStore.list(context)) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(revision) {
        history = QrHistoryStore.list(context)
    }

    val visible = remember(history, favoritesOnly) {
        if (favoritesOnly) history.filter { it.favorite } else history
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(Icons.Default.DeleteSweep, null) },
            title = { Text(context.uiText(appLanguage, "b2_qr_clear_title")) },
            text = { Text(context.uiText(appLanguage, "b2_qr_clear_desc")) },
            confirmButton = {
                Button(onClick = {
                    history = QrHistoryStore.clearNonFavorites(context)
                    confirmClear = false
                }) { Text(context.uiText(appLanguage, "b2_qr_clear_nonfav")) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(context.uiText(appLanguage, "b2_qr_cancel")) } }
        )
    }

    Column(modifier.padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(context.uiText(appLanguage, "b2_qr_history_title"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(context.uiText(appLanguage, "b2_qr_history_count", history.size, history.count { it.favorite }), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { if (history.any { !it.favorite }) confirmClear = true }) {
                Icon(Icons.Default.DeleteSweep, null); Spacer(Modifier.width(5.dp)); Text(context.uiText(appLanguage, "b2_qr_clean"))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !favoritesOnly,
                onClick = { favoritesOnly = false },
                label = { Text(context.uiText(appLanguage, "b2_qr_all")) },
                leadingIcon = { Icon(Icons.Default.History, null) }
            )
            FilterChip(
                selected = favoritesOnly,
                onClick = { favoritesOnly = true },
                label = { Text(context.uiText(appLanguage, "b2_qr_favorites")) },
                leadingIcon = { Icon(Icons.Default.Star, null) }
            )
        }
        Spacer(Modifier.height(10.dp))

        if (visible.isEmpty()) {
            Card(Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(if (favoritesOnly) Icons.Default.StarBorder else Icons.Default.History, null, modifier = Modifier.size(76.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(if (favoritesOnly) context.uiText(appLanguage, "b2_qr_empty_fav") else context.uiText(appLanguage, "b2_qr_empty_history"))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                items(visible, key = { it.id }) { item ->
                    QrHistoryCard(
                        item = item,
                        onFavorite = { history = QrHistoryStore.toggleFavorite(context, item.id) },
                        onDelete = { history = QrHistoryStore.delete(context, item.id) },
                        onGenerateAgain = { onGenerateAgain(item.value) },
                        onCopy = { copyText(context, item.value, "QR history", context.uiText(appLanguage, "b2_qr_copied")) },
                        onShare = { shareText(context, item.value, context.uiText(appLanguage, "b2_qr_share_code")) }
                    )
                }
            }
        }
    }
}

@Composable
private fun QrHistoryCard(
    item: QrHistoryItem,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
    onGenerateAgain: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit
) {
    val context = LocalContext.current
    val appLanguage = LocalAppLanguage.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (item.kind == QrHistoryKind.SCANNED) Icons.Default.QrCodeScanner else Icons.Default.QrCode2,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(context.uiText(appLanguage, if (item.kind == QrHistoryKind.SCANNED) "b2_qr_kind_scanned" else "b2_qr_kind_generated"), fontWeight = FontWeight.SemiBold)
                    Text(formatHistoryTime(item.createdAt), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onFavorite) {
                    Icon(if (item.favorite) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = context.uiText(appLanguage, "b2_qr_favorites"))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = context.uiText(appLanguage, "b2_qr_delete"))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(item.value, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(9.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                OutlinedButton(onClick = onCopy) {
                    Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(5.dp)); Text(context.uiText(appLanguage, "b2_qr_copy"))
                }
                OutlinedButton(onClick = onShare) {
                    Icon(Icons.Default.Share, null); Spacer(Modifier.width(5.dp)); Text(context.uiText(appLanguage, "b2_qr_share"))
                }
                Button(onClick = onGenerateAgain) {
                    Icon(Icons.Default.QrCode2, null); Spacer(Modifier.width(5.dp)); Text(context.uiText(appLanguage, "b2_qr_tab_create"))
                }
            }
        }
    }
}

@Composable
private fun QrField(value: String, onValueChange: (String) -> Unit, label: String, placeholder: String = "", singleLine: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp),
        label = { Text(label) },
        placeholder = if (placeholder.isBlank()) null else ({ Text(placeholder) }),
        singleLine = singleLine
    )
}

private fun saveQr(context: Context, bitmap: Bitmap): File {
    val file = File(FileUtils.outputDir(context, "qr"), "QR_${System.currentTimeMillis()}.png")
    FileOutputStream(file).use { out ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "qr_save_failed" }
    }
    return file
}

private fun copyText(context: Context, text: String, label: String, copiedMessage: String) {
    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cb.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
}

private fun shareText(context: Context, text: String, chooser: String) {
    val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    context.startActivity(Intent.createChooser(intent, chooser))
}

private fun formatHistoryTime(time: Long): String =
    SimpleDateFormat("dd/MM/yyyy • HH:mm", Locale.getDefault()).format(Date(time))

private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
private fun escapeWifi(value: String): String = value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:")

@Composable
internal fun BarcodeCamera(modifier: Modifier = Modifier, onValue: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanner = remember { BarcodeScanning.getClient() }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val latestOnValue by rememberUpdatedState(onValue)

    DisposableEffect(Unit) {
        onDispose {
            scanner.close()
            executor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).also { previewView ->
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor, BarcodeAnalyzer(scanner) { value -> latestOnValue(value) })
                    runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }
                }, ContextCompat.getMainExecutor(ctx))
            }
        }
    )
}
