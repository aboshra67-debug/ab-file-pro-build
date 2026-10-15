package com.abfilepro.app.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.abfilepro.app.core.ConversionDraftManager
import com.abfilepro.app.core.FileUtils
import com.abfilepro.app.core.PdfTools
import com.abfilepro.app.ui.components.ScreenTopBar
import com.abfilepro.app.ui.i18n.AppLanguage
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.i18n.uiText
import com.abfilepro.app.ui.i18n.localizedOperationFailure
import com.abfilepro.app.ui.components.ToolCard
import com.abfilepro.app.ui.theme.AccentRed
import com.abfilepro.app.ui.theme.RoyalBlue
import com.abfilepro.app.ui.theme.Teal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private enum class TextDialogMode { DELETE_PAGES, ROTATE_PAGES, EXTRACT_PAGES, REORDER_PAGES, PROTECT, UNLOCK, PAGE_NUMBERS }

private fun parsePageNumbers(text: String): List<Int> = text
    .replace("\u060C", ",")
    .split(",", " ", ";")
    .mapNotNull { it.trim().toIntOrNull() }

private fun savedSignatureDir(context: Context): File = File(context.filesDir, "saved_signatures").apply { mkdirs() }

private fun savedSignatureFiles(context: Context): List<File> = savedSignatureDir(context)
    .listFiles { file -> file.isFile && file.extension.equals("png", ignoreCase = true) }
    ?.sortedBy { file -> file.nameWithoutExtension.removePrefix("signature_").toIntOrNull() ?: Int.MAX_VALUE }
    .orEmpty()

private fun savedSignatureLabel(context: Context, language: AppLanguage, file: File): String {
    val number = file.nameWithoutExtension.removePrefix("signature_").toIntOrNull()
    return if (number != null) context.uiText(language, "b2_pdftools_signature_number", number) else context.uiText(language, "b2_pdftools_signature_saved")
}

private fun saveSignatureLocally(context: Context, bitmap: Bitmap): File {
    val dir = savedSignatureDir(context)
    var number = 1
    var file = File(dir, "signature_$number.png")
    while (file.exists()) {
        number += 1
        file = File(dir, "signature_$number.png")
    }
    FileOutputStream(file).use { out ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "signature_save_failed" }
    }
    return file
}


@Composable
fun PdfToolsScreen(onBack: () -> Unit, onEditPdf: (Uri) -> Unit = {}) {
    val context = LocalContext.current
    val appLanguage = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var dialogMode by remember { mutableStateOf<TextDialogMode?>(null) }
    var pendingPdf by remember { mutableStateOf<Uri?>(null) }
    var dialogText by remember { mutableStateOf("") }
    var nextDialogMode by remember { mutableStateOf<TextDialogMode?>(null) }

    var signaturePdf by remember { mutableStateOf<Uri?>(null) }
    var signImagePdf by remember { mutableStateOf<Uri?>(null) }
    var signaturePageCount by remember { mutableIntStateOf(1) }
    var showSignaturePad by remember { mutableStateOf(false) }

    var reorderPdf by remember { mutableStateOf<Uri?>(null) }
    var reorderOrder by remember { mutableStateOf<List<Int>>(emptyList()) }
    var showReorder by remember { mutableStateOf(false) }

    var watermarkTextPdf by remember { mutableStateOf<Uri?>(null) }
    var watermarkText by remember { mutableStateOf("") }
    var watermarkTextOpacity by remember { mutableFloatStateOf(30f) }
    var watermarkTextPages by remember { mutableStateOf("") }
    var showWatermarkText by remember { mutableStateOf(false) }
    var watermarkImagePdf by remember { mutableStateOf<Uri?>(null) }
    var watermarkImageOpacity by remember { mutableFloatStateOf(30f) }
    var watermarkImagePages by remember { mutableStateOf("") }
    var showWatermarkImageOptions by remember { mutableStateOf(false) }
    var compressionPdf by remember { mutableStateOf<Uri?>(null) }
    var showCompressionOptions by remember { mutableStateOf(false) }

    var pendingDraft by remember { mutableStateOf<File?>(null) }
    var pendingBatchDrafts by remember { mutableStateOf<List<File>>(emptyList()) }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) { ConversionDraftManager.cleanup(context) }
    DisposableEffect(Unit) { onDispose { activeJob?.cancel() } }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    fun runPreviewJob(block: suspend () -> File) {
        if (busy || pendingDraft != null || pendingBatchDrafts.isNotEmpty()) return
        activeJob = scope.launch {
            busy = true
            try {
                val generated = block()
                pendingDraft = ConversionDraftManager.stage(context, generated)
            } catch (error: CancellationException) {
                toast(context.uiText(appLanguage, "v15_cancelled"))
            } catch (error: Throwable) {
                toast(context.localizedOperationFailure(appLanguage, error, "b2_pdftools_error_operation"))
            } finally {
                busy = false
                activeJob = null
            }
        }
    }

    fun runPreviewBatchJob(block: suspend () -> List<File>) {
        if (busy || pendingDraft != null || pendingBatchDrafts.isNotEmpty()) return
        activeJob = scope.launch {
            busy = true
            try {
                val generated = block()
                require(generated.isNotEmpty()) { context.uiText(appLanguage, "b2_pdftools_error_no_output") }
                val staged = mutableListOf<File>()
                try {
                    generated.forEach { staged += ConversionDraftManager.stage(context, it) }
                    pendingBatchDrafts = staged.toList()
                } catch (error: Throwable) {
                    ConversionDraftManager.discardAll(context, staged)
                    generated.filter { it.exists() }.forEach { it.delete() }
                    throw error
                }
            } catch (error: CancellationException) {
                toast(context.uiText(appLanguage, "v15_cancelled"))
            } catch (error: Throwable) {
                toast(context.localizedOperationFailure(appLanguage, error, "b2_pdftools_error_operation"))
            } finally {
                busy = false
                activeJob = null
            }
        }
    }

    val mergeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.size >= 2) runPreviewJob { PdfTools.mergePdfs(context, uris) }
        else if (uris.isNotEmpty()) toast(context.uiText(appLanguage, "b2_pdftools_need_two"))
    }
    val splitLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runPreviewBatchJob { PdfTools.splitPdf(context, uri) }
    }
    val compressLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            compressionPdf = uri
            showCompressionOptions = true
        }
    }
    val rotateLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runPreviewJob { PdfTools.rotateAllPages(context, uri, 90) }
    }
    val longImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runPreviewJob { PdfTools.pdfToLongImage(context, uri) }
    }

    val dialogPdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && nextDialogMode != null) {
            pendingPdf = uri
            dialogMode = nextDialogMode
            dialogText = ""
        }
        nextDialogMode = null
    }

    val signatureImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { imageUri ->
        val pdf = signImagePdf
        if (pdf != null && imageUri != null) {
            runPreviewJob { PdfTools.addSignatureImage(context, pdf, imageUri) }
        }
        signImagePdf = null
    }
    val signatureImagePdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            signImagePdf = uri
            signatureImageLauncher.launch(arrayOf("image/*"))
        }
    }

    val signatureDrawPdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                runCatching { PdfTools.getPageCount(context, uri) }
                    .onSuccess {
                        signaturePdf = uri
                        signaturePageCount = it.coerceAtLeast(1)
                        showSignaturePad = true
                    }
                    .onFailure { toast(context.uiText(appLanguage, "b2_pdftools_error_read")) }
                busy = false
            }
        }
    }

    val reorderPdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                runCatching { PdfTools.getPageCount(context, uri) }
                    .onSuccess { count ->
                        reorderPdf = uri
                        reorderOrder = (1..count).toList()
                        showReorder = true
                    }
                    .onFailure { toast(context.uiText(appLanguage, "b2_pdftools_error_pages")) }
                busy = false
            }
        }
    }

    val watermarkTextPdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            watermarkTextPdf = uri
            watermarkText = ""
            watermarkTextOpacity = 30f
            watermarkTextPages = ""
            showWatermarkText = true
        }
    }

    val watermarkImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { imageUri ->
        val pdf = watermarkImagePdf
        if (pdf != null && imageUri != null) {
            val pages = watermarkImagePages.trim().takeIf { it.isNotEmpty() }?.let(::parsePageNumbers)
            val opacity = watermarkImageOpacity.toInt()
            runPreviewJob { PdfTools.addImageWatermark(context, pdf, imageUri, opacity, .42f, pages) }
        }
        watermarkImagePdf = null
    }
    val watermarkImagePdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            watermarkImagePdf = uri
            watermarkImageOpacity = 30f
            watermarkImagePages = ""
            showWatermarkImageOptions = true
        }
    }

    val editorPdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onEditPdf(uri)
    }

    fun parsePages(text: String): List<Int> = parsePageNumbers(text)

    dialogMode?.let { mode ->
        val title = when (mode) {
            TextDialogMode.DELETE_PAGES -> context.uiText(appLanguage, "b2_pdftools_delete_pages")
            TextDialogMode.ROTATE_PAGES -> context.uiText(appLanguage, "b2_pdftools_rotate_selected")
            TextDialogMode.EXTRACT_PAGES -> context.uiText(appLanguage, "b2_pdftools_extract_pages")
            TextDialogMode.REORDER_PAGES -> context.uiText(appLanguage, "b2_pdftools_quick_reorder")
            TextDialogMode.PROTECT -> context.uiText(appLanguage, "b2_pdftools_protect")
            TextDialogMode.UNLOCK -> context.uiText(appLanguage, "b2_pdftools_unlock")
            TextDialogMode.PAGE_NUMBERS -> context.uiText(appLanguage, "b2_pdftools_page_numbers")
        }
        val hint = when (mode) {
            TextDialogMode.DELETE_PAGES, TextDialogMode.ROTATE_PAGES, TextDialogMode.EXTRACT_PAGES -> context.uiText(appLanguage, "b2_pdftools_example_pages")
            TextDialogMode.REORDER_PAGES -> context.uiText(appLanguage, "b2_pdftools_example_order")
            TextDialogMode.PROTECT, TextDialogMode.UNLOCK -> context.uiText(appLanguage, "b2_pdftools_password")
            TextDialogMode.PAGE_NUMBERS -> context.uiText(appLanguage, "b2_pdftools_start_number")
        }
        AlertDialog(
            onDismissRequest = { dialogMode = null; pendingPdf = null },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = dialogText,
                    onValueChange = { dialogText = it },
                    label = { Text(hint) },
                    singleLine = true,
                    visualTransformation = if (mode == TextDialogMode.PROTECT || mode == TextDialogMode.UNLOCK) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    val uri = pendingPdf ?: return@Button
                    val value = dialogText
                    dialogMode = null
                    pendingPdf = null
                    when (mode) {
                        TextDialogMode.DELETE_PAGES -> runPreviewJob { PdfTools.deletePages(context, uri, parsePages(value)) }
                        TextDialogMode.ROTATE_PAGES -> runPreviewJob { PdfTools.rotatePages(context, uri, parsePages(value), 90) }
                        TextDialogMode.EXTRACT_PAGES -> runPreviewJob { PdfTools.extractPages(context, uri, parsePages(value)) }
                        TextDialogMode.REORDER_PAGES -> runPreviewJob { PdfTools.reorderPages(context, uri, parsePages(value)) }
                        TextDialogMode.PROTECT -> runPreviewJob { PdfTools.protectPdf(context, uri, value) }
                        TextDialogMode.UNLOCK -> runPreviewJob { PdfTools.unlockPdf(context, uri, value) }
                        TextDialogMode.PAGE_NUMBERS -> runPreviewJob { PdfTools.addPageNumbers(context, uri, value.trim().toIntOrNull() ?: 1) }
                    }
                }) { Text(context.uiText(appLanguage, "b2_pdftools_execute")) }
            },
            dismissButton = { TextButton(onClick = { dialogMode = null; pendingPdf = null }) { Text(context.uiText(appLanguage, "b2_pdftools_cancel")) } }
        )
    }

    if (showCompressionOptions && compressionPdf != null) {
        AlertDialog(
            onDismissRequest = { showCompressionOptions = false; compressionPdf = null },
            title = { Text(context.uiText(appLanguage, "b2_pdftools_compression_title")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(context.uiText(appLanguage, "b2_pdftools_compression_desc"))
                    Button(onClick = {
                        val pdf = compressionPdf
                        showCompressionOptions = false
                        compressionPdf = null
                        if (pdf != null) runPreviewJob { PdfTools.compressPdf(context, pdf, .90f) }
                    }, modifier = Modifier.fillMaxWidth()) { Text(context.uiText(appLanguage, "b2_pdftools_quality_high")) }
                    Button(onClick = {
                        val pdf = compressionPdf
                        showCompressionOptions = false
                        compressionPdf = null
                        if (pdf != null) runPreviewJob { PdfTools.compressPdf(context, pdf, .72f) }
                    }, modifier = Modifier.fillMaxWidth()) { Text(context.uiText(appLanguage, "b2_pdftools_quality_balanced")) }
                    OutlinedButton(onClick = {
                        val pdf = compressionPdf
                        showCompressionOptions = false
                        compressionPdf = null
                        if (pdf != null) runPreviewJob { PdfTools.compressPdf(context, pdf, .55f) }
                    }, modifier = Modifier.fillMaxWidth()) { Text(context.uiText(appLanguage, "b2_pdftools_quality_small")) }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCompressionOptions = false; compressionPdf = null }) { Text(context.uiText(appLanguage, "b2_pdftools_cancel")) }
            }
        )
    }

    if (showSignaturePad && signaturePdf != null) {
        SignatureDrawDialog(
            pageCount = signaturePageCount,
            onDismiss = { showSignaturePad = false; signaturePdf = null },
            onApply = { bitmap, page, x, y, size ->
                val pdf = signaturePdf
                showSignaturePad = false
                signaturePdf = null
                if (pdf != null) {
                    runPreviewJob {
                        try { PdfTools.addSignatureBitmap(context, pdf, bitmap, page, x, y, size) }
                        finally { bitmap.recycle() }
                    }
                } else bitmap.recycle()
            }
        )
    }

    if (showReorder && reorderPdf != null) {
        ReorderPagesDialog(
            order = reorderOrder,
            onOrderChange = { reorderOrder = it },
            onDismiss = { showReorder = false; reorderPdf = null },
            onApply = {
                val pdf = reorderPdf
                val order = reorderOrder
                showReorder = false
                reorderPdf = null
                if (pdf != null) runPreviewJob { PdfTools.reorderPages(context, pdf, order) }
            }
        )
    }

    if (showWatermarkText && watermarkTextPdf != null) {
        AlertDialog(
            onDismissRequest = { showWatermarkText = false; watermarkTextPdf = null },
            title = { Text(context.uiText(appLanguage, "b2_pdftools_watermark_text_title")) },
            text = {
                Column {
                    Text(context.uiText(appLanguage, "b2_pdftools_watermark_text_desc"))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = watermarkText,
                        onValueChange = { watermarkText = it.take(60) },
                        label = { Text(context.uiText(appLanguage, "b2_pdftools_text")) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(context.uiText(appLanguage, "b2_pdftools_watermark_opacity", watermarkTextOpacity.toInt()))
                    Slider(
                        value = watermarkTextOpacity,
                        onValueChange = { watermarkTextOpacity = it },
                        valueRange = 10f..90f
                    )
                    OutlinedTextField(
                        value = watermarkTextPages,
                        onValueChange = { watermarkTextPages = it.take(80) },
                        label = { Text(context.uiText(appLanguage, "b2_pdftools_watermark_pages_hint")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val pdf = watermarkTextPdf ?: return@Button
                    val text = watermarkText
                    val opacity = watermarkTextOpacity.toInt()
                    val pages = watermarkTextPages.trim().takeIf { it.isNotEmpty() }?.let(::parsePageNumbers)
                    showWatermarkText = false
                    watermarkTextPdf = null
                    runPreviewJob { PdfTools.addTextWatermark(context, pdf, text, opacity, .52f, pages) }
                }) { Text(context.uiText(appLanguage, "b2_pdftools_add")) }
            },
            dismissButton = { TextButton(onClick = { showWatermarkText = false; watermarkTextPdf = null }) { Text(context.uiText(appLanguage, "b2_pdftools_cancel")) } }
        )
    }

    if (showWatermarkImageOptions && watermarkImagePdf != null) {
        AlertDialog(
            onDismissRequest = { showWatermarkImageOptions = false; watermarkImagePdf = null },
            title = { Text(context.uiText(appLanguage, "b2_pdftools_watermark_image_title")) },
            text = {
                Column {
                    Text(context.uiText(appLanguage, "b2_pdftools_watermark_image_desc"))
                    Spacer(Modifier.height(8.dp))
                    Text(context.uiText(appLanguage, "b2_pdftools_watermark_opacity", watermarkImageOpacity.toInt()))
                    Slider(
                        value = watermarkImageOpacity,
                        onValueChange = { watermarkImageOpacity = it },
                        valueRange = 10f..90f
                    )
                    OutlinedTextField(
                        value = watermarkImagePages,
                        onValueChange = { watermarkImagePages = it.take(80) },
                        label = { Text(context.uiText(appLanguage, "b2_pdftools_watermark_image_pages")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showWatermarkImageOptions = false
                    watermarkImageLauncher.launch(arrayOf("image/*"))
                }) { Text(context.uiText(appLanguage, "b2_pdftools_choose_image")) }
            },
            dismissButton = {
                TextButton(onClick = { showWatermarkImageOptions = false; watermarkImagePdf = null }) { Text(context.uiText(appLanguage, "b2_pdftools_cancel")) }
            }
        )
    }

    Scaffold(topBar = { ScreenTopBar(context.uiText(appLanguage, "b2_pdftools_screen_title"), onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (busy) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (activeJob != null) {
                        OutlinedButton(onClick = { activeJob?.cancel() }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Close, null)
                            Spacer(Modifier.width(6.dp))
                            Text(context.uiText(appLanguage, "v15_cancel_operation"))
                        }
                        Text(context.uiText(appLanguage, "v15_processing_progress"), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item { ToolCard(context.uiText(appLanguage, "b2_pdftools_editor_title"), context.uiText(appLanguage, "b2_pdftools_editor_desc"), Icons.Default.EditNote, Color(0xFF2563EB), { editorPdfLauncher.launch(arrayOf("application/pdf")) }) }
            item { ToolCard(context.uiText(appLanguage, "b2_pdftools_merge_title"), context.uiText(appLanguage, "b2_pdftools_merge_desc"), Icons.Default.Merge, AccentRed, { mergeLauncher.launch(arrayOf("application/pdf")) }) }
            item { ToolCard(context.uiText(appLanguage, "b2_pdftools_split_title"), context.uiText(appLanguage, "b2_pdftools_split_desc"), Icons.Default.CallSplit, RoyalBlue, { splitLauncher.launch(arrayOf("application/pdf")) }) }
            item { ToolCard(context.uiText(appLanguage, "b2_pdftools_compress_title"), context.uiText(appLanguage, "b2_pdftools_compress_desc"), Icons.Default.Compress, Color(0xFFF59E0B), { compressLauncher.launch(arrayOf("application/pdf")) }) }
            item { ToolCard(context.uiText(appLanguage, "b2_pdftools_page_numbers"), context.uiText(appLanguage, "b2_pdftools_page_numbers_desc"), Icons.Default.FormatListNumbered, Color(0xFF7C3AED), {
                nextDialogMode = TextDialogMode.PAGE_NUMBERS
                dialogPdfLauncher.launch(arrayOf("application/pdf"))
            }) }
            item { ToolCard(context.uiText(appLanguage, "b2_pdftools_long_image_title"), context.uiText(appLanguage, "b2_pdftools_long_image_desc"), Icons.Default.ViewDay, Color(0xFF0F766E), {
                longImageLauncher.launch(arrayOf("application/pdf"))
            }) }
            item { ToolCard(context.uiText(appLanguage, "b2_pdftools_rotate_all_title"), context.uiText(appLanguage, "b2_pdftools_rotate_all_desc"), Icons.Default.RotateRight, Teal, { rotateLauncher.launch(arrayOf("application/pdf")) }) }
            item { ToolCard(context.uiText(appLanguage, "b2_pdftools_rotate_selected_title"), context.uiText(appLanguage, "b2_pdftools_rotate_selected_desc"), Icons.Default.Rotate90DegreesCw, Color(0xFF14B8A6), {
                nextDialogMode = TextDialogMode.ROTATE_PAGES
                dialogPdfLauncher.launch(arrayOf("application/pdf"))
            }) }
            item { ToolCard(context.uiText(appLanguage, "b2_pdftools_extract_pages"), context.uiText(appLanguage, "b2_pdftools_extract_desc"), Icons.Default.FileCopy, Color(0xFF2563EB), {
                nextDialogMode = TextDialogMode.EXTRACT_PAGES
                dialogPdfLauncher.launch(arrayOf("application/pdf"))
            }) }
            item { ToolCard(context.uiText(appLanguage, "b2_pdftools_delete_pages"), context.uiText(appLanguage, "b2_pdftools_delete_desc"), Icons.Default.DeleteSweep, AccentRed, {
                nextDialogMode = TextDialogMode.DELETE_PAGES
                dialogPdfLauncher.launch(arrayOf("application/pdf"))
            }) }
            item { ToolCard(context.uiText(appLanguage, "b2_pdftools_quick_reorder"), context.uiText(appLanguage, "b2_pdftools_quick_reorder_desc"), Icons.Default.SwapVert, Color(0xFF0EA5E9), {
                nextDialogMode = TextDialogMode.REORDER_PAGES
                dialogPdfLauncher.launch(arrayOf("application/pdf"))
            }) }
            item { ToolCard(context.uiText(appLanguage, "b2_pdftools_drag_reorder_title"), context.uiText(appLanguage, "b2_pdftools_drag_reorder_desc"), Icons.Default.DragHandle, RoyalBlue, {
                reorderPdfLauncher.launch(arrayOf("application/pdf"))
            }) }
            item { ToolCard(context.uiText(appLanguage, "b2_pdftools_password_title"), context.uiText(appLanguage, "b2_pdftools_password_desc"), Icons.Default.Lock, Color(0xFF7C3AED), {
                nextDialogMode = TextDialogMode.PROTECT
                dialogPdfLauncher.launch(arrayOf("application/pdf"))
            }) }
            item { ToolCard(context.uiText(appLanguage, "b2_pdftools_unlock"), context.uiText(appLanguage, "b2_pdftools_unlock_desc"), Icons.Default.LockOpen, Color(0xFF0EA5E9), {
                nextDialogMode = TextDialogMode.UNLOCK
                dialogPdfLauncher.launch(arrayOf("application/pdf"))
            }) }
            item { ToolCard(context.uiText(appLanguage, "b2_pdftools_signature_image_title"), context.uiText(appLanguage, "b2_pdftools_signature_image_desc"), Icons.Default.BorderColor, Color(0xFF7C3AED), {
                signatureImagePdfLauncher.launch(arrayOf("application/pdf"))
            }) }
            item { ToolCard(context.uiText(appLanguage, "b2_pdftools_signature_draw_title"), context.uiText(appLanguage, "b2_pdftools_signature_draw_desc"), Icons.Default.Draw, Color(0xFF7C3AED), {
                signatureDrawPdfLauncher.launch(arrayOf("application/pdf"))
            }) }
            item { ToolCard(context.uiText(appLanguage, "b2_pdftools_watermark_text_title"), context.uiText(appLanguage, "b2_pdftools_watermark_text_card_desc"), Icons.Default.BrandingWatermark, Teal, {
                watermarkTextPdfLauncher.launch(arrayOf("application/pdf"))
            }) }
            item { ToolCard(context.uiText(appLanguage, "b2_pdftools_watermark_image_title"), context.uiText(appLanguage, "b2_pdftools_watermark_image_card_desc"), Icons.Default.Image, AccentRed, {
                watermarkImagePdfLauncher.launch(arrayOf("application/pdf"))
            }) }
            item {
                ToolCard(context.uiText(appLanguage, "b2_pdftools_outputs_title"), context.uiText(appLanguage, "b2_pdftools_outputs_desc"), Icons.Default.Folder, Teal, {
                    if (!busy) {
                        scope.launch {
                            busy = true
                            val count = withContext(Dispatchers.IO) {
                                runCatching { FileUtils.listGeneratedFiles(context, limit = 5000).size }.getOrDefault(0)
                            }
                            busy = false
                            toast(context.uiText(appLanguage, "b2_pdftools_output_count", count))
                        }
                    }
                }, badge = context.uiText(appLanguage, "b2_pdftools_badge_dev"))
            }
        }
    }

    pendingDraft?.let { draft ->
        ConversionPreviewDialog(
            draft = draft,
            onApprove = {
                runCatching { ConversionDraftManager.commit(context, draft) }
                    .onSuccess { saved ->
                        pendingDraft = null
                        toast(context.uiText(appLanguage, "b2_pdftools_saved_one", saved.name))
                    }
                    .onFailure { toast(context.uiText(appLanguage, "b2_pdftools_error_approve")) }
            },
            onDiscard = {
                ConversionDraftManager.discard(context, draft)
                pendingDraft = null
                toast(context.uiText(appLanguage, "b2_pdftools_discarded"))
            }
        )
    }

    if (pendingBatchDrafts.isNotEmpty()) {
        BatchPdfPreviewDialog(
            drafts = pendingBatchDrafts,
            onApprove = {
                runCatching { ConversionDraftManager.commitBatch(context, pendingBatchDrafts) }
                    .onSuccess { saved ->
                        pendingBatchDrafts = emptyList()
                        toast(context.uiText(appLanguage, "b2_pdftools_saved_many", saved.size))
                    }
                    .onFailure { toast(context.uiText(appLanguage, "b2_pdftools_error_approve_files")) }
            },
            onDiscard = {
                ConversionDraftManager.discardAll(context, pendingBatchDrafts)
                pendingBatchDrafts = emptyList()
                toast(context.uiText(appLanguage, "b2_pdftools_discarded_files"))
            }
        )
    }
}

@Composable
private fun SignatureDrawDialog(
    pageCount: Int,
    onDismiss: () -> Unit,
    onApply: (Bitmap, Int, Float, Float, Float) -> Unit
) {
    val context = LocalContext.current
    val appLanguage = LocalAppLanguage.current
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var currentStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var padSize by remember { mutableStateOf(IntSize(1, 1)) }
    var savedSignatures by remember { mutableStateOf(savedSignatureFiles(context)) }
    var selectedSavedSignature by remember { mutableStateOf<File?>(null) }
    var selectedPreview by remember { mutableStateOf<Bitmap?>(null) }
    var pageText by remember { mutableStateOf("1") }
    var horizontal by remember { mutableFloatStateOf(.85f) }
    var vertical by remember { mutableFloatStateOf(.08f) }
    var size by remember { mutableFloatStateOf(.30f) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(context.uiText(appLanguage, "b2_pdftools_signature_dialog_title"), style = MaterialTheme.typography.titleLarge)
                Text(context.uiText(appLanguage, "b2_pdftools_signature_privacy"), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))

                if (savedSignatures.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        savedSignatures.forEach { file ->
                            FilterChip(
                                selected = selectedSavedSignature?.absolutePath == file.absolutePath,
                                onClick = {
                                    selectedPreview?.takeIf { !it.isRecycled }?.recycle()
                                    selectedSavedSignature = file
                                    selectedPreview = BitmapFactory.decodeFile(file.absolutePath)
                                    strokes.clear(); currentStroke = emptyList()
                                },
                                label = { Text(savedSignatureLabel(context, appLanguage, file)) },
                                leadingIcon = { Icon(Icons.Default.Gesture, null, Modifier.size(16.dp)) }
                            )
                        }
                    }
                    selectedPreview?.let { preview ->
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            Modifier.fillMaxWidth().height(70.dp),
                            color = Color.White,
                            shape = MaterialTheme.shapes.medium,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .25f))
                        ) {
                            androidx.compose.foundation.Image(
                                bitmap = preview.asImageBitmap(),
                                contentDescription = context.uiText(appLanguage, "b2_pdftools_signature_preview"),
                                modifier = Modifier.padding(6.dp),
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit
                            )
                        }
                        TextButton(onClick = {
                            val selectedFile = selectedSavedSignature
                            selectedPreview?.takeIf { !it.isRecycled }?.recycle()
                            selectedPreview = null
                            selectedSavedSignature = null
                            if (selectedFile != null) runCatching { selectedFile.delete() }
                            savedSignatures = savedSignatureFiles(context)
                        }) { Text(context.uiText(appLanguage, "b2_pdftools_signature_delete")) }
                    }
                }

                Text(context.uiText(appLanguage, "b2_pdftools_signature_or_draw"), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth().height(210.dp),
                    color = Color.White,
                    shape = MaterialTheme.shapes.large,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .35f))
                ) {
                    Canvas(
                        Modifier.fillMaxSize()
                            .onSizeChanged { padSize = it }
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { selectedPreview?.takeIf { bmp -> !bmp.isRecycled }?.recycle(); selectedPreview = null; selectedSavedSignature = null; currentStroke = listOf(it) },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        currentStroke = currentStroke + change.position
                                    },
                                    onDragEnd = {
                                        if (currentStroke.size > 1) strokes.add(currentStroke)
                                        currentStroke = emptyList()
                                    },
                                    onDragCancel = { currentStroke = emptyList() }
                                )
                            }
                    ) {
                        fun drawStroke(points: List<Offset>) {
                            if (points.size < 2) return
                            val path = Path().apply {
                                moveTo(points.first().x, points.first().y)
                                points.drop(1).forEach { lineTo(it.x, it.y) }
                            }
                            drawPath(path, color = Color(0xFF0D1B3D), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f))
                        }
                        strokes.forEach(::drawStroke)
                        drawStroke(currentStroke)
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }) { Text(context.uiText(appLanguage, "b2_pdftools_undo")) }
                    TextButton(
                        enabled = strokes.isNotEmpty() && savedSignatures.size < 10,
                        onClick = {
                            if (strokes.isEmpty()) return@TextButton
                            val bitmap = signatureBitmap(strokes.toList(), padSize)
                            runCatching { saveSignatureLocally(context, bitmap) }
                                .onSuccess { savedSignatures = savedSignatureFiles(context) }
                            bitmap.recycle()
                        }
                    ) { Text(if (savedSignatures.size >= 10) context.uiText(appLanguage, "b2_pdftools_signature_limit") else context.uiText(appLanguage, "b2_pdftools_signature_save")) }
                    TextButton(onClick = { strokes.clear(); currentStroke = emptyList(); selectedSavedSignature = null; selectedPreview?.takeIf { !it.isRecycled }?.recycle(); selectedPreview = null }) { Text(context.uiText(appLanguage, "b2_pdftools_clear")) }
                }

                OutlinedTextField(
                    value = pageText,
                    onValueChange = { pageText = it.filter(Char::isDigit).take(3) },
                    label = { Text(context.uiText(appLanguage, "b2_pdftools_signature_page", pageCount)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(context.uiText(appLanguage, "b2_pdftools_signature_x"))
                Slider(value = horizontal, onValueChange = { horizontal = it })
                Text(context.uiText(appLanguage, "b2_pdftools_signature_y"))
                Slider(value = vertical, onValueChange = { vertical = it })
                Text(context.uiText(appLanguage, "b2_pdftools_signature_size"))
                Slider(value = size, onValueChange = { size = it }, valueRange = .15f..55f / 100f)

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(context.uiText(appLanguage, "b2_pdftools_cancel")) }
                    Button(
                        onClick = {
                            val page = pageText.toIntOrNull() ?: 1
                            if (page !in 1..pageCount) return@Button
                            val bitmap = selectedSavedSignature?.let { BitmapFactory.decodeFile(it.absolutePath) }
                                ?: if (strokes.isNotEmpty()) signatureBitmap(strokes.toList(), padSize) else null
                                ?: return@Button
                            selectedPreview?.takeIf { !it.isRecycled }?.recycle()
                            selectedPreview = null
                            onApply(bitmap, page, horizontal, vertical, size)
                        },
                        enabled = (strokes.isNotEmpty() || selectedSavedSignature != null) && (pageText.toIntOrNull() ?: 0) in 1..pageCount,
                        modifier = Modifier.weight(1f)
                    ) { Text(context.uiText(appLanguage, "b2_pdftools_signature_pdf")) }
                }
            }
        }
    }
}

private fun signatureBitmap(strokes: List<List<Offset>>, size: IntSize): Bitmap {
    val width = size.width.coerceAtLeast(200)
    val height = size.height.coerceAtLeast(100)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(13, 27, 61)
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    strokes.forEach { points ->
        if (points.size > 1) {
            val path = android.graphics.Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            canvas.drawPath(path, paint)
        }
    }
    return bitmap
}

@Composable
private fun ReorderPagesDialog(
    order: List<Int>,
    onOrderChange: (List<Int>) -> Unit,
    onDismiss: () -> Unit,
    onApply: () -> Unit
) {
    val context = LocalContext.current
    val appLanguage = LocalAppLanguage.current
    val threshold = with(LocalDensity.current) { 52.dp.toPx() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).padding(16.dp)) {
                Text(context.uiText(appLanguage, "b2_pdftools_reorder_title"), style = MaterialTheme.typography.titleLarge)
                Text(context.uiText(appLanguage, "b2_pdftools_reorder_help"), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(order, key = { _, page -> page }) { index, page ->
                        var dragTotal by remember(page) { mutableFloatStateOf(0f) }
                        Card(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.DragHandle,
                                    contentDescription = context.uiText(appLanguage, "b2_pdftools_drag"),
                                    modifier = Modifier.size(38.dp).pointerInput(page, index, order) {
                                        detectDragGesturesAfterLongPress(
                                            onDragEnd = { dragTotal = 0f },
                                            onDragCancel = { dragTotal = 0f },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragTotal += amount.y
                                                if (dragTotal > threshold && index < order.lastIndex) {
                                                    val list = order.toMutableList()
                                                    val temp = list[index]
                                                    list[index] = list[index + 1]
                                                    list[index + 1] = temp
                                                    onOrderChange(list)
                                                    dragTotal = 0f
                                                } else if (dragTotal < -threshold && index > 0) {
                                                    val list = order.toMutableList()
                                                    val temp = list[index]
                                                    list[index] = list[index - 1]
                                                    list[index - 1] = temp
                                                    onOrderChange(list)
                                                    dragTotal = 0f
                                                }
                                            }
                                        )
                                    }
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(context.uiText(appLanguage, "b2_pdftools_page", page), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                IconButton(enabled = index > 0, onClick = {
                                    val list = order.toMutableList()
                                    val temp = list[index]
                                    list[index] = list[index - 1]
                                    list[index - 1] = temp
                                    onOrderChange(list)
                                }) { Icon(Icons.Default.KeyboardArrowUp, null) }
                                IconButton(enabled = index < order.lastIndex, onClick = {
                                    val list = order.toMutableList()
                                    val temp = list[index]
                                    list[index] = list[index + 1]
                                    list[index + 1] = temp
                                    onOrderChange(list)
                                }) { Icon(Icons.Default.KeyboardArrowDown, null) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDismiss, Modifier.weight(1f)) { Text(context.uiText(appLanguage, "b2_pdftools_cancel")) }
                    Button(onClick = onApply, Modifier.weight(1f)) { Text(context.uiText(appLanguage, "b2_pdftools_save_order")) }
                }
            }
        }
    }
}
