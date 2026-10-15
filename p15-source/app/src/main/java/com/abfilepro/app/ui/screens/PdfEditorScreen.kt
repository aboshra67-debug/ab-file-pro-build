package com.abfilepro.app.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clipScrollableContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.abfilepro.app.core.ConversionDraftManager
import com.abfilepro.app.core.FileUtils
import com.abfilepro.app.core.PdfEditorEngine
import com.abfilepro.app.core.PdfViewerUtils
import com.abfilepro.app.core.RuntimeSafety
import com.abfilepro.app.ui.components.ScreenTopBar
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.i18n.uiText
import com.abfilepro.app.ui.i18n.localizedOperationFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private enum class EditorTool {
    PAN, SELECT, PENCIL, SIGNATURE, HIGHLIGHT, RECTANGLE, ELLIPSE, ARROW,
    UNDERLINE, STRIKEOUT, REDACT, TEXT, NOTE, IMAGE, STAMP
}

private enum class StrokeKind { PENCIL, SIGNATURE, HIGHLIGHT }
private enum class ShapeKind { RECTANGLE, ELLIPSE, ARROW, UNDERLINE, STRIKEOUT, REDACT }
private enum class ElementKind { STROKE, SHAPE, LABEL, IMAGE, STAMP }
private enum class EditorTextAlign { LEFT, CENTER, RIGHT }

private data class EditorStroke(
    val points: List<Offset>,
    val kind: StrokeKind,
    val scale: Float = 1f,
    val order: Long,
    val layer: Long = order,
    val colorArgb: Int = 0xFF0D1B3D.toInt(),
    val thickness: Float = 1f,
    val opacity: Float = 1f
)

private data class EditorShape(
    val start: Offset,
    val end: Offset,
    val kind: ShapeKind,
    val order: Long,
    val layer: Long = order,
    val scale: Float = 1f,
    val colorArgb: Int = 0xFF2563EB.toInt(),
    val thickness: Float = 1f
)

private data class EditorLabel(
    val text: String,
    val position: Offset,
    val note: Boolean,
    val scale: Float = 1f,
    val order: Long,
    val layer: Long = order,
    val colorArgb: Int = 0xFF0D1B3D.toInt(),
    val align: EditorTextAlign = EditorTextAlign.RIGHT
)

private data class EditorImage(
    val bitmap: Bitmap,
    val position: Offset,
    val widthFraction: Float,
    val order: Long,
    val rotationDegrees: Float = 0f,
    val layer: Long = order
)

private data class EditorStamp(
    val text: String,
    val position: Offset,
    val scale: Float = 1f,
    val order: Long,
    val rotationDegrees: Float = 0f,
    val layer: Long = order
)

private data class EditorSelection(val kind: ElementKind, val index: Int)

private enum class RenderKind { STROKE, SHAPE, LABEL, IMAGE, STAMP }
private data class RenderRef(val order: Long, val kind: RenderKind, val index: Int)

private data class EditorPageDraft(
    val strokes: List<EditorStroke>,
    val shapes: List<EditorShape>,
    val labels: List<EditorLabel>,
    val images: List<EditorImage>,
    val stamps: List<EditorStamp>
) {
    fun isEmpty(): Boolean = strokes.isEmpty() && shapes.isEmpty() && labels.isEmpty() && images.isEmpty() && stamps.isEmpty()
    fun maxOrder(): Long = sequenceOf(
        strokes.maxOfOrNull { it.order } ?: -1L,
        shapes.maxOfOrNull { it.order } ?: -1L,
        labels.maxOfOrNull { it.order } ?: -1L,
        images.maxOfOrNull { it.order } ?: -1L,
        stamps.maxOfOrNull { it.order } ?: -1L
    ).maxOrNull() ?: -1L
}

@Composable
fun PdfEditorScreen(uriText: String, onBack: () -> Unit, onOpenPdf: (File) -> Unit) {
    val context = LocalContext.current
    val appLanguage = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    val sourceUri = remember(uriText) { Uri.parse(uriText) }

    var workingFile by remember(uriText) { mutableStateOf<File?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var pageIndex by remember { mutableIntStateOf(0) }
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(true) }
    var tool by remember { mutableStateOf(EditorTool.PENCIL) }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val strokes = remember { mutableStateListOf<EditorStroke>() }
    val shapes = remember { mutableStateListOf<EditorShape>() }
    val labels = remember { mutableStateListOf<EditorLabel>() }
    val images = remember { mutableStateListOf<EditorImage>() }
    val stamps = remember { mutableStateListOf<EditorStamp>() }
    val pageDrafts = remember { mutableStateMapOf<Int, EditorPageDraft>() }
    var shapeStart by remember { mutableStateOf<Offset?>(null) }
    var shapeEnd by remember { mutableStateOf<Offset?>(null) }
    var placement by remember { mutableStateOf(Offset(.5f, .5f)) }
    var showTextDialog by remember { mutableStateOf(false) }
    var showStampDialog by remember { mutableStateOf(false) }
    var textValue by remember { mutableStateOf("") }
    var textAsNote by remember { mutableStateOf(false) }
    var editingLabelIndex by remember { mutableStateOf<Int?>(null) }
    var editingStampIndex by remember { mutableStateOf<Int?>(null) }
    var selected by remember { mutableStateOf<EditorSelection?>(null) }
    var editSerial by remember { mutableLongStateOf(0L) }
    var viewZoom by remember { mutableFloatStateOf(1f) }
    var viewPan by remember { mutableStateOf(Offset.Zero) }
    var activeColorArgb by remember { mutableIntStateOf(0xFF0D1B3D.toInt()) }
    var activeThickness by remember { mutableFloatStateOf(1f) }
    var activeHighlightOpacity by remember { mutableFloatStateOf(.38f) }
    var activeTextAlign by remember { mutableStateOf(EditorTextAlign.RIGHT) }
    var pendingEditorDraft by remember { mutableStateOf<File?>(null) }
    var pendingEditorSummary by remember { mutableStateOf("") }
    var activeSaveJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) { ConversionDraftManager.cleanup(context) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    fun nextOrder(): Long { editSerial += 1L; return editSerial }

    fun clearCurrentEdits() {
        strokes.clear(); shapes.clear(); labels.clear(); images.clear(); stamps.clear()
        current = emptyList(); shapeStart = null; shapeEnd = null
        selected = null; editSerial = 0L
    }

    fun currentDraft() = EditorPageDraft(
        strokes.toList(), shapes.toList(), labels.toList(), images.toList(), stamps.toList()
    )

    fun storeCurrentDraft() {
        val draft = currentDraft()
        if (draft.isEmpty()) pageDrafts.remove(pageIndex) else pageDrafts[pageIndex] = draft
    }

    fun restoreDraft(index: Int) {
        clearCurrentEdits()
        pageDrafts[index]?.let { draft ->
            strokes.addAll(draft.strokes)
            shapes.addAll(draft.shapes)
            labels.addAll(draft.labels)
            images.addAll(draft.images)
            stamps.addAll(draft.stamps)
            editSerial = draft.maxOrder().coerceAtLeast(0L)
        }
    }

    fun hasCurrentEdits() = !currentDraft().isEmpty()

    fun selectedScale(): Pair<Float, ClosedFloatingPointRange<Float>>? = when (val s = selected) {
        null -> null
        else -> when (s.kind) {
            ElementKind.STROKE -> strokes.getOrNull(s.index)?.let { it.scale to (.55f..2.4f) }
            ElementKind.SHAPE -> shapes.getOrNull(s.index)?.takeIf { it.kind != ShapeKind.REDACT }?.let { it.scale to (.50f..2.5f) }
            ElementKind.LABEL -> labels.getOrNull(s.index)?.let { it.scale to (.60f..2.5f) }
            ElementKind.IMAGE -> images.getOrNull(s.index)?.let { it.widthFraction to (.08f..0.75f) }
            ElementKind.STAMP -> stamps.getOrNull(s.index)?.let { it.scale to (.60f..2.5f) }
        }
    }

    fun updateSelectedScale(value: Float) {
        when (val s = selected) {
            null -> Unit
            else -> when (s.kind) {
                ElementKind.STROKE -> strokes.getOrNull(s.index)?.let { strokes[s.index] = it.copy(scale = value) }
                ElementKind.SHAPE -> shapes.getOrNull(s.index)?.takeIf { it.kind != ShapeKind.REDACT }?.let { shapes[s.index] = it.copy(scale = value.coerceIn(.50f, 2.5f)) }
                ElementKind.LABEL -> labels.getOrNull(s.index)?.let { labels[s.index] = it.copy(scale = value) }
                ElementKind.IMAGE -> images.getOrNull(s.index)?.let { images[s.index] = it.copy(widthFraction = value) }
                ElementKind.STAMP -> stamps.getOrNull(s.index)?.let { stamps[s.index] = it.copy(scale = value) }
            }
        }
    }

    fun selectedRotation(): Float? = when (val s = selected) {
        null -> null
        else -> when (s.kind) {
            ElementKind.IMAGE -> images.getOrNull(s.index)?.rotationDegrees
            ElementKind.STAMP -> stamps.getOrNull(s.index)?.rotationDegrees
            else -> null
        }
    }

    fun updateSelectedRotation(value: Float) {
        val normalized = value.coerceIn(-180f, 180f)
        when (val s = selected) {
            null -> Unit
            else -> when (s.kind) {
                ElementKind.IMAGE -> images.getOrNull(s.index)?.let { images[s.index] = it.copy(rotationDegrees = normalized) }
                ElementKind.STAMP -> stamps.getOrNull(s.index)?.let { stamps[s.index] = it.copy(rotationDegrees = normalized) }
                else -> Unit
            }
        }
    }

    fun selectedColor(): Int? = when (val s = selected) {
        null -> null
        else -> when (s.kind) {
            ElementKind.STROKE -> strokes.getOrNull(s.index)?.colorArgb
            ElementKind.SHAPE -> shapes.getOrNull(s.index)?.takeIf { it.kind != ShapeKind.REDACT }?.colorArgb
            ElementKind.LABEL -> labels.getOrNull(s.index)?.colorArgb
            ElementKind.STAMP -> 0xFFB91C1C.toInt()
            ElementKind.IMAGE -> null
        }
    }

    fun updateSelectedColor(argb: Int) {
        when (val s = selected) {
            null -> Unit
            else -> when (s.kind) {
                ElementKind.STROKE -> strokes.getOrNull(s.index)?.let { strokes[s.index] = it.copy(colorArgb = argb) }
                ElementKind.SHAPE -> shapes.getOrNull(s.index)?.takeIf { it.kind != ShapeKind.REDACT }?.let { shapes[s.index] = it.copy(colorArgb = argb) }
                ElementKind.LABEL -> labels.getOrNull(s.index)?.let { labels[s.index] = it.copy(colorArgb = argb) }
                else -> Unit
            }
        }
    }

    fun selectedThickness(): Float? = when (val s = selected) {
        null -> null
        else -> when (s.kind) {
            ElementKind.STROKE -> strokes.getOrNull(s.index)?.thickness
            ElementKind.SHAPE -> shapes.getOrNull(s.index)?.takeIf { it.kind != ShapeKind.REDACT }?.thickness
            else -> null
        }
    }

    fun updateSelectedThickness(value: Float) {
        val v = value.coerceIn(.6f, 3f)
        val s = selected ?: return
        when (s.kind) {
            ElementKind.STROKE -> strokes.getOrNull(s.index)?.let { strokes[s.index] = it.copy(thickness = v) }
            ElementKind.SHAPE -> shapes.getOrNull(s.index)?.takeIf { it.kind != ShapeKind.REDACT }?.let { shapes[s.index] = it.copy(thickness = v) }
            else -> Unit
        }
    }

    fun selectedHighlightOpacity(): Float? {
        val s = selected ?: return null
        if (s.kind != ElementKind.STROKE) return null
        return strokes.getOrNull(s.index)?.takeIf { it.kind == StrokeKind.HIGHLIGHT }?.opacity
    }

    fun updateSelectedHighlightOpacity(value: Float) {
        val s = selected ?: return
        if (s.kind == ElementKind.STROKE) strokes.getOrNull(s.index)?.takeIf { it.kind == StrokeKind.HIGHLIGHT }?.let {
            strokes[s.index] = it.copy(opacity = value.coerceIn(.15f, .8f))
        }
    }

    fun selectedTextAlign(): EditorTextAlign? {
        val s = selected ?: return null
        return if (s.kind == ElementKind.LABEL) labels.getOrNull(s.index)?.align else null
    }

    fun updateSelectedTextAlign(align: EditorTextAlign) {
        val s = selected ?: return
        if (s.kind == ElementKind.LABEL) labels.getOrNull(s.index)?.let { labels[s.index] = it.copy(align = align) }
    }

    fun resetView() {
        viewZoom = 1f
        viewPan = Offset.Zero
    }

    fun clampPan(candidate: Offset, zoom: Float, width: Float, height: Float): Offset {
        if (zoom <= 1.001f) return Offset.Zero
        val maxX = width * (zoom - 1f) / 2f
        val maxY = height * (zoom - 1f) / 2f
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }

    fun selectedLayer(): Long? = when (val s = selected) {
        null -> null
        else -> when (s.kind) {
            ElementKind.STROKE -> strokes.getOrNull(s.index)?.layer
            ElementKind.SHAPE -> shapes.getOrNull(s.index)?.takeIf { it.kind != ShapeKind.REDACT }?.layer
            ElementKind.LABEL -> labels.getOrNull(s.index)?.layer
            ElementKind.IMAGE -> images.getOrNull(s.index)?.layer
            ElementKind.STAMP -> stamps.getOrNull(s.index)?.layer
        }
    }

    fun setSelectedLayer(layer: Long) {
        when (val s = selected) {
            null -> Unit
            else -> when (s.kind) {
                ElementKind.STROKE -> strokes.getOrNull(s.index)?.let { strokes[s.index] = it.copy(layer = layer) }
                ElementKind.SHAPE -> shapes.getOrNull(s.index)?.takeIf { it.kind != ShapeKind.REDACT }?.let { shapes[s.index] = it.copy(layer = layer) }
                ElementKind.LABEL -> labels.getOrNull(s.index)?.let { labels[s.index] = it.copy(layer = layer) }
                ElementKind.IMAGE -> images.getOrNull(s.index)?.let { images[s.index] = it.copy(layer = layer) }
                ElementKind.STAMP -> stamps.getOrNull(s.index)?.let { stamps[s.index] = it.copy(layer = layer) }
            }
        }
    }

    fun replaceLayer(oldLayer: Long, newLayer: Long) {
        strokes.indexOfFirst { it.layer == oldLayer }.takeIf { it >= 0 }?.let { i -> strokes[i] = strokes[i].copy(layer = newLayer); return }
        shapes.indexOfFirst { it.layer == oldLayer }.takeIf { it >= 0 }?.let { i -> shapes[i] = shapes[i].copy(layer = newLayer); return }
        labels.indexOfFirst { it.layer == oldLayer }.takeIf { it >= 0 }?.let { i -> labels[i] = labels[i].copy(layer = newLayer); return }
        images.indexOfFirst { it.layer == oldLayer }.takeIf { it >= 0 }?.let { i -> images[i] = images[i].copy(layer = newLayer); return }
        stamps.indexOfFirst { it.layer == oldLayer }.takeIf { it >= 0 }?.let { i -> stamps[i] = stamps[i].copy(layer = newLayer); return }
    }

    fun moveSelectedLayer(forward: Boolean) {
        val currentLayer = selectedLayer() ?: return
        val allLayers = buildList {
            addAll(strokes.map { it.layer }); addAll(shapes.map { it.layer }); addAll(labels.map { it.layer })
            addAll(images.map { it.layer }); addAll(stamps.map { it.layer })
        }.distinct().sorted()
        val position = allLayers.indexOf(currentLayer)
        if (position < 0) return
        val targetIndex = if (forward) position + 1 else position - 1
        if (targetIndex !in allLayers.indices) return
        val targetLayer = allLayers[targetIndex]
        replaceLayer(targetLayer, currentLayer)
        setSelectedLayer(targetLayer)
    }

    fun moveSelected(delta: Offset) {
        fun moved(p: Offset) = Offset((p.x + delta.x).coerceIn(0f, 1f), (p.y + delta.y).coerceIn(0f, 1f))
        when (val s = selected) {
            null -> Unit
            else -> when (s.kind) {
                ElementKind.STROKE -> strokes.getOrNull(s.index)?.let { st ->
                    strokes[s.index] = st.copy(points = st.points.map(::moved))
                }
                ElementKind.SHAPE -> shapes.getOrNull(s.index)?.takeIf { it.kind != ShapeKind.REDACT }?.let { sh ->
                    shapes[s.index] = sh.copy(start = moved(sh.start), end = moved(sh.end))
                }
                ElementKind.LABEL -> labels.getOrNull(s.index)?.let { labels[s.index] = it.copy(position = moved(it.position)) }
                ElementKind.IMAGE -> images.getOrNull(s.index)?.let { images[s.index] = it.copy(position = moved(it.position)) }
                ElementKind.STAMP -> stamps.getOrNull(s.index)?.let { stamps[s.index] = it.copy(position = moved(it.position)) }
            }
        }
    }

    fun latestOrder(): Pair<ElementKind?, Long> {
        var kind: ElementKind? = null
        var max = -1L
        strokes.lastOrNull()?.takeIf { it.order > max }?.let { max = it.order; kind = ElementKind.STROKE }
        labels.lastOrNull()?.takeIf { it.order > max }?.let { max = it.order; kind = ElementKind.LABEL }
        images.lastOrNull()?.takeIf { it.order > max }?.let { max = it.order; kind = ElementKind.IMAGE }
        stamps.lastOrNull()?.takeIf { it.order > max }?.let { max = it.order; kind = ElementKind.STAMP }
        val shapeMax = shapes.maxOfOrNull { it.order } ?: -1L
        if (shapeMax > max) { max = shapeMax; kind = null }
        return kind to max
    }

    fun deleteSelected() {
        when (val s = selected) {
            null -> Unit
            else -> when (s.kind) {
                ElementKind.STROKE -> if (s.index in strokes.indices) strokes.removeAt(s.index)
                ElementKind.SHAPE -> if (s.index in shapes.indices && shapes[s.index].kind != ShapeKind.REDACT) shapes.removeAt(s.index)
                ElementKind.LABEL -> if (s.index in labels.indices) labels.removeAt(s.index)
                ElementKind.IMAGE -> if (s.index in images.indices) images.removeAt(s.index)
                ElementKind.STAMP -> if (s.index in stamps.indices) stamps.removeAt(s.index)
            }
        }
        selected = null
    }

    fun duplicateSelected() {
        fun shifted(p: Offset) = Offset((p.x + .025f).coerceAtMost(.96f), (p.y + .025f).coerceAtMost(.96f))
        when (val s = selected) {
            null -> Unit
            else -> when (s.kind) {
                ElementKind.STROKE -> strokes.getOrNull(s.index)?.let { src ->
                    val newOrder = nextOrder()
                    strokes += src.copy(points = src.points.map(::shifted), order = newOrder, layer = newOrder)
                    selected = EditorSelection(ElementKind.STROKE, strokes.lastIndex)
                }
                ElementKind.SHAPE -> shapes.getOrNull(s.index)?.takeIf { it.kind != ShapeKind.REDACT }?.let { src ->
                    val newOrder = nextOrder()
                    shapes += src.copy(start = shifted(src.start), end = shifted(src.end), order = newOrder, layer = newOrder)
                    selected = EditorSelection(ElementKind.SHAPE, shapes.lastIndex)
                }
                ElementKind.LABEL -> labels.getOrNull(s.index)?.let { src ->
                    val newOrder = nextOrder()
                    labels += src.copy(position = shifted(src.position), order = newOrder, layer = newOrder)
                    selected = EditorSelection(ElementKind.LABEL, labels.lastIndex)
                }
                ElementKind.IMAGE -> images.getOrNull(s.index)?.let { src ->
                    val newOrder = nextOrder()
                    images += src.copy(position = shifted(src.position), order = newOrder, layer = newOrder)
                    selected = EditorSelection(ElementKind.IMAGE, images.lastIndex)
                }
                ElementKind.STAMP -> stamps.getOrNull(s.index)?.let { src ->
                    val newOrder = nextOrder()
                    stamps += src.copy(position = shifted(src.position), order = newOrder, layer = newOrder)
                    selected = EditorSelection(ElementKind.STAMP, stamps.lastIndex)
                }
            }
        }
    }

    fun editSelectedText() {
        when (val s = selected) {
            null -> Unit
            else -> when (s.kind) {
                ElementKind.LABEL -> labels.getOrNull(s.index)?.let { label ->
                    editingLabelIndex = s.index
                    textAsNote = label.note
                    textValue = label.text
                    showTextDialog = true
                }
                ElementKind.STAMP -> stamps.getOrNull(s.index)?.let {
                    editingStampIndex = s.index
                    showStampDialog = true
                }
                else -> Unit
            }
        }
    }

    LaunchedEffect(uriText) {
        busy = true
        try {
            val file = withContext(Dispatchers.IO) {
                RuntimeSafety.ensureStorage(context)
                val dir = File(context.cacheDir, "pdf_editor_work/${System.currentTimeMillis()}").apply { mkdirs() }
                val temp = File(dir, "EditSource.pdf")
                context.contentResolver.openInputStream(sourceUri).use { input ->
                    requireNotNull(input) { "editor_source_open_failed" }
                    FileOutputStream(temp).use { output -> RuntimeSafety.copyCancellable(input, output) }
                }
                temp
            }
            workingFile = file
            pageCount = withContext(Dispatchers.IO) { PdfViewerUtils.pageCount(file) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            toast(context.uiText(appLanguage, "b2_editor_error_open"))
        } finally {
            busy = false
        }
    }

    LaunchedEffect(workingFile?.absolutePath, pageIndex, pageCount) {
        val file = workingFile ?: return@LaunchedEffect
        if (pageIndex !in 0 until pageCount) return@LaunchedEffect
        pageBitmap?.recycle(); pageBitmap = null
        busy = true
        try {
            pageBitmap = withContext(Dispatchers.IO) { PdfViewerUtils.renderPage(file, pageIndex, 1500) }
            restoreDraft(pageIndex)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            pageBitmap = null
        } finally {
            busy = false
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { decodeBitmapSafely(context.contentResolver.openInputStream(uri)) }
                if (bmp != null) {
                    images += EditorImage(bmp, placement, .28f, order = nextOrder())
                    selected = EditorSelection(ElementKind.IMAGE, images.lastIndex)
                    tool = EditorTool.SELECT
                } else toast(context.uiText(appLanguage, "b2_editor_error_image"))
            }
        }
    }

    if (showTextDialog) {
        AlertDialog(
            onDismissRequest = { editingLabelIndex = null; textValue = ""; showTextDialog = false },
            title = { Text(if (editingLabelIndex != null) context.uiText(appLanguage, "b2_editor_edit_text") else if (textAsNote) context.uiText(appLanguage, "b2_editor_add_note") else context.uiText(appLanguage, "b2_editor_add_text")) },
            text = {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it.take(if (textAsNote) 500 else 220) },
                    label = { Text(context.uiText(appLanguage, "b2_editor_text")) },
                    minLines = if (textAsNote) 3 else 1,
                    maxLines = if (textAsNote) 6 else 3,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (textValue.isNotBlank()) {
                        val editIndex = editingLabelIndex
                        if (editIndex != null && editIndex in labels.indices) {
                            val old = labels[editIndex]
                            labels[editIndex] = old.copy(text = textValue.trim(), note = textAsNote)
                            selected = EditorSelection(ElementKind.LABEL, editIndex)
                        } else {
                            labels += EditorLabel(textValue.trim(), placement, textAsNote, 1f, nextOrder(), colorArgb = activeColorArgb, align = activeTextAlign)
                            selected = EditorSelection(ElementKind.LABEL, labels.lastIndex)
                        }
                        tool = EditorTool.SELECT
                    }
                    textValue = ""; editingLabelIndex = null; showTextDialog = false
                }) { Text(if (editingLabelIndex != null) context.uiText(appLanguage, "b2_editor_save") else context.uiText(appLanguage, "b2_editor_add")) }
            },
            dismissButton = { TextButton(onClick = { editingLabelIndex = null; textValue = ""; showTextDialog = false }) { Text(context.uiText(appLanguage, "b2_editor_cancel")) } }
        )
    }

    if (showStampDialog) {
        AlertDialog(
            onDismissRequest = { editingStampIndex = null; showStampDialog = false },
            title = { Text(if (editingStampIndex != null) context.uiText(appLanguage, "b2_editor_edit_stamp") else context.uiText(appLanguage, "b2_editor_add_stamp")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(context.uiText(appLanguage, "b2_editor_stamp_approved"), context.uiText(appLanguage, "b2_editor_stamp_reviewed"), context.uiText(appLanguage, "b2_editor_stamp_confidential"), context.uiText(appLanguage, "b2_editor_stamp_copy")).forEach { stampText ->
                        OutlinedButton(
                            onClick = {
                                val editIndex = editingStampIndex
                                if (editIndex != null && editIndex in stamps.indices) {
                                    val old = stamps[editIndex]
                                    stamps[editIndex] = old.copy(text = stampText)
                                    selected = EditorSelection(ElementKind.STAMP, editIndex)
                                } else {
                                    stamps += EditorStamp(stampText, placement, 1f, order = nextOrder())
                                    selected = EditorSelection(ElementKind.STAMP, stamps.lastIndex)
                                }
                                editingStampIndex = null
                                tool = EditorTool.SELECT
                                showStampDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stampText) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { editingStampIndex = null; showStampDialog = false }) { Text(context.uiText(appLanguage, "b2_editor_cancel")) } }
        )
    }

    Scaffold(topBar = { ScreenTopBar(context.uiText(appLanguage, "b2_editor_title"), onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (activeSaveJob != null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(context.uiText(appLanguage, "v15_processing_progress"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = { activeSaveJob?.cancel() }) { Text(context.uiText(appLanguage, "v15_cancel_operation")) }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(if (pageCount > 0) context.uiText(appLanguage, "b2_editor_page", pageIndex + 1, pageCount) else context.uiText(appLanguage, "b2_editor_opening"))
                Row {
                    IconButton(
                        onClick = { if (pageIndex > 0) { storeCurrentDraft(); pageIndex-- } },
                        enabled = pageIndex > 0 && !busy
                    ) { Icon(Icons.Default.ChevronRight, null) }
                    IconButton(
                        onClick = { if (pageIndex + 1 < pageCount) { storeCurrentDraft(); pageIndex++ } },
                        enabled = pageIndex + 1 < pageCount && !busy
                    ) { Icon(Icons.Default.ChevronLeft, null) }
                }
            }

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                EditorToolButton(context.uiText(appLanguage, "b2_editor_tool_view"), Icons.Default.ZoomIn, tool == EditorTool.PAN) { tool = EditorTool.PAN; selected = null }
                EditorToolButton(context.uiText(appLanguage, "b2_editor_tool_move"), Icons.Default.OpenWith, tool == EditorTool.SELECT) { tool = EditorTool.SELECT }
                EditorToolButton(context.uiText(appLanguage, "b2_editor_tool_pen"), Icons.Default.Draw, tool == EditorTool.PENCIL) { tool = EditorTool.PENCIL; selected = null; activeColorArgb = 0xFF0D1B3D.toInt() }
                EditorToolButton(context.uiText(appLanguage, "b2_editor_tool_signature"), Icons.Default.Gesture, tool == EditorTool.SIGNATURE) { tool = EditorTool.SIGNATURE; selected = null; activeColorArgb = 0xFF111827.toInt() }
                EditorToolButton(context.uiText(appLanguage, "b2_editor_tool_highlight"), Icons.Default.Highlight, tool == EditorTool.HIGHLIGHT) { tool = EditorTool.HIGHLIGHT; selected = null; activeColorArgb = 0xFFFDE047.toInt() }
                EditorToolButton(context.uiText(appLanguage, "b2_editor_tool_underline"), Icons.Default.FormatUnderlined, tool == EditorTool.UNDERLINE) { tool = EditorTool.UNDERLINE; selected = null; activeColorArgb = 0xFF2563EB.toInt() }
                EditorToolButton(context.uiText(appLanguage, "b2_editor_tool_strike"), Icons.Default.StrikethroughS, tool == EditorTool.STRIKEOUT) { tool = EditorTool.STRIKEOUT; selected = null; activeColorArgb = 0xFFDC2626.toInt() }
                EditorToolButton(context.uiText(appLanguage, "b2_editor_tool_redact"), Icons.Default.VisibilityOff, tool == EditorTool.REDACT) { tool = EditorTool.REDACT; selected = null }
                EditorToolButton(context.uiText(appLanguage, "b2_editor_tool_rect"), Icons.Default.CropSquare, tool == EditorTool.RECTANGLE) { tool = EditorTool.RECTANGLE; selected = null; activeColorArgb = 0xFF2563EB.toInt() }
                EditorToolButton(context.uiText(appLanguage, "b2_editor_tool_circle"), Icons.Default.Circle, tool == EditorTool.ELLIPSE) { tool = EditorTool.ELLIPSE; selected = null; activeColorArgb = 0xFF2563EB.toInt() }
                EditorToolButton(context.uiText(appLanguage, "b2_editor_tool_arrow"), Icons.Default.ArrowForward, tool == EditorTool.ARROW) { tool = EditorTool.ARROW; selected = null; activeColorArgb = 0xFFDC2626.toInt() }
                EditorToolButton(context.uiText(appLanguage, "b2_editor_tool_text"), Icons.Default.TextFields, tool == EditorTool.TEXT) {
                    tool = EditorTool.TEXT; editingLabelIndex = null; textValue = ""; textAsNote = false; showTextDialog = true
                }
                EditorToolButton(context.uiText(appLanguage, "b2_editor_tool_note"), Icons.Default.StickyNote2, tool == EditorTool.NOTE) {
                    tool = EditorTool.NOTE; editingLabelIndex = null; textValue = ""; textAsNote = true; showTextDialog = true
                }
                EditorToolButton(context.uiText(appLanguage, "b2_editor_tool_image"), Icons.Default.Image, tool == EditorTool.IMAGE) {
                    tool = EditorTool.IMAGE; imagePicker.launch(arrayOf("image/*"))
                }
                EditorToolButton(context.uiText(appLanguage, "b2_editor_tool_stamp"), Icons.Default.CheckCircle, tool == EditorTool.STAMP) {
                    tool = EditorTool.STAMP; editingStampIndex = null; showStampDialog = true
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(context.uiText(appLanguage, "b2_editor_zoom", (viewZoom * 100).toInt()), style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = viewZoom,
                    onValueChange = { newValue ->
                        viewZoom = newValue.coerceIn(1f, 3f)
                        if (viewZoom <= 1.001f) viewPan = Offset.Zero
                    },
                    valueRange = 1f..3f,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = ::resetView) { Text("100%") }
            }

            val styleColor = selectedColor()
            val styleTargetVisible = styleColor != null || tool in setOf(
                EditorTool.PENCIL, EditorTool.SIGNATURE, EditorTool.HIGHLIGHT, EditorTool.RECTANGLE, EditorTool.ELLIPSE,
                EditorTool.ARROW, EditorTool.UNDERLINE, EditorTool.STRIKEOUT, EditorTool.TEXT, EditorTool.NOTE
            )
            if (styleTargetVisible) {
                val shownColor = styleColor ?: activeColorArgb
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(context.uiText(appLanguage, "b2_editor_color"), style = MaterialTheme.typography.labelSmall)
                    listOf(
                        0xFF0D1B3D.toInt(), 0xFF111827.toInt(), 0xFF2563EB.toInt(),
                        0xFFDC2626.toInt(), 0xFF16A34A.toInt(), 0xFFF59E0B.toInt(), 0xFF7C3AED.toInt()
                    ).forEach { argb ->
                        FilterChip(
                            selected = shownColor == argb,
                            onClick = {
                                if (styleColor != null) updateSelectedColor(argb) else activeColorArgb = argb
                            },
                            label = { Box(Modifier.size(20.dp).background(Color(argb), shape = MaterialTheme.shapes.small)) }
                        )
                    }
                }
            }

            val selectedWidth = selectedThickness()
            val drawingWidthVisible = tool in setOf(
                EditorTool.PENCIL, EditorTool.SIGNATURE, EditorTool.HIGHLIGHT, EditorTool.RECTANGLE, EditorTool.ELLIPSE,
                EditorTool.ARROW, EditorTool.UNDERLINE, EditorTool.STRIKEOUT
            )
            if (selectedWidth != null || drawingWidthVisible) {
                val widthValue = selectedWidth ?: activeThickness
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(context.uiText(appLanguage, "b2_editor_thickness", String.format("%.1f", widthValue)), style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = widthValue,
                        onValueChange = { if (selectedWidth != null) updateSelectedThickness(it) else activeThickness = it },
                        valueRange = .6f..3f,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            val selectedOpacity = selectedHighlightOpacity()
            if (selectedOpacity != null || tool == EditorTool.HIGHLIGHT) {
                val opacityValue = selectedOpacity ?: activeHighlightOpacity
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(context.uiText(appLanguage, "b2_editor_opacity", (opacityValue * 100).toInt()), style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = opacityValue,
                        onValueChange = { if (selectedOpacity != null) updateSelectedHighlightOpacity(it) else activeHighlightOpacity = it },
                        valueRange = .15f..0.8f,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            val currentAlign = selectedTextAlign() ?: activeTextAlign
            if (selectedTextAlign() != null || tool == EditorTool.TEXT || tool == EditorTool.NOTE) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 1.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    listOf(
                        EditorTextAlign.RIGHT to context.uiText(appLanguage, "b2_editor_align_right"),
                        EditorTextAlign.CENTER to context.uiText(appLanguage, "b2_editor_align_center"),
                        EditorTextAlign.LEFT to context.uiText(appLanguage, "b2_editor_align_left")
                    ).forEach { (align, label) ->
                        FilterChip(
                            selected = currentAlign == align,
                            onClick = {
                                if (selectedTextAlign() != null) updateSelectedTextAlign(align) else activeTextAlign = align
                            },
                            label = { Text(label) },
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )
                    }
                }
            }

            selectedScale()?.let { (value, range) ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(context.uiText(appLanguage, "b2_editor_size"), style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = value.coerceIn(range.start, range.endInclusive),
                        onValueChange = ::updateSelectedScale,
                        valueRange = range,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            selectedRotation()?.let { degrees ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(context.uiText(appLanguage, "b2_editor_rotation", degrees.toInt()), style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = degrees.coerceIn(-180f, 180f),
                        onValueChange = ::updateSelectedRotation,
                        valueRange = -180f..180f,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { updateSelectedRotation(0f) }) { Text(context.uiText(appLanguage, "b2_editor_zero")) }
                }
            }

            if (tool == EditorTool.SELECT && selected != null) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AssistChip(
                        onClick = ::duplicateSelected,
                        label = { Text(context.uiText(appLanguage, "b2_editor_copy")) },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp)) }
                    )
                    AssistChip(
                        onClick = { moveSelectedLayer(true) },
                        label = { Text(context.uiText(appLanguage, "b2_editor_front")) },
                        leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, null, modifier = Modifier.size(18.dp)) }
                    )
                    AssistChip(
                        onClick = { moveSelectedLayer(false) },
                        label = { Text(context.uiText(appLanguage, "b2_editor_back")) },
                        leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(18.dp)) }
                    )
                    AssistChip(
                        onClick = ::deleteSelected,
                        label = { Text(context.uiText(appLanguage, "b2_editor_delete")) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp)) }
                    )
                    if (selected?.kind == ElementKind.LABEL || selected?.kind == ElementKind.STAMP) {
                        AssistChip(
                            onClick = ::editSelectedText,
                            label = { Text(context.uiText(appLanguage, "b2_editor_edit")) },
                            leadingIcon = { Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp)) }
                        )
                    }
                }
            }

            if (tool == EditorTool.PAN) {
                Text(
                    context.uiText(appLanguage, "b2_editor_zoom_help"),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            } else if (tool == EditorTool.SELECT) {
                Text(
                    context.uiText(appLanguage, "b2_editor_select_help"),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f)
                )
            } else if (tool == EditorTool.REDACT) {
                Text(
                    context.uiText(appLanguage, "b2_editor_redact_help"),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Box(
                Modifier.weight(1f).fillMaxWidth().clipToBounds().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .3f)),
                contentAlignment = Alignment.Center
            ) {
                val bmp = pageBitmap
                if (bmp == null) {
                    if (!busy) Text(context.uiText(appLanguage, "b2_editor_error_display"))
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth(.96f)
                            .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat())
                            .graphicsLayer {
                                scaleX = viewZoom
                                scaleY = viewZoom
                                translationX = viewPan.x
                                translationY = viewPan.y
                            }
                            .background(Color.White)
                    ) {
                        androidx.compose.foundation.Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = context.uiText(appLanguage, "b2_editor_pdf_page"),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds
                        )

                        Canvas(
                            Modifier.fillMaxSize().pointerInput(
                                tool, strokes.size, shapes.size, labels.size, images.size, stamps.size, selected, viewZoom
                            ) {
                                if (tool == EditorTool.PAN) {
                                    detectTransformGestures { _, pan, zoomChange, _ ->
                                        val newZoom = (viewZoom * zoomChange).coerceIn(1f, 3f)
                                        viewZoom = newZoom
                                        viewPan = clampPan(viewPan + pan, newZoom, size.width.toFloat(), size.height.toFloat())
                                    }
                                    return@pointerInput
                                }
                                detectDragGestures(
                                    onDragStart = { pos ->
                                        val n = Offset(
                                            (pos.x / size.width).coerceIn(0f, 1f),
                                            (pos.y / size.height).coerceIn(0f, 1f)
                                        )
                                        placement = n
                                        when (tool) {
                                            EditorTool.SELECT -> selected = findSelection(
                                                n, size.width.toFloat(), size.height.toFloat(),
                                                strokes, shapes, labels, images, stamps
                                            )
                                            EditorTool.PENCIL, EditorTool.SIGNATURE, EditorTool.HIGHLIGHT -> current = listOf(n)
                                            EditorTool.RECTANGLE, EditorTool.ELLIPSE, EditorTool.ARROW, EditorTool.UNDERLINE,
                                            EditorTool.STRIKEOUT, EditorTool.REDACT -> {
                                                shapeStart = n; shapeEnd = n
                                            }
                                            EditorTool.PAN -> Unit
                                            else -> Unit
                                        }
                                    },
                                    onDrag = { change, dragAmount ->
                                        val n = Offset(
                                            (change.position.x / size.width).coerceIn(0f, 1f),
                                            (change.position.y / size.height).coerceIn(0f, 1f)
                                        )
                                        when (tool) {
                                            EditorTool.SELECT -> moveSelected(
                                                Offset(dragAmount.x / size.width, dragAmount.y / size.height)
                                            )
                                            EditorTool.PENCIL, EditorTool.SIGNATURE, EditorTool.HIGHLIGHT -> current = current + n
                                            EditorTool.RECTANGLE, EditorTool.ELLIPSE, EditorTool.ARROW, EditorTool.UNDERLINE,
                                            EditorTool.STRIKEOUT, EditorTool.REDACT -> shapeEnd = n
                                            EditorTool.PAN -> Unit
                                            else -> Unit
                                        }
                                    },
                                    onDragEnd = {
                                        when (tool) {
                                            EditorTool.PENCIL, EditorTool.SIGNATURE, EditorTool.HIGHLIGHT -> {
                                                if (current.size > 1) {
                                                    val kind = when (tool) {
                                                        EditorTool.SIGNATURE -> StrokeKind.SIGNATURE
                                                        EditorTool.HIGHLIGHT -> StrokeKind.HIGHLIGHT
                                                        else -> StrokeKind.PENCIL
                                                    }
                                                    strokes += EditorStroke(current, kind, 1f, nextOrder(), colorArgb = activeColorArgb, thickness = activeThickness, opacity = if (kind == StrokeKind.HIGHLIGHT) activeHighlightOpacity else 1f)
                                                    current = emptyList()
                                                }
                                            }
                                            EditorTool.RECTANGLE, EditorTool.ELLIPSE, EditorTool.ARROW, EditorTool.UNDERLINE,
                                            EditorTool.STRIKEOUT, EditorTool.REDACT -> {
                                                val a = shapeStart; val b = shapeEnd
                                                if (a != null && b != null && distance(a, b) > .008f) {
                                                    val kind = when (tool) {
                                                        EditorTool.ELLIPSE -> ShapeKind.ELLIPSE
                                                        EditorTool.ARROW -> ShapeKind.ARROW
                                                        EditorTool.UNDERLINE -> ShapeKind.UNDERLINE
                                                        EditorTool.STRIKEOUT -> ShapeKind.STRIKEOUT
                                                        EditorTool.REDACT -> ShapeKind.REDACT
                                                        else -> ShapeKind.RECTANGLE
                                                    }
                                                    shapes += EditorShape(a, b, kind, nextOrder(), colorArgb = if (kind == ShapeKind.REDACT) 0xFF000000.toInt() else activeColorArgb, thickness = activeThickness)
                                                }
                                                shapeStart = null; shapeEnd = null
                                            }
                                            else -> Unit
                                        }
                                    }
                                )
                            }
                        ) {
                            fun p(o: Offset) = Offset(o.x * size.width, o.y * size.height)

                            fun drawStrokePreview(s: EditorStroke) {
                                val points = transformedPoints(s)
                                if (points.size < 2) return
                                val path = Path().apply {
                                    val first = p(points.first())
                                    moveTo(first.x, first.y)
                                    points.drop(1).forEach { point ->
                                        val q = p(point); lineTo(q.x, q.y)
                                    }
                                }
                                val isHighlight = s.kind == StrokeKind.HIGHLIGHT
                                val alpha = if (isHighlight) s.opacity.coerceIn(.15f, .8f) else 1f
                                val color = Color(s.colorArgb).copy(alpha = alpha)
                                val baseWidth = if (isHighlight) 22f else 5f
                                drawPath(path, color, style = Stroke(width = baseWidth * s.thickness.coerceIn(.6f, 3f)))
                            }

                            fun drawShapePreview(s: EditorShape) {
                                drawEditorShapeCompose(s, size.width, size.height)
                            }

                            val native = drawContext.canvas.nativeCanvas
                            renderRefs(strokes, shapes, labels, images, stamps).forEach { ref ->
                                when (ref.kind) {
                                    RenderKind.STROKE -> strokes.getOrNull(ref.index)?.let(::drawStrokePreview)
                                    RenderKind.SHAPE -> shapes.getOrNull(ref.index)?.let(::drawShapePreview)
                                    RenderKind.LABEL -> labels.getOrNull(ref.index)?.let { drawLabel(native, it, size.width, size.height) }
                                    RenderKind.STAMP -> stamps.getOrNull(ref.index)?.let { drawStamp(native, it, size.width, size.height) }
                                    RenderKind.IMAGE -> images.getOrNull(ref.index)?.let { im ->
                                        val w = (size.width * im.widthFraction).toInt().coerceAtLeast(20)
                                        val h = (w * (im.bitmap.height.toFloat() / im.bitmap.width.coerceAtLeast(1))).toInt().coerceAtLeast(20)
                                        val c = p(im.position)
                                        val x = (c.x - w / 2).toInt(); val y = (c.y - h / 2).toInt()
                                        rotate(im.rotationDegrees, c) {
                                            drawImage(im.bitmap.asImageBitmap(), dstOffset = IntOffset(x, y), dstSize = IntSize(w, h))
                                        }
                                    }
                                }
                            }

                            if (current.size > 1) {
                                val previewKind = when (tool) {
                                    EditorTool.SIGNATURE -> StrokeKind.SIGNATURE
                                    EditorTool.HIGHLIGHT -> StrokeKind.HIGHLIGHT
                                    else -> StrokeKind.PENCIL
                                }
                                drawStrokePreview(EditorStroke(current, previewKind, 1f, -1L, colorArgb = activeColorArgb, thickness = activeThickness, opacity = if (previewKind == StrokeKind.HIGHLIGHT) activeHighlightOpacity else 1f))
                            }

                            if (shapeStart != null && shapeEnd != null) {
                                val previewKind = when (tool) {
                                    EditorTool.ELLIPSE -> ShapeKind.ELLIPSE
                                    EditorTool.ARROW -> ShapeKind.ARROW
                                    EditorTool.UNDERLINE -> ShapeKind.UNDERLINE
                                    EditorTool.STRIKEOUT -> ShapeKind.STRIKEOUT
                                    EditorTool.REDACT -> ShapeKind.REDACT
                                    else -> ShapeKind.RECTANGLE
                                }
                                drawShapePreview(EditorShape(shapeStart!!, shapeEnd!!, previewKind, -1L, colorArgb = if (previewKind == ShapeKind.REDACT) 0xFF000000.toInt() else activeColorArgb, thickness = activeThickness))
                            }

                            selected?.let { selection ->
                                selectionCenter(selection, strokes, shapes, labels, images, stamps)?.let { center ->
                                    drawCircle(
                                        Color(0xFF0EA5E9),
                                        radius = 11f,
                                        center = p(center),
                                        style = Stroke(width = 3f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val (_, order) = latestOrder()
                        if (order < 0) return@OutlinedButton
                        when {
                            shapes.any { it.order == order } -> shapes.removeAll { it.order == order }
                            stamps.any { it.order == order } -> stamps.removeAll { it.order == order }
                            images.any { it.order == order } -> {
                                val index = images.indexOfFirst { it.order == order }
                                if (index >= 0) images.removeAt(index)
                            }
                            labels.any { it.order == order } -> labels.removeAll { it.order == order }
                            strokes.any { it.order == order } -> strokes.removeAll { it.order == order }
                        }
                        selected = null
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Undo, null); Spacer(Modifier.width(4.dp)); Text(context.uiText(appLanguage, "b2_editor_undo"))
                }

                OutlinedButton(onClick = { pageDrafts.remove(pageIndex); clearCurrentEdits() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.DeleteSweep, null); Spacer(Modifier.width(4.dp)); Text(context.uiText(appLanguage, "b2_editor_clear"))
                }

                Button(
                    onClick = {
                        val file = workingFile ?: return@Button
                        storeCurrentDraft()
                        if (pageDrafts.isEmpty()) {
                            toast(context.uiText(appLanguage, "b2_editor_need_edit")); return@Button
                        }
                        val draftsToSave = pageDrafts.toSortedMap()
                        busy = true
                        activeSaveJob = scope.launch {
                            val patchFiles = mutableListOf<File>()
                            try {
                                val patches = withContext(Dispatchers.IO) {
                                    RuntimeSafety.ensureStorage(context)
                                    buildList {
                                        draftsToSave.forEach { (draftPageIndex, draft) ->
                                            currentCoroutineContext().ensureActive()
                                            val base = PdfViewerUtils.renderPage(file, draftPageIndex, 1500)
                                            val hasTrueRedaction = draft.shapes.any { it.kind == ShapeKind.REDACT }
                                            val rendered = try {
                                                if (hasTrueRedaction) {
                                                    renderFlattenedPage(base, draft.strokes, draft.shapes, draft.labels, draft.images, draft.stamps)
                                                } else {
                                                    renderOverlay(base.width, base.height, draft.strokes, draft.shapes, draft.labels, draft.images, draft.stamps)
                                                }
                                            } finally {
                                                if (!base.isRecycled) base.recycle()
                                            }
                                            try {
                                                currentCoroutineContext().ensureActive()
                                                val dir = File(context.cacheDir, "pdf_editor_patches").apply { mkdirs() }
                                                val patchFile = File(dir, "page_${draftPageIndex + 1}_${System.nanoTime()}.png")
                                                FileOutputStream(patchFile).use { out ->
                                                    check(rendered.compress(Bitmap.CompressFormat.PNG, 100, out)) { context.uiText(appLanguage, "b2_editor_prepare_page_error", draftPageIndex + 1) }
                                                }
                                                patchFiles += patchFile
                                                add(PdfEditorEngine.PagePatch(draftPageIndex + 1, patchFile, hasTrueRedaction))
                                            } finally {
                                                if (!rendered.isRecycled) rendered.recycle()
                                            }
                                        }
                                    }
                                }
                                val out = PdfEditorEngine.applyPagePatches(context, Uri.fromFile(file), patches)
                                val editedCount = draftsToSave.size
                                val redactedCount = draftsToSave.values.count { draft -> draft.shapes.any { it.kind == ShapeKind.REDACT } }
                                try {
                                    val draft = ConversionDraftManager.stage(context, out)
                                    pendingEditorSummary =
                                        if (redactedCount > 0) context.uiText(appLanguage, "b2_editor_summary_redacted", editedCount, redactedCount)
                                        else context.uiText(appLanguage, "b2_editor_summary", editedCount)
                                    pendingEditorDraft = draft
                                } catch (error: Throwable) {
                                    if (out.exists()) out.delete()
                                    throw error
                                }
                            } catch (error: Throwable) {
                                toast(context.localizedOperationFailure(appLanguage, error, "b2_editor_error_prepare"))
                            } finally {
                                patchFiles.forEach { runCatching { it.delete() } }
                                busy = false
                                activeSaveJob = null
                            }
                        }
                    },
                    enabled = !busy && pageBitmap != null,
                    modifier = Modifier.weight(1.25f)
                ) {
                    Icon(Icons.Default.Preview, null); Spacer(Modifier.width(4.dp)); Text(context.uiText(appLanguage, "b2_editor_preview_save"))
                }
            }

            Text(
                context.uiText(appLanguage, "b2_editor_safe_note"),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f)
            )
        }
    }

    pendingEditorDraft?.let { draft ->
        ConversionPreviewDialog(
            draft = draft,
            onApprove = {
                runCatching { ConversionDraftManager.commit(context, draft) }
                    .onSuccess { saved ->
                        pendingEditorDraft = null
                        val summary = pendingEditorSummary
                        pendingEditorSummary = ""
                        pageDrafts.clear()
                        clearCurrentEdits()
                        workingFile = saved
                        toast(context.uiText(appLanguage, "b2_editor_approved", summary))
                        onOpenPdf(saved)
                    }
                    .onFailure { toast(context.uiText(appLanguage, "b2_editor_error_approve")) }
            },
            onDiscard = {
                ConversionDraftManager.discard(context, draft)
                pendingEditorDraft = null
                pendingEditorSummary = ""
                toast(context.uiText(appLanguage, "b2_editor_discarded"))
            }
        )
    }
}

@Composable
private fun EditorToolButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, null, modifier = Modifier.size(18.dp)) }
    )
}

private fun transformedPoints(stroke: EditorStroke): List<Offset> {
    if (stroke.points.isEmpty() || stroke.scale == 1f) return stroke.points
    val cx = stroke.points.sumOf { it.x.toDouble() }.toFloat() / stroke.points.size
    val cy = stroke.points.sumOf { it.y.toDouble() }.toFloat() / stroke.points.size
    return stroke.points.map { p ->
        Offset(
            (cx + (p.x - cx) * stroke.scale).coerceIn(0f, 1f),
            (cy + (p.y - cy) * stroke.scale).coerceIn(0f, 1f)
        )
    }
}

private fun distance(a: Offset, b: Offset): Float {
    val dx = a.x - b.x; val dy = a.y - b.y
    return sqrt((dx * dx + dy * dy).toDouble()).toFloat()
}

private fun shapeCenter(shape: EditorShape): Offset = Offset(
    (shape.start.x + shape.end.x) / 2f,
    (shape.start.y + shape.end.y) / 2f
)

private fun transformedShapeEndpoints(shape: EditorShape): Pair<Offset, Offset> {
    if (shape.scale == 1f) return shape.start to shape.end
    val center = shapeCenter(shape)
    fun scaled(point: Offset) = Offset(
        (center.x + (point.x - center.x) * shape.scale).coerceIn(0f, 1f),
        (center.y + (point.y - center.y) * shape.scale).coerceIn(0f, 1f)
    )
    return scaled(shape.start) to scaled(shape.end)
}

private fun pointToSegmentDistancePx(
    px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float
): Float {
    val dx = bx - ax; val dy = by - ay
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared <= .0001f) return sqrt(((px - ax) * (px - ax) + (py - ay) * (py - ay)).toDouble()).toFloat()
    val t = (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
    val qx = ax + t * dx; val qy = ay + t * dy
    return sqrt(((px - qx) * (px - qx) + (py - qy) * (py - qy)).toDouble()).toFloat()
}

private fun findSelection(
    point: Offset,
    canvasWidth: Float,
    canvasHeight: Float,
    strokes: List<EditorStroke>,
    shapes: List<EditorShape>,
    labels: List<EditorLabel>,
    images: List<EditorImage>,
    stamps: List<EditorStamp>
): EditorSelection? {
    data class Candidate(val selection: EditorSelection, val order: Long, val distance: Float)
    val candidates = mutableListOf<Candidate>()

    images.forEachIndexed { index, im ->
        val ratio = im.bitmap.height.toFloat() / im.bitmap.width.coerceAtLeast(1)
        val dx = (point.x - im.position.x) * canvasWidth
        val dy = (point.y - im.position.y) * canvasHeight
        val radians = Math.toRadians((-im.rotationDegrees).toDouble())
        val localX = dx * cos(radians).toFloat() - dy * sin(radians).toFloat()
        val localY = dx * sin(radians).toFloat() + dy * cos(radians).toFloat()
        val halfW = canvasWidth * im.widthFraction / 2f
        val halfH = canvasWidth * im.widthFraction * ratio / 2f
        if (abs(localX) <= halfW && abs(localY) <= halfH) {
            candidates += Candidate(EditorSelection(ElementKind.IMAGE, index), im.layer, distance(point, im.position))
        }
    }

    labels.forEachIndexed { index, label ->
        val hit = if (label.note) .18f * label.scale else .11f * label.scale
        val d = distance(point, label.position)
        if (d <= hit) candidates += Candidate(EditorSelection(ElementKind.LABEL, index), label.layer, d)
    }

    stamps.forEachIndexed { index, stamp ->
        val d = distance(point, stamp.position)
        if (d <= .16f * stamp.scale) candidates += Candidate(EditorSelection(ElementKind.STAMP, index), stamp.layer, d)
    }

    shapes.forEachIndexed { index, shape ->
        if (shape.kind == ShapeKind.REDACT) return@forEachIndexed
        val (start, end) = transformedShapeEndpoints(shape)
        val px = point.x * canvasWidth
        val py = point.y * canvasHeight
        val ax = start.x * canvasWidth
        val ay = start.y * canvasHeight
        val bx = end.x * canvasWidth
        val by = end.y * canvasHeight
        val tolerance = 18f
        val hit = when (shape.kind) {
            ShapeKind.RECTANGLE -> {
                val left = minOf(ax, bx); val right = maxOf(ax, bx)
                val top = minOf(ay, by); val bottom = maxOf(ay, by)
                val insideExpanded = px in (left - tolerance)..(right + tolerance) && py in (top - tolerance)..(bottom + tolerance)
                val borderDistance = minOf(abs(px - left), abs(px - right), abs(py - top), abs(py - bottom))
                insideExpanded && (borderDistance <= tolerance || (right - left < 52f && bottom - top < 52f))
            }
            ShapeKind.ELLIPSE -> {
                val cx = (ax + bx) / 2f; val cy = (ay + by) / 2f
                val rx = abs(bx - ax) / 2f; val ry = abs(by - ay) / 2f
                if (rx < 3f || ry < 3f) false else {
                    val normalizedRadius = sqrt((((px - cx) / rx) * ((px - cx) / rx) + ((py - cy) / ry) * ((py - cy) / ry)).toDouble()).toFloat()
                    abs(normalizedRadius - 1f) * minOf(rx, ry) <= tolerance
                }
            }
            ShapeKind.ARROW, ShapeKind.UNDERLINE, ShapeKind.STRIKEOUT ->
                pointToSegmentDistancePx(px, py, ax, ay, bx, by) <= tolerance
            ShapeKind.REDACT -> false
        }
        if (hit) {
            val center = shapeCenter(shape)
            candidates += Candidate(EditorSelection(ElementKind.SHAPE, index), shape.layer, distance(point, center))
        }
    }

    strokes.forEachIndexed { index, stroke ->
        if (stroke.kind == StrokeKind.HIGHLIGHT) return@forEachIndexed
        val minDistance = transformedPoints(stroke).minOfOrNull { distance(point, it) } ?: 1f
        if (minDistance <= .035f) {
            candidates += Candidate(EditorSelection(ElementKind.STROKE, index), stroke.layer, minDistance)
        }
    }

    return candidates.sortedWith(compareByDescending<Candidate> { it.order }.thenBy { it.distance }).firstOrNull()?.selection
}

private fun selectionCenter(
    selection: EditorSelection,
    strokes: List<EditorStroke>,
    shapes: List<EditorShape>,
    labels: List<EditorLabel>,
    images: List<EditorImage>,
    stamps: List<EditorStamp>
): Offset? = when (selection.kind) {
    ElementKind.SHAPE -> shapes.getOrNull(selection.index)?.takeIf { it.kind != ShapeKind.REDACT }?.let(::shapeCenter)
    ElementKind.LABEL -> labels.getOrNull(selection.index)?.position
    ElementKind.IMAGE -> images.getOrNull(selection.index)?.position
    ElementKind.STAMP -> stamps.getOrNull(selection.index)?.position
    ElementKind.STROKE -> strokes.getOrNull(selection.index)?.let { stroke ->
        val pts = transformedPoints(stroke)
        if (pts.isEmpty()) null else Offset(
            pts.sumOf { it.x.toDouble() }.toFloat() / pts.size,
            pts.sumOf { it.y.toDouble() }.toFloat() / pts.size
        )
    }
}

private fun renderRefs(
    strokes: List<EditorStroke>,
    shapes: List<EditorShape>,
    labels: List<EditorLabel>,
    images: List<EditorImage>,
    stamps: List<EditorStamp>
): List<RenderRef> = buildList {
    strokes.forEachIndexed { index, item -> add(RenderRef(item.layer, RenderKind.STROKE, index)) }
    shapes.forEachIndexed { index, item -> add(RenderRef(item.layer, RenderKind.SHAPE, index)) }
    labels.forEachIndexed { index, item -> add(RenderRef(item.layer, RenderKind.LABEL, index)) }
    images.forEachIndexed { index, item -> add(RenderRef(item.layer, RenderKind.IMAGE, index)) }
    stamps.forEachIndexed { index, item -> add(RenderRef(item.layer, RenderKind.STAMP, index)) }
}.sortedBy { it.order }

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEditorShapeCompose(
    shape: EditorShape,
    width: Float,
    height: Float
) {
    fun p(o: Offset) = Offset(o.x * width, o.y * height)
    val (shapeStart, shapeEnd) = transformedShapeEndpoints(shape)
    val a = p(shapeStart); val b = p(shapeEnd)
    val color = if (shape.kind == ShapeKind.REDACT) Color.Black else Color(shape.colorArgb)
    val strokeWidth = 5f * shape.thickness.coerceIn(.6f, 3f)
    when (shape.kind) {
        ShapeKind.ARROW -> {
            drawLine(color, a, b, strokeWidth = strokeWidth)
            val ang = atan2(b.y - a.y, b.x - a.x); val len = 22f
            drawLine(color, b, Offset(b.x - len * cos(ang - .55f), b.y - len * sin(ang - .55f)), strokeWidth)
            drawLine(color, b, Offset(b.x - len * cos(ang + .55f), b.y - len * sin(ang + .55f)), strokeWidth)
        }
        ShapeKind.RECTANGLE -> drawRect(
            color,
            topLeft = Offset(minOf(a.x, b.x), minOf(a.y, b.y)),
            size = androidx.compose.ui.geometry.Size(abs(a.x - b.x), abs(a.y - b.y)),
            style = Stroke(width = 4f * shape.thickness.coerceIn(.6f, 3f))
        )
        ShapeKind.ELLIPSE -> drawOval(
            color,
            topLeft = Offset(minOf(a.x, b.x), minOf(a.y, b.y)),
            size = androidx.compose.ui.geometry.Size(abs(a.x - b.x), abs(a.y - b.y)),
            style = Stroke(width = 4f * shape.thickness.coerceIn(.6f, 3f))
        )
        ShapeKind.UNDERLINE, ShapeKind.STRIKEOUT -> drawLine(color, a, b, strokeWidth = strokeWidth)
        ShapeKind.REDACT -> drawRect(
            Color.Black,
            topLeft = Offset(minOf(a.x, b.x), minOf(a.y, b.y)),
            size = androidx.compose.ui.geometry.Size(abs(a.x - b.x), abs(a.y - b.y))
        )
    }
}

private fun renderOverlay(
    width: Int,
    height: Int,
    strokes: List<EditorStroke>,
    shapes: List<EditorShape>,
    labels: List<EditorLabel>,
    images: List<EditorImage>,
    stamps: List<EditorStamp>
): Bitmap {
    val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    drawAllEdits(canvas, width, height, strokes, shapes, labels, images, stamps)
    return bitmap
}

private fun renderFlattenedPage(
    basePage: Bitmap,
    strokes: List<EditorStroke>,
    shapes: List<EditorShape>,
    labels: List<EditorLabel>,
    images: List<EditorImage>,
    stamps: List<EditorStamp>
): Bitmap {
    val bitmap = Bitmap.createBitmap(basePage.width, basePage.height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)
    canvas.drawBitmap(basePage, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
    drawAllEdits(canvas, basePage.width, basePage.height, strokes, shapes, labels, images, stamps)
    return bitmap
}

private fun drawAllEdits(
    canvas: android.graphics.Canvas,
    width: Int,
    height: Int,
    strokes: List<EditorStroke>,
    shapes: List<EditorShape>,
    labels: List<EditorLabel>,
    images: List<EditorImage>,
    stamps: List<EditorStamp>
) {
    fun p(o: Offset) = android.graphics.PointF(o.x * width, o.y * height)

    fun drawStroke(stroke: EditorStroke) {
        val points = transformedPoints(stroke)
        if (points.size <= 1) return
        val highlight = stroke.kind == StrokeKind.HIGHLIGHT
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = stroke.colorArgb
            alpha = if (highlight) (stroke.opacity.coerceIn(.15f, .8f) * 255f).toInt() else 255
            style = Paint.Style.STROKE
            strokeWidth = (if (highlight) 24f else 6f) * stroke.thickness.coerceIn(.6f, 3f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = android.graphics.Path()
        val first = p(points.first()); path.moveTo(first.x, first.y)
        points.drop(1).forEach { point -> val q = p(point); path.lineTo(q.x, q.y) }
        canvas.drawPath(path, paint)
    }

    fun drawShape(shape: EditorShape) {
        val (shapeStart, shapeEnd) = transformedShapeEndpoints(shape)
        val a = p(shapeStart); val b = p(shapeEnd)
        val thickness = shape.thickness.coerceIn(.6f, 3f)
        when (shape.kind) {
            ShapeKind.ARROW -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = shape.colorArgb; style = Paint.Style.STROKE; strokeWidth = 6f * thickness
                }
                canvas.drawLine(a.x, a.y, b.x, b.y, paint)
                val ang = atan2(b.y - a.y, b.x - a.x); val len = 28f
                canvas.drawLine(b.x, b.y, b.x - len * cos(ang - .55f), b.y - len * sin(ang - .55f), paint)
                canvas.drawLine(b.x, b.y, b.x - len * cos(ang + .55f), b.y - len * sin(ang + .55f), paint)
            }
            ShapeKind.RECTANGLE -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = shape.colorArgb; style = Paint.Style.STROKE; strokeWidth = 6f * thickness
                }
                canvas.drawRect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y), paint)
            }
            ShapeKind.ELLIPSE -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = shape.colorArgb; style = Paint.Style.STROKE; strokeWidth = 6f * thickness
                }
                canvas.drawOval(android.graphics.RectF(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y)), paint)
            }
            ShapeKind.UNDERLINE, ShapeKind.STRIKEOUT -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = shape.colorArgb
                    style = Paint.Style.STROKE; strokeWidth = 6f * thickness; strokeCap = Paint.Cap.ROUND
                }
                canvas.drawLine(a.x, a.y, b.x, b.y, paint)
            }
            ShapeKind.REDACT -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.BLACK; style = Paint.Style.FILL
                }
                canvas.drawRect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y), paint)
            }
        }
    }

    renderRefs(strokes, shapes, labels, images, stamps).forEach { ref ->
        when (ref.kind) {
            RenderKind.STROKE -> strokes.getOrNull(ref.index)?.let(::drawStroke)
            RenderKind.SHAPE -> shapes.getOrNull(ref.index)?.let(::drawShape)
            RenderKind.LABEL -> labels.getOrNull(ref.index)?.let { drawLabel(canvas, it, width.toFloat(), height.toFloat()) }
            RenderKind.STAMP -> stamps.getOrNull(ref.index)?.let { drawStamp(canvas, it, width.toFloat(), height.toFloat()) }
            RenderKind.IMAGE -> images.getOrNull(ref.index)?.let { im ->
                val w = width * im.widthFraction
                val h = w * (im.bitmap.height.toFloat() / im.bitmap.width.coerceAtLeast(1))
                val c = p(im.position)
                val dst = android.graphics.RectF(c.x - w / 2, c.y - h / 2, c.x + w / 2, c.y + h / 2)
                canvas.save()
                canvas.rotate(im.rotationDegrees, c.x, c.y)
                canvas.drawBitmap(im.bitmap, null, dst, Paint(Paint.ANTI_ALIAS_FLAG))
                canvas.restore()
            }
        }
    }

}

private fun drawLabel(
    canvas: android.graphics.Canvas,
    label: EditorLabel,
    width: Float,
    height: Float
) {
    val anchorX = label.position.x * width
    val y = label.position.y * height
    val paintAlign = when (label.align) {
        EditorTextAlign.LEFT -> Paint.Align.LEFT
        EditorTextAlign.CENTER -> Paint.Align.CENTER
        EditorTextAlign.RIGHT -> Paint.Align.RIGHT
    }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = label.colorArgb
        textSize = ((width * .034f).coerceAtLeast(24f) * label.scale)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        textAlign = paintAlign
    }

    if (!label.note) {
        val lines = label.text.split('\n').take(4)
        val lineHeight = textPaint.fontSpacing
        lines.forEachIndexed { index, line -> canvas.drawText(line, anchorX, y + index * lineHeight, textPaint) }
        return
    }

    val maxWidth = (width * .46f * label.scale).coerceIn(width * .20f, width * .78f)
    val left = when (label.align) {
        EditorTextAlign.LEFT -> anchorX
        EditorTextAlign.CENTER -> anchorX - maxWidth / 2f
        EditorTextAlign.RIGHT -> anchorX - maxWidth
    }.coerceIn(8f, (width - maxWidth - 8f).coerceAtLeast(8f))
    val drawX = when (label.align) {
        EditorTextAlign.LEFT -> left + 8f
        EditorTextAlign.CENTER -> left + maxWidth / 2f
        EditorTextAlign.RIGHT -> left + maxWidth - 8f
    }
    val lines = wrapText(label.text, textPaint, maxWidth - 16f).take(8)
    val lineHeight = textPaint.fontSpacing
    val boxHeight = (lineHeight * lines.size + 26f).coerceAtLeast(62f)
    val top = y - lineHeight
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(255, 243, 166); style = Paint.Style.FILL
    }
    canvas.drawRoundRect(
        left,
        top - 8f,
        (left + maxWidth).coerceAtMost(width - 8f),
        (top + boxHeight).coerceAtMost(height),
        12f, 12f, bg
    )
    lines.forEachIndexed { index, line ->
        canvas.drawText(line, drawX, y + index * lineHeight, textPaint)
    }
}

private fun drawStamp(
    canvas: android.graphics.Canvas,
    stamp: EditorStamp,
    width: Float,
    height: Float
) {
    val x = stamp.position.x * width
    val y = stamp.position.y * height
    canvas.save()
    canvas.rotate(stamp.rotationDegrees, x, y)
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(185, 28, 28)
        textSize = ((width * .038f).coerceAtLeast(28f) * stamp.scale)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val textWidth = textPaint.measureText(stamp.text)
    val h = textPaint.fontSpacing * 1.35f
    val padX = 22f * stamp.scale
    val rect = android.graphics.RectF(
        (x - textWidth / 2f - padX).coerceAtLeast(2f),
        (y - h * .78f).coerceAtLeast(2f),
        (x + textWidth / 2f + padX).coerceAtMost(width - 2f),
        (y + h * .35f).coerceAtMost(height - 2f)
    )
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(185, 28, 28); style = Paint.Style.STROKE; strokeWidth = (4f * stamp.scale).coerceAtLeast(3f)
    }
    canvas.drawRoundRect(rect, 12f, 12f, border)
    canvas.drawText(stamp.text, x, y, textPaint)
    canvas.restore()
}

private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
    val result = mutableListOf<String>()
    text.split('\n').forEach { paragraph ->
        if (paragraph.isBlank()) {
            result += ""
        } else {
            var line = ""
            paragraph.trim().split(Regex("\\s+")).forEach { word ->
                val candidate = if (line.isBlank()) word else "$line $word"
                if (paint.measureText(candidate) <= maxWidth || line.isBlank()) {
                    line = candidate
                } else {
                    result += line
                    line = word
                }
            }
            if (line.isNotBlank()) result += line
        }
    }
    return result.ifEmpty { listOf("") }
}

private fun decodeBitmapSafely(input: java.io.InputStream?): Bitmap? {
    if (input == null) return null
    val bytes = input.use { it.readBytes() }
    if (bytes.isEmpty()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
    while (maxSide / sample > 2200) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample.coerceAtLeast(1)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}
