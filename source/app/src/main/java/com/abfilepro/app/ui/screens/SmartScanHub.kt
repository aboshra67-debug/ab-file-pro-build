package com.abfilepro.app.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abfilepro.app.R
import com.abfilepro.app.ui.components.ScreenTopBar
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.i18n.uiText
import java.io.File

enum class SmartScanAction {
    DOCUMENT,
    BATCH,
    ID_CARD,
    RECEIPT,
    BARCODE,
    TRANSLATION,
    IMAGES_TO_PDF
}

private data class SmartScanHubEntry(
    val action: SmartScanAction,
    @DrawableRes val imageRes: Int,
    val titleKey: String,
    val subtitleKey: String,
    val directKey: String = "alpha11_scan_direct_camera"
)

@Composable
fun SmartScanHub(
    onAction: (SmartScanAction) -> Unit,
    onBack: () -> Unit,
    lastSavedFile: File? = null,
    onOpenSaved: (File) -> Unit = {},
    onShareSaved: (File) -> Unit = {}
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val entries = listOf(
        SmartScanHubEntry(SmartScanAction.DOCUMENT, R.drawable.scan_mode_document, "alpha11_scan_document", "alpha11_scan_document_desc"),
        SmartScanHubEntry(SmartScanAction.BATCH, R.drawable.scan_mode_batch, "alpha11_scan_batch", "alpha11_scan_batch_desc"),
        SmartScanHubEntry(SmartScanAction.ID_CARD, R.drawable.scan_mode_id, "alpha11_scan_id", "alpha11_scan_id_desc"),
        SmartScanHubEntry(SmartScanAction.RECEIPT, R.drawable.scan_mode_receipt, "alpha11_scan_receipt", "alpha11_scan_receipt_desc"),
        SmartScanHubEntry(SmartScanAction.BARCODE, R.drawable.scan_mode_barcode, "alpha11_scan_barcode", "alpha11_scan_barcode_desc", "alpha11_scan_direct_live"),
        SmartScanHubEntry(SmartScanAction.TRANSLATION, R.drawable.scan_mode_translation, "alpha11_scan_translation", "alpha11_scan_translation_desc"),
        SmartScanHubEntry(SmartScanAction.IMAGES_TO_PDF, R.drawable.scan_mode_images_pdf, "alpha11_scan_images_pdf", "alpha11_scan_images_pdf_desc")
    )

    Scaffold(topBar = { ScreenTopBar(context.uiText(language, "alpha11_scan_title"), onBack) }) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp)) {
                    Text(
                        context.uiText(language, "alpha11_scan_choose"),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        context.uiText(language, "alpha11_scan_choose_desc"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(entries, key = { it.action.name }, span = { entry ->
                if (entry.action == SmartScanAction.IMAGES_TO_PDF) GridItemSpan(maxLineSpan) else GridItemSpan(1)
            }) { entry ->
                SmartScanImageCard(
                    entry = entry,
                    onClick = { onAction(entry.action) },
                    modifier = if (entry.action == SmartScanAction.IMAGES_TO_PDF) {
                        Modifier.height(204.dp)
                    } else {
                        Modifier.height(218.dp)
                    }
                )
            }

            lastSavedFile?.let { file ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .38f))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text(context.uiText(language, "alpha11_saved_title"), fontWeight = FontWeight.Bold)
                            Text(file.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(context.uiText(language, "alpha11_saved_manager"), style = MaterialTheme.typography.bodySmall)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onOpenSaved(file) }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.FolderOpen, null)
                                    Spacer(Modifier.width(5.dp))
                                    Text(context.uiText(language, "alpha11_saved_open"))
                                }
                                Button(onClick = { onShareSaved(file) }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.Share, null)
                                    Spacer(Modifier.width(5.dp))
                                    Text(context.uiText(language, "alpha11_saved_share"))
                                }
                            }
                        }
                    }
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SmartScanImageCard(
    entry: SmartScanHubEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val isWide = entry.action == SmartScanAction.IMAGES_TO_PDF
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(entry.imageRes),
                contentDescription = context.uiText(language, entry.titleKey),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isWide) 112.dp else 106.dp)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
                contentScale = ContentScale.Crop
            )
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    context.uiText(language, entry.titleKey),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1
                )
                Text(
                    context.uiText(language, entry.subtitleKey),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (isWide) 1 else 2
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f)
                ) {
                    Text(
                        context.uiText(language, entry.directKey),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
