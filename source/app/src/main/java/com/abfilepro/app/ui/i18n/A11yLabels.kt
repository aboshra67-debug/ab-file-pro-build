package com.abfilepro.app.ui.i18n

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/** P21: spoken labels for icon-only buttons (TalkBack), in the app language. */
@Composable
fun a11yLabel(icon: ImageVector): String? {
    val language = LocalAppLanguage.current
    val (ar, en, fr) = when (icon) {
        Icons.Default.ArrowBack -> Triple("رجوع", "Back", "Retour")
        Icons.Default.Home -> Triple("الرئيسية", "Home", "Accueil")
        Icons.Default.Refresh -> Triple("تحديث", "Refresh", "Actualiser")
        Icons.Default.DeleteOutline -> Triple("حذف", "Delete", "Supprimer")
        Icons.Default.MoreVert -> Triple("خيارات أكثر", "More options", "Plus d'options")
        Icons.Default.Remove -> Triple("تقليل", "Decrease", "Diminuer")
        Icons.Default.Add -> Triple("إضافة", "Add", "Ajouter")
        Icons.Default.Close -> Triple("إغلاق", "Close", "Fermer")
        Icons.Default.Share -> Triple("مشاركة", "Share", "Partager")
        else -> return null
    }
    return when (language.code) { "en" -> en; "fr" -> fr; else -> ar }
}
