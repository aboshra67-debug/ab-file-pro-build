package com.abfilepro.app.ui.screens

import android.net.Uri
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.abfilepro.app.core.OcrProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A review step, not a fake success dialog: Word is not made until OCR text
 * is visible and editable. The source image stays unchanged.
 */
@Composable
fun ImageWordReviewDialog(
    uri: Uri,
    language: OcrProcessor.Language,
    onDismiss: () -> Unit,
    onCreateWord: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cropLeft by remember(uri) { mutableFloatStateOf(0f) }
    var cropRight by remember(uri) { mutableFloatStateOf(0f) }
    var cropTop by remember(uri) { mutableFloatStateOf(0f) }
    var cropBottom by remember(uri) { mutableFloatStateOf(0f) }
    var preview by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var reviewed by remember(uri) { mutableStateOf<String?>(null) }
    var working by remember(uri) { mutableStateOf(false) }
    var error by remember(uri) { mutableStateOf<String?>(null) }

    val crop = OcrProcessor.CropSpec(cropLeft, cropTop, cropRight, cropBottom)
    DisposableEffect(preview) {
        val bitmapToRelease = preview
        onDispose { bitmapToRelease?.recycle() }
    }
    // Debounce slider updates: no bitmap decoding in the UI thread.
    LaunchedEffect(uri, cropLeft, cropTop, cropRight, cropBottom, reviewed) {
        if (reviewed != null) return@LaunchedEffect
        delay(160)
        try {
            val thumbnail = OcrProcessor.loadPreview(context, uri, crop)
            preview = thumbnail
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            error = e.message ?: "تعذرت معاينة الصورة"
        }
    }
    Dialog(onDismissRequest = { if (!working) onDismiss() }) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 730.dp).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("صورة إلى Word — مراجعة العربي", style = MaterialTheme.typography.titleLarge)
                if (reviewed == null) {
                    Text("حدّد الجزء المكتوب فقط؛ استبعد الخلفية والأزرار. ثم استخرج النص وراجعه قبل حفظ Word.", style = MaterialTheme.typography.bodySmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (working) return@OutlinedButton
                                working = true
                                error = null
                                scope.launch {
                                    try {
                                        val suggestion = OcrProcessor.suggestDialogCrop(context, uri)
                                        if (suggestion == null) {
                                            error = "لم تُكتشف نافذة واضحة؛ يمكنك تحديد منطقة النص يدويًا."
                                        } else {
                                            cropLeft = suggestion.left
                                            cropTop = suggestion.top
                                            cropRight = suggestion.right
                                            cropBottom = suggestion.bottom
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (e: Exception) {
                                        error = e.message ?: "تعذر تحديد النافذة"
                                    } finally {
                                        working = false
                                    }
                                }
                            },
                            enabled = !working
                        ) { Text("تحديد النافذة تلقائيًا") }
                        TextButton(
                            onClick = {
                                cropLeft = 0f
                                cropTop = 0f
                                cropRight = 0f
                                cropBottom = 0f
                                error = null
                            },
                            enabled = !working && !crop.isEmpty()
                        ) { Text("إلغاء القص") }
                    }
                    preview?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "معاينة منطقة القراءة",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().height(178.dp)
                        )
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        CropSlider("قص من الأعلى", cropTop, !working) { cropTop = it }
                        CropSlider("قص من الأسفل", cropBottom, !working) { cropBottom = it }
                        CropSlider("قص من اليمين", cropRight, !working) { cropRight = it }
                        CropSlider("قص من اليسار", cropLeft, !working) { cropLeft = it }
                    }
                } else {
                    Text("صحّح أي كلمات أو روابط أو أرقام غير دقيقة، ثم أنشئ Word.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = reviewed.orEmpty(),
                        onValueChange = { reviewed = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 410.dp),
                        minLines = 7,
                        label = { Text("النص المستخرج القابل للتعديل") }
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss, enabled = !working) { Text("إلغاء") }
                    Spacer(Modifier.weight(1f))
                    if (reviewed == null) {
                        Button(onClick = {
                            if (working) return@Button
                            working = true
                            error = null
                            scope.launch {
                                try {
                                    val result = OcrProcessor.recognizeForWord(context, uri, language, crop)
                                    if (result.isBlank()) error = "لم يُستخرج نص. حدد منطقة أوضح ثم أعد القراءة."
                                    else reviewed = result
                                } catch (_: CancellationException) {
                                    // Dismissing dialog cancels its coroutine; no false error shown.
                                } catch (e: Exception) {
                                    error = e.message ?: "فشلت قراءة الصورة"
                                } finally {
                                    working = false
                                }
                            }
                        }, enabled = !working) { Text("استخراج النص") }
                    } else {
                        OutlinedButton(onClick = { reviewed = null }, enabled = !working) { Text("إعادة القراءة") }
                        Button(onClick = { reviewed?.takeIf(String::isNotBlank)?.let(onCreateWord) }, enabled = !working && !reviewed.isNullOrBlank()) {
                            Text("إنشاء Word")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CropSlider(label: String, value: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.width(108.dp), style = MaterialTheme.typography.bodySmall)
        Slider(value = value, onValueChange = onChange, valueRange = 0f..0.45f,
            modifier = Modifier.weight(1f), enabled = enabled)
    }
}
