package com.abfilepro.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint as AndroidPaint
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlin.math.hypot
import com.abfilepro.app.R
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.i18n.uiText
import com.abfilepro.app.ui.i18n.localizedLabel
import com.abfilepro.app.ui.i18n.localizedOperationFailure
import com.abfilepro.app.core.*
import com.abfilepro.app.ui.components.ScreenTopBar
import com.abfilepro.app.ui.components.ScreenHelpRobot
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class ScanUiStage { HUB, CAMERA, PROCESSING, BOUNDS, EDITOR, SAVE_OPTIONS, REVIEW, SAVING, SUCCESS }
private enum class ScanOutputFormat { PDF, JPG }

@Composable
fun ScanScreen(onBack: () -> Unit, onOpenPdf: (File) -> Unit, onOpenBarcode: () -> Unit, onOpenSavedLocation: (File) -> Unit) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()

    var lastResult by remember { mutableStateOf(context.uiText(language, "scan_screen_001")) }
    var lastSavedFile by remember { mutableStateOf<File?>(null) }
    var saveSuccessFile by remember { mutableStateOf<File?>(null) }
    var barcodeReaderOpen by remember { mutableStateOf(false) }
    var translationResult by remember { mutableStateOf<ScanTranslationResult?>(null) }
    var translationBusy by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var savePageImages by remember { mutableStateOf(false) }
    var openAfterSave by remember { mutableStateOf(true) }
    var customName by remember { mutableStateOf("") }
    var singleDocumentOutputFormat by remember { mutableStateOf(ScanOutputFormat.PDF) }
    var quality by remember { mutableIntStateOf(82) }
    var pageSize by remember { mutableStateOf(ScanPdfPageSize.AUTO) }
    var reviewPages by remember { mutableStateOf<List<ScanDraftPage>>(emptyList()) }
    var cameraPages by remember { mutableStateOf<List<ScanDraftPage>>(emptyList()) }
    // Alpha49: capture all BATCH pages first, then review/process them page by page.
    var multiPageCapturedPages by remember { mutableStateOf<List<ScanDraftPage>>(emptyList()) }
    var multiPageFinalizedPages by remember { mutableStateOf<List<ScanDraftPage>>(emptyList()) }
    var multiPageReviewIndex by remember { mutableIntStateOf(0) }
    var multiPageRetakeIndex by remember { mutableStateOf<Int?>(null) }
    var flowStage by remember { mutableStateOf(ScanUiStage.HUB) }
    var selectedMode by remember { mutableStateOf(ScanCaptureMode.DOCUMENT) }
    var singleDocumentRetakeIndex by remember { mutableStateOf<Int?>(null) }
    var professionalScanMode by remember { mutableStateOf(ScanCaptureMode.DOCUMENT) }
    var showSingleDocumentFallback by remember { mutableStateOf(false) }
    var singleDocumentFallbackBackup by remember { mutableStateOf<ScanDraftPage?>(null) }
    var boundsDetectionBusy by remember { mutableStateOf(false) }
    var boundsDetectionRequestId by remember { mutableLongStateOf(0L) }
    // Single-document corner adjustment owns its captured page explicitly.
    // Do not derive this stage from reviewPages; a transient empty review list used to bounce back to Camera.
    var singleDocumentBoundsPage by remember { mutableStateOf<ScanDraftPage?>(null) }
    var singleDocumentBoundsEnteredAtMs by remember { mutableLongStateOf(0L) }
    var documentProcessingRequestId by remember { mutableLongStateOf(0L) }
    var documentProcessingBusy by remember { mutableStateOf(false) }
    var documentDiagnostic by remember { mutableStateOf("Diagnostic: waiting") }
    var autoOpenEditorPageId by remember { mutableStateOf<Long?>(null) }
    val primaryScannerModes = remember {
        listOf(
            ScannerModeEntry(ScanCaptureMode.DOCUMENT, "scanner_stage1_document_desc"),
            ScannerModeEntry(ScanCaptureMode.BATCH, "scanner_stage1_batch_desc"),
            ScannerModeEntry(ScanCaptureMode.BOOK, "scanner_stage1_book_desc"),
            ScannerModeEntry(ScanCaptureMode.ID_CARD, "scanner_stage1_card_desc")
        )
    }
    val moreScannerModes = remember {
        listOf(
            ScannerModeEntry(ScanCaptureMode.RECEIPT, "scanner_stage1_receipt_desc"),
            ScannerModeEntry(ScanCaptureMode.DUPLEX, "scanner_stage1_duplex_desc"),
            ScannerModeEntry(ScanCaptureMode.TRANSLATION, "scanner_stage1_translation_desc")
        )
    }
    var showMoreScannerModes by remember { mutableStateOf(false) }
    var appendNextScan by remember { mutableStateOf(false) }
    var activeSaveJob by remember { mutableStateOf<Job?>(null) }
    var pendingSingleDocumentSavePage by remember { mutableStateOf<ScanDraftPage?>(null) }
    var showSingleDocumentSaveOptions by remember { mutableStateOf(false) }
    var showSaveFolderPicker by remember { mutableStateOf(false) }
    var showMissingCardSide by remember { mutableStateOf(false) }
    var selectedSaveDirectory by remember { mutableStateOf<File?>(null) }

    val singleDocumentOptions = remember {
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(1)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
    }
    val singleDocumentScanner = remember { GmsDocumentScanning.getClient(singleDocumentOptions) }

    val options = remember {
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(50)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
    }
    val scanner = remember { GmsDocumentScanning.getClient(options) }

    fun openSingleDocumentBounds(page: ScanDraftPage, cleanupExistingReview: Boolean = true) {
        val priorPages = if (cleanupExistingReview) reviewPages.filterNot { it.file.absolutePath == page.file.absolutePath } else emptyList()
        if (priorPages.isNotEmpty()) ScanProcessor.cleanup(priorPages)

        val seeded = if (selectedMode == ScanCaptureMode.DOCUMENT || selectedMode == ScanCaptureMode.BATCH) {
            page.copy(
                perspective = page.perspective ?: ScanQuad.insetDefault(.055f),
                filter = ScanFilter.DOCUMENT,
                brightness = 4,
                contrast = 1.08f,
                sharpness = .16f
            )
        } else {
            page.copy(perspective = ScanQuad.insetDefault(.055f))
        }
        reviewPages = listOf(seeded)
        if (selectedMode == ScanCaptureMode.DOCUMENT || selectedMode == ScanCaptureMode.BATCH) {
            singleDocumentBoundsPage = seeded
        }
        cameraPages = emptyList()
        boundsDetectionBusy = true
        boundsDetectionRequestId += 1L
        singleDocumentBoundsEnteredAtMs = android.os.SystemClock.elapsedRealtime()
        flowStage = ScanUiStage.BOUNDS
    }

    fun batchPageCount(): Int {
        val replacementPending = multiPageRetakeIndex?.let { it in multiPageCapturedPages.indices } == true
        return multiPageCapturedPages.size - if (replacementPending) 1 else 0
    }

    fun acceptBatchPages(pages: List<ScanDraftPage>) {
        val accepted = pages.take((selectedMode.maxPages - batchPageCount()).coerceAtLeast(0))
        if (accepted.isEmpty()) return
        val replaceIndex = multiPageRetakeIndex?.takeIf { it in multiPageCapturedPages.indices }
        if (replaceIndex == null) {
            multiPageCapturedPages = (multiPageCapturedPages + accepted).take(selectedMode.maxPages)
        } else {
            val replacement = accepted.first()
            if (!replacement.file.isFile || replacement.file.length() == 0L) return
            val previous = multiPageCapturedPages[replaceIndex]
            multiPageCapturedPages = multiPageCapturedPages.toMutableList().also { updated ->
                updated[replaceIndex] = replacement
                updated.addAll(accepted.drop(1))
            }
            multiPageFinalizedPages = multiPageFinalizedPages.filterNot { it.id == previous.id }
            // Retire only the replaced temporary image after its replacement is valid.
            // Gallery pages share a cache directory; deleting it would erase siblings.
            if (previous.file.absolutePath != replacement.file.absolutePath) runCatching { previous.file.delete() }
        }
        multiPageRetakeIndex = null
    }

    fun openBatchReview() {
        // Closing the camera without a replacement keeps the prior page intact.
        multiPageRetakeIndex = null
        val completedIds = multiPageFinalizedPages.map { it.id }.toSet()
        val nextIndex = multiPageCapturedPages.indexOfFirst { it.id !in completedIds }
        cameraPages = emptyList()
        reviewPages = emptyList()
        if (multiPageCapturedPages.isEmpty()) {
            flowStage = ScanUiStage.CAMERA
        } else if (nextIndex < 0) {
            reviewPages = multiPageCapturedPages
            flowStage = ScanUiStage.REVIEW
        } else {
            multiPageReviewIndex = nextIndex
            openSingleDocumentBounds(multiPageCapturedPages[nextIndex], cleanupExistingReview = false)
        }
    }


    fun openProcessedSingleDocumentReview(page: ScanDraftPage, cleanupExistingReview: Boolean = true) {
        documentProcessingRequestId += 1L
        val requestId = documentProcessingRequestId

        val priorPages = if (cleanupExistingReview) {
            reviewPages.filterNot { it.file.absolutePath == page.file.absolutePath }
        } else {
            emptyList()
        }
        if (priorPages.isNotEmpty()) ScanProcessor.cleanup(priorPages)

        // Preserve the captured file as immutable source. Auto processing only updates reversible metadata.
        val original = page.copy(
            rotation = 0,
            filter = ScanFilter.ORIGINAL,
            brightness = 0,
            contrast = 1f,
            sharpness = 0f,
            cropLeft = 0f,
            cropTop = 0f,
            cropRight = 0f,
            cropBottom = 0f,
            perspective = null
        )
        reviewPages = listOf(original)
        singleDocumentBoundsPage = null
        cameraPages = emptyList()
        documentProcessingBusy = true
        flowStage = ScanUiStage.PROCESSING

        scope.launch {
            val detection = withContext(Dispatchers.IO) {
                val bitmap = runCatching { ScanProcessor.renderSourcePreview(original, 1920) }.getOrNull()
                if (bitmap == null) {
                    null
                } else {
                    try {
                        runCatching { StaticDocumentDetector.detect(bitmap) }.getOrNull()
                    } finally {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }
            }

            if (documentProcessingRequestId != requestId) return@launch

            documentDiagnostic = if (detection == null) {
                "Diagnostic: detector returned null"
            } else {
                "OpenCV=${if (detection.openCvLoaded) "LOADED" else "FAILED"} | source=${detection.source} | contours=${detection.contourCount} | candidates=${detection.candidateCount} | score=${(detection.bestScore * 100).toInt()}% | reason=${detection.reason ?: "accepted"}"
            }
            val detectedQuad = detection?.quad
            val enhanced = original.copy(
                perspective = detectedQuad,
                filter = ScanFilter.DOCUMENT,
                brightness = 4,
                contrast = 1.08f,
                sharpness = .16f
            )
            reviewPages = listOf(enhanced)
            documentProcessingBusy = false

            if (detectedQuad != null) {
                // High-confidence path: show the already corrected/enhanced result directly.
                flowStage = ScanUiStage.REVIEW
            } else {
                // Safe fallback: never guess a crop. Let the user place the four corners manually.
                singleDocumentBoundsPage = enhanced.copy(
                    perspective = ScanQuad.insetDefault(.055f)
                )
                boundsDetectionBusy = false
                boundsDetectionRequestId += 1L
                singleDocumentBoundsEnteredAtMs = android.os.SystemClock.elapsedRealtime()
                flowStage = ScanUiStage.BOUNDS
            }
        }
    }


    fun commitSingleDocumentBounds(page: ScanDraftPage) {
        boundsDetectionRequestId += 1L
        boundsDetectionBusy = false
        val adjusted = (singleDocumentBoundsPage ?: page).let { current ->
            if (current.perspective == null) current.copy(perspective = ScanQuad.insetDefault(.055f)) else current
        }

        documentProcessingRequestId += 1L
        val requestId = documentProcessingRequestId
        documentProcessingBusy = true
        reviewPages = listOf(adjusted)
        flowStage = ScanUiStage.PROCESSING

        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    ScanProcessor.commitPerspectiveCrop(context, adjusted)
                }
            }
            if (documentProcessingRequestId != requestId) return@launch

            result.onSuccess { flattened ->
                if (selectedMode == ScanCaptureMode.BATCH) {
                    // Perspective commit replaces the cache file; retain its new reference.
                    multiPageCapturedPages = multiPageCapturedPages.map { current ->
                        if (current.id == flattened.id) flattened else current
                    }
                }
                reviewPages = listOf(flattened)
                singleDocumentBoundsPage = null
                autoOpenEditorPageId = null
                documentProcessingBusy = false
                flowStage = ScanUiStage.EDITOR
            }.onFailure { error ->
                // Keep the approved corners intact so a processing failure never loses the capture.
                singleDocumentBoundsPage = adjusted
                reviewPages = listOf(adjusted)
                documentProcessingBusy = false
                flowStage = ScanUiStage.BOUNDS
                Toast.makeText(
                    context,
                    error.message ?: context.uiText(language, "scan_screen_005"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun cancelDocumentProcessing() {
        // P9: processing is cancellable. Invalidate the in-flight result and return
        // to the captured page instead of trapping the user behind a spinner.
        documentProcessingRequestId += 1L
        documentProcessingBusy = false
        val current = reviewPages.firstOrNull()
        if (current != null && (selectedMode == ScanCaptureMode.DOCUMENT || selectedMode == ScanCaptureMode.BATCH)) {
            val recoverable = current.copy(
                perspective = current.perspective ?: ScanQuad.insetDefault(.055f)
            )
            reviewPages = listOf(recoverable)
            singleDocumentBoundsPage = recoverable
            boundsDetectionBusy = false
            boundsDetectionRequestId += 1L
            singleDocumentBoundsEnteredAtMs = android.os.SystemClock.elapsedRealtime()
            flowStage = ScanUiStage.BOUNDS
        } else if (current != null) {
            flowStage = ScanUiStage.REVIEW
        } else {
            flowStage = ScanUiStage.CAMERA
        }
    }

    fun leaveBoundsForCamera(deleteCurrent: Boolean) {
        if ((selectedMode == ScanCaptureMode.DOCUMENT || selectedMode == ScanCaptureMode.BATCH) && singleDocumentBoundsPage != null) {
            val elapsed = android.os.SystemClock.elapsedRealtime() - singleDocumentBoundsEnteredAtMs
            if (elapsed in 0L..699L) return
        }
        boundsDetectionRequestId += 1L
        boundsDetectionBusy = false
        if (deleteCurrent) {
            val pagesToDelete = (reviewPages + listOfNotNull(singleDocumentBoundsPage))
                .distinctBy { it.file.absolutePath }
            if (selectedMode == ScanCaptureMode.BATCH) {
                val current = singleDocumentBoundsPage ?: reviewPages.firstOrNull()
                multiPageRetakeIndex = current?.let { page ->
                    multiPageCapturedPages.indexOfFirst { it.id == page.id }.takeIf { it >= 0 }
                }
            } else {
                ScanProcessor.cleanup(pagesToDelete)
            }
        }
        singleDocumentBoundsPage = null
        reviewPages = emptyList()
        cameraPages = emptyList()
        flowStage = ScanUiStage.CAMERA
    }

    fun restoreSingleDocumentFallbackBackup() {
        val backup = singleDocumentFallbackBackup ?: return
        if (reviewPages.isEmpty() && cameraPages.isEmpty()) {
            reviewPages = listOf(backup)
        }
        singleDocumentFallbackBackup = null
    }

    fun discardSingleDocumentFallbackBackup() {
        singleDocumentFallbackBackup?.let { oldPage -> runCatching { oldPage.file.delete() } }
        singleDocumentFallbackBackup = null
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selected ->
        if (selected.isNotEmpty()) {
            scope.launch {
                busy = true
                try {
                    val currentCount = if (selectedMode == ScanCaptureMode.BATCH) {
                        batchPageCount()
                    } else {
                        reviewPages.size + cameraPages.size
                    }
                    val remaining = (selectedMode.maxPages - currentCount).coerceAtLeast(0)
                    val chosen = selected.take(remaining)
                    val cached = withContext(Dispatchers.IO) {
                        ScanProcessor.cacheScannerPages(context, chosen).map { it.copy(filter = selectedMode.defaultFilter) }
                    }
                    if (cached.isEmpty()) {
                        Toast.makeText(context, context.uiText(language, "scan_screen_002"), Toast.LENGTH_LONG).show()
                    } else if (flowStage == ScanUiStage.CAMERA) {
                        val nextCameraPages = cameraPages + cached
                        val total = reviewPages.size + nextCameraPages.size
                        if (selectedMode == ScanCaptureMode.BATCH) {
                            acceptBatchPages(cached)
                            val nextBatchPages = multiPageCapturedPages
                            cameraPages = emptyList()
                            reviewPages = emptyList()
                            if (nextBatchPages.size >= selectedMode.maxPages) {
                                multiPageFinalizedPages = nextBatchPages
                                reviewPages = nextBatchPages
                                flowStage = ScanUiStage.REVIEW
                            }
                        } else if (selectedMode == ScanCaptureMode.DOCUMENT && nextCameraPages.isNotEmpty()) {
                            nextCameraPages.drop(1).forEach { runCatching { it.file.delete() } }
                            openProcessedSingleDocumentReview(nextCameraPages.first())
                            discardSingleDocumentFallbackBackup()
                        } else if (selectedMode == ScanCaptureMode.TRANSLATION && nextCameraPages.isNotEmpty()) {
                            nextCameraPages.drop(1).forEach { runCatching { it.file.delete() } }
                            openSingleDocumentBounds(nextCameraPages.first())
                        } else if (total >= selectedMode.maxPages) {
                            reviewPages = reviewPages + nextCameraPages
                            cameraPages = emptyList()
                            flowStage = ScanUiStage.REVIEW
                        } else {
                            cameraPages = nextCameraPages
                        }
                        lastResult = context.uiText(language, "scan_screen_003", cached.size)
                    } else {
                        ScanProcessor.cleanup(reviewPages)
                        reviewPages = cached
                        flowStage = ScanUiStage.REVIEW
                        lastResult = context.uiText(language, "scan_screen_004", cached.size)
                    }
                } catch (error: Throwable) {
                    Toast.makeText(
                        context,
                        error.message?.takeIf { it.isNotBlank() }
                            ?: context.localizedOperationFailure(language, error, "scan_screen_002"),
                        Toast.LENGTH_LONG
                    ).show()
                } finally {
                    busy = false
                }
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result: ActivityResult ->
        busy = false
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pages = scanResult?.pages.orEmpty()
            val pdfUri = scanResult?.pdf?.uri

            if (pages.isNotEmpty()) {
                scope.launch {
                    busy = true
                    try {
                        val cached = withContext(Dispatchers.IO) {
                            ScanProcessor.cacheScannerPages(context, pages.map { it.imageUri })
                                .map { it.copy(filter = selectedMode.defaultFilter) }
                        }
                        if (cached.isEmpty()) {
                            Toast.makeText(context, context.uiText(language, "scan_screen_005"), Toast.LENGTH_LONG).show()
                        } else {
                            val nextReviewPages = if (appendNextScan) reviewPages + cached else {
                                ScanProcessor.cleanup(reviewPages)
                                cached
                            }
                            reviewPages = nextReviewPages
                            flowStage = ScanUiStage.REVIEW
                            lastResult = context.uiText(language, "scan_screen_006", nextReviewPages.size)
                        }
                    } catch (error: Throwable) {
                        Toast.makeText(
                            context,
                            error.message?.takeIf { it.isNotBlank() }
                                ?: context.localizedOperationFailure(language, error, "scan_screen_005"),
                            Toast.LENGTH_LONG
                        ).show()
                    } finally {
                        appendNextScan = false
                        busy = false
                    }
                }
            } else if (pdfUri != null) {
                scope.launch {
                    busy = true
                    val saved = withContext(Dispatchers.IO) {
                        FileUtils.copyUriToOutput(
                            context,
                            pdfUri,
                            "scans",
                            "${safeBaseName(customName)}_${System.currentTimeMillis()}.pdf"
                        )
                    }
                    busy = false
                    lastResult = context.uiText(language, "scan_screen_007", saved.name)
                    if (openAfterSave) onOpenPdf(saved)
                }
            }
        } else {
            appendNextScan = false
        }
    }

    val singleDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result: ActivityResult ->
        if (result.resultCode != Activity.RESULT_OK) {
            busy = false
            flowStage = if (reviewPages.isNotEmpty()) ScanUiStage.REVIEW else ScanUiStage.HUB
            singleDocumentRetakeIndex = null
            return@rememberLauncherForActivityResult
        }

        val pageUri = GmsDocumentScanningResult
            .fromActivityResultIntent(result.data)
            ?.pages
            ?.firstOrNull()
            ?.imageUri

        if (pageUri == null) {
            busy = false
            flowStage = if (reviewPages.isNotEmpty()) ScanUiStage.REVIEW else ScanUiStage.HUB
            singleDocumentRetakeIndex = null
            Toast.makeText(context, context.uiText(language, "scan_screen_005"), Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }

        val requestedRetakeIndex = singleDocumentRetakeIndex
        scope.launch {
            busy = true
            try {
                val cached = withContext(Dispatchers.IO) {
                    ScanProcessor.cacheScannerPages(context, listOf(pageUri))
                        .map { it.copy(filter = professionalScanMode.defaultFilter) }
                }
                val page = cached.firstOrNull()
                if (page == null) {
                    Toast.makeText(context, context.uiText(language, "scan_screen_005"), Toast.LENGTH_LONG).show()
                } else if (requestedRetakeIndex != null && requestedRetakeIndex in reviewPages.indices) {
                    val oldPage = reviewPages[requestedRetakeIndex]
                    runCatching { oldPage.file.delete() }
                    reviewPages = emptyList()
                    if (professionalScanMode == ScanCaptureMode.DOCUMENT) {
                        openProcessedSingleDocumentReview(page, cleanupExistingReview = false)
                    } else {
                        openSingleDocumentBounds(page, cleanupExistingReview = false)
                    }
                    lastResult = context.uiText(language, "scan_screen_006", 1)
                } else {
                    if (professionalScanMode == ScanCaptureMode.DOCUMENT) {
                        openProcessedSingleDocumentReview(page)
                    } else {
                        openSingleDocumentBounds(page)
                    }
                    lastResult = context.uiText(language, "scan_screen_006", 1)
                }
            } catch (error: Throwable) {
                flowStage = if (reviewPages.isNotEmpty()) ScanUiStage.REVIEW else ScanUiStage.HUB
                Toast.makeText(
                    context,
                    error.message?.takeIf { it.isNotBlank() }
                        ?: context.localizedOperationFailure(language, error, "scan_screen_005"),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                singleDocumentRetakeIndex = null
                busy = false
            }
        }
    }

    fun launchProfessionalSinglePageScanner(mode: ScanCaptureMode, retakeIndex: Int? = null) {
        if (busy) return
        require(mode == ScanCaptureMode.DOCUMENT || mode == ScanCaptureMode.TRANSLATION) { "professional_single_page_mode_required" }
        selectedMode = mode
        professionalScanMode = mode
        cameraPages = emptyList()
        flowStage = if (retakeIndex != null && reviewPages.isNotEmpty()) ScanUiStage.REVIEW else ScanUiStage.HUB
        singleDocumentRetakeIndex = retakeIndex
        lastResult = context.uiText(language, "scan_screen_001")

        if (activity == null) {
            showSingleDocumentFallback = true
            return
        }

        busy = true
        singleDocumentScanner.getStartScanIntent(activity)
            .addOnSuccessListener { intentSender ->
                showSingleDocumentFallback = false
                singleDocumentLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener {
                busy = false
                showSingleDocumentFallback = true
            }
    }

    fun launchSingleDocumentScanner(retakeIndex: Int? = null) =
        launchProfessionalSinglePageScanner(ScanCaptureMode.DOCUMENT, retakeIndex)

    fun startLegacyScan(append: Boolean = false) {
        if (activity == null) {
            Toast.makeText(context, context.uiText(language, "scan_screen_008"), Toast.LENGTH_SHORT).show()
            return
        }
        appendNextScan = append
        busy = true
        scanner.getStartScanIntent(activity)
            .addOnSuccessListener { intentSender ->
                launcher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener {
                busy = false
                appendNextScan = false
                Toast.makeText(context, context.uiText(language, "scan_screen_009"), Toast.LENGTH_LONG).show()
            }
    }

    fun saveReviewedScan(
        pagesOverride: List<ScanDraftPage>? = null,
        returnToEditorOnFailure: Boolean = false,
        destinationDir: File? = null,
        outputFormatOverride: ScanOutputFormat? = null
    ) {
        if (busy || activeSaveJob?.isActive == true) return
        val pagesSnapshot = pagesOverride ?: reviewPages
        if (pagesSnapshot.isEmpty()) return
        if (selectedMode == ScanCaptureMode.ID_CARD && pagesSnapshot.size < 2) {
            showMissingCardSide = true
            return
        }
        // Alpha42: snapshot the requested format at click time. Compose state updates are asynchronous;
        // reading singleDocumentOutputFormat later could still see the previous PDF value after JPG was tapped.
        val requestedOutputFormat = outputFormatOverride ?: singleDocumentOutputFormat
        // Alpha43: snapshot the capture mode too. The save coroutine must not depend on
        // recomposed UI state after the user presses Save Now.
        val requestedMode = selectedMode
        reviewPages = pagesSnapshot
        busy = true
        flowStage = ScanUiStage.SAVING
        activeSaveJob = scope.launch {
            try {
                if (requestedMode == ScanCaptureMode.TRANSLATION) {
                    val translated = TranslationProcessor.translateReviewedPage(
                        context = context,
                        page = pagesSnapshot.first(),
                        quality = quality
                    )
                    require(translated.file.exists() && translated.file.length() > 0L) { "scan_save_output_invalid" }
                    translationResult = translated
                    lastSavedFile = translated.file
                    flowStage = ScanUiStage.SUCCESS
                    ScanProcessor.cleanup(pagesSnapshot)
                    reviewPages = emptyList()
                    lastResult = translated.file.name
                } else {
                    val saved = withContext(Dispatchers.IO) {
                        if (requestedMode == ScanCaptureMode.ID_CARD && pagesSnapshot.size >= 2) {
                            val front = ScanProcessor.exportEditedPageToCache(context, pagesSnapshot[0], quality)
                            val back = ScanProcessor.exportEditedPageToCache(context, pagesSnapshot[1], quality)
                            try {
                                CardScanProcessor.saveCombinedPdf(
                                    context = context,
                                    front = front,
                                    back = back,
                                    layout = CardPageLayout.VERTICAL,
                                    baseName = "Card"
                                )
                            } finally {
                                runCatching { front.parentFile?.deleteRecursively() }
                                runCatching { back.parentFile?.deleteRecursively() }
                            }
                        } else if (
                            requestedMode == ScanCaptureMode.DOCUMENT &&
                            pagesSnapshot.size == 1 &&
                            requestedOutputFormat == ScanOutputFormat.JPG
                        ) {
                            ScanProcessor.saveSinglePageJpeg(
                                context = context,
                                page = pagesSnapshot.first(),
                                baseName = safeBaseName(customName),
                                quality = quality,
                                destinationDir = destinationDir
                            )
                        } else if (
                            requestedMode == ScanCaptureMode.BATCH &&
                            requestedOutputFormat == ScanOutputFormat.JPG
                        ) {
                            val savedImages = pagesSnapshot.mapIndexed { index, page ->
                                ScanProcessor.saveSinglePageJpeg(
                                    context = context,
                                    page = page,
                                    baseName = "${safeBaseName(customName)}_${index + 1}",
                                    quality = quality,
                                    destinationDir = destinationDir
                                )
                            }
                            require(savedImages.isNotEmpty()) { "scan_save_output_invalid" }
                            savedImages.forEach { image ->
                                require(image.exists() && image.length() > 0L) { "scan_save_output_invalid" }
                            }
                            savedImages.first()
                        } else {
                            ScanProcessor.createPdf(
                                context,
                                pagesSnapshot,
                                safeBaseName(customName),
                                quality,
                                pageSize,
                                preserveSourceDetail = requestedMode == ScanCaptureMode.DOCUMENT,
                                destinationDir = destinationDir
                            )
                        }
                    }
                    val verifiedSaved = FileUtils.confirmSavedFile(context, saved)
                    lastSavedFile = verifiedSaved
                    saveSuccessFile = verifiedSaved
                    flowStage = ScanUiStage.SUCCESS
                    pendingSingleDocumentSavePage = null
                    showSingleDocumentSaveOptions = false
                    ScanProcessor.cleanup(pagesSnapshot)
                    reviewPages = emptyList()
                    lastResult = context.uiText(language, "scan_screen_010", pagesSnapshot.size, verifiedSaved.name)
                    // P10 Batch flow fix: a multi-page scan must stay inside the scanner after
                    // a successful save. Opening the saved location here made BATCH behave like
                    // a single-document scan and kicked the user out of the multi-page flow.
                    if (requestedMode != ScanCaptureMode.BATCH) {
                        onOpenSavedLocation(verifiedSaved)
                    }
                }
            } catch (error: Throwable) {
                reviewPages = pagesSnapshot
                flowStage = if (returnToEditorOnFailure) ScanUiStage.EDITOR else ScanUiStage.REVIEW
                Toast.makeText(
                    context,
                    error.message?.takeIf { it.isNotBlank() } ?: context.localizedOperationFailure(language, error, "scan_screen_012"),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                busy = false
                activeSaveJob = null
            }
        }
    }

    val latestReviewPages by rememberUpdatedState(reviewPages)
    val latestCameraPages by rememberUpdatedState(cameraPages)
    val latestSingleDocumentBoundsPage by rememberUpdatedState(singleDocumentBoundsPage)
    val latestDocumentFallbackBackup by rememberUpdatedState(singleDocumentFallbackBackup)
    DisposableEffect(Unit) {
        onDispose {
            activeSaveJob?.cancel()
            val ownedPages = (latestReviewPages + latestCameraPages + listOfNotNull(latestSingleDocumentBoundsPage))
                .distinctBy { it.file.absolutePath }
            if (ownedPages.isNotEmpty()) ScanProcessor.cleanup(ownedPages)
            latestDocumentFallbackBackup?.file?.delete()
        }
    }

    if (showMissingCardSide) {
        AlertDialog(
            onDismissRequest = { showMissingCardSide = false },
            title = { Text(context.uiText(language, "p11_card_missing_side_title")) },
            text = { Text(context.uiText(language, "p11_card_missing_side_message")) },
            confirmButton = {
                TextButton(onClick = {
                    showMissingCardSide = false
                    cameraPages = reviewPages
                    reviewPages = emptyList()
                    flowStage = ScanUiStage.CAMERA
                }) { Text(context.uiText(language, "p11_card_capture_second_side")) }
            },
            dismissButton = {
                TextButton(onClick = { showMissingCardSide = false }) {
                    Text(context.uiText(language, "scan_screen_052"))
                }
            }
        )
    }

    if (showSingleDocumentFallback) {
        AlertDialog(
            onDismissRequest = {
                if (!busy) {
                    showSingleDocumentFallback = false
                    singleDocumentRetakeIndex = null
                }
            },
            title = { Text(context.uiText(language, "single_doc_scanner_unavailable_title")) },
            text = { Text(context.uiText(language, "single_doc_scanner_unavailable_message")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val retakeIndex = singleDocumentRetakeIndex
                        if (retakeIndex != null && retakeIndex in reviewPages.indices) {
                            singleDocumentFallbackBackup = reviewPages[retakeIndex]
                            reviewPages = reviewPages.toMutableList().also { it.removeAt(retakeIndex) }
                        }
                        singleDocumentRetakeIndex = null
                        showSingleDocumentFallback = false
                        selectedMode = professionalScanMode
                        cameraPages = emptyList()
                        flowStage = ScanUiStage.CAMERA
                    }
                ) {
                    Text(context.uiText(language, "single_doc_use_camera"))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showSingleDocumentFallback = false
                        singleDocumentRetakeIndex = null
                    }
                ) {
                    Text(context.uiText(language, "single_doc_cancel"))
                }
            }
        )
    }

    if (barcodeReaderOpen) {
        BarcodeScreen(onBack = { barcodeReaderOpen = false }, readerOnly = true)
        return
    }

    if (showSaveFolderPicker) {
        WorkspaceFolderPickerPane(
            initialDirectory = selectedSaveDirectory ?: runCatching { FileUtils.defaultSaveDirectory(context) }.getOrNull(),
            onCancel = {
                showSaveFolderPicker = false
                if (pendingSingleDocumentSavePage != null) {
                    showSingleDocumentSaveOptions = false
                    flowStage = ScanUiStage.SAVE_OPTIONS
                }
            },
            onFolderPicked = { folder ->
                runCatching { FileUtils.setDefaultSaveDirectory(context, folder) }
                    .onSuccess { selectedSaveDirectory = folder }
                    .onFailure {
                        Toast.makeText(context, it.message ?: context.uiText(language, "alpha37_workspace_unavailable"), Toast.LENGTH_LONG).show()
                    }
                showSaveFolderPicker = false
                if (pendingSingleDocumentSavePage != null) {
                    showSingleDocumentSaveOptions = false
                    flowStage = ScanUiStage.SAVE_OPTIONS
                }
            }
        )
        return
    }

    if (showSingleDocumentSaveOptions) {
        val pendingPage = pendingSingleDocumentSavePage
        if (pendingPage != null) {
            val persistentReady = DeviceStorageManager.hasAllFilesAccess()
            val currentSaveDirectory = selectedSaveDirectory
                ?.takeIf { it.isDirectory && runCatching { FileUtils.isInsideRoot(context, it) }.getOrDefault(false) }
                ?: runCatching { FileUtils.defaultSaveDirectory(context) }.getOrNull()
                ?: FileUtils.outputDir(context, "scans")

            ScanSaveOptionsDialog(
                initialName = customName.ifBlank { "Scan" },
                initialFormat = singleDocumentOutputFormat,
                saveDirectory = currentSaveDirectory,
                persistentStorageReady = persistentReady,
                onChangeLocation = {
                    if (!busy) {
                        showSingleDocumentSaveOptions = false
                        showSaveFolderPicker = true
                    }
                },
                onDismiss = {
                    if (!busy) {
                        showSingleDocumentSaveOptions = false
                        pendingSingleDocumentSavePage = null
                    }
                },
                onConfirm = { requestedName, requestedFormat ->
                    if (!busy) {
                        if (!DeviceStorageManager.hasAllFilesAccess()) {
                            showSingleDocumentSaveOptions = false
                            showSaveFolderPicker = true
                        } else {
                            val targetDirectory = selectedSaveDirectory
                                ?.takeIf { it.isDirectory && runCatching { FileUtils.isInsideRoot(context, it) }.getOrDefault(false) }
                                ?: WorkspaceStorageManager.ensureDefaultWorkspace(context)
                                ?: currentSaveDirectory
                            customName = normalizedDocumentName(requestedName)
                            singleDocumentOutputFormat = requestedFormat
                            selectedSaveDirectory = targetDirectory
                            showSingleDocumentSaveOptions = false
                            // Alpha40: keep the pending page alive until the save job has
                            // verified the output. Clearing it here could recompose SAVE_OPTIONS
                            // back to EDITOR before the save coroutine reaches SUCCESS.
                            saveReviewedScan(
                                pagesOverride = listOf(pendingPage),
                                returnToEditorOnFailure = true,
                                destinationDir = targetDirectory,
                                outputFormatOverride = requestedFormat
                            )
                        }
                    }
                }
            )
        } else {
            LaunchedEffect(showSingleDocumentSaveOptions) {
                showSingleDocumentSaveOptions = false
            }
        }
    }

    translationResult?.let { result ->
        ScanTranslationResultScreen(
            result = result,
            busy = translationBusy,
            onTargetChange = { target ->
                if (!translationBusy) {
                    translationBusy = true
                    scope.launch {
                        try {
                            val updated = TranslationProcessor.retranslate(context, result, target)
                            translationResult = updated
                            lastSavedFile = updated.file
                        } catch (error: Throwable) {
                            Toast.makeText(context, error.message ?: context.uiText(language, "scan_screen_012"), Toast.LENGTH_LONG).show()
                        } finally {
                            translationBusy = false
                        }
                    }
                }
            },
            onDone = {
                lastSavedFile = result.file
                saveSuccessFile = result.file
                translationResult = null
                flowStage = ScanUiStage.SUCCESS
            }
        )
        return
    }

    saveSuccessFile?.let { savedFile ->
        ScanSaveSuccessContent(
            file = savedFile,
            onOpenSaved = { file ->
                if (file.extension.equals("pdf", ignoreCase = true)) onOpenPdf(file)
                else runCatching { FileUtils.openFile(context, file) }
            },
            onShareSaved = { file -> runCatching { FileUtils.shareFile(context, file) } },
            onWhatsApp = { file -> runCatching { FileUtils.shareFileToWhatsApp(context, file) } },
            onMessenger = { file -> runCatching { shareToMessenger(context, file) }.onFailure { FileUtils.shareFile(context, file) } },
            onNewScan = {
                val restartBatch = selectedMode == ScanCaptureMode.BATCH
                saveSuccessFile = null
                if (!restartBatch) {
                    selectedMode = ScanCaptureMode.DOCUMENT
                    professionalScanMode = ScanCaptureMode.DOCUMENT
                }
                cameraPages = emptyList()
                reviewPages = emptyList()
                multiPageCapturedPages = emptyList()
                multiPageFinalizedPages = emptyList()
                multiPageReviewIndex = 0
                multiPageRetakeIndex = null
                documentProcessingRequestId += 1L
                documentProcessingBusy = false
                discardSingleDocumentFallbackBackup()
                singleDocumentRetakeIndex = null
                flowStage = ScanUiStage.CAMERA
            },
            onGoHome = {
                saveSuccessFile = null
                flowStage = ScanUiStage.HUB
            }
        )
        return
    }

    fun launchMode(mode: ScanCaptureMode) {
        selectedMode = mode
        cameraPages = emptyList()
        if (mode == ScanCaptureMode.BATCH) {
            multiPageCapturedPages = emptyList()
            multiPageFinalizedPages = emptyList()
            multiPageReviewIndex = 0
            multiPageRetakeIndex = null
            reviewPages = emptyList()
            singleDocumentBoundsPage = null
        }
        if (mode == ScanCaptureMode.DOCUMENT) {
            documentProcessingRequestId += 1L
            documentProcessingBusy = false
            discardSingleDocumentFallbackBackup()
            singleDocumentRetakeIndex = null
        }
        flowStage = ScanUiStage.CAMERA
        lastResult = context.uiText(language, "scan_screen_001")
    }

    when (flowStage) {
        ScanUiStage.CAMERA -> {
            // Fix3: a captured single-document bounds page has priority over the generic CAMERA stage.
            // This prevents any stale/late navigation callback from bouncing the user back to Camera
            // while the freshly captured page is loading its corner-adjustment preview.
            val pendingSingleBounds = if (selectedMode == ScanCaptureMode.DOCUMENT || selectedMode == ScanCaptureMode.BATCH) singleDocumentBoundsPage else null
            if (pendingSingleBounds != null) {
                SingleDocumentBoundsContent(
                    page = pendingSingleBounds,
                    detecting = boundsDetectionBusy,
                    onPageChange = { updated ->
                        // Fix4: keep touch edits isolated from generic review state.
                        boundsDetectionRequestId += 1L
                        boundsDetectionBusy = false
                        singleDocumentBoundsPage = updated
                    },
                    onDetectionFinished = { detectedQuad ->
                        val current = singleDocumentBoundsPage?.takeIf { it.id == pendingSingleBounds.id }
                        if (current != null && boundsDetectionBusy) {
                            val detectedPage = current.copy(
                                perspective = detectedQuad ?: current.perspective ?: ScanQuad.insetDefault(.055f)
                            )
                            reviewPages = listOf(detectedPage)
                            singleDocumentBoundsPage = detectedPage
                        }
                        boundsDetectionBusy = false
                    },
                    onContinue = {
                        commitSingleDocumentBounds(pendingSingleBounds)
                    },
                    onRetake = { leaveBoundsForCamera(deleteCurrent = true) },
                    onBack = { leaveBoundsForCamera(deleteCurrent = true) }
                )
                return
            }

            ScannerProCamera(
                mode = selectedMode,
                existingPageCount = if (selectedMode == ScanCaptureMode.BATCH) batchPageCount() else reviewPages.size + cameraPages.size,
                onPagesCaptured = { captured ->
                    val currentCount = if (selectedMode == ScanCaptureMode.BATCH) {
                        batchPageCount()
                    } else {
                        reviewPages.size + cameraPages.size
                    }
                    val remaining = (selectedMode.maxPages - currentCount).coerceAtLeast(0)
                    val accepted = captured.take(remaining)
                    if (accepted.isNotEmpty()) {
                        val nextCameraPages = cameraPages + accepted
                        val total = nextCameraPages.size + reviewPages.size
                        lastResult = context.uiText(language, "scan_screen_013", total)

                        // Alpha21: single-document capture stays simple in CameraX.
                        // Detection, perspective correction and enhancement run only after the still image exists.
                        if (selectedMode == ScanCaptureMode.DOCUMENT) {
                            val capturedPage = nextCameraPages.first()
                            openSingleDocumentBounds(capturedPage)
                            discardSingleDocumentFallbackBackup()
                        } else if (selectedMode == ScanCaptureMode.BATCH) {
                            acceptBatchPages(nextCameraPages)
                            cameraPages = emptyList()
                            reviewPages = emptyList()
                            flowStage = ScanUiStage.CAMERA
                        } else if (selectedMode == ScanCaptureMode.TRANSLATION) {
                            val capturedPage = nextCameraPages.first()
                            openSingleDocumentBounds(capturedPage)
                            discardSingleDocumentFallbackBackup()
                        } else if (total >= selectedMode.maxPages) {
                            reviewPages = reviewPages + nextCameraPages
                            cameraPages = emptyList()
                            flowStage = ScanUiStage.REVIEW
                        } else {
                            cameraPages = nextCameraPages
                        }
                    }
                },
                onImportRequested = { if (!busy) galleryLauncher.launch("image/*") },
                onFallbackScanner = {
                    flowStage = ScanUiStage.HUB
                    startLegacyScan(reviewPages.isNotEmpty())
                },
                onReviewRequested = {
                    val pending = cameraPages
                    when {
                        pending.isNotEmpty() -> {
                            if (selectedMode == ScanCaptureMode.DOCUMENT) {
                                openSingleDocumentBounds(pending.first())
                                discardSingleDocumentFallbackBackup()
                            } else if (selectedMode == ScanCaptureMode.BATCH) {
                                acceptBatchPages(pending)
                                openBatchReview()
                            } else if (selectedMode == ScanCaptureMode.TRANSLATION) {
                                openSingleDocumentBounds(pending.first())
                                discardSingleDocumentFallbackBackup()
                            } else {
                                reviewPages = reviewPages + pending
                                cameraPages = emptyList()
                                flowStage = ScanUiStage.REVIEW
                            }
                        }
                        selectedMode == ScanCaptureMode.BATCH && multiPageCapturedPages.isNotEmpty() -> {
                            openBatchReview()
                        }
                        reviewPages.isNotEmpty() -> flowStage = if (selectedMode == ScanCaptureMode.TRANSLATION) ScanUiStage.BOUNDS else ScanUiStage.REVIEW
                        singleDocumentFallbackBackup != null -> {
                            restoreSingleDocumentFallbackBackup()
                            flowStage = if (selectedMode == ScanCaptureMode.TRANSLATION) ScanUiStage.BOUNDS else ScanUiStage.REVIEW
                        }
                        else -> flowStage = ScanUiStage.HUB
                    }
                },
                onClose = {
                    val pending = cameraPages
                    when {
                        selectedMode == ScanCaptureMode.DOCUMENT && documentProcessingBusy -> {
                            flowStage = ScanUiStage.PROCESSING
                        }
                        pending.isNotEmpty() -> {
                            if (selectedMode == ScanCaptureMode.DOCUMENT) {
                                openSingleDocumentBounds(pending.first())
                                discardSingleDocumentFallbackBackup()
                            } else if (selectedMode == ScanCaptureMode.BATCH) {
                                acceptBatchPages(pending)
                                openBatchReview()
                            } else if (selectedMode == ScanCaptureMode.TRANSLATION) {
                                openSingleDocumentBounds(pending.first())
                                discardSingleDocumentFallbackBackup()
                            } else {
                                reviewPages = reviewPages + pending
                                cameraPages = emptyList()
                                flowStage = ScanUiStage.REVIEW
                            }
                        }
                        selectedMode == ScanCaptureMode.BATCH && multiPageCapturedPages.isNotEmpty() -> {
                            openBatchReview()
                        }
                        reviewPages.isNotEmpty() -> flowStage = if (selectedMode == ScanCaptureMode.TRANSLATION) ScanUiStage.BOUNDS else ScanUiStage.REVIEW
                        singleDocumentFallbackBackup != null -> {
                            restoreSingleDocumentFallbackBackup()
                            flowStage = if (selectedMode == ScanCaptureMode.TRANSLATION) ScanUiStage.BOUNDS else ScanUiStage.REVIEW
                        }
                        else -> flowStage = ScanUiStage.HUB
                    }
                }
            )
            return
        }

        ScanUiStage.PROCESSING -> {
            BackHandler(enabled = true) { if (!busy) cancelDocumentProcessing() }
            Scaffold { padding ->
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            context.uiText(language, "alpha23_processing_capture"),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            context.uiText(language, "alpha23_processing_capture_hint"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = { cancelDocumentProcessing() },
                            enabled = !busy
                        ) {
                            Text(context.uiText(language, "p9_scanner_cancel_processing"))
                        }
                    }
                }
            }
            return
        }

        ScanUiStage.BOUNDS -> {
            val page = if (selectedMode == ScanCaptureMode.DOCUMENT || selectedMode == ScanCaptureMode.BATCH) {
                singleDocumentBoundsPage ?: reviewPages.firstOrNull()
            } else {
                reviewPages.firstOrNull()
            }
            if (page == null) {
                // Never silently bounce a captured single document back to Camera.
                // The Camera transition is explicit via Retake/Back only.
                if (selectedMode != ScanCaptureMode.DOCUMENT) {
                    LaunchedEffect(flowStage) { flowStage = ScanUiStage.CAMERA }
                }
                return
            }

            SingleDocumentBoundsContent(
                page = page,
                detecting = boundsDetectionBusy,
                onPageChange = { updated ->
                    // Fix4: corner dragging mutates only the dedicated bounds-session page.
                    // Do not touch generic review/navigation state while a finger is on the editor.
                    boundsDetectionRequestId += 1L
                    boundsDetectionBusy = false
                    if (selectedMode == ScanCaptureMode.DOCUMENT || selectedMode == ScanCaptureMode.BATCH) {
                        singleDocumentBoundsPage = updated
                    } else {
                        reviewPages = listOf(updated)
                    }
                },
                onDetectionFinished = { detectedQuad ->
                    val current = if (selectedMode == ScanCaptureMode.DOCUMENT || selectedMode == ScanCaptureMode.BATCH) {
                        singleDocumentBoundsPage?.takeIf { it.id == page.id }
                    } else {
                        reviewPages.firstOrNull { it.id == page.id }
                    }
                    if (current != null && boundsDetectionBusy) {
                        val detectedPage = current.copy(
                            perspective = detectedQuad ?: current.perspective ?: ScanQuad.insetDefault(.055f)
                        )
                        reviewPages = listOf(detectedPage)
                        if (selectedMode == ScanCaptureMode.DOCUMENT || selectedMode == ScanCaptureMode.BATCH) {
                            singleDocumentBoundsPage = detectedPage
                        }
                    }
                    boundsDetectionBusy = false
                },
                onContinue = {
                    if (selectedMode == ScanCaptureMode.DOCUMENT || selectedMode == ScanCaptureMode.BATCH) {
                        val adjusted = singleDocumentBoundsPage ?: page
                        // Transfer bounds ownership before processing; failure restores it safely.
                        singleDocumentBoundsPage = null
                        commitSingleDocumentBounds(adjusted)
                    } else {
                        boundsDetectionRequestId += 1L
                        boundsDetectionBusy = false
                        flowStage = ScanUiStage.REVIEW
                    }
                },
                onRetake = {
                    discardSingleDocumentFallbackBackup()
                    leaveBoundsForCamera(deleteCurrent = true)
                },
                onBack = {
                    discardSingleDocumentFallbackBackup()
                    leaveBoundsForCamera(deleteCurrent = true)
                }
            )
            return
        }

        ScanUiStage.EDITOR -> {
            val page = reviewPages.firstOrNull()
            if (page == null) {
                LaunchedEffect(flowStage) {
                    flowStage = if (singleDocumentBoundsPage != null) ScanUiStage.BOUNDS else ScanUiStage.CAMERA
                }
                return
            }

            // Alpha32: direct state-machine handoff. The editor is now a real stage,
            // not a one-shot side effect inside a review card. This makes
            // BOUNDS -> PROCESSING -> EDITOR deterministic on-device.
            Box(modifier = Modifier.fillMaxSize()) {
                ScanPageAdjustDialog(
                    page = page,
                    busy = busy,
                    onDismiss = {
                        if (!busy) flowStage = if (selectedMode == ScanCaptureMode.BATCH) ScanUiStage.BOUNDS else ScanUiStage.REVIEW
                    },
                    onApply = { updated ->
                        reviewPages = listOf(updated)
                        flowStage = if (selectedMode == ScanCaptureMode.BATCH) ScanUiStage.BOUNDS else ScanUiStage.REVIEW
                    },
                    onSaveAndContinue = { updated ->
                        if (!busy) {
                            if (selectedMode == ScanCaptureMode.BATCH) {
                                multiPageCapturedPages = multiPageCapturedPages.map { current ->
                                    if (current.id == updated.id) updated else current
                                }
                                val completedIds = multiPageFinalizedPages.map { it.id }.toSet() + updated.id
                                multiPageFinalizedPages = multiPageCapturedPages.filter { it.id in completedIds }
                                reviewPages = emptyList()
                                singleDocumentBoundsPage = null
                                cameraPages = emptyList()
                                openBatchReview()
                            } else {
                                reviewPages = listOf(updated)
                                pendingSingleDocumentSavePage = updated
                                if (customName.isBlank()) customName = "Scan"
                                showSingleDocumentSaveOptions = false
                                flowStage = ScanUiStage.SAVE_OPTIONS
                            }
                        }
                    }
                )
            }
            return
        }

        ScanUiStage.SAVE_OPTIONS -> {
            val pendingPage = pendingSingleDocumentSavePage
            if (pendingPage == null) {
                LaunchedEffect(flowStage) { flowStage = ScanUiStage.EDITOR }
                return
            }

            val persistentReady = DeviceStorageManager.hasAllFilesAccess()
            val currentSaveDirectory = selectedSaveDirectory
                ?.takeIf { it.isDirectory && runCatching { FileUtils.isInsideRoot(context, it) }.getOrDefault(false) }
                ?: runCatching { FileUtils.defaultSaveDirectory(context) }.getOrNull()
                ?: FileUtils.outputDir(context, "scans")

            ScanSaveOptionsDialog(
                initialName = customName.ifBlank { "Scan" },
                initialFormat = singleDocumentOutputFormat,
                saveDirectory = currentSaveDirectory,
                persistentStorageReady = persistentReady,
                onChangeLocation = {
                    if (!busy) showSaveFolderPicker = true
                },
                onDismiss = {
                    if (!busy) {
                        pendingSingleDocumentSavePage = null
                        flowStage = ScanUiStage.EDITOR
                    }
                },
                onConfirm = { requestedName, requestedFormat ->
                    if (!busy) {
                        if (!DeviceStorageManager.hasAllFilesAccess()) {
                            showSaveFolderPicker = true
                        } else {
                            val targetDirectory = selectedSaveDirectory
                                ?.takeIf { it.isDirectory && runCatching { FileUtils.isInsideRoot(context, it) }.getOrDefault(false) }
                                ?: WorkspaceStorageManager.ensureDefaultWorkspace(context)
                                ?: currentSaveDirectory
                            customName = normalizedDocumentName(requestedName)
                            singleDocumentOutputFormat = requestedFormat
                            selectedSaveDirectory = targetDirectory
                            // Alpha40: the successful save path clears pendingSingleDocumentSavePage
                            // only after FileUtils.confirmSavedFile() verifies the real output.
                            saveReviewedScan(
                                pagesOverride = listOf(pendingPage),
                                returnToEditorOnFailure = true,
                                destinationDir = targetDirectory,
                                outputFormatOverride = requestedFormat
                            )
                        }
                    }
                }
            )
            return
        }

        ScanUiStage.REVIEW, ScanUiStage.SAVING -> {
            if (reviewPages.isEmpty()) {
                LaunchedEffect(flowStage) {
                    if (flowStage != ScanUiStage.SUCCESS) flowStage = ScanUiStage.CAMERA
                }
                return
            }

            ScanReviewContent(
                pages = reviewPages,
                quality = quality,
                pageSize = pageSize,
                maxPages = selectedMode.maxPages,
                singleDocument = selectedMode == ScanCaptureMode.DOCUMENT || selectedMode == ScanCaptureMode.BATCH,
                fileName = customName,
                outputFormat = singleDocumentOutputFormat,
                primaryActionKey = if (selectedMode == ScanCaptureMode.TRANSLATION) "alpha11_translate_action" else "scan_screen_036",
                busy = busy || documentProcessingBusy || flowStage == ScanUiStage.SAVING,
                autoOpenEditorPageId = autoOpenEditorPageId,
                onAutoEditorConsumed = { pageId ->
                    if (autoOpenEditorPageId == pageId) autoOpenEditorPageId = null
                },
                onFileNameChange = { customName = it },
                onOutputFormatChange = { singleDocumentOutputFormat = it },
                onQualityChange = { quality = it },
                onPageSizeChange = { pageSize = it },
                onPagesChange = { updated ->
                    reviewPages = updated
                    if (selectedMode == ScanCaptureMode.BATCH) {
                        multiPageCapturedPages = updated
                        multiPageFinalizedPages = updated
                        multiPageRetakeIndex = null
                    }
                },
                onRetakePage = { index ->
                    val page = reviewPages.getOrNull(index)
                    if (page != null) {
                        if (selectedMode == ScanCaptureMode.BATCH) {
                            // Keep the prior draft as a backup until a new capture replaces it.
                            multiPageCapturedPages = reviewPages
                            multiPageFinalizedPages = reviewPages
                            multiPageRetakeIndex = index
                        } else if (selectedMode == ScanCaptureMode.DOCUMENT) {
                            documentProcessingRequestId += 1L
                            documentProcessingBusy = false
                            singleDocumentFallbackBackup = page
                        } else if (selectedMode == ScanCaptureMode.TRANSLATION) {
                            singleDocumentFallbackBackup = page
                        } else {
                            runCatching { page.file.delete() }
                        }
                        reviewPages = if (selectedMode == ScanCaptureMode.BATCH) {
                            emptyList()
                        } else {
                            reviewPages.toMutableList().also { it.removeAt(index) }
                        }
                        cameraPages = emptyList()
                        flowStage = ScanUiStage.CAMERA
                    }
                },
                onEditBounds = { index ->
                    val page = reviewPages.getOrNull(index)
                    if ((selectedMode == ScanCaptureMode.DOCUMENT || selectedMode == ScanCaptureMode.BATCH) && page != null) {
                        if (selectedMode == ScanCaptureMode.BATCH) {
                            multiPageCapturedPages = reviewPages
                            multiPageFinalizedPages = reviewPages.filterNot { it.id == page.id }
                            multiPageReviewIndex = index
                            reviewPages = listOf(page)
                        }
                        documentProcessingRequestId += 1L
                        documentProcessingBusy = false
                        singleDocumentBoundsPage = page.copy(
                            perspective = page.perspective ?: ScanQuad.insetDefault(.055f)
                        )
                        boundsDetectionBusy = false
                        boundsDetectionRequestId += 1L
                        singleDocumentBoundsEnteredAtMs = android.os.SystemClock.elapsedRealtime()
                        flowStage = ScanUiStage.BOUNDS
                    }
                },
                onAddPages = {
                    if (!busy && reviewPages.size < selectedMode.maxPages) {
                        if (selectedMode == ScanCaptureMode.BATCH) {
                            multiPageCapturedPages = reviewPages
                            multiPageFinalizedPages = reviewPages
                            multiPageRetakeIndex = null
                            reviewPages = emptyList()
                        }
                        cameraPages = emptyList()
                        flowStage = ScanUiStage.CAMERA
                    }
                },
                onCancel = {
                    if (selectedMode == ScanCaptureMode.DOCUMENT) {
                        documentProcessingRequestId += 1L
                        documentProcessingBusy = false
                        discardSingleDocumentFallbackBackup()
                    }
                    ScanProcessor.cleanup((reviewPages + multiPageFinalizedPages + multiPageCapturedPages).distinctBy { it.file.absolutePath })
                    reviewPages = emptyList()
                    multiPageCapturedPages = emptyList()
                    multiPageFinalizedPages = emptyList()
                    multiPageReviewIndex = 0
                    multiPageRetakeIndex = null
                    cameraPages = emptyList()
                    flowStage = ScanUiStage.HUB
                    lastResult = context.uiText(language, "scan_screen_014")
                },
                onSave = { saveReviewedScan() },
                onCancelOperation = {
                    activeSaveJob?.cancel()
                    if (documentProcessingBusy) {
                        documentProcessingRequestId += 1L
                        documentProcessingBusy = false
                    }
                },
                onBack = {
                    if (selectedMode == ScanCaptureMode.DOCUMENT) {
                        // Stepwise back navigation: editor/review -> corner adjustment -> camera.
                        documentProcessingRequestId += 1L
                        documentProcessingBusy = false
                        singleDocumentBoundsPage = reviewPages.firstOrNull()
                        flowStage = ScanUiStage.BOUNDS
                    } else if (selectedMode == ScanCaptureMode.TRANSLATION) {
                        flowStage = ScanUiStage.BOUNDS
                    } else {
                        when (ScanInteractionRules.backTarget(ScanFlowStage.REVIEW, reviewPages.isNotEmpty())) {
                            ScanBackTarget.CAMERA -> {
                                cameraPages = emptyList()
                                flowStage = ScanUiStage.CAMERA
                            }
                            ScanBackTarget.HUB -> {
                                ScanProcessor.cleanup(reviewPages)
                                reviewPages = emptyList()
                                cameraPages = emptyList()
                                singleDocumentRetakeIndex = null
                                flowStage = ScanUiStage.HUB
                            }
                            else -> onBack()
                        }
                    }
                }
            )
            return
        }

        ScanUiStage.SUCCESS -> {
            // The concrete success/translation screens above own this stage.
            // If state is unexpectedly incomplete, never silently fall back to the hub.
            LaunchedEffect(saveSuccessFile, translationResult) {
                if (saveSuccessFile == null && translationResult == null) {
                    flowStage = if (reviewPages.isNotEmpty()) ScanUiStage.REVIEW else ScanUiStage.CAMERA
                }
            }
            return
        }

        ScanUiStage.HUB -> {
            SmartScanHub(
                onAction = { action ->
                    when (action) {
                        SmartScanAction.DOCUMENT -> launchMode(ScanCaptureMode.DOCUMENT)
                        SmartScanAction.BATCH -> launchMode(ScanCaptureMode.BATCH)
                        SmartScanAction.ID_CARD -> launchMode(ScanCaptureMode.ID_CARD)
                        SmartScanAction.RECEIPT -> launchMode(ScanCaptureMode.RECEIPT)
                        SmartScanAction.BARCODE -> barcodeReaderOpen = true
                        SmartScanAction.TRANSLATION -> launchMode(ScanCaptureMode.TRANSLATION)
                        SmartScanAction.IMAGES_TO_PDF -> launchMode(ScanCaptureMode.PHOTO)
                    }
                },
                onBack = onBack,
                lastSavedFile = lastSavedFile,
                onOpenSaved = { file ->
                    if (file.extension.equals("pdf", ignoreCase = true)) onOpenPdf(file)
                    else runCatching { FileUtils.openFile(context, file) }
                },
                onShareSaved = { file -> runCatching { FileUtils.shareFile(context, file) } }
            )
        }
    }

}

@Composable
private fun ScanSaveOptionsDialog(
    initialName: String,
    initialFormat: ScanOutputFormat,
    saveDirectory: File,
    persistentStorageReady: Boolean,
    onChangeLocation: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String, ScanOutputFormat) -> Unit
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    var name by remember(initialName) { mutableStateOf(initialName) }
    var format by remember(initialFormat) { mutableStateOf(initialFormat) }
    val safeName = normalizedDocumentName(name)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                context.uiText(language, "alpha36_save_options_title"),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(context.uiText(language, "alpha36_file_name")) },
                    supportingText = {
                        Text(
                            "$safeName.${if (format == ScanOutputFormat.PDF) "pdf" else "jpg"}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                )
                Text(
                    context.uiText(language, "alpha36_format"),
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilterChip(
                        selected = format == ScanOutputFormat.PDF,
                        onClick = { format = ScanOutputFormat.PDF },
                        label = { Text("PDF") },
                        leadingIcon = if (format == ScanOutputFormat.PDF) {
                            { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                        } else null,
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = format == ScanOutputFormat.JPG,
                        onClick = { format = ScanOutputFormat.JPG },
                        label = { Text("JPG") },
                        leadingIcon = if (format == ScanOutputFormat.JPG) {
                            { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                        } else null,
                        modifier = Modifier.weight(1f)
                    )
                }
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            context.uiText(language, "alpha36_save_location"),
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            saveDirectory.absolutePath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(onClick = onChangeLocation, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.FolderOpen, null)
                            Spacer(Modifier.width(6.dp))
                            Text(context.uiText(language, "alpha37_change_save_location"))
                        }
                        if (!persistentStorageReady) {
                            Text(
                                context.uiText(language, "alpha37_storage_permission_required"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            Text(
                                context.uiText(language, "alpha37_persistent_storage_note"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(safeName, format) },
                enabled = persistentStorageReady && safeName.isNotBlank()
            ) {
                Icon(Icons.Default.Save, null)
                Spacer(Modifier.width(6.dp))
                Text(context.uiText(language, "alpha36_save_now"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(context.uiText(language, "alpha36_cancel"))
            }
        }
    )
}

@Composable
private fun ScanSaveSuccessContent(
    file: File,
    onOpenSaved: (File) -> Unit,
    onShareSaved: (File) -> Unit,
    onWhatsApp: (File) -> Unit,
    onMessenger: (File) -> Unit,
    onNewScan: () -> Unit,
    onGoHome: () -> Unit
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    // Alpha36: consume system Back on the success screen. The user must choose an
    // explicit action, preventing a queued/accidental Back event from bouncing to the hub.
    BackHandler(enabled = true) { }

    Scaffold(
        topBar = {
            ScreenTopBar(
                context.uiText(language, "alpha15_save_success_title"),
                onBack = onGoHome
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 22.dp, vertical = 18.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f)
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.padding(22.dp).size(72.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                context.uiText(language, "alpha15_save_success_message"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                file.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                context.uiText(language, "alpha15_save_success_location", file.parentFile?.name ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(26.dp))
            Button(
                onClick = { onOpenSaved(file) },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Icon(Icons.Default.FolderOpen, null)
                Spacer(Modifier.width(8.dp))
                Text(context.uiText(language, "alpha15_save_open"))
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { onShareSaved(file) },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Icon(Icons.Default.Share, null)
                Spacer(Modifier.width(8.dp))
                Text(context.uiText(language, "alpha15_save_share"))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { onWhatsApp(file) },
                    modifier = Modifier.weight(1f).height(50.dp)
                ) {
                    Icon(Icons.Default.Chat, null)
                    Spacer(Modifier.width(6.dp))
                    Text(context.uiText(language, "alpha18_whatsapp"))
                }
                OutlinedButton(
                    onClick = { onMessenger(file) },
                    modifier = Modifier.weight(1f).height(50.dp)
                ) {
                    Icon(Icons.Default.Send, null)
                    Spacer(Modifier.width(6.dp))
                    Text(context.uiText(language, "alpha18_messenger"))
                }
            }
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onNewScan, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.DocumentScanner, null)
                Spacer(Modifier.width(8.dp))
                Text(context.uiText(language, "alpha15_save_new_scan"))
            }
            TextButton(onClick = onGoHome, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Home, null)
                Spacer(Modifier.width(8.dp))
                Text(context.uiText(language, "alpha36_back_to_scan_home"))
            }
        }
    }
}

private data class ScannerModeEntry(
    val mode: ScanCaptureMode,
    val descriptionKey: String
)

private fun scannerModeIconRes(mode: ScanCaptureMode): Int = when (mode) {
    ScanCaptureMode.DOCUMENT -> R.drawable.ic_scan_document
    ScanCaptureMode.BATCH -> R.drawable.ic_scan_batch
    ScanCaptureMode.BOOK -> R.drawable.ic_scan_book
    ScanCaptureMode.ID_CARD -> R.drawable.ic_scan_id
    ScanCaptureMode.RECEIPT -> R.drawable.ic_scan_receipt
    ScanCaptureMode.DUPLEX -> R.drawable.ic_scan_duplex
    ScanCaptureMode.TRANSLATION -> R.drawable.ic_scan_translate
    ScanCaptureMode.PASSPORT -> R.drawable.ic_scan_id
    ScanCaptureMode.WHITEBOARD -> R.drawable.ic_scan_document
    ScanCaptureMode.PHOTO -> R.drawable.ic_scan_gallery
}

@Composable
private fun ScannerModeCard(
    entry: ScannerModeEntry,
    selected: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    Card(
        onClick = onClick,
        enabled = !busy,
        modifier = modifier.heightIn(min = 150.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .72f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .52f)
        ),
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primary.copy(alpha = .12f)
            ) {
                Icon(
                    painterResource(scannerModeIconRes(entry.mode)),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(9.dp).size(30.dp)
                )
            }
            Text(
                context.localizedLabel(language, entry.mode),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                context.uiText(language, entry.descriptionKey),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .78f)
            )
            if (entry.mode == ScanCaptureMode.DOCUMENT) {
                Text(
                    context.uiText(language, "scanner_stage2_document_auto_status"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (entry.mode == ScanCaptureMode.TRANSLATION) {
                Text(
                    context.uiText(language, "scanner_stage1_translation_stage"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun SingleDocumentBoundsContent(
    page: ScanDraftPage,
    detecting: Boolean,
    onPageChange: (ScanDraftPage) -> Unit,
    onDetectionFinished: (ScanQuad?) -> Unit,
    onContinue: () -> Unit,
    onRetake: () -> Unit,
    onBack: () -> Unit,
    diagnostic: String? = null
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    var sourceBitmap by remember(page.id, page.file.absolutePath) { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember(page.id, page.file.absolutePath) { mutableStateOf(false) }

    LaunchedEffect(page.id, page.file.absolutePath) {
        val prepared = withContext(Dispatchers.IO) {
            runCatching {
                val loaded = ScanProcessor.renderSourcePreview(page, 1280)
                val detected = runCatching { StaticDocumentDetector.detect(loaded).quad }.getOrNull()
                loaded to detected
            }
        }
        prepared.onSuccess { (loaded, detected) ->
            sourceBitmap?.takeIf { it !== loaded }?.recycle()
            sourceBitmap = loaded
            loadFailed = false
            onDetectionFinished(detected)
        }.onFailure {
            loadFailed = true
            onDetectionFinished(null)
        }
    }
    DisposableEffect(sourceBitmap) {
        val owned = sourceBitmap
        onDispose { owned?.takeIf { !it.isRecycled }?.recycle() }
    }
    // Fix4: while adjusting corners, touch/back events must never collapse the session to Camera.
    // Camera exit is explicit through the Retake button only.
    BackHandler(enabled = true) { }

    Scaffold(
        topBar = { ScreenTopBar(context.uiText(language, "alpha20_bounds_title"), onBack = null) },
        bottomBar = {
            Surface(shadowElevation = 8.dp, tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onRetake,
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) {
                        Icon(Icons.Default.Cameraswitch, null)
                        Spacer(Modifier.width(6.dp))
                        Text(context.uiText(language, "alpha20_retake"))
                    }
                    Button(
                        onClick = onContinue,
                        enabled = sourceBitmap != null && !detecting,
                        modifier = Modifier.weight(1.25f).height(52.dp)
                    ) {
                        Text(context.uiText(language, "alpha20_continue"))
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.ArrowForward, null)
                    }
                }
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                context.uiText(language, "alpha20_bounds_hint"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!diagnostic.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        diagnostic,
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            if (detecting) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    context.uiText(language, "alpha20_detecting"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            val bitmap = sourceBitmap
            if (bitmap == null) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    if (loadFailed) {
                        Text(
                            context.uiText(language, "scan_screen_005"),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                }
            } else {
                Card(Modifier.fillMaxWidth().weight(1f)) {
                    Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                        PerspectiveQuadEditor(
                            bitmap = bitmap,
                            quad = page.perspective ?: ScanQuad.insetDefault(.055f),
                            onQuadChange = { quad -> onPageChange(page.copy(perspective = quad)) },
                            editorHeight = 390.dp
                        )
                    }
                }
            }
            Text(
                context.uiText(language, "alpha20_bounds_manual"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ScanReviewContent(
    pages: List<ScanDraftPage>,
    quality: Int,
    pageSize: ScanPdfPageSize,
    maxPages: Int,
    singleDocument: Boolean,
    fileName: String,
    outputFormat: ScanOutputFormat,
    primaryActionKey: String,
    busy: Boolean,
    autoOpenEditorPageId: Long?,
    onAutoEditorConsumed: (Long) -> Unit,
    onFileNameChange: (String) -> Unit,
    onOutputFormatChange: (ScanOutputFormat) -> Unit,
    onQualityChange: (Int) -> Unit,
    onPageSizeChange: (ScanPdfPageSize) -> Unit,
    onPagesChange: (List<ScanDraftPage>) -> Unit,
    onRetakePage: (Int) -> Unit,
    onEditBounds: (Int) -> Unit,
    onAddPages: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onCancelOperation: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    BackHandler { if (!busy) onBack() }

    val reviewCard: @Composable (Int) -> Unit = { index ->
        val page = pages[index]
        ScanPageReviewCard(
            page = page,
            index = index,
            count = pages.size,
            singleDocument = singleDocument,
            enabled = !busy,
            autoOpenEditor = autoOpenEditorPageId == page.id,
            onAutoEditorConsumed = { onAutoEditorConsumed(page.id) },
            onUpdate = { updated ->
                onPagesChange(pages.toMutableList().also { it[index] = updated })
            },
            onDelete = {
                runCatching { page.file.delete() }
                onPagesChange(pages.toMutableList().also { it.removeAt(index) })
            },
            onRetake = { onRetakePage(index) },
            onEditBounds = { onEditBounds(index) },
            onMoveEarlier = {
                if (index > 0) onPagesChange(pages.toMutableList().also {
                    val item = it.removeAt(index); it.add(index - 1, item)
                })
            },
            onMoveLater = {
                if (index < pages.lastIndex) onPagesChange(pages.toMutableList().also {
                    val item = it.removeAt(index); it.add(index + 1, item)
                })
            },
            onApplyEnhancementsToAll = { source ->
                onPagesChange(pages.map { current ->
                    if (current.id == source.id) current else current.copy(
                        filter = source.filter,
                        brightness = source.brightness,
                        contrast = source.contrast,
                        sharpness = source.sharpness
                    )
                })
            }
        )
    }

    Scaffold(
        topBar = {
            ScreenTopBar(
                context.uiText(language, "scan_screen_029"),
                onBack = { if (!busy) onBack() }
            )
        },
        bottomBar = {
            ScanReviewSaveBar(
                primaryActionKey = primaryActionKey,
                busy = busy,
                singleDocument = singleDocument,
                outputFormat = outputFormat,
                canSave = pages.isNotEmpty(),
                onSave = onSave,
                onCancelOperation = onCancelOperation,
                onCancel = onCancel
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            if (singleDocument) {
                // Alpha22: in single-document mode the captured/processed image is the first thing the user sees.
                items(pages.size, key = { pages[it].id }) { index -> reviewCard(index) }
            }

            item {
                Spacer(Modifier.height(4.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        if (singleDocument) {
                            OutlinedTextField(
                                value = fileName,
                                onValueChange = onFileNameChange,
                                label = { Text(context.uiText(language, "alpha18_file_name")) },
                                placeholder = { Text(context.uiText(language, "alpha18_file_name_hint")) },
                                singleLine = true,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(context.uiText(language, "alpha18_save_format"), fontWeight = FontWeight.SemiBold)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = outputFormat == ScanOutputFormat.PDF,
                                    onClick = { onOutputFormatChange(ScanOutputFormat.PDF) },
                                    label = { Text("PDF") },
                                    leadingIcon = { Icon(Icons.Default.PictureAsPdf, null, Modifier.size(18.dp)) },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f)
                                )
                                FilterChip(
                                    selected = outputFormat == ScanOutputFormat.JPG,
                                    onClick = { onOutputFormatChange(ScanOutputFormat.JPG) },
                                    label = { Text("JPG") },
                                    leadingIcon = { Icon(Icons.Default.Image, null, Modifier.size(18.dp)) },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(context.uiText(language, "scan_screen_030", pages.size), fontWeight = FontWeight.Bold)
                            Text(context.uiText(language, "scan_screen_031", quality), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        }
                        Slider(
                            value = quality.toFloat(),
                            onValueChange = { onQualityChange(it.toInt()) },
                            valueRange = 55f..95f,
                            steps = 7,
                            enabled = !busy
                        )
                        if (!singleDocument || outputFormat == ScanOutputFormat.PDF) {
                            Text(context.uiText(language, "scan_screen_032"), fontWeight = FontWeight.SemiBold)
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ScanPdfPageSize.entries.forEach { size ->
                                    FilterChip(
                                        selected = pageSize == size,
                                        onClick = { if (!busy) onPageSizeChange(size) },
                                        label = { Text(context.localizedLabel(language, size)) },
                                        enabled = !busy
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            context.uiText(language, "scan_screen_033"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f)
                        )
                    }
                }
            }

            if (!singleDocument) {
                items(pages.size, key = { pages[it].id }) { index -> reviewCard(index) }
            }


            item {
                if (maxPages > 1 && pages.size < maxPages) {
                    OutlinedButton(onClick = onAddPages, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.AddPhotoAlternate, null)
                        Spacer(Modifier.width(8.dp))
                        Text(context.uiText(language, "scan_screen_034") + " (${pages.size}/$maxPages)")
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

}


@Composable
private fun ScanReviewSaveBar(
    primaryActionKey: String,
    busy: Boolean,
    singleDocument: Boolean,
    outputFormat: ScanOutputFormat,
    canSave: Boolean,
    onSave: () -> Unit,
    onCancelOperation: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current

    Surface(
        modifier = Modifier.navigationBarsPadding(),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = onSave,
                enabled = !busy && canSave,
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Default.PictureAsPdf, null)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (busy) {
                        if (singleDocument && outputFormat == ScanOutputFormat.JPG) "جاري حفظ JPG..."
                        else context.uiText(language, "scan_screen_035")
                    } else context.uiText(language, primaryActionKey)
                )
            }

            if (busy) {
                Text(
                    context.uiText(language, "v15_processing_progress"),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                TextButton(onClick = onCancelOperation, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Close, null)
                    Spacer(Modifier.width(6.dp))
                    Text(context.uiText(language, "v15_cancel_operation"))
                }
            } else {
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(context.uiText(language, "scan_screen_037"))
                }
            }
        }
    }
}

@Composable
private fun ScanPageReviewCard(
    page: ScanDraftPage,
    index: Int,
    count: Int,
    singleDocument: Boolean,
    enabled: Boolean,
    autoOpenEditor: Boolean,
    onAutoEditorConsumed: () -> Unit,
    onUpdate: (ScanDraftPage) -> Unit,
    onDelete: () -> Unit,
    onRetake: () -> Unit,
    onEditBounds: () -> Unit,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    onApplyEnhancementsToAll: (ScanDraftPage) -> Unit
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    var showEditor by remember(page.id) { mutableStateOf(autoOpenEditor) }
    LaunchedEffect(autoOpenEditor, page.id) {
        if (autoOpenEditor) {
            showEditor = true
            onAutoEditorConsumed()
        }
    }
    var confirmDelete by remember { mutableStateOf(false) }
    var smartCropBusy by remember(page.id) { mutableStateOf(false) }
    var preview by remember(
        page.file.absolutePath, page.rotation, page.filter, page.brightness, page.contrast, page.sharpness,
        page.cropLeft, page.cropTop, page.cropRight, page.cropBottom, page.perspective
    ) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(
        page.file.absolutePath, page.rotation, page.filter, page.brightness, page.contrast, page.sharpness,
        page.cropLeft, page.cropTop, page.cropRight, page.cropBottom, page.perspective
    ) {
        preview = null
        // Show a lightweight source thumbnail first so the review page never looks blank
        // while perspective correction and document filtering are still being rendered.
        val fastPreview = if (singleDocument && page.perspective != null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching { ScanProcessor.renderSourcePreview(page, 360) }.getOrNull()
            }
        }
        if (fastPreview != null) preview = fastPreview
        val processedPreview = withContext(Dispatchers.IO) {
            runCatching { ScanProcessor.renderPreview(page) }.getOrNull()
        }
        if (processedPreview != null) preview = processedPreview
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(context.uiText(language, "scan_screen_038", index + 1, count), fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    var dragAccumulator by remember(page.id) { mutableFloatStateOf(0f) }
                    Icon(
                        Icons.Default.DragHandle,
                        contentDescription = context.uiText(language, "scan_screen_039"),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(44.dp)
                            .padding(8.dp)
                            .pointerInput(page.id, index, count, enabled) {
                                if (!enabled) return@pointerInput
                                detectVerticalDragGestures(
                                    onDragStart = { dragAccumulator = 0f },
                                    onDragEnd = {
                                        val threshold = 34.dp.toPx()
                                        when {
                                            dragAccumulator <= -threshold && index > 0 -> onMoveEarlier()
                                            dragAccumulator >= threshold && index < count - 1 -> onMoveLater()
                                        }
                                        dragAccumulator = 0f
                                    },
                                    onDragCancel = { dragAccumulator = 0f },
                                    onVerticalDrag = { change, amount ->
                                        change.consume()
                                        dragAccumulator += amount
                                    }
                                )
                            }
                    )
                    IconButton(onClick = onMoveEarlier, enabled = enabled && index > 0) { Icon(Icons.Default.ArrowUpward, context.uiText(language, "scan_screen_040")) }
                    IconButton(onClick = onMoveLater, enabled = enabled && index < count - 1) { Icon(Icons.Default.ArrowDownward, context.uiText(language, "scan_screen_041")) }
                    IconButton(onClick = { confirmDelete = true }, enabled = enabled) { Icon(Icons.Default.DeleteOutline, context.uiText(language, "scan_screen_042")) }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 420.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val bmp = preview
                    if (bmp == null) CircularProgressIndicator()
                    else Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = context.uiText(language, "scan_screen_043", index + 1),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 420.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { onUpdate(page.copy(rotation = page.rotation - 90)) }, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.RotateLeft, null); Spacer(Modifier.width(3.dp)); Text(context.uiText(language, "scan_screen_044"))
                }
                OutlinedButton(onClick = { onUpdate(page.copy(rotation = page.rotation + 90)) }, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.RotateRight, null); Spacer(Modifier.width(3.dp)); Text(context.uiText(language, "scan_screen_045"))
                }
                OutlinedButton(onClick = onRetake, enabled = enabled, modifier = Modifier.weight(1.2f)) {
                    Icon(Icons.Default.Cameraswitch, null); Spacer(Modifier.width(3.dp)); Text(context.uiText(language, "scan_screen_046"))
                }
            }

            if (singleDocument) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onEditBounds,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CropFree, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(context.uiText(language, "alpha20_bounds_title"))
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = {
                            onUpdate(
                                page.copy(
                                    filter = ScanFilter.DOCUMENT,
                                    brightness = 4,
                                    contrast = 1.08f,
                                    sharpness = .16f
                                )
                            )
                        },
                        enabled = enabled,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.AutoFixHigh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(context.uiText(language, "alpha21_quick_enhance"))
                    }
                    OutlinedButton(
                        onClick = {
                            if (!smartCropBusy) {
                                smartCropBusy = true
                                scope.launch {
                                    val quad = withContext(Dispatchers.IO) {
                                        val bitmap = runCatching { ScanProcessor.renderSourcePreview(page, 1800) }.getOrNull()
                                        if (bitmap == null) {
                                            null
                                        } else {
                                            try {
                                                runCatching { StaticDocumentDetector.detect(bitmap).quad }.getOrNull()
                                            } finally {
                                                bitmap.recycle()
                                            }
                                        }
                                    }
                                    if (quad != null) {
                                        onUpdate(page.copy(perspective = quad))
                                        Toast.makeText(
                                            context,
                                            context.uiText(language, "alpha21_smart_crop_done"),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            context.uiText(language, "alpha21_smart_crop_not_found"),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    smartCropBusy = false
                                }
                            }
                        },
                        enabled = enabled && !smartCropBusy,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (smartCropBusy) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.CropFree, null, Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(context.uiText(language, "alpha21_smart_crop"))
                    }
                    OutlinedButton(
                        onClick = {
                            onUpdate(
                                page.copy(
                                    rotation = 0,
                                    filter = ScanFilter.ORIGINAL,
                                    brightness = 0,
                                    contrast = 1f,
                                    sharpness = 0f,
                                    cropLeft = 0f,
                                    cropTop = 0f,
                                    cropRight = 0f,
                                    cropBottom = 0f,
                                    perspective = null
                                )
                            )
                        },
                        enabled = enabled,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.RestartAlt, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(context.uiText(language, "alpha21_original"))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showEditor = true },
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Tune, null)
                    Spacer(Modifier.width(4.dp))
                    Text(context.uiText(language, "scan_screen_047"))
                }
                OutlinedButton(
                    onClick = { onApplyEnhancementsToAll(page) },
                    enabled = enabled && count > 1,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.AutoFixHigh, null)
                    Spacer(Modifier.width(4.dp))
                    Text(context.uiText(language, "scan_screen_048"))
                }
            }
            Text(
                context.uiText(language, "scan_screen_049"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f)
            )

        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(context.uiText(language, "scan_screen_050")) },
            text = { Text(context.uiText(language, "scan_screen_051")) },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text(context.uiText(language, "scan_screen_042")) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(context.uiText(language, "scan_screen_052")) } }
        )
    }

    if (showEditor) {
        ScanPageAdjustDialog(
            page = page,
            onDismiss = { showEditor = false },
            onApply = { updated ->
                onUpdate(updated)
                showEditor = false
            }
        )
    }
}

private enum class ScanEditorSection { FILTERS, ADJUST, CROP, TEXT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanPageAdjustDialog(
    page: ScanDraftPage,
    busy: Boolean = false,
    onDismiss: () -> Unit,
    onApply: (ScanDraftPage) -> Unit,
    onSaveAndContinue: ((ScanDraftPage) -> Unit)? = null
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    var section by remember(page.id) { mutableStateOf(ScanEditorSection.FILTERS) }
    var rotation by remember(page.id) { mutableIntStateOf(page.rotation) }
    var filter by remember(page.id) { mutableStateOf(page.filter) }
    var brightness by remember(page.id) { mutableFloatStateOf(page.brightness.toFloat()) }
    var contrast by remember(page.id) { mutableFloatStateOf(page.contrast) }
    var sharpness by remember(page.id) { mutableFloatStateOf(page.sharpness) }
    var cropLeft by remember(page.id) { mutableFloatStateOf(page.cropLeft) }
    var cropTop by remember(page.id) { mutableFloatStateOf(page.cropTop) }
    var cropRight by remember(page.id) { mutableFloatStateOf(page.cropRight) }
    var cropBottom by remember(page.id) { mutableFloatStateOf(page.cropBottom) }
    var perspectiveEnabled by remember(page.id) { mutableStateOf(page.perspective != null) }
    var perspective by remember(page.id) { mutableStateOf(page.perspective ?: ScanQuad.insetDefault()) }
    var annotations by remember(page.id) { mutableStateOf(page.annotations) }
    var selectedAnnotationId by remember(page.id) { mutableStateOf(page.annotations.firstOrNull()?.id) }
    var newAnnotationText by remember(page.id) { mutableStateOf("") }
    var compareOriginal by remember(page.id) { mutableStateOf(false) }
    var undoStack by remember(page.id) { mutableStateOf<List<ScanDraftPage>>(emptyList()) }
    var redoStack by remember(page.id) { mutableStateOf<List<ScanDraftPage>>(emptyList()) }
    var continuousEditStart by remember(page.id) { mutableStateOf<ScanDraftPage?>(null) }
    var editorPreview by remember { mutableStateOf<Bitmap?>(null) }
    var sourcePreview by remember { mutableStateOf<Bitmap?>(null) }
    val primaryEditorFilters = remember {
        listOf(ScanFilter.ORIGINAL, ScanFilter.DOCUMENT, ScanFilter.COLOR, ScanFilter.GRAYSCALE, ScanFilter.BW)
    }
    var filterPreviews by remember(page.id) { mutableStateOf<Map<ScanFilter, Bitmap>>(emptyMap()) }

    val draft = page.copy(
        rotation = rotation,
        filter = filter,
        brightness = brightness.toInt(),
        contrast = contrast,
        sharpness = sharpness,
        cropLeft = cropLeft,
        cropTop = cropTop,
        cropRight = cropRight,
        cropBottom = cropBottom,
        perspective = if (perspectiveEnabled) perspective else null,
        annotations = annotations
    )

    fun applySnapshot(snapshot: ScanDraftPage) {
        rotation = snapshot.rotation
        filter = snapshot.filter
        brightness = snapshot.brightness.toFloat()
        contrast = snapshot.contrast
        sharpness = snapshot.sharpness
        cropLeft = snapshot.cropLeft
        cropTop = snapshot.cropTop
        cropRight = snapshot.cropRight
        cropBottom = snapshot.cropBottom
        perspectiveEnabled = snapshot.perspective != null
        perspective = snapshot.perspective ?: ScanQuad.insetDefault()
        annotations = snapshot.annotations
        val currentSelection = selectedAnnotationId
        if (currentSelection == null || snapshot.annotations.none { it.id == currentSelection }) {
            selectedAnnotationId = snapshot.annotations.firstOrNull()?.id
        }
    }

    fun pushUndo() {
        continuousEditStart = null
        undoStack = (undoStack + draft).takeLast(40)
        redoStack = emptyList()
    }

    fun beginContinuousEdit() {
        if (continuousEditStart == null) continuousEditStart = draft
    }

    fun commitContinuousEdit() {
        continuousEditStart?.let { snapshot ->
            undoStack = (undoStack + snapshot).takeLast(40)
            redoStack = emptyList()
        }
        continuousEditStart = null
    }

    fun undo() {
        val target = undoStack.lastOrNull() ?: return
        redoStack = (redoStack + draft).takeLast(40)
        undoStack = undoStack.dropLast(1)
        applySnapshot(target)
    }

    fun redo() {
        val target = redoStack.lastOrNull() ?: return
        undoStack = (undoStack + draft).takeLast(40)
        redoStack = redoStack.dropLast(1)
        applySnapshot(target)
    }

    fun restoreInitial() {
        pushUndo()
        applySnapshot(
            page.copy(
                rotation = 0,
                filter = ScanFilter.ORIGINAL,
                brightness = 0,
                contrast = 1f,
                sharpness = 0f,
                cropLeft = 0f,
                cropTop = 0f,
                cropRight = 0f,
                cropBottom = 0f,
                perspective = null,
                annotations = emptyList()
            )
        )
    }

    fun applyAutoEnhance() {
        pushUndo()
        filter = ScanFilter.DOCUMENT
        brightness = 4f
        contrast = 1.08f
        sharpness = .16f
    }

    fun applySmartClarity() {
        val preview = sourcePreview ?: return
        pushUndo()
        val suggested = ScanProcessor.suggestSmartClarity(draft, preview)
        filter = suggested.filter
        brightness = suggested.brightness.toFloat()
        contrast = suggested.contrast
        sharpness = suggested.sharpness
    }

    LaunchedEffect(page.file.absolutePath) {
        sourcePreview = withContext(Dispatchers.IO) {
            runCatching { ScanProcessor.renderSourcePreview(page, 900) }.getOrNull()
        }
        filterPreviews = withContext(Dispatchers.IO) {
            primaryEditorFilters.mapNotNull { candidate ->
                runCatching {
                    candidate to ScanProcessor.renderPreview(
                        page.copy(filter = candidate, annotations = emptyList()),
                        220
                    )
                }.getOrNull()
            }.toMap()
        }
    }

    LaunchedEffect(
        rotation, filter, brightness, contrast, sharpness, cropLeft, cropTop, cropRight, cropBottom,
        perspectiveEnabled, perspective, annotations
    ) {
        editorPreview = withContext(Dispatchers.IO) {
            runCatching { ScanProcessor.renderPreview(draft.copy(annotations = emptyList()), 820) }.getOrNull()
        }
    }

    BackHandler { onDismiss() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Scaffold(
                topBar = {
                    CenterAlignedTopAppBar(
                        title = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    context.uiText(language, "scan_screen_053"),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text(
                                    context.uiText(language, "alpha34_editor_subtitle"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            }
                        }
                    )
                },
                bottomBar = {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(bottom = 18.dp),
                        tonalElevation = 5.dp,
                        shadowElevation = 10.dp,
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { restoreInitial() },
                                modifier = Modifier.weight(1f).height(58.dp),
                                shape = MaterialTheme.shapes.extraLarge
                            ) {
                                Icon(Icons.Default.RestartAlt, null)
                                Spacer(Modifier.width(6.dp))
                                Text(context.uiText(language, "alpha34_reset_short"), fontWeight = FontWeight.SemiBold)
                            }
                            Button(
                                onClick = {
                                    if (!busy) {
                                        val action = onSaveAndContinue
                                        if (action != null) action(draft) else onApply(draft)
                                    }
                                },
                                enabled = !busy,
                                modifier = Modifier.weight(1.55f).height(58.dp),
                                shape = MaterialTheme.shapes.extraLarge
                            ) {
                                if (busy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(Modifier.width(7.dp))
                                    Text(context.uiText(language, "scan_screen_035"), fontWeight = FontWeight.Bold)
                                } else {
                                    Icon(Icons.Default.Download, null)
                                    Spacer(Modifier.width(7.dp))
                                    Text(context.uiText(language, "alpha34_save_continue"), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            ) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(320.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .72f),
                        shape = MaterialTheme.shapes.extraLarge,
                        tonalElevation = 1.dp
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            val bmp = if (compareOriginal) sourcePreview else editorPreview
                            if (bmp == null) {
                                CircularProgressIndicator(Modifier.size(30.dp))
                            } else {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = context.uiText(language, "scan_screen_059"),
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp),
                                    contentScale = ContentScale.Fit
                                )
                                if (!compareOriginal && annotations.isNotEmpty()) {
                                    ScanAnnotationOverlay(
                                        bitmapWidth = bmp.width,
                                        bitmapHeight = bmp.height,
                                        annotations = annotations,
                                        selectedId = selectedAnnotationId,
                                        onMoveStart = { beginContinuousEdit() },
                                        onMove = { id, x, y ->
                                            annotations = annotations.map { item ->
                                                if (item.id == id) item.copy(x = x, y = y) else item
                                            }
                                        },
                                        onMoveEnd = { commitContinuousEdit() }
                                    )
                                }
                            }

                            Surface(
                                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                                shape = MaterialTheme.shapes.extraLarge,
                                color = MaterialTheme.colorScheme.surface.copy(alpha = .88f)
                            ) {
                                Text(
                                    "1/1",
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }

                            Surface(
                                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                                shape = MaterialTheme.shapes.extraLarge,
                                color = MaterialTheme.colorScheme.surface.copy(alpha = .92f),
                                shadowElevation = 3.dp
                            ) {
                                IconButton(onClick = { compareOriginal = !compareOriginal }) {
                                    Icon(
                                        if (compareOriginal) Icons.Default.Visibility else Icons.Default.CenterFocusStrong,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        EditorToolButton(
                            modifier = Modifier.weight(1f),
                            text = context.uiText(language, "alpha12_editor_auto"),
                            icon = Icons.Default.AutoFixHigh,
                            onClick = { applyAutoEnhance() }
                        )
                        EditorToolButton(
                            modifier = Modifier.weight(1f),
                            text = context.uiText(language, "alpha33_smart_clarity"),
                            icon = Icons.Default.CenterFocusStrong,
                            enabled = sourcePreview != null,
                            onClick = { applySmartClarity() }
                        )
                        EditorToolButton(
                            modifier = Modifier.weight(1f),
                            text = context.uiText(language, "alpha34_rotate"),
                            icon = Icons.Default.RotateRight,
                            onClick = { pushUndo(); rotation += 90 }
                        )
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        EditorSectionButton(
                            modifier = Modifier.weight(1f),
                            selected = section == ScanEditorSection.FILTERS,
                            text = context.uiText(language, "alpha12_editor_filters"),
                            icon = Icons.Default.FilterAlt,
                            onClick = { section = ScanEditorSection.FILTERS }
                        )
                        EditorSectionButton(
                            modifier = Modifier.weight(1f),
                            selected = section == ScanEditorSection.ADJUST,
                            text = context.uiText(language, "alpha12_editor_adjust"),
                            icon = Icons.Default.Tune,
                            onClick = { section = ScanEditorSection.ADJUST }
                        )
                        EditorSectionButton(
                            modifier = Modifier.weight(1f),
                            selected = section == ScanEditorSection.CROP,
                            text = context.uiText(language, "alpha12_editor_crop"),
                            icon = Icons.Default.Crop,
                            onClick = { section = ScanEditorSection.CROP }
                        )
                        EditorSectionButton(
                            modifier = Modifier.weight(1f),
                            selected = section == ScanEditorSection.TEXT,
                            text = context.uiText(language, "alpha18_text"),
                            icon = Icons.Default.TextFields,
                            onClick = { section = ScanEditorSection.TEXT }
                        )
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { undo() }, enabled = undoStack.isNotEmpty()) {
                            Icon(Icons.Default.Undo, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(context.uiText(language, "alpha18_undo"))
                        }
                        TextButton(onClick = { redo() }, enabled = redoStack.isNotEmpty()) {
                            Icon(Icons.Default.Redo, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(context.uiText(language, "alpha18_redo"))
                        }
                    }

                    Column(
                        Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        when (section) {
                            ScanEditorSection.FILTERS -> {
                                Text(
                                    context.uiText(language, "alpha12_editor_filters"),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    primaryEditorFilters.forEach { candidate ->
                                        EditorFilterPreview(
                                            modifier = Modifier.weight(1f),
                                            bitmap = filterPreviews[candidate],
                                            label = context.localizedLabel(language, candidate),
                                            selected = filter == candidate,
                                            onClick = { pushUndo(); filter = candidate }
                                        )
                                    }
                                }
                                val advancedFilters = ScanFilter.entries.filterNot { it in primaryEditorFilters }
                                if (advancedFilters.isNotEmpty()) {
                                    Row(
                                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                                    ) {
                                        advancedFilters.forEach { candidate ->
                                            FilterChip(
                                                selected = filter == candidate,
                                                onClick = { pushUndo(); filter = candidate },
                                                label = { Text(context.localizedLabel(language, candidate)) }
                                            )
                                        }
                                    }
                                }
                            }
                            ScanEditorSection.ADJUST -> {
                                AdjustmentSlider(context.uiText(language, "scan_screen_060"), brightness, -45f..45f, "${brightness.toInt()}", { beginContinuousEdit(); brightness = it }, { commitContinuousEdit() })
                                AdjustmentSlider(context.uiText(language, "scan_screen_061"), contrast, .7f..1.5f, String.format("%.2f", contrast), { beginContinuousEdit(); contrast = it }, { commitContinuousEdit() })
                                AdjustmentSlider(context.uiText(language, "scan_screen_062"), sharpness, 0f..0.6f, "${(sharpness * 100).toInt()}%", { beginContinuousEdit(); sharpness = it }, { commitContinuousEdit() })
                            }
                            ScanEditorSection.CROP -> {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(context.uiText(language, "scan_screen_054"), fontWeight = FontWeight.Bold)
                                        Text(
                                            context.uiText(language, "scan_screen_055"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f)
                                        )
                                    }
                                    Switch(checked = perspectiveEnabled, onCheckedChange = { pushUndo(); perspectiveEnabled = it })
                                }
                                if (perspectiveEnabled) {
                                    val source = sourcePreview
                                    if (source == null) {
                                        Box(Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                                    } else {
                                        PerspectiveQuadEditor(
                                            bitmap = source,
                                            quad = perspective,
                                            onEditStart = { beginContinuousEdit() },
                                            onQuadChange = { perspective = it },
                                            onEditEnd = { commitContinuousEdit() }
                                        )
                                    }
                                    TextButton(onClick = { pushUndo(); perspective = ScanQuad.insetDefault() }) {
                                        Icon(Icons.Default.CropFree, null)
                                        Spacer(Modifier.width(4.dp))
                                        Text(context.uiText(language, "scan_screen_056"))
                                    }
                                }
                                HorizontalDivider()
                                Text(context.uiText(language, "scan_screen_063"), fontWeight = FontWeight.Bold)
                                AdjustmentSlider(context.uiText(language, "scan_screen_065"), cropLeft, 0f..0.35f, "${(cropLeft * 100).toInt()}%", { beginContinuousEdit(); cropLeft = it }, { commitContinuousEdit() })
                                AdjustmentSlider(context.uiText(language, "scan_screen_066"), cropRight, 0f..0.35f, "${(cropRight * 100).toInt()}%", { beginContinuousEdit(); cropRight = it }, { commitContinuousEdit() })
                                AdjustmentSlider(context.uiText(language, "scan_screen_067"), cropTop, 0f..0.35f, "${(cropTop * 100).toInt()}%", { beginContinuousEdit(); cropTop = it }, { commitContinuousEdit() })
                                AdjustmentSlider(context.uiText(language, "scan_screen_068"), cropBottom, 0f..0.35f, "${(cropBottom * 100).toInt()}%", { beginContinuousEdit(); cropBottom = it }, { commitContinuousEdit() })
                            }
                            ScanEditorSection.TEXT -> {
                                OutlinedTextField(
                                    value = newAnnotationText,
                                    onValueChange = { newAnnotationText = it },
                                    label = { Text(context.uiText(language, "alpha18_add_text")) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Button(
                                    onClick = {
                                        val value = newAnnotationText.trim()
                                        if (value.isNotEmpty()) {
                                            pushUndo()
                                            val item = ScanTextAnnotation(text = value)
                                            annotations = annotations + item
                                            selectedAnnotationId = item.id
                                            newAnnotationText = ""
                                        }
                                    },
                                    enabled = newAnnotationText.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Add, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(context.uiText(language, "alpha18_add_text_action"))
                                }

                                if (annotations.isEmpty()) {
                                    Text(
                                        context.uiText(language, "alpha18_no_text"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Text(context.uiText(language, "alpha18_select_text"), fontWeight = FontWeight.SemiBold)
                                    Row(
                                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        annotations.forEachIndexed { index, item ->
                                            FilterChip(
                                                selected = item.id == selectedAnnotationId,
                                                onClick = { selectedAnnotationId = item.id },
                                                label = { Text("${index + 1}: ${item.text.take(14)}") }
                                            )
                                        }
                                    }

                                    val selected = annotations.firstOrNull { it.id == selectedAnnotationId }
                                    if (selected != null) {
                                        OutlinedTextField(
                                            value = selected.text,
                                            onValueChange = { value ->
                                                pushUndo()
                                                annotations = annotations.map { if (it.id == selected.id) it.copy(text = value) else it }
                                            },
                                            label = { Text(context.uiText(language, "alpha18_edit_text")) },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        AdjustmentSlider(
                                            context.uiText(language, "alpha18_text_size"),
                                            selected.sizeScale,
                                            .025f..0.14f,
                                            "${(selected.sizeScale * 1000).toInt()}",
                                            onValueChange = { value ->
                                                beginContinuousEdit()
                                                annotations = annotations.map { if (it.id == selected.id) it.copy(sizeScale = value) else it }
                                            },
                                            onValueChangeFinished = { commitContinuousEdit() }
                                        )

                                        Text(context.uiText(language, "alpha18_text_color"), fontWeight = FontWeight.SemiBold)
                                        AnnotationColorPicker(
                                            selected = selected.colorArgb,
                                            colors = listOf(0xFF111111, 0xFFFFFFFF, 0xFFD32F2F, 0xFF1976D2, 0xFF388E3C),
                                            onSelect = { value ->
                                                pushUndo()
                                                annotations = annotations.map { if (it.id == selected.id) it.copy(colorArgb = value) else it }
                                            }
                                        )

                                        Text(context.uiText(language, "alpha18_text_background"), fontWeight = FontWeight.SemiBold)
                                        Row(
                                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            listOf<Long?>(null, 0xCCFFFFFF, 0xCC000000, 0xCCFFF59D).forEachIndexed { index, value ->
                                                FilterChip(
                                                    selected = selected.backgroundArgb == value,
                                                    onClick = {
                                                        pushUndo()
                                                        annotations = annotations.map { if (it.id == selected.id) it.copy(backgroundArgb = value) else it }
                                                    },
                                                    label = { Text(context.uiText(language, "alpha18_background_${index}")) }
                                                )
                                            }
                                        }

                                        Text(
                                            context.uiText(language, "alpha18_drag_text_hint"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        OutlinedButton(
                                            onClick = {
                                                pushUndo()
                                                val remaining = annotations.filterNot { it.id == selected.id }
                                                annotations = remaining
                                                selectedAnnotationId = remaining.firstOrNull()?.id
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.Default.DeleteOutline, null)
                                            Spacer(Modifier.width(6.dp))
                                            Text(context.uiText(language, "alpha18_delete_text"))
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorToolButton(
    modifier: Modifier = Modifier,
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(56.dp),
        shape = MaterialTheme.shapes.extraLarge,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(5.dp))
        Text(text, maxLines = 1, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EditorSectionButton(
    modifier: Modifier = Modifier,
    selected: Boolean,
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    val container = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    val content = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = modifier.height(52.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = container,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.outlineVariant),
        onClick = onClick
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, Modifier.size(19.dp), tint = if (selected) MaterialTheme.colorScheme.primary else content)
            Spacer(Modifier.width(4.dp))
            Text(text, maxLines = 1, fontSize = 12.sp, color = content, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
        }
    }
}

@Composable
private fun EditorFilterPreview(
    modifier: Modifier = Modifier,
    bitmap: Bitmap?,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .72f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        onClick = onClick
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(82.dp), contentAlignment = Alignment.Center) {
                if (bitmap == null) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().padding(3.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }
            Text(
                label,
                modifier = Modifier.padding(horizontal = 3.dp, vertical = 6.dp),
                maxLines = 1,
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ScanAnnotationOverlay(
    bitmapWidth: Int,
    bitmapHeight: Int,
    annotations: List<ScanTextAnnotation>,
    selectedId: Long?,
    onMoveStart: () -> Unit,
    onMove: (Long, Float, Float) -> Unit,
    onMoveEnd: () -> Unit
) {
    val latestAnnotations by rememberUpdatedState(annotations)
    val latestSelectedId by rememberUpdatedState(selectedId)
    val latestOnMoveStart by rememberUpdatedState(onMoveStart)
    val latestOnMove by rememberUpdatedState(onMove)
    val latestOnMoveEnd by rememberUpdatedState(onMoveEnd)
    Canvas(
        Modifier
            .fillMaxSize()
            .padding(6.dp)
            .pointerInput(bitmapWidth, bitmapHeight) {
                var activeDragId: Long? = null
                detectDragGestures(
                    onDragStart = { start ->
                        val selected = latestAnnotations.firstOrNull { it.id == latestSelectedId }
                        if (selected != null && size.width > 0 && size.height > 0 && bitmapWidth > 0 && bitmapHeight > 0) {
                            val imageAspect = bitmapWidth.toFloat() / bitmapHeight.toFloat()
                            val canvasAspect = size.width / size.height
                            val fittedWidth: Float
                            val fittedHeight: Float
                            val imageLeft: Float
                            val imageTop: Float
                            if (canvasAspect > imageAspect) {
                                fittedHeight = size.height.toFloat()
                                fittedWidth = fittedHeight * imageAspect
                                imageLeft = (size.width - fittedWidth) / 2f
                                imageTop = 0f
                            } else {
                                fittedWidth = size.width.toFloat()
                                fittedHeight = fittedWidth / imageAspect
                                imageLeft = 0f
                                imageTop = (size.height - fittedHeight) / 2f
                            }
                            val shortSide = minOf(fittedWidth, fittedHeight).coerceAtLeast(1f)
                            val textSize = (shortSide * selected.sizeScale.coerceIn(.025f, .14f)).coerceAtLeast(12f)
                            val x = imageLeft + selected.x.coerceIn(.02f, .92f) * fittedWidth
                            val y = imageTop + selected.y.coerceIn(.08f, .96f) * fittedHeight
                            val estimatedWidth = textSize * .62f * selected.text.length.coerceAtLeast(1)
                            val pad = textSize * .45f
                            val hit = start.x in (x - pad)..(x + estimatedWidth + pad) &&
                                start.y in (y - textSize * 1.35f - pad)..(y + textSize * .55f + pad)
                            if (hit) {
                                activeDragId = selected.id
                                latestOnMoveStart()
                            }
                        }
                    },
                    onDragEnd = {
                        if (activeDragId != null) latestOnMoveEnd()
                        activeDragId = null
                    },
                    onDragCancel = {
                        if (activeDragId != null) latestOnMoveEnd()
                        activeDragId = null
                    },
                    onDrag = { change, dragAmount ->
                        val id = activeDragId ?: return@detectDragGestures
                        val selected = latestAnnotations.firstOrNull { it.id == id } ?: return@detectDragGestures
                        change.consume()
                        if (size.width > 0 && size.height > 0 && bitmapWidth > 0 && bitmapHeight > 0) {
                            val imageAspect = bitmapWidth.toFloat() / bitmapHeight.toFloat()
                            val canvasAspect = size.width / size.height
                            val fittedWidth: Float
                            val fittedHeight: Float
                            if (canvasAspect > imageAspect) {
                                fittedHeight = size.height.toFloat()
                                fittedWidth = fittedHeight * imageAspect
                            } else {
                                fittedWidth = size.width.toFloat()
                                fittedHeight = fittedWidth / imageAspect
                            }
                            latestOnMove(
                                selected.id,
                                (selected.x + dragAmount.x / fittedWidth.coerceAtLeast(1f)).coerceIn(.02f, .92f),
                                (selected.y + dragAmount.y / fittedHeight.coerceAtLeast(1f)).coerceIn(.08f, .96f)
                            )
                        }
                    }
                )
            }
    ) {
        val native = drawContext.canvas.nativeCanvas
        val imageAspect = bitmapWidth.coerceAtLeast(1).toFloat() / bitmapHeight.coerceAtLeast(1).toFloat()
        val canvasAspect = size.width / size.height.coerceAtLeast(1f)
        val fittedWidth: Float
        val fittedHeight: Float
        val imageLeft: Float
        val imageTop: Float
        if (canvasAspect > imageAspect) {
            fittedHeight = size.height
            fittedWidth = fittedHeight * imageAspect
            imageLeft = (size.width - fittedWidth) / 2f
            imageTop = 0f
        } else {
            fittedWidth = size.width
            fittedHeight = fittedWidth / imageAspect
            imageLeft = 0f
            imageTop = (size.height - fittedHeight) / 2f
        }
        val shortSide = minOf(fittedWidth, fittedHeight).coerceAtLeast(1f)
        annotations.forEach { item ->
            val label = item.text.ifBlank { " " }
            val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                color = item.colorArgb.toInt()
                textSize = (shortSide * item.sizeScale.coerceIn(.025f, .14f)).coerceAtLeast(12f)
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            }
            val x = imageLeft + item.x.coerceIn(.02f, .92f) * fittedWidth
            val y = imageTop + item.y.coerceIn(.08f, .96f) * fittedHeight
            val textWidth = paint.measureText(label)
            val fm = paint.fontMetrics
            val pad = paint.textSize * .22f
            item.backgroundArgb?.let { background ->
                val bg = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply { color = background.toInt() }
                native.drawRoundRect(
                    x - pad,
                    y + fm.ascent - pad,
                    (x + textWidth + pad).coerceAtMost(imageLeft + fittedWidth),
                    (y + fm.descent + pad).coerceAtMost(imageTop + fittedHeight),
                    pad,
                    pad,
                    bg
                )
            }
            native.drawText(label, x, y, paint)
            if (item.id == selectedId) {
                val outline = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFFFC107.toInt()
                    style = AndroidPaint.Style.STROKE
                    strokeWidth = 3f
                }
                native.drawRoundRect(
                    x - pad,
                    y + fm.ascent - pad,
                    (x + textWidth + pad).coerceAtMost(imageLeft + fittedWidth),
                    (y + fm.descent + pad).coerceAtMost(imageTop + fittedHeight),
                    pad,
                    pad,
                    outline
                )
            }
        }
    }
}

@Composable
private fun AnnotationColorPicker(
    selected: Long,
    colors: List<Long>,
    onSelect: (Long) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        colors.forEach { value ->
            val isSelected = selected == value
            FilledIconButton(
                onClick = { onSelect(value) },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color(value),
                    contentColor = if (value == 0xFF111111 || value == 0xFFD32F2F || value == 0xFF1976D2 || value == 0xFF388E3C) Color.White else Color.Black
                )
            ) {
                if (isSelected) Icon(Icons.Default.Check, null) else Icon(Icons.Default.Circle, null, Modifier.size(10.dp))
            }
        }
    }
}

@Composable
private fun PerspectiveQuadEditor(
    bitmap: Bitmap,
    quad: ScanQuad,
    onEditStart: () -> Unit = {},
    onQuadChange: (ScanQuad) -> Unit,
    onEditEnd: () -> Unit = {},
    editorHeight: androidx.compose.ui.unit.Dp = 250.dp
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    var activeCorner by remember { mutableIntStateOf(-1) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val latestQuad by rememberUpdatedState(quad)
    val latestOnEditStart by rememberUpdatedState(onEditStart)
    val latestOnQuadChange by rememberUpdatedState(onQuadChange)
    val latestOnEditEnd by rememberUpdatedState(onEditEnd)

    fun points(q: ScanQuad) = listOf(q.topLeft, q.topRight, q.bottomRight, q.bottomLeft)

    fun sanitize(index: Int, x: Float, y: Float, current: ScanQuad): ScanQuad {
        val margin = .035f
        val pts = points(current).toMutableList()
        val nx = x.coerceIn(.01f, .99f)
        val ny = y.coerceIn(.01f, .99f)
        pts[index] = when (index) {
            0 -> ScanPoint(nx.coerceAtMost(pts[1].x - margin), ny.coerceAtMost(pts[3].y - margin))
            1 -> ScanPoint(nx.coerceAtLeast(pts[0].x + margin), ny.coerceAtMost(pts[2].y - margin))
            2 -> ScanPoint(nx.coerceAtLeast(pts[3].x + margin), ny.coerceAtLeast(pts[1].y + margin))
            else -> ScanPoint(nx.coerceAtMost(pts[2].x - margin), ny.coerceAtLeast(pts[0].y + margin))
        }
        return ScanQuad(pts[0], pts[1], pts[2], pts[3])
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(editorHeight)
            .pointerInput(bitmap) {
                detectDragGestures(
                    onDragStart = { start ->
                        latestOnEditStart()
                        val pts = points(latestQuad).map { Offset(it.x * size.width, it.y * size.height) }
                        activeCorner = pts.indices.minByOrNull { i ->
                            hypot((pts[i].x - start.x).toDouble(), (pts[i].y - start.y).toDouble())
                        } ?: -1
                    },
                    onDragEnd = {
                        activeCorner = -1
                        latestOnEditEnd()
                    },
                    onDragCancel = {
                        activeCorner = -1
                        latestOnEditEnd()
                    },
                    onDrag = { change, _ ->
                        val index = activeCorner
                        if (index >= 0 && size.width > 0 && size.height > 0) {
                            change.consume()
                            latestOnQuadChange(
                                sanitize(
                                    index,
                                    change.position.x / size.width,
                                    change.position.y / size.height,
                                    latestQuad
                                )
                            )
                        }
                    }
                )
            }
    ) {
        Image(
            bitmap = image,
            contentDescription = context.uiText(language, "scan_screen_071"),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )
        Canvas(Modifier.fillMaxSize()) {
            val pts = points(quad).map { Offset(it.x * size.width, it.y * size.height) }
            pts.indices.forEach { i ->
                drawLine(
                    color = Color(0xFFFFC107),
                    start = pts[i],
                    end = pts[(i + 1) % pts.size],
                    strokeWidth = 5f
                )
                drawCircle(
                    color = if (i == activeCorner) Color.White else Color(0xFFFFC107),
                    radius = if (i == activeCorner) 15f else 12f,
                    center = pts[i]
                )
            }
        }
    }
    Text(
        context.uiText(language, "scan_screen_072"),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .60f)
    )
}

@Composable
private fun AdjustmentSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit = {}
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(valueText, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onValueChange, onValueChangeFinished = onValueChangeFinished, valueRange = range)
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .56f))
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun normalizedDocumentName(value: String): String {
    val trimmed = value.trim()
    val withoutKnownExtension = trimmed.replace(Regex("(?i)\\.(pdf|jpe?g)$"), "")
    return safeBaseName(withoutKnownExtension).take(80).ifBlank { "Scan" }
}

private fun safeBaseName(value: String): String =
    value.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "Scan" }

private fun shareToMessenger(context: Context, file: File) {
    require(file.exists() && file.isFile) { "scan_share_file_missing" }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val mime = if (file.extension.equals("pdf", ignoreCase = true)) "application/pdf" else "image/jpeg"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        setPackage("com.facebook.orca")
    }
    context.grantUriPermission("com.facebook.orca", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(intent)
}
