package com.abfilepro.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.i18n.uiText
import com.abfilepro.app.ui.i18n.localizedLabel
import com.abfilepro.app.ui.i18n.localizedScannerWarning
import com.abfilepro.app.core.DocumentFrameAnalyzer
import com.abfilepro.app.core.FileUtils
import com.abfilepro.app.core.ScanCaptureMode
import com.abfilepro.app.core.ScanDraftPage
import com.abfilepro.app.core.ScanProcessor
import com.abfilepro.app.core.ScanQuad
import com.abfilepro.app.core.ScanInteractionRules
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerProCamera(
    mode: ScanCaptureMode,
    existingPageCount: Int,
    onPagesCaptured: (List<ScanDraftPage>) -> Unit,
    onImportRequested: () -> Unit,
    onFallbackScanner: () -> Unit,
    onReviewRequested: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        if (!granted) Toast.makeText(context, context.uiText(language, "scanner_pro_camera_001"), Toast.LENGTH_LONG).show()
    }

    // Alpha46: keep the proven manual single-document camera untouched by default.
    // Automatic bounds is an explicit camera option; it only adds live frame analysis.
    val simpleDocumentCapture = mode == ScanCaptureMode.DOCUMENT || mode == ScanCaptureMode.BATCH
    var documentAutoBounds by remember(mode) { mutableStateOf(false) }
    val effectiveDocumentDetection = mode.documentDetection && (!simpleDocumentCapture || documentAutoBounds)
    var autoCapture by remember(mode) { mutableStateOf(!simpleDocumentCapture && mode.autoCaptureDefault && mode.documentDetection) }
    var torchEnabled by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var metrics by remember { mutableStateOf<DocumentFrameAnalyzer.FrameResult?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var cameraControl by remember { mutableStateOf<androidx.camera.core.CameraControl?>(null) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var minZoomRatio by remember { mutableFloatStateOf(1f) }
    var maxZoomRatio by remember { mutableFloatStateOf(1f) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var lastAutoCaptureAt by remember { mutableLongStateOf(0L) }
    var capturedDocumentFile by remember { mutableStateOf<File?>(null) }
    var capturedDocumentPreview by remember { mutableStateOf<Bitmap?>(null) }
    // P9: retire preview bitmaps shortly after Compose has switched away from them.
    // This prevents Batch scans from retaining every page preview without recycling
    // a bitmap that may still be referenced by the current frame.
    var retiredDocumentPreview by remember { mutableStateOf<Bitmap?>(null) }
    var capturedDocumentQuad by remember { mutableStateOf<ScanQuad?>(null) }
    var savedDocumentFile by remember { mutableStateOf<File?>(null) }

    fun retireDocumentPreview() {
        val old = capturedDocumentPreview
        capturedDocumentPreview = null
        if (old != null && !old.isRecycled) {
            retiredDocumentPreview?.takeIf { it !== old && !it.isRecycled }?.recycle()
            retiredDocumentPreview = old
        }
    }

    fun clearCapturedDocumentPreview() {
        val file = capturedDocumentFile
        capturedDocumentFile = null
        retireDocumentPreview()
        capturedDocumentQuad = null
        savedDocumentFile = null
        runCatching { file?.parentFile?.deleteRecursively() }
        torchEnabled = false
        focusPoint = null
        busy = false
    }

    fun saveCapturedDocument() {
        val source = capturedDocumentFile ?: return
        if (busy || !source.exists() || source.length() <= 0L) return
        busy = true
        analysisExecutor.execute {
            val result = runCatching {
                val destinationDir = FileUtils.outputDir(context, "scans")
                val finalFile = File(
                    destinationDir,
                    "Scan_${System.currentTimeMillis()}_${System.nanoTime()}.jpg"
                )
                val tempFile = File(destinationDir, ".${finalFile.name}.part")
                try {
                    source.inputStream().use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                    }
                    require(tempFile.exists() && tempFile.length() > 0L) { "alpha25_save_output_invalid" }
                    if (!tempFile.renameTo(finalFile)) {
                        tempFile.copyTo(finalFile, overwrite = false)
                        require(tempFile.delete()) { "alpha25_save_temp_cleanup_failed" }
                    }
                    require(finalFile.exists() && finalFile.length() > 0L) { "alpha25_save_output_invalid" }
                    finalFile
                } catch (error: Throwable) {
                    runCatching { tempFile.delete() }
                    runCatching { finalFile.delete() }
                    throw error
                }
            }
            mainExecutor.execute {
                result.onSuccess { saved ->
                    savedDocumentFile = saved
                    busy = false
                    Toast.makeText(
                        context,
                        context.uiText(language, "alpha25_save_success"),
                        Toast.LENGTH_SHORT
                    ).show()
                }.onFailure {
                    busy = false
                    Toast.makeText(
                        context,
                        context.uiText(language, "alpha25_save_failed"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    fun continueCapturedDocument() {
        val source = capturedDocumentFile ?: return
        if (busy || !source.exists() || source.length() <= 0L) return

        // P9 Batch fix: hand the page to ScanScreen, clear the captured preview and
        // let the CameraX AndroidView bind again for the next page. Previously the
        // old preview stayed on screen after "next page" while CameraX was unbound.
        busy = true
        val page = ScanDraftPage(id = System.nanoTime(), file = source, perspective = capturedDocumentQuad)
        capturedDocumentFile = null
        savedDocumentFile = null
        capturedDocumentQuad = null
        metrics = null
        retireDocumentPreview()
        onPagesCaptured(listOf(page))
        busy = false
    }

    BackHandler {
        if (capturedDocumentPreview != null) {
            if (!busy) clearCapturedDocumentPreview()
        } else if (!busy) {
            onClose()
        }
    }

    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(900)
            focusPoint = null
        }
    }

    LaunchedEffect(retiredDocumentPreview) {
        val retired = retiredDocumentPreview ?: return@LaunchedEffect
        delay(250)
        if (!retired.isRecycled) retired.recycle()
        if (retiredDocumentPreview === retired) retiredDocumentPreview = null
    }

    val latestCapturedDocumentPreview by rememberUpdatedState(capturedDocumentPreview)
    val latestRetiredDocumentPreview by rememberUpdatedState(retiredDocumentPreview)

    DisposableEffect(Unit) {
        onDispose {
            runCatching { cameraProvider?.unbindAll() }
            runCatching { latestCapturedDocumentPreview?.takeIf { !it.isRecycled }?.recycle() }
            runCatching { latestRetiredDocumentPreview?.takeIf { !it.isRecycled }?.recycle() }
            analysisExecutor.shutdown()
        }
    }

    fun loadCapturedDocumentPreview(file: File): Bitmap {
        val source = ScanProcessor.renderSourcePreview(
            ScanDraftPage(id = System.nanoTime(), file = file),
            maxDimension = 1600
        )
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return source
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source) source.recycle()
        return rotated
    }

    fun remainingCapacity(): Int = (mode.maxPages - existingPageCount).coerceAtLeast(0)

    fun captureNow() {
        val capture = imageCapture
        if (capture == null || busy || remainingCapacity() <= 0) return
        busy = true
        val outputDir = File(context.cacheDir, "scanner_pro/${System.currentTimeMillis()}").apply { mkdirs() }
        val output = File(outputDir, "capture_${System.nanoTime()}.jpg")
        val rotation = previewViewRef?.display?.rotation
        if (rotation != null) capture.targetRotation = rotation
        val currentMetrics = metrics
        val liveDocumentSeedReady = simpleDocumentCapture && documentAutoBounds &&
            currentMetrics?.quad != null &&
            currentMetrics.confidence >= .54f &&
            currentMetrics.stableFrames >= 4 &&
            currentMetrics.warning !in setOf(
                DocumentFrameAnalyzer.Warning.LOW_LIGHT,
                DocumentFrameAnalyzer.Warning.GLARE,
                DocumentFrameAnalyzer.Warning.BLUR_OR_SHAKE,
                DocumentFrameAnalyzer.Warning.NO_DOCUMENT
            )
        val canTrustDetection = effectiveDocumentDetection &&
            (if (simpleDocumentCapture && documentAutoBounds) liveDocumentSeedReady else currentMetrics?.captureReady == true)
        val detectedRect = if (canTrustDetection) currentMetrics?.rect?.let { RectF(it) } else null
        val detectedQuad: ScanQuad? = if (canTrustDetection) currentMetrics?.quad else null
        val options = ImageCapture.OutputFileOptions.Builder(output).build()
        capture.takePicture(
            options,
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    if (simpleDocumentCapture) {
                        if (!(output.exists() && output.length() > 0L)) {
                            busy = false
                            runCatching { output.parentFile?.deleteRecursively() }
                            Toast.makeText(context, context.uiText(language, "alpha24_capture_failed"), Toast.LENGTH_LONG).show()
                            return
                        }

                        if (documentAutoBounds && mode != ScanCaptureMode.BATCH) {
                            // Single document keeps the proven Alpha47 behavior.
                            val page = ScanDraftPage(
                                id = System.nanoTime(),
                                file = output,
                                perspective = detectedQuad
                            )
                            busy = false
                            onPagesCaptured(listOf(page))
                            return
                        }

                        if (mode == ScanCaptureMode.BATCH) {
                            capturedDocumentQuad = if (documentAutoBounds) detectedQuad else null
                        }

                        analysisExecutor.execute {
                            val previewResult = runCatching { loadCapturedDocumentPreview(output) }
                            mainExecutor.execute {
                                previewResult.onSuccess { preview ->
                                    capturedDocumentFile = output
                                    capturedDocumentPreview = preview
                                    busy = false
                                }.onFailure {
                                    busy = false
                                    runCatching { output.parentFile?.deleteRecursively() }
                                    Toast.makeText(context, context.uiText(language, "alpha24_capture_failed"), Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        return
                    }

                    // P9 performance fix: book/card/receipt/etc. can decode, rotate, crop and
                    // split multi-megapixel images. Never run that work on the UI callback.
                    val capacity = remainingCapacity()
                    analysisExecutor.execute {
                        val pagesResult = runCatching {
                            ScanProcessor.prepareCameraCapture(output, mode, detectedRect, detectedQuad)
                        }
                        mainExecutor.execute {
                            pagesResult.onSuccess { pages ->
                                val accepted = pages.take(capacity)
                                busy = false
                                if (accepted.isNotEmpty()) onPagesCaptured(accepted)
                            }.onFailure {
                                busy = false
                                runCatching { output.parentFile?.deleteRecursively() }
                                Toast.makeText(context, context.uiText(language, "scanner_pro_camera_002"), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    busy = false
                    runCatching { output.parentFile?.deleteRecursively() }
                    Toast.makeText(context, context.uiText(language, "scanner_pro_camera_003"), Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    LaunchedEffect(metrics?.stableFrames, autoCapture, busy, existingPageCount, mode, effectiveDocumentDetection) {
        val current = metrics ?: return@LaunchedEffect
        if (!autoCapture || !effectiveDocumentDetection || busy || !current.captureReady || remainingCapacity() <= 0) return@LaunchedEffect
        // P22: the camera keeps running under the confirmation; never auto-capture behind it.
        if (capturedDocumentPreview != null) return@LaunchedEffect
        val now = SystemClock.elapsedRealtime()
        if (now - lastAutoCaptureAt >= 2500L) {
            lastAutoCaptureAt = now
            captureNow()
        }
    }

    val modeInstructionKey = when (mode) {
        ScanCaptureMode.DOCUMENT -> "scanner_stage1_hint_document"
        ScanCaptureMode.BATCH -> "scanner_stage1_hint_batch"
        ScanCaptureMode.BOOK -> "scanner_stage1_hint_book"
        ScanCaptureMode.ID_CARD, ScanCaptureMode.DUPLEX -> if (existingPageCount == 0) "scanner_stage1_hint_front" else "scanner_stage1_hint_back"
        ScanCaptureMode.RECEIPT -> "scanner_stage1_hint_receipt"
        ScanCaptureMode.TRANSLATION -> "scanner_stage1_hint_translation"
        ScanCaptureMode.PASSPORT, ScanCaptureMode.WHITEBOARD, ScanCaptureMode.PHOTO -> "scanner_stage1_hint_document"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scanner Pro • ${context.localizedLabel(language, mode)}") },
                navigationIcon = {
                    IconButton(onClick = onClose, enabled = !busy) { Icon(Icons.Default.Close, context.uiText(language, "scanner_pro_camera_004")) }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceVariant)) {
                if (permissionGranted) {
                    val capturedPreview = capturedDocumentPreview
                    // P22: the live camera stays composed (and bound) under the captured-page
                    // confirmation, so "next page" shows the camera instantly instead of a black
                    // screen while CameraX re-opens.

                    key(if (simpleDocumentCapture) documentAutoBounds else false) {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                scaleType = PreviewView.ScaleType.FIT_CENTER
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                previewViewRef = this
                                val previewView = this

                                val scaleDetector = ScaleGestureDetector(
                                    ctx,
                                    object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                                        override fun onScale(detector: ScaleGestureDetector): Boolean {
                                            val next = ScanInteractionRules.nextZoomRatio(
                                                currentRatio = zoomRatio,
                                                scaleFactor = detector.scaleFactor,
                                                minRatio = minZoomRatio,
                                                maxRatio = maxZoomRatio
                                            )
                                            zoomRatio = next
                                            cameraControl?.setZoomRatio(next)
                                            return true
                                        }
                                    }
                                )
                                val tapDetector = GestureDetector(
                                    ctx,
                                    object : GestureDetector.SimpleOnGestureListener() {
                                        override fun onDown(e: MotionEvent): Boolean = true

                                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                                            val point = previewView.meteringPointFactory.createPoint(e.x, e.y)
                                            val action = FocusMeteringAction.Builder(
                                                point,
                                                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                                            )
                                                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                                .build()
                                            cameraControl?.startFocusAndMetering(action)
                                            focusPoint = Offset(e.x, e.y)
                                            previewView.performClick()
                                            return true
                                        }
                                    }
                                )
                                setOnTouchListener { _, event ->
                                    scaleDetector.onTouchEvent(event)
                                    tapDetector.onTouchEvent(event)
                                    true
                                }

                                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                                providerFuture.addListener({
                                    runCatching {
                                        val provider = providerFuture.get()
                                        cameraProvider = provider
                                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(surfaceProvider) }
                                        val capture = ImageCapture.Builder()
                                            .setCaptureMode(
                                                if (mode == ScanCaptureMode.PHOTO) ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                                                else ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                                            )
                                            .build()
                                        imageCapture = capture

                                        provider.unbindAll()
                                        val camera = if (simpleDocumentCapture && !documentAutoBounds) {
                                            // Manual document mode is the original lean camera: preview + still capture only.
                                            metrics = null
                                            provider.bindToLifecycle(
                                                lifecycleOwner,
                                                CameraSelector.DEFAULT_BACK_CAMERA,
                                                preview,
                                                capture
                                            )
                                        } else {
                                            val analysis = ImageAnalysis.Builder()
                                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                                .build()
                                            analysis.setAnalyzer(
                                                analysisExecutor,
                                                DocumentFrameAnalyzer(
                                                    mode = mode,
                                                    liveDocumentPreview = simpleDocumentCapture && documentAutoBounds
                                                ) { result ->
                                                    mainExecutor.execute { metrics = result }
                                                }
                                            )
                                            provider.bindToLifecycle(
                                                lifecycleOwner,
                                                CameraSelector.DEFAULT_BACK_CAMERA,
                                                preview,
                                                capture,
                                                analysis
                                            )
                                        }
                                        cameraControl = camera.cameraControl
                                        camera.cameraInfo.zoomState.value?.let { state ->
                                            minZoomRatio = state.minZoomRatio
                                            maxZoomRatio = state.maxZoomRatio
                                            zoomRatio = state.zoomRatio
                                        }
                                    }.onFailure {
                                        Toast.makeText(ctx, context.uiText(language, "scanner_pro_camera_005"), Toast.LENGTH_LONG).show()
                                    }
                                }, mainExecutor)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    }

                    val overlayQuad = if (effectiveDocumentDetection) metrics?.quad else null
                    val ready = effectiveDocumentDetection &&
                        (if (simpleDocumentCapture && documentAutoBounds) {
                            metrics?.quad != null &&
                                (metrics?.confidence ?: 0f) >= .54f &&
                                (metrics?.stableFrames ?: 0) >= 4 &&
                                metrics?.warning !in setOf(
                                    DocumentFrameAnalyzer.Warning.LOW_LIGHT,
                                    DocumentFrameAnalyzer.Warning.GLARE,
                                    DocumentFrameAnalyzer.Warning.BLUR_OR_SHAKE,
                                    DocumentFrameAnalyzer.Warning.NO_DOCUMENT
                                )
                        } else metrics?.captureReady == true)
                    val strokeColor = if (ready) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                    Canvas(Modifier.fillMaxSize()) {
                        overlayQuad?.let { quad ->
                            // P9 alignment fix: PreviewView is FIT_CENTER (letterboxed), not FILL_CENTER.
                            // Use the same fit transform here so the live four-corner outline stays
                            // on the paper instead of drifting on tall/wide screens.
                            val imageAspect = (metrics?.previewAspectRatio ?: (size.width / size.height)).coerceIn(.25f, 4f)
                            val viewAspect = size.width / size.height.coerceAtLeast(1f)
                            val scaledWidth: Float
                            val scaledHeight: Float
                            if (imageAspect > viewAspect) {
                                scaledWidth = size.width
                                scaledHeight = size.width / imageAspect
                            } else {
                                scaledHeight = size.height
                                scaledWidth = size.height * imageAspect
                            }
                            val offsetX = (size.width - scaledWidth) / 2f
                            val offsetY = (size.height - scaledHeight) / 2f
                            fun mapPoint(point: com.abfilepro.app.core.ScanPoint) = Offset(
                                offsetX + point.x * scaledWidth,
                                offsetY + point.y * scaledHeight
                            )
                            val points = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft).map(::mapPoint)
                            points.indices.forEach { i ->
                                drawLine(
                                    color = strokeColor.copy(alpha = .95f),
                                    start = points[i],
                                    end = points[(i + 1) % points.size],
                                    strokeWidth = if (ready) 8f else 6f
                                )
                                drawCircle(strokeColor.copy(alpha = .95f), radius = if (ready) 10f else 8f, center = points[i])
                            }
                        }
                        focusPoint?.let { point ->
                            drawCircle(
                                color = strokeColor,
                                radius = 34f,
                                center = point,
                                style = Stroke(width = 4f)
                            )
                            drawCircle(strokeColor, radius = 4f, center = point)
                        }
                    }

                    Surface(
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = .88f)
                    ) {
                        Text(
                            String.format("%.1f×", zoomRatio),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }

                    Surface(
                        modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 56.dp, vertical = 12.dp),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = .88f)
                    ) {
                        val text = when {
                            simpleDocumentCapture && !documentAutoBounds -> context.uiText(language, "scanner_stage1_hint_document")
                            !effectiveDocumentDetection -> context.uiText(language, "alpha18_camera_full_frame_hint")
                            ready -> context.uiText(language, "scanner_pro_camera_007")
                            metrics?.warning != null -> metrics?.warning?.let { context.localizedScannerWarning(language, it) }
                                ?: context.uiText(language, modeInstructionKey)
                            else -> context.uiText(language, modeInstructionKey)
                        }
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (ready) Icons.Default.CheckCircle else Icons.Default.Info,
                                null,
                                tint = if (ready) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(text, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    
                    if (simpleDocumentCapture && capturedPreview != null) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
                        Image(
                            bitmap = capturedPreview.asImageBitmap(),
                            contentDescription = context.uiText(language, "alpha24_capture_success"),
                            modifier = Modifier.fillMaxSize().padding(12.dp),
                            contentScale = ContentScale.Fit
                        )
                        Surface(
                            modifier = Modifier.align(Alignment.TopCenter).padding(14.dp),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = .94f),
                            tonalElevation = 4.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                                Text(context.uiText(language, "alpha24_capture_success"), style = MaterialTheme.typography.titleSmall)
                            }
                        }

                        }
                    }
                } else {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.NoPhotography, null, modifier = Modifier.size(72.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(context.uiText(language, "scanner_pro_camera_009"), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text(context.uiText(language, "scanner_pro_camera_010"))
                        }
                        TextButton(onClick = onImportRequested) { Text(context.uiText(language, "scanner_pro_camera_011")) }
                    }
                }
            }

            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (simpleDocumentCapture && capturedDocumentPreview != null) {
                        Text(
                            if (mode == ScanCaptureMode.BATCH)
                                context.uiText(language, "alpha50_batch_page_number", existingPageCount + 1)
                            else context.uiText(language, "alpha24_capture_success"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { clearCapturedDocumentPreview() },
                                enabled = !busy,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Refresh, null)
                                Spacer(Modifier.width(6.dp))
                                Text(context.uiText(language, "alpha24_retake"))
                            }
                            Button(
                                onClick = { continueCapturedDocument() },
                                enabled = !busy,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.ArrowForward, null)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (mode == ScanCaptureMode.BATCH) context.uiText(language, "alpha50_next_page")
                                    else context.uiText(language, "alpha20_continue")
                                )
                            }
                        }
                    } else {
                    if (simpleDocumentCapture) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = !documentAutoBounds,
                                onClick = {
                                    if (!busy) {
                                        documentAutoBounds = false
                                        metrics = null
                                    }
                                },
                                label = { Text(context.uiText(language, "alpha46_camera_manual")) },
                                leadingIcon = { Icon(Icons.Default.TouchApp, null, Modifier.size(18.dp)) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = documentAutoBounds,
                                onClick = {
                                    if (!busy) {
                                        documentAutoBounds = true
                                        metrics = null
                                    }
                                },
                                label = { Text(context.uiText(language, "alpha46_camera_auto")) },
                                leadingIcon = { Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (mode.maxPages > 1) {
                            AssistChip(
                                onClick = {},
                                label = { Text(context.uiText(language, "scanner_pro_camera_012", existingPageCount, mode.maxPages)) },
                                leadingIcon = { Icon(Icons.Default.Collections, null, Modifier.size(18.dp)) }
                            )
                        } else {
                            Text(
                                context.uiText(language, modeInstructionKey),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (effectiveDocumentDetection && !simpleDocumentCapture) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(context.uiText(language, "scanner_pro_camera_013"), style = MaterialTheme.typography.bodySmall)
                                Switch(checked = autoCapture, onCheckedChange = { autoCapture = it }, enabled = !busy)
                            }
                        }
                        IconButton(
                            onClick = {
                                torchEnabled = !torchEnabled
                                cameraControl?.enableTorch(torchEnabled)
                            },
                            enabled = permissionGranted
                        ) {
                            Icon(if (torchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff, context.uiText(language, "scanner_pro_camera_014"))
                        }
                    }

                    Text(
                        context.uiText(language, "alpha12_camera_gesture_hint"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = onImportRequested, modifier = Modifier.weight(1f), enabled = !busy && remainingCapacity() > 0) {
                            Icon(Icons.Default.PhotoLibrary, null)
                            Spacer(Modifier.width(6.dp))
                            Text(context.uiText(language, "scanner_pro_camera_015"))
                        }
                        FilledIconButton(
                            onClick = { captureNow() },
                            enabled = permissionGranted && !busy && remainingCapacity() > 0,
                            modifier = Modifier.size(66.dp)
                        ) {
                            if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.CameraAlt, context.uiText(language, "scanner_pro_camera_016"), modifier = Modifier.size(30.dp))
                        }
                        Spacer(Modifier.weight(1f))
                    }

                    if (existingPageCount > 0) {
                        Button(onClick = onReviewRequested, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Preview, null)
                            Spacer(Modifier.width(7.dp))
                            Text(
                                if (mode == ScanCaptureMode.BATCH) context.uiText(language, "alpha50_save_review", existingPageCount)
                                else if (remainingCapacity() == 0) context.uiText(language, "scanner_pro_camera_018")
                                else context.uiText(language, "scanner_pro_camera_019", existingPageCount)
                            )
                        }
                    }

                    if (effectiveDocumentDetection && metrics != null) {
                        val confidence = ((metrics?.confidence ?: 0f) * 100f).toInt().coerceIn(0, 100)
                        LinearProgressIndicator(
                            progress = { confidence / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            context.uiText(language, "scanner_pro_camera_020", confidence, metrics?.stableFrames ?: 0),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f)
                        )
                    }
                    }
                }
            }
        }
    }
}
