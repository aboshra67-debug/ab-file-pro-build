package com.abfilepro.app.ui.screens.assistant

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF as AndroidRectF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Surface
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

internal enum class PortraitMode {
    SELFIE, CLOSE, HALF_BODY, FULL_BODY, FORMAL, GROUP;

    fun label(code: String): String = when (this) {
        SELFIE -> portraitText(code, "سيلفي", "Selfie", "Selfie")
        CLOSE -> portraitText(code, "بورتريه قريب", "Close portrait", "Portrait rapproché")
        HALF_BODY -> portraitText(code, "نصف جسم", "Half body", "Mi-corps")
        FULL_BODY -> portraitText(code, "جسم كامل", "Full body", "Plein pied")
        FORMAL -> portraitText(code, "صورة رسمية", "Formal", "Photo officielle")
        GROUP -> portraitText(code, "مجموعة", "Group", "Groupe")
    }

    fun shortLabel(code: String): String = when (this) {
        SELFIE -> portraitText(code, "سيلفي", "Selfie", "Selfie")
        CLOSE -> portraitText(code, "قريب", "Close", "Proche")
        HALF_BODY -> portraitText(code, "نصف", "Half", "Mi-corps")
        FULL_BODY -> portraitText(code, "كامل", "Full", "Plein pied")
        FORMAL -> portraitText(code, "رسمي", "Formal", "Officiel")
        GROUP -> portraitText(code, "مجموعة", "Group", "Groupe")
    }
}

internal enum class PortraitFilter {
    NATURAL, SOFT, WARM, COOL, CINEMATIC, MONO, PRO;

    fun label(code: String): String = when (this) {
        NATURAL -> portraitText(code, "طبيعي", "Natural", "Naturel")
        SOFT -> portraitText(code, "ناعم", "Soft", "Doux")
        WARM -> portraitText(code, "دافئ", "Warm", "Chaud")
        COOL -> portraitText(code, "بارد", "Cool", "Froid")
        CINEMATIC -> portraitText(code, "سينمائي", "Cinematic", "Cinéma")
        MONO -> portraitText(code, "أبيض وأسود", "B&W", "N&B")
        PRO -> portraitText(code, "احترافي", "Professional", "Professionnel")
    }
}

private enum class PortraitCrop(val ratio: Float?) {
    ORIGINAL(null), FOUR_FIVE(4f / 5f), SQUARE(1f), THREE_FOUR(3f / 4f);

    fun label(code: String): String = when (this) {
        ORIGINAL -> portraitText(code, "أصلي", "Original", "Original")
        FOUR_FIVE -> "4:5"
        SQUARE -> "1:1"
        THREE_FOUR -> "3:4"
    }
}

private enum class PortraitFlash { OFF, AUTO, ON }

private enum class PortraitGuidance {
    NO_FACE, NEED_MORE_PEOPLE, MOVE_CLOSER, STEP_BACK, CENTER_FACE, RAISE_PHONE, LOWER_PHONE, LOW_LIGHT, HOLD_STILL, READY
}

private data class PortraitAnalysisResult(
    val faces: List<Rect> = emptyList(),
    val primaryFace: Rect? = null,
    val luminance: Float = 100f,
    val stableFrames: Int = 0,
    val guidance: PortraitGuidance = PortraitGuidance.NO_FACE,
) {
    val captureReady: Boolean get() = guidance == PortraitGuidance.READY && stableFrames >= 7
}

private sealed interface PortraitImageSource {
    data class FileSource(val file: File) : PortraitImageSource
    data class UriSource(val uri: Uri) : PortraitImageSource
}

@Composable
internal fun SmartPortraitCameraScreen(
    onBack: () -> Unit,
    onSaved: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val analyzer = remember { PortraitFrameAnalyzer(analysisExecutor) }

    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        if (!granted) {
            Toast.makeText(context, portraitText(language.code, "يلزم السماح بالكاميرا للتصوير الشخصي", "Camera permission is required", "Autorisation caméra requise"), Toast.LENGTH_LONG).show()
        }
    }

    var mode by remember { mutableStateOf(PortraitMode.SELFIE) }
    var selectedFilter by remember { mutableStateOf(PortraitFilter.NATURAL) }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_FRONT) }
    var gridEnabled by remember { mutableStateOf(true) }
    var autoCapture by remember { mutableStateOf(true) }
    var timerSeconds by remember { mutableIntStateOf(0) }
    var flash by remember { mutableStateOf(PortraitFlash.OFF) }
    var countdown by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var screenFlash by remember { mutableStateOf(false) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var cameraControl by remember { mutableStateOf<androidx.camera.core.CameraControl?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var minZoom by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(1f) }
    var analysis by remember { mutableStateOf(PortraitAnalysisResult()) }
    var editorSource by remember { mutableStateOf<PortraitImageSource?>(null) }
    var lastAutoCaptureAt by remember { mutableLongStateOf(0L) }

    analyzer.mode = mode
    analyzer.onResult = { result -> mainExecutor.execute { analysis = result } }

    fun deleteTemporarySource(source: PortraitImageSource?) {
        (source as? PortraitImageSource.FileSource)?.file?.let { runCatching { it.delete() } }
    }

    fun captureNow(useTimer: Boolean) {
        val capture = imageCapture ?: return
        if (busy || editorSource != null) return
        busy = true
        scope.launch {
            if (useTimer && timerSeconds > 0) {
                for (second in timerSeconds downTo 1) {
                    countdown = second
                    delay(1_000)
                }
                countdown = 0
            }

            val shouldScreenFlash = lensFacing == CameraSelector.LENS_FACING_FRONT &&
                (flash == PortraitFlash.ON || (flash == PortraitFlash.AUTO && analysis.luminance < 72f))
            if (shouldScreenFlash) {
                screenFlash = true
                delay(260)
            }

            val captureDir = File(context.cacheDir, "smart_portrait_capture").apply { mkdirs() }
            val file = File(captureDir, "portrait_${System.currentTimeMillis()}_${System.nanoTime()}.jpg")
            capture.targetRotation = previewView?.display?.rotation ?: Surface.ROTATION_0
            capture.flashMode = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                when (flash) {
                    PortraitFlash.OFF -> ImageCapture.FLASH_MODE_OFF
                    PortraitFlash.AUTO -> ImageCapture.FLASH_MODE_AUTO
                    PortraitFlash.ON -> ImageCapture.FLASH_MODE_ON
                }
            } else {
                ImageCapture.FLASH_MODE_OFF
            }
            val options = ImageCapture.OutputFileOptions.Builder(file).build()
            capture.takePicture(
                options,
                mainExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        screenFlash = false
                        countdown = 0
                        busy = false
                        if (!file.exists() || file.length() <= 0L) {
                            Toast.makeText(context, portraitText(language.code, "تعذر حفظ اللقطة المؤقتة", "Capture failed", "Échec de la capture"), Toast.LENGTH_LONG).show()
                            return
                        }
                        editorSource = PortraitImageSource.FileSource(file)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        screenFlash = false
                        countdown = 0
                        busy = false
                        runCatching { file.delete() }
                        Toast.makeText(context, portraitText(language.code, "تعذر التقاط الصورة", "Could not capture photo", "Impossible de prendre la photo"), Toast.LENGTH_LONG).show()
                    }
                }
            )
        }
    }

    LaunchedEffect(permissionGranted) {
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(autoCapture, analysis.captureReady, busy, editorSource) {
        if (autoCapture && analysis.captureReady && !busy && editorSource == null) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAutoCaptureAt > 3_500L) {
                lastAutoCaptureAt = now
                captureNow(useTimer = false)
            }
        }
    }

    LaunchedEffect(zoom, cameraControl) {
        cameraControl?.setZoomRatio(zoom.coerceIn(minZoom, maxZoom))
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { cameraProvider?.unbindAll() }
            analyzer.close()
            analysisExecutor.shutdown()
        }
    }

    LaunchedEffect(editorSource) {
        if (editorSource != null) runCatching { cameraProvider?.unbindAll() }
    }

    val source = editorSource
    if (source != null) {
        PortraitEditorScreen(
            source = source,
            initialFilter = selectedFilter,
            onBack = {
                deleteTemporarySource(source)
                editorSource = null
            },
            onSaved = { uri ->
                deleteTemporarySource(source)
                onSaved(uri)
            },
        )
        return
    }

    BackHandler(enabled = !busy) { onBack() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (permissionGranted) {
            key(lensFacing) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            previewView = this
                            val providerFuture = ProcessCameraProvider.getInstance(ctx)
                            providerFuture.addListener({
                                runCatching {
                                    val provider = providerFuture.get()
                                    cameraProvider = provider
                                    val preview = Preview.Builder()
                                        .setTargetRotation(display?.rotation ?: Surface.ROTATION_0)
                                        .build()
                                        .also { it.setSurfaceProvider(surfaceProvider) }
                                    val capture = ImageCapture.Builder()
                                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                        .setJpegQuality(96)
                                        .setTargetRotation(display?.rotation ?: Surface.ROTATION_0)
                                        .build()
                                    imageCapture = capture
                                    val imageAnalysis = ImageAnalysis.Builder()
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .setTargetRotation(display?.rotation ?: Surface.ROTATION_0)
                                        .build()
                                        .also { it.setAnalyzer(analysisExecutor, analyzer) }
                                    provider.unbindAll()
                                    val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                                    val camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, capture, imageAnalysis)
                                    cameraControl = camera.cameraControl
                                    camera.cameraInfo.zoomState.value?.let { state ->
                                        minZoom = state.minZoomRatio
                                        maxZoom = state.maxZoomRatio
                                        zoom = 1f.coerceIn(minZoom, maxZoom)
                                    }
                                }.onFailure {
                                    Toast.makeText(ctx, portraitText(language.code, "تعذر تشغيل الكاميرا", "Could not start camera", "Impossible de démarrer la caméra"), Toast.LENGTH_LONG).show()
                                }
                            }, mainExecutor)
                        }
                    },
                )
            }

            PortraitGuideOverlay(mode, analysis.faces, gridEnabled, analysis.captureReady)

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = .58f))
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = .42f),
                    ) {
                        IconButton(onClick = onBack, enabled = !busy) {
                            Icon(Icons.Default.Close, com.abfilepro.app.ui.i18n.a11yLabel(Icons.Default.Close), tint = Color.White)
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = mode.label(language.code),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            maxLines = 1,
                        )
                        Text(
                            text = portraitText(language.code, "كاميرا بورتريه ذكية", "Smart portrait camera", "Caméra portrait intelligente"),
                            color = Color.White.copy(alpha = .68f),
                            fontSize = 10.sp,
                            maxLines = 1,
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = Color.Black.copy(alpha = .42f)) {
                            IconButton(
                                onClick = {
                                    flash = when (flash) {
                                        PortraitFlash.OFF -> PortraitFlash.AUTO
                                        PortraitFlash.AUTO -> PortraitFlash.ON
                                        PortraitFlash.ON -> PortraitFlash.OFF
                                    }
                                },
                                enabled = !busy,
                            ) {
                                Icon(
                                    imageVector = when (flash) {
                                        PortraitFlash.OFF -> Icons.Default.FlashOff
                                        PortraitFlash.AUTO -> Icons.Default.FlashAuto
                                        PortraitFlash.ON -> Icons.Default.FlashOn
                                    },
                                    contentDescription = null,
                                    tint = if (flash == PortraitFlash.OFF) Color.White else Color(0xFF7DFFB2),
                                )
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        Surface(shape = CircleShape, color = Color.Black.copy(alpha = .42f)) {
                            IconButton(
                                onClick = {
                                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                                        CameraSelector.LENS_FACING_BACK
                                    } else {
                                        CameraSelector.LENS_FACING_FRONT
                                    }
                                    flash = PortraitFlash.OFF
                                    zoom = 1f
                                },
                                enabled = !busy,
                            ) {
                                Icon(Icons.Default.Cameraswitch, null, tint = Color.White)
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PortraitMode.entries.forEach { item ->
                        PortraitModePill(
                            text = item.shortLabel(language.code),
                            selected = item == mode,
                            onClick = { mode = item },
                        )
                    }
                }

                Surface(
                    color = if (analysis.guidance == PortraitGuidance.READY) {
                        Color(0xFF123B2A).copy(alpha = .92f)
                    } else {
                        Color.Black.copy(alpha = .62f)
                    },
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(
                        1.dp,
                        if (analysis.guidance == PortraitGuidance.READY) Color(0xFF7DFFB2).copy(alpha = .55f)
                        else Color.White.copy(alpha = .10f),
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = if (countdown > 0) countdown.toString() else guidanceText(language.code, analysis.guidance),
                            color = if (analysis.guidance == PortraitGuidance.READY) Color(0xFF7DFFB2) else Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = if (countdown > 0) 22.sp else 12.sp,
                            textAlign = TextAlign.Center,
                        )
                        if (countdown == 0) {
                            Text(
                                text = modeTip(language.code, mode),
                                color = Color.White.copy(alpha = .72f),
                                fontSize = 10.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = .84f))
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = portraitText(language.code, "فلاتر", "Filters", "Filtres"),
                        color = Color.White.copy(alpha = .70f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    PortraitFilter.entries.forEach { item ->
                        PortraitFilterPill(
                            text = item.label(language.code),
                            selected = item == selectedFilter,
                            onClick = { selectedFilter = item },
                        )
                    }
                }

                if (maxZoom > minZoom + .05f) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.White.copy(alpha = .07f),
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = .08f)),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White.copy(alpha = .10f),
                            ) {
                                Text(
                                    text = "${String.format("%.1f", zoom)}×",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                            Slider(
                                value = zoom,
                                onValueChange = { zoom = it },
                                valueRange = minZoom..maxZoom.coerceAtMost(8f),
                                modifier = Modifier.weight(1f).padding(start = 8.dp),
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier.fillMaxWidth().height(82.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    PortraitAutoCapturePill(
                        enabled = autoCapture,
                        onClick = { autoCapture = !autoCapture },
                        modifier = Modifier.align(Alignment.CenterStart),
                    )

                    Surface(
                        modifier = Modifier.size(78.dp),
                        shape = CircleShape,
                        color = Color.Transparent,
                        border = BorderStroke(3.dp, Color.White.copy(alpha = .95f)),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            FilledIconButton(
                                onClick = { captureNow(useTimer = true) },
                                enabled = !busy,
                                modifier = Modifier.size(62.dp),
                            ) {
                                if (busy) {
                                    CircularProgressIndicator(modifier = Modifier.size(27.dp), strokeWidth = 3.dp)
                                } else {
                                    Icon(Icons.Default.CameraAlt, null, modifier = Modifier.size(31.dp))
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PortraitCircleTool(
                            onClick = {
                                timerSeconds = when (timerSeconds) {
                                    0 -> 3
                                    3 -> 5
                                    5 -> 10
                                    else -> 0
                                }
                            },
                            active = timerSeconds > 0,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Timer, null, modifier = Modifier.size(21.dp))
                                Text(if (timerSeconds == 0) "0" else "${timerSeconds}s", fontSize = 8.sp)
                            }
                        }
                        PortraitCircleTool(
                            onClick = { gridEnabled = !gridEnabled },
                            active = gridEnabled,
                        ) {
                            Icon(Icons.Default.GridOn, null, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
        } else {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.CameraAlt, null, tint = Color.White, modifier = Modifier.size(54.dp))
                Spacer(Modifier.height(12.dp))
                Text(portraitText(language.code, "اسمح بالكاميرا لبدء التصوير الشخصي", "Allow camera access to start", "Autorisez la caméra pour commencer"), color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text(portraitText(language.code, "السماح بالكاميرا", "Allow camera", "Autoriser la caméra"))
                }
            }
        }

        if (screenFlash) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = .94f)))
    }
}

@Composable
private fun PortraitModePill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = if (selected) Color(0xFF2F80ED) else Color.Black.copy(alpha = .38f),
        contentColor = Color.White,
        border = BorderStroke(
            1.dp,
            if (selected) Color(0xFF7EB6FF) else Color.White.copy(alpha = .22f),
        ),
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 8.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun PortraitFilterPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) Color(0xFFE6DDFF) else Color.White.copy(alpha = .07f),
        contentColor = if (selected) Color(0xFF20173A) else Color.White,
        border = BorderStroke(
            1.dp,
            if (selected) Color(0xFFCBB8FF) else Color.White.copy(alpha = .10f),
        ),
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun PortraitAutoCapturePill(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val language = LocalAppLanguage.current
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = if (enabled) Color(0xFF2F80ED) else Color.White.copy(alpha = .08f),
        contentColor = Color.White,
        border = BorderStroke(
            1.dp,
            if (enabled) Color(0xFF79B5FF) else Color.White.copy(alpha = .10f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(17.dp))
            Text(
                text = portraitText(language.code, "تلقائي", "Auto", "Auto"),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun PortraitCircleTool(
    onClick: () -> Unit,
    active: Boolean,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(43.dp),
        shape = CircleShape,
        color = if (active) Color(0xFF163B2E) else Color.White.copy(alpha = .08f),
        contentColor = if (active) Color(0xFF7DFFB2) else Color.White,
        border = BorderStroke(
            1.dp,
            if (active) Color(0xFF7DFFB2).copy(alpha = .35f) else Color.White.copy(alpha = .08f),
        ),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
internal fun SmartPortraitGalleryEditorScreen(
    uri: Uri,
    onBack: () -> Unit,
    onSaved: (Uri) -> Unit,
) {
    PortraitEditorScreen(PortraitImageSource.UriSource(uri), PortraitFilter.NATURAL, onBack, onSaved)
}

@Composable
private fun PortraitEditorScreen(
    source: PortraitImageSource,
    initialFilter: PortraitFilter,
    onBack: () -> Unit,
    onSaved: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    var sourceBitmap by remember(source) { mutableStateOf<Bitmap?>(null) }
    var previewBitmap by remember(source) { mutableStateOf<Bitmap?>(null) }
    var faceRect by remember(source) { mutableStateOf<AndroidRectF?>(null) }
    var filter by remember(source) { mutableStateOf(initialFilter) }
    var autoEnhance by remember(source) { mutableStateOf(true) }
    var faceLight by remember(source) { mutableFloatStateOf(.18f) }
    var brightness by remember(source) { mutableFloatStateOf(0f) }
    var warmth by remember(source) { mutableFloatStateOf(0f) }
    var crop by remember(source) { mutableStateOf(PortraitCrop.ORIGINAL) }
    var rotation by remember(source) { mutableIntStateOf(0) }
    var busy by remember(source) { mutableStateOf(false) }

    BackHandler(enabled = !busy) { onBack() }

    LaunchedEffect(source) {
        sourceBitmap = withContext(Dispatchers.IO) { PortraitImageProcessor.loadBitmap(context, source, 1500) }
        val bitmap = sourceBitmap
        if (bitmap != null) faceRect = PortraitImageProcessor.detectPrimaryFaceNormalized(bitmap)
    }

    LaunchedEffect(sourceBitmap, filter, autoEnhance, faceLight, brightness, warmth, crop, rotation, faceRect) {
        val sourceImage = sourceBitmap ?: return@LaunchedEffect
        previewBitmap = withContext(Dispatchers.Default) {
            PortraitImageProcessor.applyEdits(
                source = sourceImage,
                filter = filter,
                autoEnhance = autoEnhance,
                faceLight = faceLight,
                brightness = brightness,
                warmth = warmth,
                crop = crop,
                rotationDegrees = rotation,
                faceRectNormalized = faceRect,
            )
        }
    }

    fun produceFinal(onReady: (Bitmap) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            val finalBitmap = withContext(Dispatchers.IO) { PortraitImageProcessor.loadBitmap(context, source, 3200) }
            if (finalBitmap == null) {
                busy = false
                Toast.makeText(context, portraitText(language.code, "تعذر قراءة الصورة", "Could not read image", "Impossible de lire l'image"), Toast.LENGTH_LONG).show()
                return@launch
            }
            val processed = withContext(Dispatchers.Default) {
                PortraitImageProcessor.applyEdits(
                    source = finalBitmap,
                    filter = filter,
                    autoEnhance = autoEnhance,
                    faceLight = faceLight,
                    brightness = brightness,
                    warmth = warmth,
                    crop = crop,
                    rotationDegrees = rotation,
                    faceRectNormalized = faceRect,
                )
            }
            if (processed !== finalBitmap && !finalBitmap.isRecycled) finalBitmap.recycle()
            onReady(processed)
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0B0F16))) {
        val display = previewBitmap
        if (display == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else {
            Image(
                bitmap = display.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(top = 64.dp, bottom = 238.dp),
            )
        }

        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(onClick = onBack, enabled = !busy) { Icon(Icons.Default.Refresh, com.abfilepro.app.ui.i18n.a11yLabel(Icons.Default.Refresh)) }
            Spacer(Modifier.width(8.dp))
            Text(portraitText(language.code, "تعديل الصورة", "Edit photo", "Modifier la photo"), color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.AutoAwesome, null, tint = Color(0xFFFFD166))
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xEE111827)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PortraitFilter.entries.forEach { item ->
                    FilterChip(selected = item == filter, onClick = { filter = item }, label = { Text(item.label(language.code), fontSize = 10.sp) })
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(portraitText(language.code, "تحسين تلقائي", "Auto enhance", "Amélioration auto"), color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Switch(checked = autoEnhance, onCheckedChange = { autoEnhance = it })
                IconButton(onClick = { rotation = (rotation + 90) % 360 }) { Icon(Icons.Default.RotateRight, null, tint = Color.White) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(portraitText(language.code, "إضاءة الوجه", "Face light", "Lumière visage"), color = Color.White, fontSize = 10.sp, modifier = Modifier.width(78.dp))
                Slider(value = faceLight, onValueChange = { faceLight = it }, valueRange = 0f..0.45f, modifier = Modifier.weight(1f))
                Text(portraitText(language.code, "سطوع", "Light", "Lumière"), color = Color.White, fontSize = 10.sp, modifier = Modifier.width(38.dp))
                Slider(value = brightness, onValueChange = { brightness = it }, valueRange = -.18f..0.20f, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(portraitText(language.code, "دفء اللون", "Warmth", "Chaleur"), color = Color.White, fontSize = 10.sp, modifier = Modifier.width(78.dp))
                Slider(value = warmth, onValueChange = { warmth = it }, valueRange = -.35f..0.35f, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Crop, null, tint = Color.White)
                PortraitCrop.entries.forEach { item ->
                    FilterChip(selected = crop == item, onClick = { crop = item }, label = { Text(item.label(language.code), fontSize = 10.sp) })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text(portraitText(language.code, "إعادة", "Retake", "Reprendre"))
                }
                OutlinedButton(
                    onClick = {
                        produceFinal { bitmap ->
                            scope.launch {
                                val uri = withContext(Dispatchers.IO) { PortraitImageProcessor.writeShareCopy(context, bitmap) }
                                if (!bitmap.isRecycled) bitmap.recycle()
                                busy = false
                                if (uri != null) {
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "image/jpeg"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, portraitText(language.code, "مشاركة الصورة", "Share photo", "Partager la photo")))
                                } else {
                                    Toast.makeText(context, portraitText(language.code, "تعذرت المشاركة", "Share failed", "Échec du partage"), Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(4.dp)); Text(portraitText(language.code, "مشاركة", "Share", "Partager")) }
                Button(
                    onClick = {
                        produceFinal { bitmap ->
                            scope.launch {
                                val uri = withContext(Dispatchers.IO) { PortraitImageProcessor.saveToGallery(context, bitmap) }
                                if (!bitmap.isRecycled) bitmap.recycle()
                                busy = false
                                if (uri != null) {
                                    Toast.makeText(context, portraitText(language.code, "تم حفظ الصورة", "Photo saved", "Photo enregistrée"), Toast.LENGTH_SHORT).show()
                                    onSaved(uri)
                                } else {
                                    Toast.makeText(context, portraitText(language.code, "تعذر حفظ الصورة", "Save failed", "Échec de l'enregistrement"), Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Save, null)
                    Spacer(Modifier.width(4.dp))
                    Text(portraitText(language.code, "حفظ", "Save", "Enregistrer"))
                }
            }
        }
    }
}

@Composable
private fun PortraitGuideOverlay(
    mode: PortraitMode,
    faces: List<Rect>,
    grid: Boolean,
    ready: Boolean,
) {
    Canvas(Modifier.fillMaxSize()) {
        val topInset = 168.dp.toPx().coerceAtMost(size.height * .24f)
        val bottomInset = 230.dp.toPx().coerceAtMost(size.height * .33f)
        val safeTop = topInset
        val safeBottom = (size.height - bottomInset).coerceAtLeast(safeTop + 100f)
        val safeHeight = safeBottom - safeTop

        if (grid) {
            val gridColor = Color.White.copy(alpha = .16f)
            val left = size.width * .06f
            val right = size.width * .94f
            val top = safeTop
            val bottom = safeBottom
            drawLine(gridColor, Offset(size.width / 3f, top), Offset(size.width / 3f, bottom), 1f)
            drawLine(gridColor, Offset(size.width * 2f / 3f, top), Offset(size.width * 2f / 3f, bottom), 1f)
            drawLine(gridColor, Offset(left, top + safeHeight / 3f), Offset(right, top + safeHeight / 3f), 1f)
            drawLine(gridColor, Offset(left, top + safeHeight * 2f / 3f), Offset(right, top + safeHeight * 2f / 3f), 1f)
        }

        val guide = when (mode) {
            PortraitMode.SELFIE -> Rect(size.width * .21f, safeTop + safeHeight * .08f, size.width * .79f, safeTop + safeHeight * .72f)
            PortraitMode.CLOSE -> Rect(size.width * .18f, safeTop + safeHeight * .05f, size.width * .82f, safeTop + safeHeight * .78f)
            PortraitMode.HALF_BODY -> Rect(size.width * .16f, safeTop + safeHeight * .03f, size.width * .84f, safeTop + safeHeight * .90f)
            PortraitMode.FULL_BODY -> Rect(size.width * .29f, safeTop + safeHeight * .02f, size.width * .71f, safeTop + safeHeight * .96f)
            PortraitMode.FORMAL -> Rect(size.width * .23f, safeTop + safeHeight * .05f, size.width * .77f, safeTop + safeHeight * .80f)
            PortraitMode.GROUP -> Rect(size.width * .07f, safeTop + safeHeight * .16f, size.width * .93f, safeTop + safeHeight * .80f)
        }

        val guideColor = if (ready) Color(0xFF7DFFB2) else Color.White
        drawRoundRect(
            color = guideColor.copy(alpha = if (ready) .88f else .72f),
            topLeft = Offset(guide.left, guide.top),
            size = androidx.compose.ui.geometry.Size(guide.width, guide.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(32f, 32f),
            style = Stroke(width = if (ready) 5f else 3.5f),
        )

        if (mode != PortraitMode.GROUP) {
            val headWidth = guide.width * if (mode == PortraitMode.FULL_BODY) .22f else .30f
            val headHeight = guide.height * if (mode == PortraitMode.FULL_BODY) .14f else .20f
            val headLeft = guide.center.x - headWidth / 2f
            val headTop = guide.top + guide.height * .12f
            drawOval(
                color = guideColor.copy(alpha = .20f),
                topLeft = Offset(headLeft, headTop),
                size = androidx.compose.ui.geometry.Size(headWidth, headHeight),
                style = Stroke(width = 2.5f),
            )
            val shoulderTop = headTop + headHeight * .78f
            val shoulderWidth = guide.width * if (mode == PortraitMode.FULL_BODY) .48f else .62f
            drawArc(
                color = guideColor.copy(alpha = .18f),
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(guide.center.x - shoulderWidth / 2f, shoulderTop),
                size = androidx.compose.ui.geometry.Size(shoulderWidth, guide.height * .28f),
                style = Stroke(width = 2.5f),
            )
        } else {
            val y = guide.top + guide.height * .30f
            listOf(.34f, .50f, .66f).forEach { x ->
                drawCircle(
                    color = guideColor.copy(alpha = .18f),
                    radius = guide.width * .055f,
                    center = Offset(size.width * x, y),
                    style = Stroke(width = 2.2f),
                )
            }
        }

        faces.take(5).forEach { face ->
            val left = face.left * size.width
            val top = face.top * size.height
            val right = face.right * size.width
            val bottom = face.bottom * size.height
            val corner = 12.dp.toPx()
            val faceColor = Color(0xFF7DFFB2).copy(alpha = .68f)
            drawLine(faceColor, Offset(left, top), Offset(left + corner, top), 2f)
            drawLine(faceColor, Offset(left, top), Offset(left, top + corner), 2f)
            drawLine(faceColor, Offset(right, top), Offset(right - corner, top), 2f)
            drawLine(faceColor, Offset(right, top), Offset(right, top + corner), 2f)
            drawLine(faceColor, Offset(left, bottom), Offset(left + corner, bottom), 2f)
            drawLine(faceColor, Offset(left, bottom), Offset(left, bottom - corner), 2f)
            drawLine(faceColor, Offset(right, bottom), Offset(right - corner, bottom), 2f)
            drawLine(faceColor, Offset(right, bottom), Offset(right, bottom - corner), 2f)
        }
    }
}

private class PortraitFrameAnalyzer(
    private val callbackExecutor: ExecutorService,
) : ImageAnalysis.Analyzer {
    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(.08f)
            .enableTracking()
            .build()
    )
    private val processing = AtomicBoolean(false)
    @Volatile var mode: PortraitMode = PortraitMode.SELFIE
    @Volatile var onResult: (PortraitAnalysisResult) -> Unit = {}
    private var lastCenterX = Float.NaN
    private var lastCenterY = Float.NaN
    private var lastHeight = Float.NaN
    private var lastFaceCount = 0
    private var stableFrames = 0

    @ExperimentalGetImage
    override fun analyze(image: ImageProxy) {
        if (!processing.compareAndSet(false, true)) {
            image.close()
            return
        }
        val media = image.image
        if (media == null) {
            processing.set(false)
            image.close()
            return
        }
        val rotation = image.imageInfo.rotationDegrees
        val normalizedWidth = if (rotation == 90 || rotation == 270) image.height.toFloat() else image.width.toFloat()
        val normalizedHeight = if (rotation == 90 || rotation == 270) image.width.toFloat() else image.height.toFloat()
        val luminance = averageLuma(image)
        val input = InputImage.fromMediaImage(media, rotation)
        detector.process(input)
            .addOnSuccessListener { detected ->
                val rects = detected.mapNotNull { face -> normalizeFace(face, normalizedWidth, normalizedHeight) }
                    .sortedByDescending { it.width * it.height }
                val primary = rects.firstOrNull()
                val count = rects.size
                if (primary != null) {
                    val cx = primary.center.x
                    val cy = primary.center.y
                    val h = primary.height
                    val motion = if (lastCenterX.isNaN()) 1f else hypot((cx - lastCenterX).toDouble(), (cy - lastCenterY).toDouble()).toFloat()
                    val sizeDelta = if (lastHeight.isNaN()) 1f else abs(h - lastHeight)
                    stableFrames = if (motion < .018f && sizeDelta < .028f && count == lastFaceCount) (stableFrames + 1).coerceAtMost(30) else 0
                    lastCenterX = cx
                    lastCenterY = cy
                    lastHeight = h
                    lastFaceCount = count
                } else {
                    stableFrames = 0
                    lastCenterX = Float.NaN
                    lastCenterY = Float.NaN
                    lastHeight = Float.NaN
                    lastFaceCount = 0
                }
                onResult(
                    PortraitAnalysisResult(
                        faces = rects,
                        primaryFace = primary,
                        luminance = luminance,
                        stableFrames = stableFrames,
                        guidance = evaluateGuidance(mode, rects, luminance, stableFrames),
                    )
                )
            }
            .addOnFailureListener {
                stableFrames = 0
            }
            .addOnCompleteListener(callbackExecutor) {
                processing.set(false)
                image.close()
            }
    }

    private fun normalizeFace(face: Face, width: Float, height: Float): Rect? {
        if (width <= 0f || height <= 0f) return null
        val box = face.boundingBox
        val left = (box.left / width).coerceIn(0f, 1f)
        val top = (box.top / height).coerceIn(0f, 1f)
        val right = (box.right / width).coerceIn(0f, 1f)
        val bottom = (box.bottom / height).coerceIn(0f, 1f)
        if (right <= left || bottom <= top) return null
        return Rect(left, top, right, bottom)
    }

    private fun averageLuma(image: ImageProxy): Float {
        val plane = image.planes.firstOrNull() ?: return 100f
        val buffer = plane.buffer.duplicate()
        if (!buffer.hasRemaining()) return 100f
        val step = max(1, buffer.remaining() / 800)
        var sum = 0L
        var count = 0
        var index = buffer.position()
        val limit = buffer.limit()
        while (index < limit) {
            sum += buffer.get(index).toInt() and 0xFF
            count++
            index += step
        }
        return if (count == 0) 100f else sum.toFloat() / count
    }

    fun close() {
        detector.close()
    }
}

private fun evaluateGuidance(mode: PortraitMode, faces: List<Rect>, luminance: Float, stableFrames: Int): PortraitGuidance {
    if (luminance < 52f) return PortraitGuidance.LOW_LIGHT
    if (faces.isEmpty()) return PortraitGuidance.NO_FACE
    if (mode == PortraitMode.GROUP && faces.size < 2) return PortraitGuidance.NEED_MORE_PEOPLE

    val targetRect = if (mode == PortraitMode.GROUP) {
        val left = faces.minOf { it.left }
        val top = faces.minOf { it.top }
        val right = faces.maxOf { it.right }
        val bottom = faces.maxOf { it.bottom }
        Rect(left, top, right, bottom)
    } else faces.first()

    val faceHeight = if (mode == PortraitMode.GROUP) faces.first().height else targetRect.height
    val range = when (mode) {
        PortraitMode.SELFIE -> .34f.. .58f
        PortraitMode.CLOSE -> .25f.. .45f
        PortraitMode.HALF_BODY -> .14f.. .27f
        PortraitMode.FULL_BODY -> .065f.. .155f
        PortraitMode.FORMAL -> .24f.. .38f
        PortraitMode.GROUP -> .08f.. .25f
    }
    if (faceHeight < range.start) return PortraitGuidance.MOVE_CLOSER
    if (faceHeight > range.endInclusive) return PortraitGuidance.STEP_BACK

    val targetY = when (mode) {
        PortraitMode.SELFIE -> .39f
        PortraitMode.CLOSE -> .34f
        PortraitMode.HALF_BODY -> .25f
        PortraitMode.FULL_BODY -> .15f
        PortraitMode.FORMAL -> .31f
        PortraitMode.GROUP -> .40f
    }
    val centerX = targetRect.center.x
    val centerY = targetRect.center.y
    if (abs(centerX - .5f) > .11f) return PortraitGuidance.CENTER_FACE
    if (centerY > targetY + .12f) return PortraitGuidance.RAISE_PHONE
    if (centerY < targetY - .12f) return PortraitGuidance.LOWER_PHONE
    if (stableFrames < 7) return PortraitGuidance.HOLD_STILL
    return PortraitGuidance.READY
}

private object PortraitImageProcessor {
    suspend fun detectPrimaryFaceNormalized(bitmap: Bitmap): AndroidRectF? {
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setMinFaceSize(.06f)
                .build()
        )
        return try {
            val faces = detector.process(InputImage.fromBitmap(bitmap, 0)).await()
            val largest = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return null
            AndroidRectF(
                largest.boundingBox.left.toFloat() / bitmap.width,
                largest.boundingBox.top.toFloat() / bitmap.height,
                largest.boundingBox.right.toFloat() / bitmap.width,
                largest.boundingBox.bottom.toFloat() / bitmap.height,
            )
        } catch (_: Throwable) {
            null
        } finally {
            detector.close()
        }
    }

    fun loadBitmap(context: Context, source: PortraitImageSource, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openSource(context, source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDimension * 1.35f) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = openSource(context, source)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val orientation = readOrientation(context, source)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90f
            ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180f
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270f
            else -> 0f
        }
        val mirrorX = orientation == ExifInterface.ORIENTATION_FLIP_HORIZONTAL || orientation == ExifInterface.ORIENTATION_TRANSPOSE || orientation == ExifInterface.ORIENTATION_TRANSVERSE
        if (degrees == 0f && !mirrorX) return decoded
        val matrix = Matrix().apply {
            if (mirrorX) postScale(-1f, 1f, decoded.width / 2f, decoded.height / 2f)
            if (degrees != 0f) postRotate(degrees)
        }
        val result = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (result !== decoded) decoded.recycle()
        return result
    }

    private fun openSource(context: Context, source: PortraitImageSource) = when (source) {
        is PortraitImageSource.FileSource -> source.file.inputStream()
        is PortraitImageSource.UriSource -> context.contentResolver.openInputStream(source.uri)
    }

    private fun readOrientation(context: Context, source: PortraitImageSource): Int = runCatching {
        when (source) {
            is PortraitImageSource.FileSource -> ExifInterface(source.file.absolutePath)
            is PortraitImageSource.UriSource -> context.contentResolver.openInputStream(source.uri)?.use { ExifInterface(it) }
                ?: return@runCatching ExifInterface.ORIENTATION_NORMAL
        }.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    fun applyEdits(
        source: Bitmap,
        filter: PortraitFilter,
        autoEnhance: Boolean,
        faceLight: Float,
        brightness: Float,
        warmth: Float,
        crop: PortraitCrop,
        rotationDegrees: Int,
        faceRectNormalized: AndroidRectF?,
    ): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(output)
        val matrix = buildColorMatrix(filter, autoEnhance, brightness, warmth)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
        canvas.drawBitmap(source, 0f, 0f, paint)

        if (faceLight > .005f && faceRectNormalized != null) {
            val face = AndroidRectF(
                faceRectNormalized.left * output.width,
                faceRectNormalized.top * output.height,
                faceRectNormalized.right * output.width,
                faceRectNormalized.bottom * output.height,
            )
            val expanded = AndroidRectF(
                face.left - face.width() * .38f,
                face.top - face.height() * .42f,
                face.right + face.width() * .38f,
                face.bottom + face.height() * .48f,
            )
            val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                alpha = (faceLight * 105f).toInt().coerceIn(0, 48)
            }
            canvas.drawOval(expanded, facePaint)
        }

        var current = output
        crop.ratio?.let { ratio ->
            val cropped = centerCrop(current, ratio, faceRectNormalized)
            if (cropped !== current) {
                current.recycle()
                current = cropped
            }
        }
        if (rotationDegrees % 360 != 0) {
            val rotationMatrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(current, 0, 0, current.width, current.height, rotationMatrix, true)
            if (rotated !== current) current.recycle()
            current = rotated
        }
        return current
    }

    private fun buildColorMatrix(filter: PortraitFilter, autoEnhance: Boolean, brightness: Float, warmth: Float): ColorMatrix {
        val result = ColorMatrix()
        if (autoEnhance) {
            result.postConcat(contrastMatrix(1.07f, 5f))
            val saturation = ColorMatrix().apply { setSaturation(1.04f) }
            result.postConcat(saturation)
        }
        when (filter) {
            PortraitFilter.NATURAL -> Unit
            PortraitFilter.SOFT -> {
                result.postConcat(contrastMatrix(.96f, 7f))
                result.postConcat(ColorMatrix().apply { setSaturation(.94f) })
            }
            PortraitFilter.WARM -> result.postConcat(channelMatrix(1.06f, 1.02f, .94f, 5f, 2f, -2f))
            PortraitFilter.COOL -> result.postConcat(channelMatrix(.96f, 1.01f, 1.07f, -2f, 1f, 4f))
            PortraitFilter.CINEMATIC -> {
                result.postConcat(contrastMatrix(1.13f, -3f))
                result.postConcat(ColorMatrix().apply { setSaturation(.84f) })
                result.postConcat(channelMatrix(1.03f, 1f, .96f, 2f, 0f, 2f))
            }
            PortraitFilter.MONO -> result.postConcat(ColorMatrix().apply { setSaturation(0f) })
            PortraitFilter.PRO -> {
                result.postConcat(contrastMatrix(1.11f, 2f))
                result.postConcat(ColorMatrix().apply { setSaturation(1.07f) })
            }
        }
        if (abs(brightness) > .001f) {
            val b = brightness * 255f
            result.postConcat(channelMatrix(1f, 1f, 1f, b, b, b))
        }
        if (abs(warmth) > .001f) {
            result.postConcat(channelMatrix(1f + warmth * .16f, 1f, 1f - warmth * .16f, warmth * 8f, 0f, -warmth * 8f))
        }
        return result
    }

    private fun contrastMatrix(contrast: Float, brightness: Float): ColorMatrix {
        val translate = 128f * (1f - contrast) + brightness
        return ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f,
        ))
    }

    private fun channelMatrix(r: Float, g: Float, b: Float, rt: Float, gt: Float, bt: Float): ColorMatrix = ColorMatrix(floatArrayOf(
        r, 0f, 0f, 0f, rt,
        0f, g, 0f, 0f, gt,
        0f, 0f, b, 0f, bt,
        0f, 0f, 0f, 1f, 0f,
    ))

    private fun centerCrop(bitmap: Bitmap, targetRatio: Float, face: AndroidRectF?): Bitmap {
        val currentRatio = bitmap.width.toFloat() / bitmap.height
        if (abs(currentRatio - targetRatio) < .01f) return bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val cropWidth: Int
        val cropHeight: Int
        if (currentRatio > targetRatio) {
            cropHeight = bitmap.height
            cropWidth = (cropHeight * targetRatio).toInt().coerceAtMost(bitmap.width)
        } else {
            cropWidth = bitmap.width
            cropHeight = (cropWidth / targetRatio).toInt().coerceAtMost(bitmap.height)
        }
        val preferredCenterX = (face?.centerX() ?: .5f) * bitmap.width
        val preferredCenterY = ((face?.centerY() ?: .45f) + .08f).coerceIn(.35f, .62f) * bitmap.height
        val left = (preferredCenterX - cropWidth / 2f).toInt().coerceIn(0, bitmap.width - cropWidth)
        val top = (preferredCenterY - cropHeight * .38f).toInt().coerceIn(0, bitmap.height - cropHeight)
        return Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
    }

    fun saveToGallery(context: Context, bitmap: Bitmap): Uri? {
        val fileName = "AB_Portrait_${System.currentTimeMillis()}.jpg"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/AB File Pro/Portraits")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) error("jpeg_compress_failed")
                } ?: error("output_stream_unavailable")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                uri
            } catch (_: Throwable) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                null
            }
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "AB File Pro/Portraits").apply { mkdirs() }
            val file = File(dir, fileName)
            try {
                FileOutputStream(file).use { stream ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) error("jpeg_compress_failed")
                }
                FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            } catch (_: Throwable) {
                runCatching { file.delete() }
                null
            }
        }
    }

    fun writeShareCopy(context: Context, bitmap: Bitmap): Uri? {
        val dir = File(context.cacheDir, "portrait_share").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24L * 60L * 60L * 1000L }?.forEach { runCatching { it.delete() } }
        val file = File(dir, "AB_Portrait_${System.currentTimeMillis()}.jpg")
        return try {
            FileOutputStream(file).use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 94, stream)) error("jpeg_compress_failed")
            }
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        } catch (_: Throwable) {
            runCatching { file.delete() }
            null
        }
    }
}

private fun Rect.centerX(): Float = (left + right) / 2f
private fun Rect.centerY(): Float = (top + bottom) / 2f
private val Rect.center: Offset get() = Offset(centerX(), centerY())
private val Rect.width: Float get() = right - left
private val Rect.height: Float get() = bottom - top

private fun modeTip(code: String, mode: PortraitMode): String = when (mode) {
    PortraitMode.SELFIE -> portraitText(code, "انظر للعدسة وارفع الهاتف لمستوى العين", "Look at the lens and keep the phone at eye level", "Regardez l’objectif et gardez le téléphone au niveau des yeux")
    PortraitMode.CLOSE -> portraitText(code, "اجعل الرأس والكتفين داخل الإطار", "Keep head and shoulders inside the frame", "Gardez la tête et les épaules dans le cadre")
    PortraitMode.HALF_BODY -> portraitText(code, "قف مستقيمًا واترك مساحة بسيطة فوق الرأس", "Stand straight and leave a little space above the head", "Tenez-vous droit avec un peu d’espace au-dessus de la tête")
    PortraitMode.FULL_BODY -> portraitText(code, "قف مستقيمًا واجعل الجسم كاملًا داخل الدليل", "Stand straight and keep your full body inside the guide", "Tenez-vous droit, corps entier dans le guide")
    PortraitMode.FORMAL -> portraitText(code, "واجه الكاميرا مباشرة والكتفان مستقيمان", "Face the camera directly with level shoulders", "Faites face à la caméra, épaules droites")
    PortraitMode.GROUP -> portraitText(code, "اقتربوا من بعض واجعلوا كل الوجوه داخل الإطار", "Move closer together and keep every face in frame", "Rapprochez-vous et gardez tous les visages dans le cadre")
}

private fun guidanceText(code: String, guidance: PortraitGuidance): String = when (guidance) {
    PortraitGuidance.NO_FACE -> portraitText(code, "ضع وجهك داخل الدليل", "Place your face in the guide", "Placez votre visage dans le guide")
    PortraitGuidance.NEED_MORE_PEOPLE -> portraitText(code, "أدخل شخصًا آخر في الكادر", "Add another person to the frame", "Ajoutez une autre personne")
    PortraitGuidance.MOVE_CLOSER -> portraitText(code, "اقترب قليلًا", "Move closer", "Rapprochez-vous")
    PortraitGuidance.STEP_BACK -> portraitText(code, "ارجع خطوة", "Step back", "Reculez d'un pas")
    PortraitGuidance.CENTER_FACE -> portraitText(code, "ضع الوجه في المنتصف", "Center your face", "Centrez votre visage")
    PortraitGuidance.RAISE_PHONE -> portraitText(code, "ارفع الهاتف قليلًا", "Raise the phone", "Levez le téléphone")
    PortraitGuidance.LOWER_PHONE -> portraitText(code, "اخفض الهاتف قليلًا", "Lower the phone", "Baissez le téléphone")
    PortraitGuidance.LOW_LIGHT -> portraitText(code, "الإضاءة ضعيفة", "Low light", "Faible luminosité")
    PortraitGuidance.HOLD_STILL -> portraitText(code, "اثبت لحظة", "Hold still", "Restez immobile")
    PortraitGuidance.READY -> portraitText(code, "ممتاز — الصورة جاهزة", "Great — ready", "Parfait — prêt")
}

private fun portraitText(code: String, ar: String, en: String, fr: String): String = when (code) {
    "en" -> en
    "fr" -> fr
    else -> ar
}
