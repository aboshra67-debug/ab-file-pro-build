package com.abfilepro.app.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abfilepro.app.core.FileUtils
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.i18n.uiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * P22: saved scan images open inside the app (pinch to zoom, double tap to reset)
 * instead of a small dialog or another app.
 */
@Composable
fun ScanImageViewer(file: File, onClose: () -> Unit) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    var bitmap by remember(file.absolutePath) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(file.absolutePath) { mutableStateOf(false) }
    var scale by remember(file.absolutePath) { mutableFloatStateOf(1f) }
    var offset by remember(file.absolutePath) { mutableStateOf(Offset.Zero) }
    BackHandler(onBack = onClose)

    LaunchedEffect(file.absolutePath) {
        val decoded = withContext(Dispatchers.IO) { runCatching { decodeForScreen(file, 2600) }.getOrNull() }
        if (decoded == null) failed = true else bitmap = decoded
    }
    DisposableEffect(file.absolutePath) {
        onDispose { bitmap?.takeIf { !it.isRecycled }?.recycle() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val bmp = bitmap
        when {
            bmp != null -> Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = file.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(file.absolutePath) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale <= 1f) Offset.Zero else offset + pan
                        }
                    }
                    .pointerInput(file.absolutePath) {
                        detectTapGestures(onDoubleTap = {
                            if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                        })
                    }
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
            )
            failed -> Text(
                context.uiText(language, "p22_image_open_failed"),
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
            )
            else -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().background(Color.Black.copy(alpha = .45f)).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, context.uiText(language, "p22_close"), tint = Color.White) }
            Text(
                file.name,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { runCatching { FileUtils.shareFile(context, file) } }) {
                Icon(Icons.Default.Share, context.uiText(language, "alpha15_save_share"), tint = Color.White)
            }
        }
    }
}

private fun decodeForScreen(file: File, maxDimension: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDimension) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}
