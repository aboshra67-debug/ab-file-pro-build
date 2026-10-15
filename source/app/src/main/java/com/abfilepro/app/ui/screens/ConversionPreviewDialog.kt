package com.abfilepro.app.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.abfilepro.app.core.OfficeTools
import com.abfilepro.app.core.PdfViewerUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ConversionPreviewDialog(
    draft: File,
    onApprove: () -> Unit,
    onDiscard: () -> Unit,
    saving: Boolean = false
) {
    val context = LocalContext.current
    val appLanguage = LocalAppLanguage.current
    var bitmap by remember(draft.absolutePath) { mutableStateOf<Bitmap?>(null) }
    var pageCount by remember(draft.absolutePath) { mutableIntStateOf(0) }
    var officePreview by remember(draft.absolutePath) { mutableStateOf<String?>(null) }
    var error by remember(draft.absolutePath) { mutableStateOf<String?>(null) }
    var loading by remember(draft.absolutePath) { mutableStateOf(true) }
    var zoomPdfPreview by remember(draft.absolutePath) { mutableStateOf(false) }

    LaunchedEffect(draft.absolutePath) {
        loading = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                when (draft.extension.lowercase()) {
                    "pdf" -> {
                        runCatching {
                            val count = PdfViewerUtils.pageCount(draft)
                            require(count > 0) { context.uiText(appLanguage, "b2_preview_no_pages") }
                            count to PdfViewerUtils.renderPage(draft, 0, 1100, forPreview = true)
                        }.getOrElse { previewError ->
                            if (draft.name.startsWith("Protected_", ignoreCase = true) &&
                                draft.length() > 256L &&
                                draft.inputStream().buffered().use { input ->
                                    val header = ByteArray(5)
                                    input.read(header) == 5 && header.decodeToString() == "%PDF-"
                                }
                            ) {
                                context.uiText(appLanguage, "b2_preview_protected")
                            } else {
                                throw previewError
                            }
                        }
                    }
                    "png", "jpg", "jpeg", "webp" -> decodePreviewImage(
                        draft,
                        1400,
                        context.uiText(appLanguage, "b2_preview_error_image_read"),
                        context.uiText(appLanguage, "b2_preview_error_image_preview")
                    )
                    "docx", "xlsx" -> OfficeTools.previewGeneratedOffice(draft)
                    else -> "${draft.name}\n${draft.length()} bytes"
                }
            }
        }.onSuccess { result ->
            when (result) {
                is Pair<*, *> -> {
                    pageCount = result.first as Int
                    bitmap = result.second as Bitmap
                }
                is Bitmap -> bitmap = result
                is String -> officePreview = result
            }
        }.onFailure { error = context.uiText(appLanguage, "b2_preview_error_preview") }
        loading = false
    }

    DisposableEffect(bitmap) {
        val current = bitmap
        onDispose { current?.takeIf { !it.isRecycled }?.recycle() }
    }

    AlertDialog(
        onDismissRequest = { /* Explicit approve/discard avoids orphan draft files. */ },
        title = { Text(context.uiText(appLanguage, "b2_preview_title"), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(draft.name, style = MaterialTheme.typography.labelLarge)
                when {
                    loading -> Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    bitmap != null -> {
                        if (draft.extension.equals("pdf", ignoreCase = true)) {
                            TextButton(onClick = { zoomPdfPreview = !zoomPdfPreview }) {
                                Text(if (zoomPdfPreview) "تصغير المعاينة" else "تكبير PDF لمراجعة النص")
                            }
                        }
                        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            Image(
                                bitmap = bitmap!!.asImageBitmap(),
                                contentDescription = context.uiText(appLanguage, "b2_preview_first_page"),
                                modifier = if (zoomPdfPreview) Modifier.width(570.dp).height(805.dp)
                                    else Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 380.dp),
                                contentScale = ContentScale.Fit
                            )
                        }
                        Text(
                            when {
                                draft.extension.equals("pdf", ignoreCase = true) && pageCount == 1 -> context.uiText(appLanguage, "b2_preview_pdf_one")
                                draft.extension.equals("pdf", ignoreCase = true) -> context.uiText(appLanguage, "b2_preview_pdf_many", pageCount)
                                else -> context.uiText(appLanguage, "b2_preview_image_preview")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    officePreview != null -> Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)
                    ) {
                        Text(
                            officePreview.orEmpty(),
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Text(
                    context.uiText(appLanguage, "b2_preview_safe_note"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onApprove, enabled = !loading && error == null && !saving) {
                Icon(Icons.Default.CheckCircle, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(context.uiText(appLanguage, "b2_preview_approve"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDiscard, enabled = !saving) {
                Icon(Icons.Default.DeleteOutline, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(context.uiText(appLanguage, "b2_preview_cancel"))
            }
        }
    )
}

private fun decodePreviewImage(
    file: File,
    maxDimension: Int,
    imageReadError: String,
    imagePreviewError: String
): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { imageReadError }
    var sample = 1
    while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > maxDimension * 2) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return requireNotNull(BitmapFactory.decodeFile(file.absolutePath, options)) { imagePreviewError }
}

private data class PdfBatchPreviewItem(
    val file: File,
    val pageCount: Int,
    val bitmap: Bitmap
)

@Composable
fun BatchPdfPreviewDialog(
    drafts: List<File>,
    onApprove: () -> Unit,
    onDiscard: () -> Unit
) {
    val context = LocalContext.current
    val appLanguage = LocalAppLanguage.current
    val key = remember(drafts) { drafts.joinToString("|") { it.absolutePath } }
    var previews by remember(key) { mutableStateOf<List<PdfBatchPreviewItem>>(emptyList()) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var loading by remember(key) { mutableStateOf(true) }

    LaunchedEffect(key) {
        loading = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                drafts.take(6).map { file ->
                    require(file.extension.equals("pdf", ignoreCase = true)) { context.uiText(appLanguage, "b2_preview_unexpected", file.name) }
                    val count = PdfViewerUtils.pageCount(file)
                    require(count > 0) { context.uiText(appLanguage, "b2_preview_file_no_pages", file.name) }
                    PdfBatchPreviewItem(file, count, PdfViewerUtils.renderPage(file, 0, 700, forPreview = true))
                }
            }
        }.onSuccess { previews = it }
            .onFailure { error = context.uiText(appLanguage, "b2_preview_error_batch") }
        loading = false
    }

    DisposableEffect(previews) {
        val current = previews
        onDispose { current.forEach { item -> item.bitmap.takeIf { !it.isRecycled }?.recycle() } }
    }

    AlertDialog(
        onDismissRequest = { /* Require an explicit approve/discard decision. */ },
        title = { Text(context.uiText(appLanguage, "b2_preview_batch_title"), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(context.uiText(appLanguage, "b2_preview_batch_count", drafts.size, minOf(drafts.size, 6)))
                when {
                    loading -> Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    else -> previews.forEach { item ->
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(item.file.name, style = MaterialTheme.typography.labelMedium)
                                Image(
                                    bitmap = item.bitmap.asImageBitmap(),
                                    contentDescription = context.uiText(appLanguage, "b2_preview_image_cd", item.file.name),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp),
                                    contentScale = ContentScale.Fit
                                )
                                Text(
                                    if (item.pageCount == 1) context.uiText(appLanguage, "b2_preview_one_page") else context.uiText(appLanguage, "b2_preview_page_many", item.pageCount),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                if (drafts.size > 6) {
                    Text(
                        context.uiText(appLanguage, "b2_preview_more", drafts.size - 6),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    context.uiText(appLanguage, "b2_preview_batch_note"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onApprove, enabled = !loading && error == null) {
                Icon(Icons.Default.CheckCircle, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(context.uiText(appLanguage, "b2_preview_approve_all"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDiscard) {
                Icon(Icons.Default.DeleteOutline, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(context.uiText(appLanguage, "b2_preview_cancel_all"))
            }
        }
    )
}

