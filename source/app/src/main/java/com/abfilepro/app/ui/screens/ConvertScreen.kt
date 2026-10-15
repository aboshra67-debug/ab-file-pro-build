package com.abfilepro.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.abfilepro.app.ui.i18n.AppLanguage
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.i18n.uiText
import com.abfilepro.app.ui.i18n.localizedLabel
import com.abfilepro.app.ui.i18n.localizedOperationFailure
import com.abfilepro.app.core.ConversionCopyExporter
import com.abfilepro.app.core.ConversionDraftManager
import com.abfilepro.app.core.ConversionHistoryStore
import com.abfilepro.app.core.FileUtils
import com.abfilepro.app.core.OfficeTools
import com.abfilepro.app.core.OcrProcessor
import com.abfilepro.app.core.PdfTools
import com.abfilepro.app.ui.components.ScreenTopBar
import com.abfilepro.app.ui.components.ScreenHelpRobot
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

private const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
private const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

/** The existing converters stay untouched. This screen only orchestrates selection and review. */
@Composable
fun ConvertScreen(
    onBack: () -> Unit,
    onHome: (() -> Unit)? = null,
    onOpenFiles: ((File) -> Unit)? = null,
    onOpenPdf: ((File) -> Unit)? = null
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberLazyListState()
    val settings = remember(context) { context.getSharedPreferences("ab_file_pro_conversions", Context.MODE_PRIVATE) }
    var busy by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var ocrLanguage by remember {
        mutableStateOf(listOf(OcrProcessor.Language.ARABIC_ENGLISH, OcrProcessor.Language.ARABIC,
            OcrProcessor.Language.ENGLISH, OcrProcessor.Language.FRENCH, OcrProcessor.Language.ALL).firstOrNull {
            it.name == settings.getString("ocr_language", OcrProcessor.Language.ARABIC_ENGLISH.name)
        } ?: OcrProcessor.Language.ARABIC_ENGLISH)
    }
    var ocrMenu by remember { mutableStateOf(false) }
    var pendingDraft by remember { mutableStateOf<File?>(null) }
    var pendingImageDrafts by remember { mutableStateOf<List<File>>(emptyList()) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var selectedSource by remember { mutableStateOf<Uri?>(null) }
    var wordReviewUri by remember { mutableStateOf<Uri?>(null) }
    var sourceKind by remember { mutableStateOf("") }
    var savedResults by remember { mutableStateOf<List<File>>(emptyList()) }
    var exportSource by remember { mutableStateOf<File?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var recent by remember { mutableStateOf(emptyList<File>()) }

    val canStart = !busy && !saving && pendingDraft == null && pendingImageDrafts.isEmpty()
    fun reloadHistory() { recent = ConversionHistoryStore.recent(context).take(5) }

    DisposableEffect(Unit) { onDispose { activeJob?.cancel() } }
    LaunchedEffect(Unit) {
        recent = withContext(Dispatchers.IO) {
            ConversionDraftManager.cleanup(context)
            ConversionHistoryStore.recent(context).take(5)
        }
    }

    fun fail(error: Throwable) {
        Toast.makeText(context, context.localizedOperationFailure(language, error, "convert_screen_001"), Toast.LENGTH_LONG).show()
    }
    fun openSaved(file: File) {
        if (file.extension.equals("pdf", ignoreCase = true) && onOpenPdf != null) onOpenPdf(file)
        else FileUtils.openFile(context, file)
    }

    fun runConversion(block: suspend () -> File) {
        if (!canStart) return
        selectedSource = null
        busy = true
        activeJob = scope.launch {
            try {
                val generated = block()
                pendingDraft = withContext(Dispatchers.IO) { ConversionDraftManager.stage(context, generated) }
            } catch (_: CancellationException) {
                // Leaving the screen or pressing Cancel is not a conversion failure.
            } catch (error: Exception) {
                fail(error)
            } finally {
                busy = false
                activeJob = null
            }
        }
    }

    fun runPdfToImages(uri: Uri) {
        if (!canStart) return
        selectedSource = null
        busy = true
        activeJob = scope.launch {
            val staged = mutableListOf<File>()
            try {
                val generated = PdfTools.pdfToImages(context, uri)
                require(generated.isNotEmpty()) { "الملف لا يحتوي على صفحات" }
                withContext(Dispatchers.IO) {
                    try {
                        generated.forEach { staged += ConversionDraftManager.stage(context, it) }
                    } catch (error: Exception) {
                        ConversionDraftManager.discardAll(context, staged)
                        generated.filter { it.exists() }.forEach { it.delete() }
                        throw error
                    }
                }
                pendingImageDrafts = staged.toList()
            } catch (_: CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    ConversionDraftManager.discardAll(context, staged)
                }
            } catch (error: Exception) {
                fail(error)
            } finally {
                busy = false
                activeJob = null
            }
        }
    }

    val imagesToPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) runConversion { PdfTools.imagesToPdf(context, uris) }
    }
    val pdfToImages = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runPdfToImages(uri)
    }
    val imageToWord = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && canStart) wordReviewUri = uri
    }
    val imageTableToExcel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runConversion { OfficeTools.imageTableToXlsx(context, uri, ocrLanguage) }
    }
    val wordToPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runConversion { OfficeTools.docxToPdf(context, uri) }
    }
    val excelToPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runConversion { OfficeTools.xlsxToPdf(context, uri) }
    }
    val pdfToWord = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runConversion { OfficeTools.pdfToDocx(context, uri) }
    }
    val pdfToExcel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runConversion { OfficeTools.pdfToXlsx(context, uri) }
    }

    // SAF saves a verified copy to a user-selected folder; the workspace original remains available.
    val saveCopyPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val source = exportSource
        exportSource = null
        val destination = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        if (source != null && destination != null && !exporting) {
            exporting = true
            scope.launch {
                try {
                    ConversionCopyExporter.exportVerified(context, source, destination)
                    Toast.makeText(context, textByLanguage(language, "تم حفظ نسخة والتحقق منها", "Copy saved and verified", "Copie vérifiée et enregistrée"), Toast.LENGTH_LONG).show()
                } catch (error: Exception) {
                    fail(error)
                } finally { exporting = false }
            }
        }
    }
    fun exportFile(source: File) {
        if (exporting || busy || saving) return
        exportSource = source
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = FileUtils.mimeType(source)
            putExtra(Intent.EXTRA_TITLE, source.name)
        }
        saveCopyPicker.launch(intent)
    }

    // One-tap file selection: show ONLY conversions supported by the existing engine.
    val quickPick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && canStart) {
            val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
            val fileName = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull().orEmpty().lowercase()
            val kind = when {
                mime.startsWith("image/") || listOf(".jpg", ".jpeg", ".png", ".webp").any { fileName.endsWith(it) } -> "image"
                mime == "application/pdf" || fileName.endsWith(".pdf") -> "pdf"
                mime == DOCX || fileName.endsWith(".docx") -> "word"
                mime == XLSX || fileName.endsWith(".xlsx") -> "excel"
                else -> ""
            }
            if (kind.isBlank()) {
                Toast.makeText(context, textByLanguage(language, "هذه الصيغة غير مدعومة في التحويل الحالي", "This file type is not supported yet", "Ce format n'est pas encore pris en charge"), Toast.LENGTH_LONG).show()
            } else {
                sourceKind = kind
                selectedSource = uri
            }
        }
    }

    fun approve(drafts: List<File>) {
        if (saving || busy || drafts.isEmpty()) return
        saving = true
        activeJob = scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    val files = if (drafts.size == 1) listOf(ConversionDraftManager.commit(context, drafts.first()))
                                else ConversionDraftManager.commitBatch(context, drafts)
                    files.forEach { FileUtils.confirmSavedFile(context, it) }
                    runCatching { ConversionHistoryStore.record(context, files) }
                    files
                }
                pendingDraft = null
                pendingImageDrafts = emptyList()
                savedResults = saved
                reloadHistory()
            } catch (error: Exception) {
                // A draft stays in review if the save failed; never claim success.
                fail(error)
            } finally {
                saving = false
                activeJob = null
            }
        }
    }

    Scaffold(
        topBar = {
            ScreenTopBar(context.uiText(language, "convert_screen_002"), onBack, actions = {
                if (onHome != null) {
                    IconButton(onClick = onHome) {
                        Icon(Icons.Default.Home, contentDescription = textByLanguage(language, "الرئيسية", "Home", "Accueil"))
                    }
                }
            })
        },
        floatingActionButton = {
            ScreenHelpRobot(titleKey = "help_convert_title", bodyKey = "help_convert_body")
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 12.dp),
            state = scrollState,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "pick") {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(textByLanguage(language, "مركز تحويل الملفات", "File conversion center", "Centre de conversion"), style = MaterialTheme.typography.titleMedium)
                        Text(textByLanguage(language, "اختر ملفًا لتظهر التحويلات المناسبة له، أو اختر أداة من الأسفل.", "Choose a file to see available conversions, or use a tool below.", "Choisissez un fichier pour voir les conversions disponibles."), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { if (canStart) quickPick.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth(), enabled = canStart) {
                            Icon(Icons.Default.UploadFile, null)
                            Spacer(Modifier.width(8.dp))
                            Text(textByLanguage(language, "اختر ملفًا للتحويل", "Choose a file", "Choisir un fichier"))
                        }
                    }
                }
            }
            if (busy || saving) item(key = "progress") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if (saving) textByLanguage(language, "جارٍ اعتماد الحفظ…", "Saving…", "Enregistrement…") else context.uiText(language, "v15_processing_progress"), style = MaterialTheme.typography.labelSmall)
                    if (!saving) OutlinedButton(onClick = { activeJob?.cancel() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Close, null)
                        Spacer(Modifier.width(6.dp))
                        Text(context.uiText(language, "v15_cancel_operation"))
                    }
                }
            }
            item(key = "image_header") { ConversionSectionHeader(textByLanguage(language, "الصور وPDF", "Images and PDF", "Images et PDF")) }
            item(key = "images_pdf") { ToolCard(context.uiText(language, "convert_screen_006"), context.uiText(language, "convert_screen_007"), Icons.Default.PictureAsPdf, AccentRed, { if (canStart) imagesToPdf.launch(arrayOf("image/*")) }, enabled = canStart) }
            item(key = "pdf_images") { ToolCard(context.uiText(language, "convert_screen_008"), context.uiText(language, "convert_screen_009"), Icons.Default.Image, RoyalBlue, { if (canStart) pdfToImages.launch(arrayOf("application/pdf")) }, enabled = canStart) }

            item(key = "ocr_header") {
                ConversionSectionHeader(textByLanguage(language, "استخراج النصوص والجداول OCR", "OCR text and tables", "OCR textes et tableaux"))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(context.uiText(language, "convert_screen_003"), style = MaterialTheme.typography.bodyMedium)
                    Box {
                        OutlinedButton(onClick = { ocrMenu = true }, enabled = canStart) {
                            Text(context.localizedLabel(language, ocrLanguage))
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(expanded = ocrMenu, onDismissRequest = { ocrMenu = false }) {
                            listOf(OcrProcessor.Language.ARABIC_ENGLISH, OcrProcessor.Language.ARABIC,
                                OcrProcessor.Language.ENGLISH, OcrProcessor.Language.FRENCH, OcrProcessor.Language.ALL).forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(context.localizedLabel(language, option)) },
                                    onClick = {
                                        ocrLanguage = option
                                        settings.edit().putString("ocr_language", option.name).apply()
                                        ocrMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
            item(key = "image_word") { ToolCard(context.uiText(language, "convert_screen_010"), context.uiText(language, "convert_screen_011"), Icons.Default.DocumentScanner, Teal, { if (canStart) imageToWord.launch(arrayOf("image/*")) }, enabled = canStart, badge = "OCR → DOCX") }
            item(key = "image_xlsx") { ToolCard(context.uiText(language, "convert_screen_012"), context.uiText(language, "convert_screen_013"), Icons.Default.GridOn, Color(0xFF15803D), { if (canStart) imageTableToExcel.launch(arrayOf("image/*")) }, enabled = canStart, badge = "OCR → XLSX") }

            item(key = "office_header") { ConversionSectionHeader(textByLanguage(language, "تحويلات Word وExcel وPDF", "Office conversions", "Conversions Office")) }
            item(key = "word_pdf") { ToolCard(context.uiText(language, "convert_screen_014"), context.uiText(language, "convert_screen_015"), Icons.Default.Description, Color(0xFF2563EB), { if (canStart) wordToPdf.launch(arrayOf(DOCX, "application/zip")) }, enabled = canStart, badge = "DOCX") }
            item(key = "xlsx_pdf") { ToolCard(context.uiText(language, "convert_screen_016"), context.uiText(language, "convert_screen_017"), Icons.Default.TableChart, Color(0xFF16A34A), { if (canStart) excelToPdf.launch(arrayOf(XLSX, "application/zip")) }, enabled = canStart, badge = "XLSX") }
            item(key = "pdf_word") { ToolCard(context.uiText(language, "convert_screen_018"), context.uiText(language, "convert_screen_019"), Icons.Default.TextSnippet, Teal, { if (canStart) pdfToWord.launch(arrayOf("application/pdf")) }, enabled = canStart, badge = "DOCX") }
            item(key = "pdf_xlsx") { ToolCard(context.uiText(language, "convert_screen_020"), context.uiText(language, "convert_screen_021"), Icons.Default.GridOn, Color(0xFF16A34A), { if (canStart) pdfToExcel.launch(arrayOf("application/pdf")) }, enabled = canStart, badge = "XLSX") }

            if (recent.isNotEmpty()) {
                item(key = "recent_header") { ConversionSectionHeader(textByLanguage(language, "آخر الملفات المحفوظة", "Recently saved", "Récemment enregistrés")) }
                items(recent, key = { "history_${it.absolutePath}" }) { file ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(file.name, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                                Text(file.parentFile?.name.orEmpty(), maxLines = 1, style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(onClick = { runCatching { openSaved(file) }.onFailure(::fail) }) {
                                Icon(Icons.Default.OpenInNew, contentDescription = textByLanguage(language, "فتح", "Open", "Ouvrir"))
                            }
                            IconButton(onClick = { runCatching { FileUtils.shareFile(context, file) }.onFailure(::fail) }) {
                                Icon(Icons.Default.Share, contentDescription = textByLanguage(language, "مشاركة", "Share", "Partager"))
                            }
                        }
                    }
                }
            }
            item(key = "notice") {
                Text(context.uiText(language, "convert_screen_022"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
            }
        }
    }

    selectedSource?.let { uri ->
        AlertDialog(
            onDismissRequest = { selectedSource = null },
            title = { Text(textByLanguage(language, "اختر نوع التحويل", "Choose conversion", "Choisir la conversion")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (sourceKind) {
                        "image" -> {
                            ConversionAction(textByLanguage(language, "صورة إلى PDF", "Image to PDF", "Image vers PDF")) { runConversion { PdfTools.imagesToPdf(context, listOf(uri)) } }
                            ConversionAction(textByLanguage(language, "صورة إلى Word", "Image to Word", "Image vers Word")) { selectedSource = null; wordReviewUri = uri }
                            ConversionAction(textByLanguage(language, "جدول الصورة إلى Excel", "Image table to Excel", "Tableau vers Excel")) { runConversion { OfficeTools.imageTableToXlsx(context, uri, ocrLanguage) } }
                        }
                        "pdf" -> {
                            ConversionAction(textByLanguage(language, "PDF إلى صور", "PDF to images", "PDF vers images")) { runPdfToImages(uri) }
                            ConversionAction(textByLanguage(language, "PDF إلى Word (نص)", "PDF to Word (text)", "PDF vers Word (texte)")) { runConversion { OfficeTools.pdfToDocx(context, uri) } }
                            ConversionAction(textByLanguage(language, "PDF إلى Excel (الجداول الواضحة فقط)", "PDF to Excel (structured tables only)", "PDF vers Excel (tableaux)")) { runConversion { OfficeTools.pdfToXlsx(context, uri) } }
                        }
                        "word" -> ConversionAction("Word → PDF") { runConversion { OfficeTools.docxToPdf(context, uri) } }
                        "excel" -> ConversionAction("Excel → PDF") { runConversion { OfficeTools.xlsxToPdf(context, uri) } }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedSource = null }) { Text(textByLanguage(language, "إغلاق", "Close", "Fermer")) } }
        )
    }

    wordReviewUri?.let { source ->
        ImageWordReviewDialog(
            uri = source,
            language = ocrLanguage,
            onDismiss = { wordReviewUri = null },
            onCreateWord = { editedText ->
                wordReviewUri = null
                runConversion { OfficeTools.reviewedTextToDocx(context, editedText) }
            }
        )
    }

    pendingDraft?.let { draft ->
        ConversionPreviewDialog(
            draft = draft,
            saving = saving,
            onApprove = { approve(listOf(draft)) },
            onDiscard = {
                if (!saving) {
                    ConversionDraftManager.discard(context, draft)
                    pendingDraft = null
                }
            }
        )
    }
    if (pendingImageDrafts.isNotEmpty()) {
        BatchImagePreviewDialog(
            drafts = pendingImageDrafts,
            saving = saving,
            onApprove = { approve(pendingImageDrafts) },
            onDiscard = {
                if (!saving) {
                    ConversionDraftManager.discardAll(context, pendingImageDrafts)
                    pendingImageDrafts = emptyList()
                }
            }
        )
    }

    if (savedResults.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { if (!exporting) savedResults = emptyList() },
            icon = { Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF15803D)) },
            title = { Text(textByLanguage(language, "تم الحفظ بنجاح", "Saved successfully", "Enregistrement réussi")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(savedResults.take(3).joinToString("\n") { it.name })
                    if (savedResults.size > 3) Text("+${savedResults.size - 3}")
                    Text(savedResults.first().parentFile?.absolutePath.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        runCatching { if (savedResults.size == 1) openSaved(savedResults.first())
                            else onOpenFiles?.invoke(savedResults.first()) ?: FileUtils.shareFiles(context, savedResults) }.onFailure(::fail)
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.OpenInNew, null)
                        Spacer(Modifier.width(8.dp))
                        Text(textByLanguage(language, "فتح الناتج", "Open result", "Ouvrir le résultat"))
                    }
                    OutlinedButton(onClick = {
                        runCatching { FileUtils.shareFiles(context, savedResults) }.onFailure(::fail)
                    }, modifier = Modifier.fillMaxWidth(), enabled = !exporting) {
                        Icon(Icons.Default.Share, null)
                        Spacer(Modifier.width(8.dp))
                        Text(textByLanguage(language, "مشاركة", "Share", "Partager"))
                    }
                    if (savedResults.size == 1) {
                        OutlinedButton(onClick = { exportFile(savedResults.first()) },
                            modifier = Modifier.fillMaxWidth(), enabled = !exporting) {
                            Icon(Icons.Default.SaveAlt, null)
                            Spacer(Modifier.width(8.dp))
                            Text(textByLanguage(language, "حفظ نسخة في مكان آخر", "Save a copy elsewhere", "Enregistrer une copie ailleurs"))
                        }
                    }
                    if (onOpenFiles != null) {
                        OutlinedButton(onClick = {
                            val target = savedResults.first()
                            savedResults = emptyList()
                            onOpenFiles(target)
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.FolderOpen, null)
                            Spacer(Modifier.width(8.dp))
                            Text(textByLanguage(language, "عرض في ملفاتي", "Show in My Files", "Afficher dans mes fichiers"))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { savedResults = emptyList() }, enabled = !exporting) {
                    Text(textByLanguage(language, "تحويل جديد", "New conversion", "Nouvelle conversion"))
                }
            }
        )
    }
}

@Composable
private fun ConversionSectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
}

@Composable
private fun ConversionAction(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

private fun textByLanguage(language: AppLanguage, ar: String, en: String, fr: String): String = when (language) {
    AppLanguage.ARABIC -> ar
    AppLanguage.ENGLISH -> en
    AppLanguage.FRENCH -> fr
}
