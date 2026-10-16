package com.abfilepro.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.abfilepro.app.core.AutoCaptureTracker
import com.abfilepro.app.core.LiveTranslatedBlock
import com.abfilepro.app.core.LiveTranslationGeometry
import com.abfilepro.app.core.LiveTranslationStatus
import com.abfilepro.app.core.LiveTranslator
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean
import com.abfilepro.app.core.DocumentKind
import com.abfilepro.app.core.LiveDocumentDetector
import com.abfilepro.app.core.ScanCaptureMode
import com.abfilepro.app.core.ScanDraftPage
import com.abfilepro.app.core.ScanProcessor
import com.abfilepro.app.core.ScanQuad
import com.abfilepro.app.core.SmartCaptureFiles
import com.abfilepro.app.core.SmartCaptureMode
import com.abfilepro.app.core.SmartCapturePolicy
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.i18n.uiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * P23: AB smart camera.
 *  - live green outline on every sheet in view (OpenCV, on-device);
 *  - automatic capture with a ring that fills while the sheet is held still;
 *  - continuous shooting: the camera never stops between pages, pages stack in the corner;
 *  - mode bar: Auto (recognizes document / card / receipt / several sheets), Document,
 *    Batch, Card, Receipt, Several sheets, Translation, QR;
 *  - several sheets in one shot become separate pages.
 */
@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
fun SmartScannerCamera(
    initialMode: SmartCaptureMode,
    existingPageCount: Int,
    singlePageOnly: Boolean,
    rightToLeft: Boolean = true,
    onDone: (pages: List<ScanDraftPage>, mode: ScanCaptureMode) -> Unit,
    onClose: () -> Unit,
    onOpenQr: () -> Unit,
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
    }
    LaunchedEffect(Unit) { if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA) }

    var mode by remember { mutableStateOf(initialMode) }
    // AUTO switches to the card flow when it recognizes a card (two sides expected).
    var autoBecameCard by remember { mutableStateOf(false) }
    var autoCapture by remember { mutableStateOf(true) }
    var torch by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pages by remember { mutableStateOf<List<ScanDraftPage>>(emptyList()) }
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }
    var detection by remember { mutableStateOf<LiveDocumentDetector.Result?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var showDiscard by remember { mutableStateOf(false) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraControl by remember { mutableStateOf<androidx.camera.core.CameraControl?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val tracker = remember { AutoCaptureTracker() }
    val modeRef = rememberUpdatedState(mode)
    // P24: live translation over the preview (Translation mode).
    var liveTranslate by remember { mutableStateOf(true) }
    val liveTranslateRef = rememberUpdatedState(liveTranslate)
    var translatedBlocks by remember { mutableStateOf<List<LiveTranslatedBlock>>(emptyList()) }
    var translationStatus by remember { mutableStateOf(LiveTranslationStatus.IDLE) }
    var translationAspect by remember { mutableFloatStateOf(.75f) }
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val liveTranslator = remember(language.code) { LiveTranslator(language.code) }
    val recognizing = remember { AtomicBoolean(false) }

    val effectiveMode: SmartCaptureMode = if (mode == SmartCaptureMode.AUTO && autoBecameCard) SmartCaptureMode.ID_CARD else mode
    val maxPages = (if (singlePageOnly) 1 else effectiveMode.captureMode.maxPages - existingPageCount).coerceAtLeast(1)
    val remaining = (maxPages - pages.size).coerceAtLeast(0)
    val multiSheets = effectiveMode == SmartCaptureMode.MULTI || effectiveMode == SmartCaptureMode.AUTO
    val kind = detection?.let { SmartCapturePolicy.classify(it.candidates, it.frameAspect) } ?: DocumentKind.NONE

    // Reads the live state (callbacks run after later recompositions).
    fun finalMode(): ScanCaptureMode {
        val current = if (mode == SmartCaptureMode.AUTO && autoBecameCard) SmartCaptureMode.ID_CARD else mode
        return when (current) {
            SmartCaptureMode.AUTO, SmartCaptureMode.MULTI, SmartCaptureMode.BATCH ->
                if (pages.size <= 1 && current != SmartCaptureMode.BATCH) ScanCaptureMode.DOCUMENT else ScanCaptureMode.BATCH
            else -> current.captureMode
        }
    }

    fun finish() {
        if (pages.isEmpty()) return
        onDone(pages, finalMode())
    }

    fun refreshThumbnail(page: ScanDraftPage) {
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { runCatching { ScanProcessor.renderPreview(page, 220) }.getOrNull() }
            thumbnail?.takeIf { !it.isRecycled && it !== bmp }?.let { old -> scope.launch { kotlinx.coroutines.delay(400); old.recycle() } }
            thumbnail = bmp
        }
    }

    fun capture() {
        val capture = imageCapture ?: return
        if (busy || remaining <= 0) return
        busy = true
        progress = 0f
        val shotDetection = detection
        val quads: List<ScanQuad> = when {
            shotDetection == null -> emptyList()
            multiSheets && shotDetection.candidates.size >= 2 -> shotDetection.candidates.take(remaining).map { it.quad }
            else -> listOfNotNull(shotDetection.best?.quad)
        }
        val shotKind = shotDetection?.let { SmartCapturePolicy.classify(it.candidates, it.frameAspect) } ?: DocumentKind.NONE
        val dir = File(context.cacheDir, "smart_scan/${System.nanoTime()}").apply { mkdirs() }
        val file = File(dir, "shot.jpg")
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    tracker.captured(quads.firstOrNull(), System.currentTimeMillis())
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch {
                        val newPages = withContext(Dispatchers.IO) {
                            runCatching { SmartCaptureFiles.pagesFor(file, quads, effectiveMode.captureMode) }.getOrNull()
                        }?.let { produced ->
                            if (effectiveMode == SmartCaptureMode.BOOK) SmartCapturePolicy.bookReadingOrder(produced, rightToLeft) else produced
                        }
                        busy = false
                        if (newPages.isNullOrEmpty()) {
                            runCatching { dir.deleteRecursively() }
                            Toast.makeText(context, context.uiText(language, "alpha24_capture_failed"), Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        pages = pages + newPages
                        refreshThumbnail(newPages.last())
                        if (mode == SmartCaptureMode.AUTO && pages.size == newPages.size && shotKind == DocumentKind.ID_CARD) {
                            autoBecameCard = true
                        }
                        val cardFlow = mode == SmartCaptureMode.ID_CARD || (mode == SmartCaptureMode.AUTO && autoBecameCard)
                        val single = singlePageOnly || mode == SmartCaptureMode.DOCUMENT || mode == SmartCaptureMode.RECEIPT ||
                            mode == SmartCaptureMode.TRANSLATION
                        when {
                            single -> finish()
                            cardFlow && pages.size >= 2 -> finish()
                            pages.size >= maxPages -> finish()
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    busy = false
                    runCatching { dir.deleteRecursively() }
                    Toast.makeText(context, context.uiText(language, "alpha24_capture_failed"), Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    // Auto capture: the ring fills while the sheet is held still and sharp.
    LaunchedEffect(detection, autoCapture, busy) {
        val current = detection
        if (!autoCapture || busy || current == null || remaining <= 0) {
            progress = 0f
            if (current == null) tracker.reset()
            return@LaunchedEffect
        }
        val anchor = current.best?.quad
        val sharp = current.sharpness >= SHARP_ENOUGH && current.brightness >= 40.0
        progress = tracker.update(anchor, sharp, System.currentTimeMillis())
        if (progress >= 1f) capture()
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    ScanProcessor.cacheScannerPages(context, uris.take(remaining)).map { page ->
                        // Imported photos get the same automatic corners as camera shots.
                        val preview = runCatching { ScanProcessor.renderSourcePreview(page, 1200) }.getOrNull()
                        val quad = try {
                            preview?.let { LiveDocumentDetector.detectBitmap(it, 1)?.best?.quad }
                        } finally {
                            preview?.recycle()
                        }
                        page.copy(perspective = quad)
                    }
                }
                pages = pages + imported
                imported.lastOrNull()?.let { refreshThumbnail(it) }
                if (singlePageOnly || mode == SmartCaptureMode.DOCUMENT || mode == SmartCaptureMode.RECEIPT ||
                    mode == SmartCaptureMode.TRANSLATION || pages.size >= maxPages) finish()
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Toast.makeText(context, context.uiText(language, "scan_screen_005"), Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    BackHandler { if (pages.isNotEmpty()) showDiscard = true else onClose() }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { cameraProvider?.unbindAll() }
            analysisExecutor.shutdown()
            runCatching { recognizer.close() }
            liveTranslator.close()
            thumbnail?.takeIf { !it.isRecycled }?.recycle()
        }
    }
    LaunchedEffect(torch, cameraControl) { cameraControl?.enableTorch(torch) }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // Top bar
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { if (pages.isNotEmpty()) showDiscard = true else onClose() }) {
                Icon(Icons.Default.Close, context.uiText(language, "p22_close"), tint = Color.White)
            }
            Text(
                context.uiText(language, "p23_camera_title"),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (mode == SmartCaptureMode.TRANSLATION) {
                FilterChip(
                    selected = liveTranslate,
                    onClick = { liveTranslate = !liveTranslate; if (!liveTranslate) translatedBlocks = emptyList() },
                    label = { Text(context.uiText(language, "p24_live_translate")) },
                    colors = FilterChipDefaults.filterChipColors(
                        labelColor = Color.White,
                        selectedContainerColor = SmartGreen.copy(alpha = .3f),
                        selectedLabelColor = Color.White
                    ),
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
            FilterChip(
                selected = autoCapture,
                onClick = { autoCapture = !autoCapture; tracker.reset() },
                label = { Text(context.uiText(language, if (autoCapture) "p23_auto_on" else "p23_auto_off")) },
                colors = FilterChipDefaults.filterChipColors(
                    labelColor = Color.White,
                    selectedContainerColor = SmartGreen.copy(alpha = .3f),
                    selectedLabelColor = Color.White
                )
            )
            IconButton(onClick = { torch = !torch }) {
                Icon(if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff, context.uiText(language, "p23_torch"), tint = Color.White)
            }
        }

        // Viewfinder
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (permissionGranted) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FIT_CENTER
                            setOnTouchListener { view, event ->
                                if (event.action == MotionEvent.ACTION_UP) {
                                    val point = (view as PreviewView).meteringPointFactory.createPoint(event.x, event.y)
                                    cameraControl?.startFocusAndMetering(
                                        FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                                            .setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                                    )
                                    view.performClick()
                                }
                                true
                            }
                            val providerFuture = ProcessCameraProvider.getInstance(ctx)
                            providerFuture.addListener({
                                runCatching {
                                    val provider = providerFuture.get()
                                    cameraProvider = provider
                                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(surfaceProvider) }
                                    val still = ImageCapture.Builder()
                                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                        .build()
                                    val analysis = ImageAnalysis.Builder()
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build()
                                    var lastRun = 0L
                                    var lastText = 0L
                                    analysis.setAnalyzer(analysisExecutor) { image ->
                                        var handedOff = false
                                        try {
                                            val now = System.currentTimeMillis()
                                            // P24: every ~0.7 s in Translation mode the frame also goes to text
                                            // recognition; the frame is closed when recognition finishes.
                                            if (modeRef.value == SmartCaptureMode.TRANSLATION && liveTranslateRef.value &&
                                                now - lastText >= LIVE_TEXT_INTERVAL_MS && recognizing.compareAndSet(false, true)) {
                                                val media = image.image
                                                if (media == null) {
                                                    recognizing.set(false)
                                                } else {
                                                    lastText = now
                                                    handedOff = true
                                                    val rotation = image.imageInfo.rotationDegrees
                                                    val (uw, uh) = LiveTranslationGeometry.uprightSize(image.width, image.height, rotation)
                                                    recognizer.process(InputImage.fromMediaImage(media, rotation))
                                                        .addOnSuccessListener { text ->
                                                            val blocks = text.textBlocks.mapNotNull { block ->
                                                                block.boundingBox?.let { box ->
                                                                    LiveTranslationGeometry.normalize(block.text, box.left, box.top, box.right, box.bottom, uw, uh)
                                                                }
                                                            }.take(MAX_LIVE_BLOCKS)
                                                            scope.launch {
                                                                val translated = runCatching { liveTranslator.translate(blocks) }.getOrDefault(emptyList())
                                                                translationAspect = uw.toFloat() / uh.toFloat()
                                                                translatedBlocks = translated
                                                                translationStatus = liveTranslator.status
                                                            }
                                                        }
                                                        .addOnCompleteListener {
                                                            recognizing.set(false)
                                                            image.close()
                                                        }
                                                    return@setAnalyzer
                                                }
                                            }
                                            if (now - lastRun < ANALYSIS_INTERVAL_MS) return@setAnalyzer
                                            lastRun = now
                                            val plane = image.planes.firstOrNull() ?: return@setAnalyzer
                                            val buffer = plane.buffer
                                            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                                            val maxDocs = when (modeRef.value) {
                                                SmartCaptureMode.AUTO, SmartCaptureMode.MULTI -> 6
                                                else -> 1
                                            }
                                            val result = runCatching {
                                                LiveDocumentDetector.detectLuminance(
                                                    bytes, image.width, image.height, plane.rowStride,
                                                    image.imageInfo.rotationDegrees, maxDocs
                                                )
                                            }.getOrNull()
                                            mainExecutor.execute { detection = result }
                                        } finally {
                                            if (!handedOff) image.close()
                                        }
                                    }
                                    provider.unbindAll()
                                    val camera = provider.bindToLifecycle(
                                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, still, analysis
                                    )
                                    imageCapture = still
                                    cameraControl = camera.cameraControl
                                }.onFailure {
                                    Toast.makeText(ctx, context.uiText(language, "scanner_pro_camera_005"), Toast.LENGTH_LONG).show()
                                }
                            }, mainExecutor)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Green outlines on every detected sheet.
                val current = detection
                Canvas(Modifier.fillMaxSize()) {
                    val result = current ?: return@Canvas
                    val aspect = result.frameAspect.coerceIn(.2f, 5f)
                    val boxAspect = size.width / size.height
                    val dw: Float
                    val dh: Float
                    if (boxAspect > aspect) { dh = size.height; dw = dh * aspect } else { dw = size.width; dh = dw / aspect }
                    val ox = (size.width - dw) / 2f
                    val oy = (size.height - dh) / 2f
                    fun map(x: Float, y: Float) = Offset(ox + x * dw, oy + y * dh)
                    val shown = if (multiSheets) result.candidates else listOfNotNull(result.best)
                    shown.forEach { candidate ->
                        val q = candidate.quad
                        val path = Path().apply {
                            map(q.topLeft.x, q.topLeft.y).let { moveTo(it.x, it.y) }
                            map(q.topRight.x, q.topRight.y).let { lineTo(it.x, it.y) }
                            map(q.bottomRight.x, q.bottomRight.y).let { lineTo(it.x, it.y) }
                            map(q.bottomLeft.x, q.bottomLeft.y).let { lineTo(it.x, it.y) }
                            close()
                        }
                        drawPath(path, SmartGreen.copy(alpha = .18f))
                        drawPath(path, SmartGreen, style = Stroke(width = 3.dp.toPx()))
                    }
                }

                // P24: translated text drawn over the original lines.
                if (mode == SmartCaptureMode.TRANSLATION && liveTranslate && translatedBlocks.isNotEmpty()) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val boxW = constraints.maxWidth.toFloat()
                        val boxH = constraints.maxHeight.toFloat()
                        val aspect = translationAspect.coerceIn(.2f, 5f)
                        val dw: Float; val dh: Float
                        if (boxW / boxH > aspect) { dh = boxH; dw = dh * aspect } else { dw = boxW; dh = dw / aspect }
                        val ox = (boxW - dw) / 2f
                        val oy = (boxH - dh) / 2f
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        translatedBlocks.forEach { item ->
                            val b = item.block
                            val leftPx = ox + b.left * dw
                            val topPx = oy + b.top * dh
                            val widthPx = ((b.right - b.left) * dw).coerceAtLeast(24f)
                            val heightPx = ((b.bottom - b.top) * dh).coerceAtLeast(16f)
                            with(density) {
                                val lines = item.block.text.count { it == '\n' } + 1
                                val fontPx = (heightPx / lines * .62f).coerceIn(10.sp.toPx(), 22.sp.toPx())
                                Surface(
                                    color = Color.White.copy(alpha = .93f),
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier
                                        .offset(leftPx.toDp(), topPx.toDp())
                                        .widthIn(min = widthPx.toDp())
                                        .heightIn(min = heightPx.toDp())
                                ) {
                                    Text(
                                        item.translated,
                                        color = Color(0xFF111827),
                                        fontSize = fontPx.toSp(),
                                        lineHeight = (fontPx * 1.15f).toSp(),
                                        modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Hint pill
                val hintKey = when {
                    busy -> "p23_hint_saving"
                    mode == SmartCaptureMode.TRANSLATION && liveTranslate && translationStatus == LiveTranslationStatus.DOWNLOADING -> "p24_translate_downloading"
                    mode == SmartCaptureMode.TRANSLATION && liveTranslate && translationStatus == LiveTranslationStatus.NEEDS_INTERNET -> "p24_translate_needs_internet"
                    mode == SmartCaptureMode.TRANSLATION && liveTranslate && translationStatus == LiveTranslationStatus.SAME_LANGUAGE -> "p24_translate_same_language"
                    mode == SmartCaptureMode.TRANSLATION && liveTranslate && translatedBlocks.isNotEmpty() -> "p24_translate_live"
                    mode == SmartCaptureMode.BOOK -> "p24_hint_book"
                    current == null || current.candidates.isEmpty() -> "p23_hint_point"
                    current.brightness < 40.0 -> "p23_hint_dark"
                    current.sharpness < SHARP_ENOUGH -> "p23_hint_steady"
                    effectiveMode == SmartCaptureMode.ID_CARD && pages.size == 1 -> "p23_hint_flip_card"
                    progress > 0f -> "p23_hint_hold"
                    multiSheets && current.candidates.size >= 2 -> "p23_hint_sheets"
                    mode == SmartCaptureMode.AUTO -> when (kind) {
                        DocumentKind.ID_CARD -> "p23_kind_card"
                        DocumentKind.RECEIPT -> "p23_kind_receipt"
                        DocumentKind.MULTI -> "p23_hint_sheets"
                        else -> "p23_kind_document"
                    }
                    else -> "p23_hint_ready"
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = .55f),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                ) {
                    Text(
                        context.uiText(language, hintKey, current?.candidates?.size ?: 0),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
                if (effectiveMode == SmartCaptureMode.ID_CARD && pages.size == 1) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = SmartGreen.copy(alpha = .9f),
                        modifier = Modifier.align(Alignment.Center).padding(24.dp)
                    ) {
                        Text(
                            context.uiText(language, "p23_flip_card"),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(context.uiText(language, "scanner_pro_camera_001"), color = Color.White, textAlign = TextAlign.Center)
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text(context.uiText(language, "p23_allow_camera"))
                    }
                }
            }
        }

        // Mode bar
        val modes = remember(singlePageOnly) {
            if (singlePageOnly) emptyList() else listOf(
                SmartCaptureMode.AUTO, SmartCaptureMode.DOCUMENT, SmartCaptureMode.BATCH, SmartCaptureMode.ID_CARD,
                SmartCaptureMode.RECEIPT, SmartCaptureMode.MULTI, SmartCaptureMode.BOOK, SmartCaptureMode.TRANSLATION
            )
        }
        if (modes.isNotEmpty()) {
            LazyRow(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                items(modes) { item ->
                    val selected = item == mode
                    Text(
                        context.uiText(language, modeLabelKey(item)),
                        color = if (selected) SmartGreen else Color.White.copy(alpha = .8f),
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(enabled = !busy && pages.isEmpty()) {
                                mode = item
                                autoBecameCard = false
                                tracker.reset()
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
                item {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).clickable(enabled = !busy && pages.isEmpty()) { onOpenQr() }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.QrCodeScanner, null, tint = Color.White.copy(alpha = .8f), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("QR", color = Color.White.copy(alpha = .8f))
                    }
                }
            }
        }

        // Shutter row
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Captured pages stack
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                val thumb = thumbnail
                if (thumb != null && pages.isNotEmpty()) {
                    Image(
                        bitmap = thumb.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).border(2.dp, Color.White, RoundedCornerShape(8.dp))
                            .clickable(enabled = !busy) { finish() }
                    )
                    Surface(shape = CircleShape, color = SmartGreen, modifier = Modifier.align(Alignment.TopEnd)) {
                        Text("${pages.size}", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                } else {
                    IconButton(onClick = { galleryLauncher.launch("image/*") }, enabled = !busy && remaining > 0) {
                        Icon(Icons.Default.PhotoLibrary, context.uiText(language, "p23_import"), tint = Color.White)
                    }
                }
            }

            // Shutter with auto-capture ring
            Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Color.White, radius = size.minDimension / 2f - 3.dp.toPx(), style = Stroke(4.dp.toPx()))
                    if (progress > 0f) {
                        drawArc(
                            SmartGreen, -90f, 360f * progress, false,
                            topLeft = Offset(3.dp.toPx(), 3.dp.toPx()),
                            size = Size(size.width - 6.dp.toPx(), size.height - 6.dp.toPx()),
                            style = Stroke(6.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = if (busy || remaining <= 0 || !permissionGranted) Color.White.copy(alpha = .4f) else Color.White,
                    modifier = Modifier.size(62.dp).clip(CircleShape)
                        .clickable(enabled = !busy && remaining > 0 && permissionGranted) { capture() }
                ) {
                    if (busy) Box(contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp), color = SmartGreen) }
                }
            }

            // Done
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                if (pages.isNotEmpty()) {
                    FilledIconButton(
                        onClick = { finish() },
                        enabled = !busy,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = SmartGreen)
                    ) {
                        Icon(Icons.Default.Check, context.uiText(language, "p23_done", pages.size), tint = Color.White)
                    }
                } else if (thumbnail == null) {
                    Spacer(Modifier.size(48.dp))
                }
            }
        }
    }

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text(context.uiText(language, "p23_leave_title")) },
            text = { Text(context.uiText(language, "p23_leave_message", pages.size)) },
            confirmButton = {
                TextButton(onClick = { showDiscard = false; finish() }) { Text(context.uiText(language, "p23_leave_review")) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDiscard = false
                    ScanProcessor.cleanup(pages)
                    pages = emptyList()
                    onClose()
                }) { Text(context.uiText(language, "p22_discard_confirm")) }
            }
        )
    }
}

private val SmartGreen = Color(0xFF16A34A)
private const val SHARP_ENOUGH = 40.0
private const val ANALYSIS_INTERVAL_MS = 90L
private const val LIVE_TEXT_INTERVAL_MS = 700L
private const val MAX_LIVE_BLOCKS = 24

private fun modeLabelKey(mode: SmartCaptureMode) = when (mode) {
    SmartCaptureMode.AUTO -> "p23_mode_auto"
    SmartCaptureMode.DOCUMENT -> "p23_mode_document"
    SmartCaptureMode.BATCH -> "p23_mode_batch"
    SmartCaptureMode.ID_CARD -> "p23_mode_card"
    SmartCaptureMode.RECEIPT -> "p23_mode_receipt"
    SmartCaptureMode.MULTI -> "p23_mode_multi"
    SmartCaptureMode.TRANSLATION -> "p23_mode_translation"
    SmartCaptureMode.BOOK -> "p24_mode_book"
}
