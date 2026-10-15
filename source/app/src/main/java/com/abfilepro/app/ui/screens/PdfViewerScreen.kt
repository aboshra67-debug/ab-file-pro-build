package com.abfilepro.app.ui.screens

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abfilepro.app.core.FileUtils
import com.abfilepro.app.core.PdfViewerUtils
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.i18n.uiText
import com.abfilepro.app.ui.components.ScreenTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun PdfViewerScreen(filePath: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val appLanguage = LocalAppLanguage.current
    val file = remember(filePath) { File(filePath) }
    var selectedPage by remember(filePath) { mutableIntStateOf(0) }
    var scale by remember(filePath) { mutableFloatStateOf(1f) }
    var offsetX by remember(filePath) { mutableFloatStateOf(0f) }
    var offsetY by remember(filePath) { mutableFloatStateOf(0f) }

    var pageCount by remember(filePath) { mutableIntStateOf(0) }
    var documentLoading by remember(filePath) { mutableStateOf(true) }
    var documentError by remember(filePath) { mutableStateOf<String?>(null) }
    var pageBitmap by remember(filePath) { mutableStateOf<Bitmap?>(null) }
    var pageLoading by remember(filePath) { mutableStateOf(false) }
    var pageError by remember(filePath) { mutableStateOf<String?>(null) }

    LaunchedEffect(filePath, file.lastModified()) {
        documentLoading = true
        documentError = null
        pageCount = 0
        if (!file.exists() || !file.isFile) {
            documentError = context.uiText(appLanguage, "b2_viewer_missing")
        } else {
            val result = runCatching { withContext(Dispatchers.IO) { PdfViewerUtils.pageCount(file) } }
            result.onSuccess { count ->
                if (count > 0) pageCount = count else documentError = context.uiText(appLanguage, "b2_viewer_no_pages")
            }.onFailure { error ->
                documentError = context.uiText(appLanguage, "b2_viewer_error_read")
            }
        }
        documentLoading = false
    }

    LaunchedEffect(filePath, selectedPage, pageCount) {
        pageError = null
        pageBitmap = null
        if (pageCount > 0 && selectedPage in 0 until pageCount && file.exists()) {
            pageLoading = true
            val result = runCatching {
                withContext(Dispatchers.IO) { PdfViewerUtils.renderPage(file, selectedPage, 1500, forPreview = true) }
            }
            result.onSuccess { pageBitmap = it }
                .onFailure { pageError = context.uiText(appLanguage, "b2_viewer_error_page") }
            pageLoading = false
        } else {
            pageLoading = false
        }
    }

    DisposableEffect(pageBitmap) {
        val bitmap = pageBitmap
        onDispose { bitmap?.takeIf { !it.isRecycled }?.recycle() }
    }

    LaunchedEffect(selectedPage) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = file.name.ifBlank { context.uiText(appLanguage, "b2_viewer_title") },
                onBack = onBack,
                actions = {
                    IconButton(onClick = {
                        if (file.exists() && file.isFile) {
                            runCatching { FileUtils.shareFile(context, file) }
                                .onFailure { Toast.makeText(context, context.uiText(appLanguage, "b2_viewer_error_share"), Toast.LENGTH_LONG).show() }
                        } else {
                            Toast.makeText(context, context.uiText(appLanguage, "b2_viewer_missing"), Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Default.Share, contentDescription = context.uiText(appLanguage, "b2_viewer_share"))
                    }
                }
            )
        }
    ) { padding ->
        if (!file.exists()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(context.uiText(appLanguage, "b2_viewer_missing"), color = MaterialTheme.colorScheme.error)
            }
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(padding)) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 1.dp
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        when {
                            documentLoading -> context.uiText(appLanguage, "b2_viewer_opening")
                            documentError != null -> context.uiText(appLanguage, "b2_viewer_error_open")
                            pageCount > 0 -> context.uiText(appLanguage, "b2_viewer_page", selectedPage + 1, pageCount)
                            else -> "PDF"
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { if (selectedPage > 0) selectedPage-- },
                            enabled = selectedPage > 0
                        ) { Icon(Icons.Default.ChevronRight, contentDescription = context.uiText(appLanguage, "b2_viewer_prev")) }
                        IconButton(
                            onClick = { if (selectedPage + 1 < pageCount) selectedPage++ },
                            enabled = selectedPage + 1 < pageCount
                        ) { Icon(Icons.Default.ChevronLeft, contentDescription = context.uiText(appLanguage, "b2_viewer_next")) }
                    }
                }
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f))
                    .pointerInput(selectedPage) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(1f, 4f)
                            if (nextScale == 1f) {
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                offsetX += pan.x
                                offsetY += pan.y
                            }
                            scale = nextScale
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                val bitmap = pageBitmap
                when {
                    documentLoading || pageLoading -> CircularProgressIndicator()
                    documentError != null -> Text(documentError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    pageError != null -> Text(pageError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    bitmap != null -> androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = context.uiText(appLanguage, "b2_viewer_page_pdf"),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY
                            ),
                        contentScale = ContentScale.Fit
                    )
                }

                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = .92f),
                    shadowElevation = 4.dp
                ) {
                    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { scale = (scale - .5f).coerceAtLeast(1f) }) {
                            Icon(Icons.Default.ZoomOut, contentDescription = context.uiText(appLanguage, "b2_viewer_zoom_out"))
                        }
                        Text("${(scale * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                        IconButton(onClick = { scale = (scale + .5f).coerceAtMost(4f) }) {
                            Icon(Icons.Default.ZoomIn, contentDescription = context.uiText(appLanguage, "b2_viewer_zoom_in"))
                        }
                        IconButton(onClick = { scale = 1f; offsetX = 0f; offsetY = 0f }) {
                            Icon(Icons.Default.FitScreen, contentDescription = context.uiText(appLanguage, "b2_viewer_fit"))
                        }
                    }
                }
            }

            if (pageCount > 0) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().height(116.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items((0 until pageCount).toList(), key = { it }) { index ->
                        PdfThumbnail(
                            file = file,
                            pageIndex = index,
                            selected = index == selectedPage,
                            onClick = { selectedPage = index }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfThumbnail(file: File, pageIndex: Int, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val appLanguage = LocalAppLanguage.current
    val bitmap by produceState<Bitmap?>(initialValue = null, file.absolutePath, pageIndex) {
        value = runCatching {
            withContext(Dispatchers.IO) { PdfViewerUtils.renderPage(file, pageIndex, 220, forPreview = true) }
        }.getOrNull()
    }

    DisposableEffect(bitmap) {
        val thumbnail = bitmap
        onDispose { thumbnail?.takeIf { !it.isRecycled }?.recycle() }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .width(70.dp)
                .height(86.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .then(
                    if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                    else Modifier.border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .25f), RoundedCornerShape(8.dp))
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            bitmap?.let {
                androidx.compose.foundation.Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = context.uiText(appLanguage, "b2_viewer_thumb", pageIndex + 1),
                    modifier = Modifier.fillMaxSize().padding(2.dp),
                    contentScale = ContentScale.Fit
                )
            } ?: CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        Text(
            "${pageIndex + 1}",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
