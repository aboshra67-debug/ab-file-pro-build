package com.abfilepro.app.ui.screens

import android.app.Activity
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
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
import com.abfilepro.app.core.CardPageLayout
import com.abfilepro.app.core.CardScanProcessor
import com.abfilepro.app.core.findActivity
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.i18n.uiText
import com.abfilepro.app.ui.components.ScreenTopBar
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class CardSide { FRONT, BACK }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardScanScreen(onBack: () -> Unit, onOpenPdf: (File) -> Unit) {
    val context = LocalContext.current
    val appLanguage = LocalAppLanguage.current
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()

    var frontFile by remember { mutableStateOf<File?>(null) }
    var backFile by remember { mutableStateOf<File?>(null) }
    var frontPreview by remember { mutableStateOf<Bitmap?>(null) }
    var backPreview by remember { mutableStateOf<Bitmap?>(null) }
    var targetSide by remember { mutableStateOf(CardSide.FRONT) }
    var layout by remember { mutableStateOf(CardPageLayout.HORIZONTAL) }
    var customName by remember { mutableStateOf(context.uiText(appLanguage, "b2_card_default_name")) }
    var busy by remember { mutableStateOf(false) }

    val scannerOptions = remember {
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(1)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
    }
    val scanner = remember { GmsDocumentScanning.getClient(scannerOptions) }

    fun recyclePreview(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
    }

    fun setSide(side: CardSide, file: File, preview: Bitmap) {
        if (side == CardSide.FRONT) {
            recyclePreview(frontPreview)
            frontFile?.let { old -> if (old != file) runCatching { old.parentFile?.deleteRecursively() } }
            frontFile = file
            frontPreview = preview
        } else {
            recyclePreview(backPreview)
            backFile?.let { old -> if (old != file) runCatching { old.parentFile?.deleteRecursively() } }
            backFile = file
            backPreview = preview
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result: ActivityResult ->
        busy = false
        if (result.resultCode == Activity.RESULT_OK) {
            val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val uri = scan?.pages?.firstOrNull()?.imageUri
            if (uri != null) {
                val side = targetSide
                scope.launch {
                    busy = true
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val file = CardScanProcessor.cacheSide(context, uri, if (side == CardSide.FRONT) "front" else "back")
                            val preview = CardScanProcessor.loadPreview(file)
                            file to preview
                        }
                    }.onSuccess { (file, preview) ->
                        setSide(side, file, preview)
                    }.onFailure {
                        Toast.makeText(context, context.uiText(appLanguage, "b2_card_error_prepare"), Toast.LENGTH_LONG).show()
                    }
                    busy = false
                }
            }
        }
    }

    fun scanSide(side: CardSide) {
        if (activity == null) {
            Toast.makeText(context, context.uiText(appLanguage, "b2_card_error_camera"), Toast.LENGTH_SHORT).show()
            return
        }
        targetSide = side
        busy = true
        scanner.getStartScanIntent(activity)
            .addOnSuccessListener { sender -> launcher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener {
                busy = false
                Toast.makeText(context, context.uiText(appLanguage, "b2_card_error_scanner"), Toast.LENGTH_LONG).show()
            }
    }

    fun clearSide(side: CardSide) {
        if (side == CardSide.FRONT) {
            recyclePreview(frontPreview); frontPreview = null
            frontFile?.let { runCatching { it.parentFile?.deleteRecursively() } }; frontFile = null
        } else {
            recyclePreview(backPreview); backPreview = null
            backFile?.let { runCatching { it.parentFile?.deleteRecursively() } }; backFile = null
        }
    }

    fun saveCard() {
        val front = frontFile
        val back = backFile
        if (front == null || back == null) {
            Toast.makeText(context, context.uiText(appLanguage, "b2_card_need_both"), Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            busy = true
            runCatching {
                withContext(Dispatchers.IO) { CardScanProcessor.saveCombinedPdf(context, front, back, layout, customName) }
            }.onSuccess { file ->
                Toast.makeText(context, context.uiText(appLanguage, "b2_card_saved", file.name), Toast.LENGTH_SHORT).show()
                onOpenPdf(file)
            }.onFailure {
                Toast.makeText(context, context.uiText(appLanguage, "b2_card_error_save"), Toast.LENGTH_LONG).show()
            }
            busy = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recyclePreview(frontPreview)
            recyclePreview(backPreview)
            CardScanProcessor.cleanup(frontFile, backFile)
        }
    }

    Scaffold(topBar = { ScreenTopBar(context.uiText(appLanguage, "b2_card_title"), onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Badge, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(context.uiText(appLanguage, "b2_card_subtitle"), fontWeight = FontWeight.Bold)
                    }
                    Text(
                        context.uiText(appLanguage, "b2_card_desc"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f)
                    )
                }
            }

            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CardSidePreview(
                    title = context.uiText(appLanguage, "b2_card_front"),
                    preview = frontPreview,
                    modifier = Modifier.weight(1f),
                    onScan = { scanSide(CardSide.FRONT) },
                    onClear = { clearSide(CardSide.FRONT) },
                    busy = busy
                )
                CardSidePreview(
                    title = context.uiText(appLanguage, "b2_card_back"),
                    preview = backPreview,
                    modifier = Modifier.weight(1f),
                    onScan = { scanSide(CardSide.BACK) },
                    onClear = { clearSide(CardSide.BACK) },
                    busy = busy
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(context.uiText(appLanguage, "b2_card_layout"), fontWeight = FontWeight.SemiBold)
                FilterChip(
                    selected = layout == CardPageLayout.HORIZONTAL,
                    onClick = { layout = CardPageLayout.HORIZONTAL },
                    label = { Text(context.uiText(appLanguage, "b2_card_horizontal")) },
                    leadingIcon = { Icon(Icons.Default.ViewWeek, null) }
                )
                FilterChip(
                    selected = layout == CardPageLayout.VERTICAL,
                    onClick = { layout = CardPageLayout.VERTICAL },
                    label = { Text(context.uiText(appLanguage, "b2_card_vertical")) },
                    leadingIcon = { Icon(Icons.Default.ViewAgenda, null) }
                )
            }

            OutlinedTextField(
                value = customName,
                onValueChange = { customName = it },
                label = { Text(context.uiText(appLanguage, "b2_card_filename")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val f = frontFile; val b = backFile
                        val fp = frontPreview; val bp = backPreview
                        frontFile = b; backFile = f
                        frontPreview = bp; backPreview = fp
                    },
                    enabled = !busy && frontFile != null && backFile != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.SwapHoriz, null); Spacer(Modifier.width(6.dp)); Text(context.uiText(appLanguage, "b2_card_swap"))
                }
                Button(
                    onClick = { saveCard() },
                    enabled = !busy && frontFile != null && backFile != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(6.dp)); Text(context.uiText(appLanguage, "b2_card_save_pdf"))
                }
            }
        }
    }
}

@Composable
private fun CardSidePreview(
    title: String,
    preview: Bitmap?,
    modifier: Modifier,
    onScan: () -> Unit,
    onClear: () -> Unit,
    busy: Boolean
) {
    val context = LocalContext.current
    val appLanguage = LocalAppLanguage.current
    Card(modifier.fillMaxHeight()) {
        Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (preview == null) {
                Surface(Modifier.fillMaxWidth().weight(1f), tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Default.CreditCard, null, modifier = Modifier.size(52.dp))
                        Text(context.uiText(appLanguage, "b2_card_not_captured"), style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = title,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentScale = ContentScale.Fit
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onScan, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(if (preview == null) Icons.Default.CameraAlt else Icons.Default.Refresh, null)
                Spacer(Modifier.width(5.dp))
                Text(if (preview == null) context.uiText(appLanguage, "b2_card_capture") else context.uiText(appLanguage, "b2_card_retake"))
            }
            if (preview != null) {
                TextButton(onClick = onClear, enabled = !busy) { Icon(Icons.Default.DeleteOutline, null); Spacer(Modifier.width(4.dp)); Text(context.uiText(appLanguage, "b2_card_delete")) }
            }
        }
    }
}
