package com.abfilepro.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.i18n.uiText
import com.abfilepro.app.core.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun BatchImagePreviewDialog(
    drafts: List<File>,
    onApprove: () -> Unit,
    onDiscard: () -> Unit,
    saving: Boolean = false
) {
    val context = LocalContext.current
    val appLanguage = LocalAppLanguage.current
    var bitmap by remember(drafts.firstOrNull()?.absolutePath) { mutableStateOf<Bitmap?>(null) }
    var error by remember(drafts.firstOrNull()?.absolutePath) { mutableStateOf<String?>(null) }
    var loading by remember(drafts.firstOrNull()?.absolutePath) { mutableStateOf(true) }

    LaunchedEffect(drafts.firstOrNull()?.absolutePath) {
        loading = true
        runCatching {
            withContext(Dispatchers.IO) {
                val first = drafts.firstOrNull() ?: error(context.uiText(appLanguage, "b2_imgpreview_none"))
                FileUtils.loadImagePreview(first, 1100) ?: error(context.uiText(appLanguage, "b2_imgpreview_error_first"))
            }
        }.onSuccess { bitmap = it }
            .onFailure { error = context.uiText(appLanguage, "b2_imgpreview_error") }
        loading = false
    }

    DisposableEffect(bitmap) {
        val current = bitmap
        onDispose { current?.takeIf { !it.isRecycled }?.recycle() }
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text(context.uiText(appLanguage, "b2_imgpreview_title"), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    loading -> Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    bitmap != null -> Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = context.uiText(appLanguage, "b2_imgpreview_first"),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 380.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                Text(context.uiText(appLanguage, "b2_imgpreview_count", drafts.size))
                Text(
                    context.uiText(appLanguage, "b2_imgpreview_note"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onApprove, enabled = !loading && error == null && !saving) {
                Icon(Icons.Default.CheckCircle, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(context.uiText(appLanguage, "b2_imgpreview_approve"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDiscard, enabled = !saving) {
                Icon(Icons.Default.DeleteOutline, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(context.uiText(appLanguage, "b2_imgpreview_cancel"))
            }
        }
    )
}
