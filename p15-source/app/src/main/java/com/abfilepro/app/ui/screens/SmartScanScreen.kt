package com.abfilepro.app.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abfilepro.app.core.OcrProcessor
import com.abfilepro.app.core.SmartScanClassifier
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.i18n.localizedLabel
import com.abfilepro.app.ui.i18n.uiText
import com.abfilepro.app.ui.components.ScreenTopBar
import kotlinx.coroutines.launch

@Composable
fun SmartScanScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val appLanguage = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var preview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var result by remember { mutableStateOf<SmartScanClassifier.Result?>(null) }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
        if (selected != null) {
            uri = selected
            result = null
            busy = true
            scope.launch {
                runCatching {
                    val p = OcrProcessor.loadPreview(context, selected)
                    val r = SmartScanClassifier.analyze(context, selected)
                    p to r
                }.onSuccess { (p, r) ->
                    preview?.let { if (!it.isRecycled) it.recycle() }
                    preview = p
                    result = r
                }.onFailure {
                    Toast.makeText(context, context.uiText(appLanguage, "b2_smartscan_error"), Toast.LENGTH_LONG).show()
                }
                busy = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { preview?.let { if (!it.isRecycled) it.recycle() } }
    }

    Scaffold(topBar = { ScreenTopBar(context.uiText(appLanguage, "b2_smartscan_title"), onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text(context.uiText(appLanguage, "b2_smartscan_title"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        context.uiText(appLanguage, "b2_smartscan_desc"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            if (preview != null) {
                Card(Modifier.fillMaxWidth().weight(1f)) {
                    Image(
                        bitmap = preview!!.asImageBitmap(),
                        contentDescription = context.uiText(appLanguage, "b2_smartscan_preview"),
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                Spacer(Modifier.height(12.dp))
            } else {
                Card(Modifier.fillMaxWidth().weight(1f)) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Default.ImageSearch, null, modifier = Modifier.size(84.dp))
                        Spacer(Modifier.height(10.dp))
                        Text(context.uiText(appLanguage, "b2_smartscan_pick_hint"))
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            result?.let { r ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(context.localizedLabel(appLanguage, r.kind), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            AssistChip(onClick = {}, label = { Text(context.uiText(appLanguage, "b2_smartscan_conf", r.confidence)) })
                        }
                        Text(context.uiText(appLanguage, r.reasonKey), style = MaterialTheme.typography.bodySmall)
                        if (r.extractedPreview.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(r.extractedPreview, maxLines = 3, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = {
                            val target = when {
                                (r.kind == SmartScanClassifier.Kind.INVOICE || r.kind == SmartScanClassifier.Kind.RECEIPT) && uri != null ->
                                    "receipt?uri=${Uri.encode(uri.toString())}"
                                r.kind == SmartScanClassifier.Kind.BUSINESS_CARD && uri != null ->
                                    "businesscard?uri=${Uri.encode(uri.toString())}"
                                else -> r.suggestedRoute
                            }
                            onNavigate(target)
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.ArrowForward, null)
                            Spacer(Modifier.width(8.dp))
                            Text(context.uiText(appLanguage, r.suggestedActionKey))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch("image/*") }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(6.dp)); Text(if (uri == null) context.uiText(appLanguage, "b2_smartscan_pick") else context.uiText(appLanguage, "b2_smartscan_another"))
                }
                Button(onClick = { onNavigate("scan") }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.DocumentScanner, null); Spacer(Modifier.width(6.dp)); Text(context.uiText(appLanguage, "b2_smartscan_scanner"))
                }
            }
            TextButton(onClick = { onNavigate("cardscan") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CreditCard, null); Spacer(Modifier.width(6.dp)); Text(context.uiText(appLanguage, "b2_smartscan_card"))
            }
            TextButton(onClick = { onNavigate("receipt") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.ReceiptLong, null); Spacer(Modifier.width(6.dp)); Text(context.uiText(appLanguage, "b2_smartscan_receipt"))
            }
            TextButton(onClick = { onNavigate("businesscard") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.ContactPage, null); Spacer(Modifier.width(6.dp)); Text(context.uiText(appLanguage, "b2_smartscan_business"))
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
        }
    }
}
