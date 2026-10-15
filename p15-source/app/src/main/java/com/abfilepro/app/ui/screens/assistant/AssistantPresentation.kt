package com.abfilepro.app.ui.screens.assistant

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.CurrencyExchange
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Mosque
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.abfilepro.app.assistant.AssistantCategory
import com.abfilepro.app.assistant.AssistantModule
import com.abfilepro.app.assistant.PrayerName
import com.abfilepro.app.ui.i18n.AppLanguage

internal data class ModulePresentation(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val color: Color,
)

internal fun AssistantModule.presentation(language: AppLanguage): ModulePresentation {
    val ar = language == AppLanguage.ARABIC
    val fr = language == AppLanguage.FRENCH
    fun copy(arabic: String, english: String, french: String = english): String = when {
        ar -> arabic
        fr -> french
        else -> english
    }
    return when (this) {
        AssistantModule.PRAYER -> ModulePresentation(copy("مواقيت الصلاة والتنبيهات", "Prayer times", "Horaires de prière"), copy("الصلاة القادمة، المواقيت اليومية، وإعدادات التنبيه", "Offline times and alerts", "Horaires et alertes hors ligne"), Icons.Default.Mosque, Color(0xFF087F5B))
        AssistantModule.NOTES -> ModulePresentation(copy("الملاحظات", "Notes", "Notes"), copy("ملاحظات شخصية وعمل مع بحث وتثبيت", "Personal and work notes", "Notes personnelles et travail"), Icons.Default.EditNote, Color(0xFF6C5CE7))
        AssistantModule.TASKS -> ModulePresentation(copy("المهام والتنبيهات", "Tasks & reminders", "Tâches et rappels"), copy("مهام اليوم، التقدم، ومواعيد التنبيه", "Track what needs doing", "Suivez ce qui reste à faire"), Icons.Default.Checklist, Color(0xFFEF5350))
        AssistantModule.CALENDAR -> ModulePresentation(copy("التقويم والمواعيد", "Calendar", "Calendrier"), copy("تقويم شهري ومواعيد شخصية وعمل", "Your daily appointments", "Vos rendez-vous"), Icons.Default.CalendarMonth, Color(0xFF3F7AE0))
        AssistantModule.MEETINGS -> ModulePresentation(copy("الاجتماعات والمحاضر", "Meetings", "Réunions"), copy("جدول الاجتماع، التسجيل، والمحاضر", "Schedule, minutes and follow-up", "Horaire, compte rendu et suivi"), Icons.Default.Groups, Color(0xFF8E44AD))
        AssistantModule.VOICE -> ModulePresentation(copy("التسجيل الصوتي", "Voice recorder", "Enregistreur vocal"), copy("تسجيل سريع، جودة الصوت، وسجل التسجيلات", "Record an idea or meeting", "Enregistrez une idée ou réunion"), Icons.Default.Mic, Color(0xFFE67E22))
        AssistantModule.CAMERA -> ModulePresentation(copy("الكاميرا والمعرض", "Camera & gallery", "Caméra et galerie"), copy("تصوير بفلاتر طبيعية، تحسين تلقائي وحفظ مباشر", "Smart capture and quick save", "Capture intelligente"), Icons.Default.CameraAlt, Color(0xFFE53935))
        AssistantModule.CALCULATOR -> ModulePresentation(copy("الآلة الحاسبة", "Calculator", "Calculatrice"), copy("حاسبة بسيطة مع سجل ونسخ النتيجة", "Fast, safe calculations", "Calculs rapides et sûrs"), Icons.Default.Calculate, Color(0xFF455A64))
        AssistantModule.MONEY -> ModulePresentation(copy("المال والميزانية", "Money & budget", "Argent et budget"), copy("دفتر مصروفات شخصي محفوظ على الجهاز", "Income, spending and balance", "Revenus, dépenses et solde"), Icons.Default.AccountBalanceWallet, Color(0xFF00897B))
        AssistantModule.MARKETS -> ModulePresentation(copy("العملات والذهب", "Currencies & gold", "Devises et or"), copy("أسعار محدّثة وتنبيهات سعرية", "Live price sources", "Sources de prix en direct"), Icons.Default.CurrencyExchange, Color(0xFFD4A017))
        AssistantModule.BILLS -> ModulePresentation(copy("دفع الفواتير", "Bill payment", "Paiement de factures"), copy("اختيار الفاتورة ثم فتح تطبيق دفع مثبت", "Open an installed payment app", "Ouvrir une app de paiement"), Icons.Default.ReceiptLong, Color(0xFF00A86B))
        AssistantModule.COMPARE -> ModulePresentation(copy("مقارنة الأسعار", "Price comparison", "Comparaison des prix"), copy("قارن Amazon مصر وnoon وJumia مع بحث صوتي وباركود وتنبيه سعر", "Compare Amazon Egypt, noon and Jumia with voice, barcode and price alerts", "Comparer Amazon Égypte, noon et Jumia"), Icons.Default.CompareArrows, Color(0xFFFF7A00))
        AssistantModule.SHOPPING -> ModulePresentation(copy("قائمة التسوق", "Shopping list", "Liste de courses"), copy("قائمة مرنة بالفئات والكميات وحالة الشراء", "Organise household needs", "Organisez les besoins"), Icons.Default.ShoppingCart, Color(0xFFEC407A))
        AssistantModule.FAMILY_REQUESTS -> ModulePresentation(copy("طلبات العائلة", "Family requests", "Demandes familiales"), copy("طلبات وشات بين أفراد العائلة المرتبطين", "Requests and family chat", "Demandes et chat familial"), Icons.Default.FamilyRestroom, Color(0xFFAB47BC))
        AssistantModule.DOCUMENTS -> ModulePresentation(copy("المستندات والتجديدات", "Documents & renewals", "Documents et renouvellements"), copy("تواريخ انتهاء وتنبيهات تجديد محلية وآمنة", "Track expiry dates", "Suivez les expirations"), Icons.Default.Badge, Color(0xFF5C6BC0))
        AssistantModule.HEALTH -> ModulePresentation(copy("الصحة والأدوية", "Health & medication", "Santé et médicaments"), copy("تذكير دواء ومواعيد صحية دون تشخيص", "Medication and follow-ups", "Médicaments et suivi"), Icons.Default.Medication, Color(0xFF26A69A))
    }
}

internal fun categoryLabel(category: AssistantCategory, language: AppLanguage): String = when (language) {
    AppLanguage.ARABIC -> when (category) { AssistantCategory.ALL -> "الكل"; AssistantCategory.PERSONAL -> "شخصي"; AssistantCategory.WORK -> "عمل" }
    AppLanguage.FRENCH -> when (category) { AssistantCategory.ALL -> "Tout"; AssistantCategory.PERSONAL -> "Personnel"; AssistantCategory.WORK -> "Travail" }
    AppLanguage.ENGLISH -> when (category) { AssistantCategory.ALL -> "All"; AssistantCategory.PERSONAL -> "Personal"; AssistantCategory.WORK -> "Work" }
}

internal fun prayerLabel(prayer: PrayerName, language: AppLanguage): String = when (language) {
    AppLanguage.ARABIC -> when (prayer) { PrayerName.FAJR -> "الفجر"; PrayerName.SUNRISE -> "الشروق"; PrayerName.DHUHR -> "الظهر"; PrayerName.ASR -> "العصر"; PrayerName.MAGHRIB -> "المغرب"; PrayerName.ISHA -> "العشاء" }
    AppLanguage.FRENCH -> when (prayer) { PrayerName.FAJR -> "Fajr"; PrayerName.SUNRISE -> "Lever"; PrayerName.DHUHR -> "Dhuhr"; PrayerName.ASR -> "Asr"; PrayerName.MAGHRIB -> "Maghrib"; PrayerName.ISHA -> "Isha" }
    AppLanguage.ENGLISH -> prayer.name.lowercase().replaceFirstChar(Char::uppercase)
}

internal fun cityLabel(cityId: String, language: AppLanguage): String {
    val names = when (cityId) {
        "device" -> Triple("موقعي الحالي (GPS)", "Current GPS location", "Position GPS actuelle")
        "alexandria" -> Triple("الإسكندرية", "Alexandria", "Alexandrie")
        "giza" -> Triple("الجيزة", "Giza", "Gizeh")
        "riyadh" -> Triple("الرياض", "Riyadh", "Riyad")
        "mecca" -> Triple("مكة", "Mecca", "La Mecque")
        "dubai" -> Triple("دبي", "Dubai", "Dubaï")
        else -> Triple("القاهرة", "Cairo", "Le Caire")
    }
    return when (language) { AppLanguage.ARABIC -> names.first; AppLanguage.ENGLISH -> names.second; AppLanguage.FRENCH -> names.third }
}

internal fun tr(language: AppLanguage, arabic: String, english: String, french: String = english): String = when (language) {
    AppLanguage.ARABIC -> arabic
    AppLanguage.ENGLISH -> english
    AppLanguage.FRENCH -> french
}
