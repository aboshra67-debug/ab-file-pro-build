package com.abfilepro.app.ui.screens.assistant

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.location.LocationListener
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.abfilepro.app.assistant.AsrSchool
import com.abfilepro.app.assistant.AssistantCategory
import com.abfilepro.app.assistant.AssistantDomain
import com.abfilepro.app.assistant.AssistantEntry
import com.abfilepro.app.assistant.AssistantModule
import com.abfilepro.app.assistant.CityPreset
import com.abfilepro.app.assistant.CurrencyRateService
import com.abfilepro.app.assistant.CurrencyRatesSnapshot
import com.abfilepro.app.assistant.PrayerMethod
import com.abfilepro.app.assistant.PrayerName
import com.abfilepro.app.assistant.PrayerSettings
import com.abfilepro.app.assistant.PrayerTimesCalculator
import com.abfilepro.app.assistant.ProductPriceAlertStore
import com.abfilepro.app.assistant.ProductPriceComparisonService
import com.abfilepro.app.assistant.StorePriceResult
import com.abfilepro.app.ui.screens.BarcodeCamera
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import java.time.Instant
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun AssistantModuleScreen(
    module: AssistantModule,
    entries: List<AssistantEntry>,
    prayerSettings: PrayerSettings,
    defaultCategory: AssistantCategory,
    onBack: () -> Unit,
    onOpenScanner: () -> Unit,
    onAdd: (String, String, AssistantCategory, Long?, Double?, String) -> Unit,
    onAddTask: (String, String, AssistantCategory, Long?, Boolean) -> Unit,
    onUpdate: (AssistantEntry) -> Unit,
    onDelete: (String) -> Unit,
    onPrayerSettings: (PrayerSettings) -> Unit,
) {
    val language = LocalAppLanguage.current
    var showLegacyInteraction by remember(module) { mutableStateOf(false) }

    if (module == AssistantModule.PRAYER) {
        PrayerModuleScreen(prayerSettings, onBack, onPrayerSettings)
        return
    }

    if (module == AssistantModule.TASKS) {
        AssistantTasksScreen(entries, defaultCategory, onBack, onAddTask, onUpdate, onDelete)
        return
    }

    if (module == AssistantModule.CALENDAR) {
        AssistantCalendarScreen(entries, defaultCategory, onBack, onAdd, onUpdate, onDelete)
        return
    }

    if (module == AssistantModule.MEETINGS) {
        AssistantMeetingsScreen(entries, defaultCategory, onBack, onAdd, onUpdate, onDelete)
        return
    }

    if (module == AssistantModule.CAMERA) {
        CameraGalleryScreen(entries, defaultCategory, onBack, onOpenScanner, onAdd, onDelete)
        return
    }

    // Alpha69: voice recorder must be operational directly in Arabic too.
    // Do not stop at the static reference image before reaching the real recorder.
    if (module == AssistantModule.VOICE) {
        VoiceRecorderScreen(entries, defaultCategory, onBack, onAdd, onDelete)
        return
    }

    // Alpha72: the first calculator screen itself is operational.
    // Do not stop at the Arabic static reference and do not open a second calculator inside it.
    if (module == AssistantModule.CALCULATOR) {
        CalculatorModuleScreen(onBack)
        return
    }

    // Alpha74: money & budget keeps the approved visual identity but is now fully operational.
    // It must open directly instead of stopping at the static reference image.
    if (module == AssistantModule.MONEY) {
        MoneyModuleScreen(
            entries = entries,
            defaultCategory = defaultCategory,
            onBack = onBack,
            onAdd = onAdd,
            onDelete = onDelete,
        )
        return
    }

    // Alpha75: currencies & gold keep the approved reference layout but now use live data,
    // favorites, refresh and price alerts instead of the static raster interaction.
    if (module == AssistantModule.MARKETS) {
        MarketsOperationalScreen(onBack)
        return
    }

    // Alpha78: bill payment now opens a real native screen in Arabic too,
    // with bill-type cards, installed payment apps and local reminders.
    if (module == AssistantModule.BILLS) {
        BillPaymentOperationalScreen(onBack)
        return
    }

    // Alpha79: shopping list is a real native screen while preserving the approved layout.
    if (module == AssistantModule.SHOPPING) {
        ShoppingListOperationalScreen(
            entries = entries,
            onBack = onBack,
            onUpdateFamilyRequest = onUpdate,
        )
        return
    }

    // Alpha80: family requests are now an operational native screen with request status,
    // sharing and lightweight request notes instead of the static reference screen.
    if (module == AssistantModule.FAMILY_REQUESTS) {
        FamilyRequestsOperationalScreen(
            entries = entries,
            defaultCategory = defaultCategory,
            onBack = onBack,
            onAdd = onAdd,
            onUpdate = onUpdate,
            onDelete = onDelete,
        )
        return
    }

    // Alpha86: documents & renewals native local-only screen.
    if (module == AssistantModule.DOCUMENTS) {
        DocumentsRenewalsScreen(
            entries = entries,
            onBack = onBack,
            onAdd = onAdd,
            onUpdate = onUpdate,
            onDelete = onDelete,
        )
        return
    }

    // Alpha95: price comparison is now a real operational module in Arabic too.
    // Live prices are shown only when a retailer exposes them in the search response;
    // otherwise the store card remains usable as a direct search link without fake prices.
    if (module == AssistantModule.COMPARE) {
        PriceComparisonScreen(onBack)
        return
    }

    // Alpha85: health & medication is a real native operational screen.
    if (module == AssistantModule.HEALTH) {
        val profile = com.abfilepro.app.assistant.FamilyLinkStore.load(androidx.compose.ui.platform.LocalContext.current)
        HealthMedicationScreen(
            entries = entries,
            selfName = profile.selfName,
            partnerName = profile.partnerName,
            onBack = onBack,
            onAdd = onAdd,
            onUpdate = onUpdate,
            onDelete = onDelete,
        )
        return
    }

    if (module == AssistantModule.NOTES && language.code == "ar") {
        AssistantNotesScreen(
            entries = entries,
            defaultCategory = defaultCategory,
            onBack = onBack,
            onAdd = onAdd,
            onUpdate = onUpdate,
            onDelete = onDelete,
        )
        return
    }

    // Alpha52 visual-match: the approved PDF is the Arabic rendering source.
    // A module can still enter its Alpha51 operational screen when an action requires it.
    if (language.code == "ar" && !showLegacyInteraction) {
        AssistantReferenceModuleScreen(
            module = module,
            entries = entries,
            defaultCategory = defaultCategory,
            onBack = onBack,
            onOpenScanner = onOpenScanner,
            onAdd = onAdd,
            onOpenLegacy = { showLegacyInteraction = true },
        )
        return
    }

    when (module) {
        AssistantModule.PRAYER -> PrayerModuleScreen(prayerSettings, onBack, onPrayerSettings)
        AssistantModule.CALCULATOR -> CalculatorModuleScreen(onBack)
        AssistantModule.CAMERA -> CameraGalleryScreen(entries, defaultCategory, onBack, onOpenScanner, onAdd, onDelete)
        AssistantModule.VOICE -> VoiceRecorderScreen(entries, defaultCategory, onBack, onAdd, onDelete)
        AssistantModule.MARKETS -> MarketsScreen(onBack)
        AssistantModule.BILLS -> BillPaymentScreen(onBack)
        AssistantModule.COMPARE -> PriceComparisonScreen(onBack)
        else -> EntriesModuleScreen(module, entries, defaultCategory, onBack, onAdd, onUpdate, onDelete)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModuleScaffold(
    module: AssistantModule,
    onBack: () -> Unit,
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val language = LocalAppLanguage.current
    val p = module.presentation(language)
    Scaffold(
        containerColor = adaptSurface(Color(0xFFF7F9FC)),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(p.title, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(p.subtitle, color = adaptInk(Color(0xFF64748B)), fontSize = 11.sp, maxLines = 1)
                    }
                },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onBack, modifier = Modifier.padding(start = 6.dp)) {
                        Icon(Icons.Default.ArrowBack, tr(language, "رجوع", "Back", "Retour"))
                    }
                },
                actions = {
                    Surface(shape = RoundedCornerShape(13.dp), color = p.color.copy(alpha = .12f), modifier = Modifier.padding(end = 10.dp)) {
                        Icon(p.icon, null, tint = p.color, modifier = Modifier.padding(9.dp).size(23.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = adaptSurface(Color.White)),
            )
        },
        floatingActionButton = floatingActionButton,
        content = content,
    )
}

@Composable
private fun EntriesModuleScreen(
    module: AssistantModule,
    entries: List<AssistantEntry>,
    defaultCategory: AssistantCategory,
    onBack: () -> Unit,
    onAdd: (String, String, AssistantCategory, Long?, Double?, String) -> Unit,
    onUpdate: (AssistantEntry) -> Unit,
    onDelete: (String) -> Unit,
) {
    val language = LocalAppLanguage.current
    val context = LocalContext.current
    var showEditor by remember { mutableStateOf(false) }
    val checkable = module in setOf(AssistantModule.TASKS, AssistantModule.SHOPPING, AssistantModule.FAMILY_REQUESTS)
    val toggleEnabled = module == AssistantModule.HEALTH
    val money = module == AssistantModule.MONEY
    val showDue = module in setOf(AssistantModule.TASKS, AssistantModule.CALENDAR, AssistantModule.MEETINGS, AssistantModule.DOCUMENTS, AssistantModule.HEALTH)

    if (showEditor) {
        EntryEditorDialog(
            module = module,
            defaultCategory = defaultCategory,
            showDue = showDue,
            showAmount = money,
            onDismiss = { showEditor = false },
            onSave = { title, details, category, dueAt, amount, secondary ->
                onAdd(title, details, category, dueAt, amount, secondary)
                showEditor = false
            },
        )
    }

    ModuleScaffold(
        module = module,
        onBack = onBack,
        floatingActionButton = {
            FloatingActionButton(onClick = { showEditor = true }, containerColor = module.presentation(language).color) {
                Icon(Icons.Default.Add, tr(language, "إضافة", "Add", "Ajouter"), tint = Color.White)
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (module == AssistantModule.MONEY) {
                item { MoneySummary(entries) }
            }
            if (module == AssistantModule.FAMILY_REQUESTS) {
                item {
                    InfoCard(
                        title = tr(language, "طلبات فقط — ليست محادثة", "Requests only — not a chat", "Demandes uniquement — pas de chat"),
                        body = tr(language, "أضف الطلب ثم أرسله لشخص واحد عبر وسيلة المشاركة التي تختارها.", "Add a request, then send it to one person using your chosen share app.", "Ajoutez une demande puis envoyez-la à une seule personne."),
                    )
                }
            }
            if (entries.isEmpty()) {
                item { EmptyModuleState(module) }
            } else {
                items(entries, key = { it.id }) { entry ->
                    EntryCard(
                        entry = entry,
                        checkable = checkable,
                        toggleEnabled = toggleEnabled,
                        showMoney = money,
                        onToggle = {
                            onUpdate(
                                if (toggleEnabled) entry.copy(enabled = !entry.enabled)
                                else entry.copy(completed = !entry.completed)
                            )
                        },
                        onShare = if (module == AssistantModule.FAMILY_REQUESTS) {{
                            shareText(context, "${entry.title}\n${entry.details}".trim())
                        }} else null,
                        onCalendar = if (module == AssistantModule.CALENDAR || module == AssistantModule.MEETINGS) {{
                            insertCalendarEvent(context, entry)
                        }} else null,
                        onDelete = { onDelete(entry.id) },
                    )
                }
            }
            item { Spacer(Modifier.height(86.dp)) }
        }
    }
}

@Composable
private fun EntryCard(
    entry: AssistantEntry,
    checkable: Boolean,
    toggleEnabled: Boolean,
    showMoney: Boolean,
    onToggle: () -> Unit,
    onShare: (() -> Unit)?,
    onCalendar: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    val language = LocalAppLanguage.current
    val date = entry.dueAt?.let { formatDateTime(it, language.code) }
    Card(
        colors = CardDefaults.cardColors(containerColor = adaptSurface(Color.White)),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
            if (checkable) {
                IconButton(onClick = onToggle) {
                    Icon(
                        if (entry.completed) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        null,
                        tint = if (entry.completed) adaptInk(Color(0xFF0F9D72)) else adaptInk(Color(0xFF94A3B8)),
                    )
                }
            } else if (toggleEnabled) {
                Switch(checked = entry.enabled, onCheckedChange = { onToggle() })
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f).padding(top = 5.dp)) {
                Text(
                    entry.title,
                    fontWeight = FontWeight.Bold,
                    color = if (entry.completed) adaptInk(Color(0xFF94A3B8)) else MaterialTheme.colorScheme.onSurface,
                )
                if (entry.details.isNotBlank()) Text(entry.details, color = adaptInk(Color(0xFF64748B)), fontSize = 13.sp, lineHeight = 18.sp)
                if (date != null) Text(date, color = adaptInk(Color(0xFF3F7AE0)), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                if (showMoney && entry.amount != null) {
                    val income = entry.secondary == "income"
                    Text(
                        "${if (income) "+" else "-"}${formatAmount(entry.amount)}",
                        color = if (income) adaptInk(Color(0xFF0F9D72)) else adaptInk(Color(0xFFE53935)),
                        fontWeight = FontWeight.Black,
                    )
                }
                Text(categoryLabel(entry.category, language), color = adaptInk(Color(0xFF94A3B8)), fontSize = 10.sp)
            }
            Column {
                onShare?.let { IconButton(onClick = it) { Icon(Icons.Default.Share, tr(language, "مشاركة", "Share", "Partager"), tint = adaptInk(Color(0xFF6C5CE7))) } }
                onCalendar?.let { IconButton(onClick = it) { Icon(Icons.Default.Launch, tr(language, "إضافة للتقويم", "Add to calendar", "Ajouter au calendrier"), tint = adaptInk(Color(0xFF3F7AE0))) } }
                IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, tr(language, "حذف", "Delete", "Supprimer"), tint = adaptInk(Color(0xFFE57373))) }
            }
        }
    }
}

@Composable
private fun MoneySummary(entries: List<AssistantEntry>) {
    val language = LocalAppLanguage.current
    val income = entries.filter { it.secondary == "income" }.sumOf { it.amount ?: 0.0 }
    val expense = entries.filter { it.secondary != "income" }.sumOf { it.amount ?: 0.0 }
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF173B65))) {
        Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(tr(language, "الرصيد المسجل", "Recorded balance", "Solde enregistré"), color = Color.White.copy(alpha = .72f))
            Text(formatAmount(income - expense), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Text("+ ${formatAmount(income)}", color = Color(0xFF6EE7B7), fontWeight = FontWeight.Bold)
                Text("- ${formatAmount(expense)}", color = adaptInk(Color(0xFFFCA5A5)), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun EntryEditorDialog(
    module: AssistantModule,
    defaultCategory: AssistantCategory,
    showDue: Boolean,
    showAmount: Boolean,
    initialEntry: AssistantEntry? = null,
    onDismiss: () -> Unit,
    onSave: (String, String, AssistantCategory, Long?, Double?, String) -> Unit,
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    var title by remember(initialEntry?.id) { mutableStateOf(initialEntry?.title.orEmpty()) }
    var details by remember(initialEntry?.id) { mutableStateOf(initialEntry?.details.orEmpty()) }
    var category by remember(initialEntry?.id) { mutableStateOf(initialEntry?.category ?: AssistantDomain.sanitizeCategory(module, defaultCategory)) }
    var dueAt by remember(initialEntry?.id) { mutableStateOf<Long?>(if (showDue) initialEntry?.dueAt ?: System.currentTimeMillis() + 3_600_000 else null) }
    var amountText by remember(initialEntry?.id) { mutableStateOf(initialEntry?.amount?.toString().orEmpty()) }
    var moneyType by remember(initialEntry?.id) { mutableStateOf(initialEntry?.secondary?.takeIf { showAmount && it in setOf("income", "expense") } ?: "expense") }
    val titleHint = when (module) {
        AssistantModule.NOTES -> tr(language, "عنوان الملاحظة", "Note title", "Titre de la note")
        AssistantModule.TASKS -> tr(language, "اسم المهمة", "Task name", "Nom de la tâche")
        AssistantModule.CALENDAR -> tr(language, "اسم الموعد", "Appointment title", "Titre du rendez-vous")
        AssistantModule.MEETINGS -> tr(language, "اسم الاجتماع", "Meeting title", "Titre de la réunion")
        AssistantModule.MONEY -> tr(language, "البيان", "Description", "Description")
        AssistantModule.SHOPPING -> tr(language, "ما الذي تحتاجه؟", "What do you need?", "De quoi avez-vous besoin ?")
        AssistantModule.FAMILY_REQUESTS -> tr(language, "اكتب الطلب", "Write the request", "Écrivez la demande")
        AssistantModule.DOCUMENTS -> tr(language, "اسم المستند", "Document name", "Nom du document")
        AssistantModule.HEALTH -> tr(language, "اسم الدواء أو المتابعة", "Medication or follow-up", "Médicament ou suivi")
        else -> tr(language, "العنوان", "Title", "Titre")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialEntry == null) tr(language, "إضافة جديد", "Add new", "Ajouter") else tr(language, "تعديل", "Edit", "Modifier"), fontWeight = FontWeight.Black) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { OutlinedTextField(title, { title = it.take(240) }, label = { Text(titleHint) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(details, { details = it.take(4000) }, label = { Text(tr(language, "تفاصيل (اختياري)", "Details (optional)", "Détails (facultatif)")) }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth()) }
                if (module.categories.size > 1) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(AssistantCategory.PERSONAL, AssistantCategory.WORK).forEach { value ->
                                FilterChip(selected = category == value, onClick = { category = value }, label = { Text(categoryLabel(value, language)) })
                            }
                        }
                    }
                }
                if (showDue) {
                    item {
                        OutlinedButton(onClick = { pickDateTime(context, dueAt ?: System.currentTimeMillis()) { dueAt = it } }, modifier = Modifier.fillMaxWidth()) {
                            Text(tr(language, "الموعد: ", "Date: ", "Date : ") + formatDateTime(dueAt ?: System.currentTimeMillis(), language.code))
                        }
                    }
                }
                if (showAmount) {
                    item { OutlinedTextField(amountText, { amountText = it.filter { c -> c.isDigit() || c == '.' }.take(16) }, label = { Text(tr(language, "المبلغ", "Amount", "Montant")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth()) }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = moneyType == "expense", onClick = { moneyType = "expense" }, label = { Text(tr(language, "مصروف", "Expense", "Dépense")) })
                            FilterChip(selected = moneyType == "income", onClick = { moneyType = "income" }, label = { Text(tr(language, "دخل", "Income", "Revenu")) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(title, details, category, dueAt, amountText.toDoubleOrNull(), if (showAmount) moneyType else "") },
                enabled = title.isNotBlank() && (!showAmount || amountText.toDoubleOrNull() != null),
            ) { Text(tr(language, "حفظ", "Save", "Enregistrer")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(language, "إلغاء", "Cancel", "Annuler")) } },
    )
}

@Composable
private fun EmptyModuleState(module: AssistantModule) {
    val language = LocalAppLanguage.current
    val p = module.presentation(language)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(shape = CircleShape, color = p.color.copy(alpha = .11f)) {
            Icon(p.icon, null, tint = p.color, modifier = Modifier.padding(18.dp).size(34.dp))
        }
        Text(tr(language, "لا توجد عناصر بعد", "Nothing here yet", "Aucun élément"), fontWeight = FontWeight.Bold)
        Text(tr(language, "اضغط + لإضافة أول عنصر", "Tap + to add the first item", "Touchez + pour ajouter"), color = adaptInk(Color(0xFF64748B)), fontSize = 13.sp)
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = adaptSurface(Color(0xFFEFF6FF))), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(15.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = adaptInk(Color(0xFF173B65)))
            Text(body, color = adaptInk(Color(0xFF475569)), fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun VoiceRecorderScreen(
    entries: List<AssistantEntry>,
    defaultCategory: AssistantCategory,
    onBack: () -> Unit,
    onAdd: (String, String, AssistantCategory, Long?, Double?, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    OperationalVoiceRecorderScreen(entries, defaultCategory, onBack, onAdd, onDelete)
}

@Composable
private fun CameraGalleryScreen(
    entries: List<AssistantEntry>,
    defaultCategory: AssistantCategory,
    onBack: () -> Unit,
    onOpenScanner: () -> Unit,
    onAdd: (String, String, AssistantCategory, Long?, Double?, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    var cameraOpen by remember { mutableStateOf(false) }
    var galleryEditUri by remember { mutableStateOf<Uri?>(null) }

    fun registerSavedPortrait(uri: Uri) {
        onAdd(
            tr(language, "صورة شخصية", "Portrait photo", "Photo portrait") + " " + formatDateTime(System.currentTimeMillis(), language.code),
            "", defaultCategory, null, null, uri.toString(),
        )
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        galleryEditUri = uri
    }

    if (cameraOpen) {
        SmartPortraitCameraScreen(
            onBack = { cameraOpen = false },
            onSaved = { uri ->
                registerSavedPortrait(uri)
                cameraOpen = false
            },
        )
        return
    }

    galleryEditUri?.let { uri ->
        SmartPortraitGalleryEditorScreen(
            uri = uri,
            onBack = { galleryEditUri = null },
            onSaved = { saved ->
                registerSavedPortrait(saved)
                galleryEditUri = null
            },
        )
        return
    }

    ModuleScaffold(AssistantModule.CAMERA, onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                InfoCard(
                    tr(language, "Smart Portrait Camera", "Smart Portrait Camera", "Smart Portrait Camera"),
                    tr(language, "تصوير شخصي ذكي مع أوضاع تصوير ودليل للوقفة وتمركز الوجه والتقاط تلقائي وفلاتر وتعديل قبل الحفظ.", "Portrait camera with framing guidance, auto capture, filters and editing before save.", "Caméra portrait avec guidage, capture automatique, filtres et retouche avant l’enregistrement."),
                )
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { cameraOpen = true }, modifier = Modifier.weight(1f).height(54.dp)) { Icon(Icons.Default.CameraAlt, null); Spacer(Modifier.width(6.dp)); Text(tr(language, "الكاميرا الذكية", "Smart camera", "Caméra intelligente")) }
                    OutlinedButton(onClick = { picker.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f).height(54.dp)) { Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(6.dp)); Text(tr(language, "المعرض", "Gallery", "Galerie")) }
                }
            }
            item {
                OutlinedButton(onClick = onOpenScanner, modifier = Modifier.fillMaxWidth()) {
                    Text(tr(language, "فتح ماسح المستندات بدلًا من ذلك", "Open document scanner instead", "Ouvrir le scanner de documents"))
                }
            }
            if (entries.isEmpty()) item { EmptyModuleState(AssistantModule.CAMERA) }
            items(entries, key = { it.id }) { entry ->
                MediaReferenceCard(entry, onOpen = { openUri(context, entry.secondary, "image/*") }, onDelete = { onDelete(entry.id) })
            }
        }
    }
}

@Composable
private fun MediaReferenceCard(entry: AssistantEntry, onOpen: () -> Unit, onDelete: () -> Unit) {
    val language = LocalAppLanguage.current
    Card(colors = CardDefaults.cardColors(containerColor = adaptSurface(Color.White)), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.title, fontWeight = FontWeight.Bold, maxLines = 2)
                Text(tr(language, "محفوظ داخل جهازك", "Stored on your device", "Stocké sur votre appareil"), color = adaptInk(Color(0xFF64748B)), fontSize = 11.sp)
            }
            IconButton(onClick = onOpen) { Icon(Icons.Default.OpenInNew, tr(language, "فتح", "Open", "Ouvrir"), tint = adaptInk(Color(0xFF3F7AE0))) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, tr(language, "حذف", "Delete", "Supprimer"), tint = adaptInk(Color(0xFFE57373))) }
        }
    }
}

@Composable
private fun CalculatorModuleScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("calculator") }
    var expression by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("0") }
    val keys = listOf("7", "8", "9", "/", "4", "5", "6", "*", "1", "2", "3", "-", "0", ".", "(", ")", "C", "%", "⌫", "+")

    var amountText by remember { mutableStateOf("100") }
    var fromCurrency by remember { mutableStateOf("USD") }
    var toCurrency by remember { mutableStateOf("EGP") }
    var rates by remember { mutableStateOf<CurrencyRatesSnapshot?>(null) }
    var ratesLoading by remember { mutableStateOf(false) }
    var ratesError by remember { mutableStateOf<String?>(null) }
    val currencies = remember { listOf("EGP", "USD", "EUR", "SAR", "AED", "GBP", "KWD", "QAR", "CNY", "JPY", "CHF", "CAD") }

    fun refreshRates(force: Boolean) {
        if (ratesLoading) return
        ratesLoading = true
        ratesError = null
        scope.launch {
            runCatching { CurrencyRateService.load(context, forceRefresh = force) }
                .onSuccess { rates = it }
                .onFailure { ratesError = it.message ?: "network_error" }
            ratesLoading = false
        }
    }

    LaunchedEffect(mode) {
        if (mode == "currency" && rates == null && !ratesLoading) refreshRates(false)
    }

    if (mode == "calculator") {
        OperationalReferenceCalculator(
            onBack = onBack,
            onOpenCurrency = { mode = "currency" },
        )
        return
    }

    ModuleScaffold(AssistantModule.CALCULATOR, onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Match the native calculator's mode tabs, without introducing a new route.
            Row(
                Modifier.fillMaxWidth()
                    .background(adaptSurface(Color.White), RoundedCornerShape(22.dp))
                    .border(1.dp, adaptSurface(Color(0xFFE3E8EF)), RoundedCornerShape(22.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier.weight(1f).height(48.dp)
                        .background(adaptSurface(Color(0xFFF9FAFB)), RoundedCornerShape(18.dp))
                        .clickable { mode = "calculator" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(tr(language, "حاسبة", "Calculator", "Calculatrice"), color = adaptInk(Color(0xFF334155)), fontWeight = FontWeight.Bold)
                }
                Box(
                    Modifier.weight(1f).height(48.dp)
                        .background(adaptSurface(Color(0xFFE9DDF8)), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(tr(language, "تحويل العملات", "Convert", "Convertir"), color = adaptInk(Color(0xFF101827)), fontWeight = FontWeight.Black)
                }
            }

            if (mode == "calculator") {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF173B65)), shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.End) {
                        Text(expression.ifBlank { "0" }, color = Color.White.copy(alpha = .65f), fontSize = 18.sp, maxLines = 2)
                        Text(result, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Black, maxLines = 1)
                    }
                }
                keys.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { key ->
                            FilledTonalButton(
                                onClick = {
                                    when (key) {
                                        "C" -> { expression = ""; result = "0" }
                                        "⌫" -> expression = expression.dropLast(1)
                                        else -> if (expression.length < 120) expression += key
                                    }
                                },
                                modifier = Modifier.weight(1f).height(56.dp),
                                contentPadding = PaddingValues(0.dp),
                            ) { Text(key, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
                Button(
                    onClick = {
                        result = runCatching { formatAmount(AssistantDomain.evaluateExpression(expression)) }
                            .getOrElse { tr(language, "عملية غير صحيحة", "Invalid expression", "Expression invalide") }
                    },
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                ) { Text("=", fontSize = 25.sp, fontWeight = FontWeight.Black) }
            } else {
                CurrencyConverterPanel(
                    language = language,
                    currencies = currencies,
                    amountText = amountText,
                    onAmountChange = { amountText = sanitizeCurrencyInput(it) },
                    fromCurrency = fromCurrency,
                    toCurrency = toCurrency,
                    onFromCurrency = { fromCurrency = it },
                    onToCurrency = { toCurrency = it },
                    onSwap = { val old = fromCurrency; fromCurrency = toCurrency; toCurrency = old },
                    rates = rates,
                    isLoading = ratesLoading,
                    error = ratesError,
                    onRefresh = { refreshRates(true) },
                )
            }
        }
    }
}

@Composable
private fun CurrencyConverterPanel(
    language: com.abfilepro.app.ui.i18n.AppLanguage,
    currencies: List<String>,
    amountText: String,
    onAmountChange: (String) -> Unit,
    fromCurrency: String,
    toCurrency: String,
    onFromCurrency: (String) -> Unit,
    onToCurrency: (String) -> Unit,
    onSwap: () -> Unit,
    rates: CurrencyRatesSnapshot?,
    isLoading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val amount = parseCurrencyAmount(amountText)
    val converted = if (amount != null && rates != null) rates.convert(amount, fromCurrency, toCurrency) else null
    val providerTime = rates?.providerUpdatedAtMillis?.takeIf { it > 0L }?.let {
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.getDefault())
            .format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
    }
    val checkedTime = rates?.fetchedAtMillis?.takeIf { it > 0L }?.let {
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.getDefault())
            .format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
    }

    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF173B65)), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(tr(language, "نتيجة التحويل", "Conversion result", "Résultat de conversion"), color = Color.White.copy(alpha = .75f), fontSize = 14.sp)
            val resultText = converted?.let { "${formatCurrencyValue(it)} $toCurrency" } ?: if (isLoading) "…" else "—"
            Text(
                resultText,
                color = Color.White,
                fontSize = when {
                    resultText.length > 23 -> 23.sp
                    resultText.length > 16 -> 28.sp
                    else -> 36.sp
                },
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (amount != null) Text("${formatCurrencyValue(amount)} $fromCurrency", color = Color.White.copy(alpha = .78f), fontSize = 14.sp)
            rates?.convert(1.0, fromCurrency, toCurrency)?.let { rate ->
                Text(
                    "1 $fromCurrency ≈ ${formatCurrencyValue(rate)} $toCurrency",
                    color = Color(0xFFD2E7FF), fontSize = 13.sp, maxLines = 1,
                )
            }
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = adaptSurface(Color.White)), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr(language, "المبلغ المطلوب تحويله", "Amount to convert", "Montant à convertir"), fontWeight = FontWeight.Bold, color = adaptInk(Color(0xFF173B65)))
            OutlinedTextField(
                value = amountText,
                onValueChange = onAmountChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(tr(language, "المبلغ", "Amount", "Montant")) },
                trailingIcon = { Text(fromCurrency, color = adaptInk(Color(0xFF475569)), fontWeight = FontWeight.Bold) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
            )
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = adaptSurface(Color.White)), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr(language, "من عملة", "From currency", "Devise source"), fontWeight = FontWeight.Bold, color = adaptInk(Color(0xFF173B65)))
            CurrencyCodeRow(currencies, fromCurrency, onFromCurrency, language)
        }
    }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        FilledTonalIconButton(onClick = onSwap, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.SwapHoriz, tr(language, "تبديل العملات", "Swap currencies", "Inverser les devises"))
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = adaptSurface(Color.White)), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr(language, "إلى عملة", "To currency", "Devise cible"), fontWeight = FontWeight.Bold, color = adaptInk(Color(0xFF173B65)))
            CurrencyCodeRow(currencies, toCurrency, onToCurrency, language)
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = adaptSurface(Color.White)), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            isLoading -> tr(language, "جاري تحديث سعر الصرف…", "Updating exchange rates…", "Mise à jour des taux…")
                            error != null && rates == null -> tr(language, "تعذر تحميل الأسعار. تحقق من الإنترنت.", "Could not load rates. Check your connection.", "Impossible de charger les taux.")
                            rates?.fromCache == true -> tr(language, "آخر سعر محفوظ على الجهاز", "Last saved rate on device", "Dernier taux enregistré")
                            else -> tr(language, "أسعار محدثة من الإنترنت", "Rates updated from the internet", "Taux mis à jour en ligne")
                        },
                        fontWeight = FontWeight.Bold,
                    )
                    if (rates != null) {
                        val freshnessText = when {
                            rates.providerName == "ExchangeRate.fun" && rates.providerDate != null ->
                                tr(language, "المصدر: ${rates.providerName} • تحديث كل ساعة • بيانات ${rates.providerDate}", "Source: ${rates.providerName} • hourly updates • data ${rates.providerDate}", "Source : ${rates.providerName} • mise à jour horaire • données ${rates.providerDate}")
                            providerTime != null ->
                                tr(language, "المصدر: ${rates.providerName} • آخر تحديث: $providerTime", "Source: ${rates.providerName} • provider update: $providerTime", "Source : ${rates.providerName} • mise à jour : $providerTime")
                            else -> tr(language, "المصدر: ${rates.providerName}", "Source: ${rates.providerName}", "Source : ${rates.providerName}")
                        }
                        Text(freshnessText, color = adaptInk(Color(0xFF64748B)), fontSize = 12.sp)
                        if (checkedTime != null) Text(
                            tr(language, "آخر فحص من التطبيق: $checkedTime", "App last checked: $checkedTime", "Dernière vérification : $checkedTime"),
                            color = adaptInk(Color(0xFF64748B)),
                            fontSize = 11.sp,
                        )
                        if (rates.usedFallbackProvider) Text(
                            tr(language, "تم استخدام المصدر الاحتياطي مؤقتًا", "Backup rate source is being used temporarily", "Source de secours utilisée temporairement"),
                            color = adaptInk(Color(0xFFB45309)),
                            fontSize = 11.sp,
                        )
                    }
                }
                IconButton(onClick = onRefresh, enabled = !isLoading) {
                    Icon(Icons.Default.Refresh, tr(language, "تحديث", "Refresh", "Actualiser"), tint = adaptInk(Color(0xFF2563EB)))
                }
            }
            Text(
                tr(language, "للاسترشاد فقط؛ قد يختلف سعر البنك أو شركة الصرافة.", "Reference rate only; bank or exchange-office rates may differ.", "Taux indicatif; le taux bancaire peut différer."),
                color = adaptInk(Color(0xFF64748B)), fontSize = 11.sp,
            )
            TextButton(onClick = { openWeb(context, "https://www.exchangerate.fun") }, contentPadding = PaddingValues(0.dp)) {
                Text("Hourly rates by ExchangeRate.fun", fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun CurrencyCodeRow(
    currencies: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
    language: com.abfilepro.app.ui.i18n.AppLanguage,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(currencies, key = { it }) { code ->
            FilterChip(
                selected = code == selected,
                onClick = { onSelected(code) },
                label = { Text("$code · ${currencyDisplayName(code, language)}", fontWeight = FontWeight.Bold, maxLines = 1) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = adaptSurface(Color(0xFFE9DDF8)),
                    selectedLabelColor = adaptInk(Color(0xFF3B236B)),
                ),
            )
        }
    }
}

// Immutable labels are initialized once rather than allocated per displayed chip.
private val currencyNamesEnglish = mapOf("EGP" to "Egyptian pound", "USD" to "US dollar", "EUR" to "Euro", "SAR" to "Saudi riyal", "AED" to "UAE dirham", "GBP" to "British pound", "KWD" to "Kuwaiti dinar", "QAR" to "Qatari riyal", "CNY" to "Chinese yuan", "JPY" to "Japanese yen", "CHF" to "Swiss franc", "CAD" to "Canadian dollar")
private val currencyNamesFrench = mapOf("EGP" to "Livre égyptienne", "USD" to "Dollar américain", "EUR" to "Euro", "SAR" to "Riyal saoudien", "AED" to "Dirham émirati", "GBP" to "Livre sterling", "KWD" to "Dinar koweïtien", "QAR" to "Riyal qatari", "CNY" to "Yuan chinois", "JPY" to "Yen japonais", "CHF" to "Franc suisse", "CAD" to "Dollar canadien")
private val currencyNamesArabic = mapOf("EGP" to L("جنيه مصري"), "USD" to L("دولار أمريكي"), "EUR" to L("يورو"), "SAR" to L("ريال سعودي"), "AED" to L("درهم إماراتي"), "GBP" to L("جنيه إسترليني"), "KWD" to L("دينار كويتي"), "QAR" to L("ريال قطري"), "CNY" to L("يوان صيني"), "JPY" to L("ين ياباني"), "CHF" to L("فرنك سويسري"), "CAD" to L("دولار كندي"))

private fun currencyDisplayName(code: String, language: com.abfilepro.app.ui.i18n.AppLanguage): String {
    val names = when (language.code) {
        "en" -> currencyNamesEnglish
        "fr" -> currencyNamesFrench
        else -> currencyNamesArabic
    }
    return names[code] ?: code
}

private fun sanitizeCurrencyInput(raw: String): String {
    val mapped = buildString {
        raw.forEach { ch ->
            append(when (ch) {
                '٠' -> '0'; '١' -> '1'; '٢' -> '2'; '٣' -> '3'; '٤' -> '4'
                '٥' -> '5'; '٦' -> '6'; '٧' -> '7'; '٨' -> '8'; '٩' -> '9'
                '٫', ',' -> '.'
                else -> ch
            })
        }
    }.filter { it.isDigit() || it == '.' }
    val firstDot = mapped.indexOf('.')
    val normalized = if (firstDot >= 0) mapped.substring(0, firstDot + 1) + mapped.substring(firstDot + 1).replace(".", "") else mapped
    return normalized.take(18)
}

private fun parseCurrencyAmount(text: String): Double? = sanitizeCurrencyInput(text).toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }

private fun formatCurrencyValue(value: Double): String = when {
    !value.isFinite() -> "—"
    kotlin.math.abs(value) >= 1_000_000 -> "%,.2f".format(Locale.US, value)
    kotlin.math.abs(value) >= 1 -> "%,.2f".format(Locale.US, value).trimEnd('0').trimEnd('.')
    else -> "%,.4f".format(Locale.US, value).trimEnd('0').trimEnd('.')
}

@Composable
private fun MarketsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    ModuleScaffold(AssistantModule.MARKETS, onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { InfoCard(tr(language, "أسعار متغيرة — نفتح المصدر مباشرة", "Changing prices — open the source directly", "Prix variables — ouvrir la source"), tr(language, "لا يعرض المساعد رقمًا قديمًا ولا يعتبر الأسعار نصيحة مالية.", "The assistant does not show stale numbers or treat prices as financial advice.", "L’assistant n’affiche pas de prix périmés.")) }
            item { ExternalActionCard(tr(language, "أسعار العملات في مصر", "Egypt exchange rates", "Taux de change en Égypte"), tr(language, "بحث مباشر في المصادر الرسمية", "Direct search in official sources", "Recherche dans les sources officielles")) { openWeb(context, "https://www.google.com/search?q=سعر+الدولار+اليوم+البنك+المركزي+المصري") } }
            item { ExternalActionCard(tr(language, "سعر الذهب اليوم", "Gold price today", "Cours de l’or"), tr(language, "بحث مباشر بحسب العيار", "Direct search by karat", "Recherche par carat")) { openWeb(context, "https://www.google.com/search?q=سعر+الذهب+اليوم+مصر") } }
            item { ExternalActionCard(tr(language, "حاسبة تحويل العملات", "Currency converter", "Convertisseur de devises"), tr(language, "افتح محول Google المباشر", "Open Google’s live converter", "Ouvrir le convertisseur Google")) { openWeb(context, "https://www.google.com/finance/markets/currencies") } }
        }
    }
}

@Composable
private fun BillPaymentScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val providers = listOf(
        Triple("فوري / myFawry", "com.fawry.myfawry", "myFawry"),
        Triple("سهل / Sahl", "com.sahl.app", "Sahl Egypt"),
        Triple("InstaPay", "com.egyptianbanks.instapay", "InstaPay Egypt"),
        Triple("Vodafone Cash", "com.emeint.android.myservices", "Ana Vodafone"),
    )
    ModuleScaffold(AssistantModule.BILLS, onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { InfoCard(tr(language, "المساعد لا يستلم بيانات الدفع", "The assistant never receives payment details", "L’assistant ne reçoit aucune donnée de paiement"), tr(language, "الزر يفتح التطبيق المثبت، وإن لم يكن موجودًا يفتح صفحة البحث في المتجر. التأكيد يتم داخل تطبيق الدفع نفسه.", "A button opens the installed app; otherwise it opens Play search. Confirmation happens inside the payment app.", "Le paiement est confirmé dans l’application de paiement.")) }
            items(providers) { (name, packageName, search) ->
                ExternalActionCard(name, tr(language, "فتح التطبيق بأمان", "Open app safely", "Ouvrir l’application")) { launchPackageOrStore(context, packageName, search) }
            }
        }
    }
}

@Composable
private fun PriceComparisonScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<StorePriceResult>>(emptyList()) }
    var searchedQuery by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var sortMode by remember { mutableStateOf(PriceSortMode.CHEAPEST) }
    var showAlertDialog by remember { mutableStateOf(false) }
    var alertTarget by remember { mutableStateOf("") }
    var alerts by remember { mutableStateOf(ProductPriceAlertStore.load(context)) }
    var showBarcodeDialog by remember { mutableStateOf(false) }
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
        if (granted) showBarcodeDialog = true
        else Toast.makeText(context, tr(language, "يلزم إذن الكاميرا لمسح الباركود", "Camera permission is required", "Autorisation caméra requise"), Toast.LENGTH_LONG).show()
    }
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
        if (spoken.isNotBlank()) query = spoken.take(180)
    }

    fun startSearch(value: String = query) {
        val q = value.trim()
        if (q.isBlank() || loading) return
        loading = true
        errorMessage = null
        scope.launch {
            val loaded = runCatching { ProductPriceComparisonService.search(q) }
            loaded.onSuccess { snapshot ->
                searchedQuery = snapshot.query
                results = snapshot.results
                if (snapshot.results.none { it.livePriceAvailable }) {
                    errorMessage = tr(
                        language,
                        "المتاجر لم تعرض سعرًا قابلًا للقراءة الآن؛ افتح المتجر من البطاقة لمراجعة السعر الرسمي.",
                        "The stores did not expose a readable price right now; open the store card to check the official price.",
                        "Les boutiques n’ont pas fourni de prix lisible; ouvrez la boutique pour vérifier le prix officiel.",
                    )
                }
            }.onFailure {
                searchedQuery = q
                results = emptyList()
                errorMessage = tr(language, "تعذر تحديث الأسعار الآن. تحقق من الإنترنت وحاول مرة أخرى.", "Could not refresh prices. Check your connection and try again.", "Impossible d’actualiser les prix.")
            }
            loading = false
        }
    }

    val sortedResults = remember(results, sortMode) {
        when (sortMode) {
            PriceSortMode.CHEAPEST -> results.sortedWith(
                compareBy<StorePriceResult> { it.priceEgp == null }.thenBy { it.priceEgp ?: Double.MAX_VALUE }
            )
            PriceSortMode.FASTEST -> results.sortedWith(
                compareBy<StorePriceResult> { it.deliveryDaysMax == null }.thenBy { it.deliveryDaysMax ?: Int.MAX_VALUE }.thenBy { it.priceEgp ?: Double.MAX_VALUE }
            )
        }
    }
    val cheapest = results.filter { it.priceEgp != null }.minByOrNull { it.priceEgp ?: Double.MAX_VALUE }
    val currentAlert = alerts.firstOrNull { it.query.equals(searchedQuery.ifBlank { query }.trim(), ignoreCase = true) }

    if (showBarcodeDialog) {
        Dialog(onDismissRequest = { showBarcodeDialog = false }) {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = adaptSurface(Color.White))) {
                Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(tr(language, "امسح باركود المنتج", "Scan product barcode", "Scanner le code-barres"), fontWeight = FontWeight.Black, fontSize = 20.sp)
                    Spacer(Modifier.height(10.dp))
                    if (hasCameraPermission) {
                        BarcodeCamera(
                            modifier = Modifier.fillMaxWidth().height(360.dp),
                            onValue = { value ->
                                if (value.isNotBlank()) {
                                    query = value.take(180)
                                    showBarcodeDialog = false
                                    startSearch(value)
                                }
                            },
                        )
                    } else {
                        Column(Modifier.fillMaxWidth().height(250.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.size(72.dp), tint = adaptInk(Color(0xFFEF3340)))
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { cameraPermission.launch(Manifest.permission.CAMERA) }) {
                                Text(tr(language, "السماح بالكاميرا", "Allow camera", "Autoriser la caméra"))
                            }
                        }
                    }
                    TextButton(onClick = { showBarcodeDialog = false }) { Text(tr(language, "إلغاء", "Cancel", "Annuler")) }
                }
            }
        }
    }

    if (showAlertDialog) {
        AlertDialog(
            onDismissRequest = { showAlertDialog = false },
            title = { Text(tr(language, "إنشاء تنبيه سعر", "Create price alert", "Créer une alerte de prix"), fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(searchedQuery.ifBlank { query }.ifBlank { tr(language, "اكتب اسم المنتج أولًا", "Enter a product first", "Saisissez d’abord un produit") })
                    OutlinedTextField(
                        value = alertTarget,
                        onValueChange = { alertTarget = sanitizeCurrencyInput(it) },
                        label = { Text(tr(language, "السعر المطلوب بالجنيه", "Target price in EGP", "Prix cible en EGP")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                    Text(
                        tr(language, "سيتم الفحص دوريًا في الخلفية عند توفر الإنترنت، ولن يصلك تنبيه إلا عند وجود سعر مقروء يحقق الهدف.", "The app checks periodically when online and alerts only when a readable price meets your target.", "L’application vérifie périodiquement en ligne."),
                        fontSize = 12.sp,
                        color = adaptInk(Color(0xFF64748B)),
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = (searchedQuery.ifBlank { query }).isNotBlank() && (parseCurrencyAmount(alertTarget) ?: 0.0) > 0.0,
                    onClick = {
                        val q = searchedQuery.ifBlank { query }.trim()
                        val target = parseCurrencyAmount(alertTarget) ?: return@Button
                        ProductPriceAlertStore.add(context, q, target)
                        alerts = ProductPriceAlertStore.load(context)
                        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        alertTarget = ""
                        showAlertDialog = false
                        Toast.makeText(context, tr(language, "تم إنشاء تنبيه السعر", "Price alert created", "Alerte créée"), Toast.LENGTH_SHORT).show()
                    },
                ) { Text(tr(language, "حفظ", "Save", "Enregistrer")) }
            },
            dismissButton = { TextButton(onClick = { showAlertDialog = false }) { Text(tr(language, "إلغاء", "Cancel", "Annuler")) } },
        )
    }

    Scaffold(
        containerColor = adaptSurface(Color(0xFFFDFDFE)),
        topBar = {
            Surface(
                color = adaptSurface(Color.White),
                shadowElevation = 2.dp,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(78.dp)
                        .padding(horizontal = 18.dp),
                ) {
                    FilledTonalIconButton(
                        onClick = onBack,
                        modifier = Modifier.align(Alignment.CenterStart),
                    ) {
                        Icon(Icons.Default.ArrowBack, tr(language, "رجوع", "Back", "Retour"))
                    }
                    Text(
                        tr(language, "مقارنة الأسعار", "Price comparison", "Comparaison des prix"),
                        modifier = Modifier.align(Alignment.Center),
                        fontWeight = FontWeight.Black,
                        fontSize = 30.sp,
                        color = adaptInk(Color(0xFF0F172A)),
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = adaptSurface(Color.White)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it.take(180) },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text(tr(language, "ابحث باسم المنتج", "Search by product name", "Rechercher un produit")) },
                            trailingIcon = {
                                IconButton(onClick = { startSearch() }, enabled = query.isNotBlank() && !loading) {
                                    if (loading) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                    else Icon(Icons.Default.Search, null, tint = adaptInk(Color(0xFF64748B)))
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(20.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = adaptSurface(Color(0xFFF9FAFF)),
                                unfocusedContainerColor = adaptSurface(Color(0xFFF9FAFF)),
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                            ),
                        )
                        Surface(
                            modifier = Modifier.size(58.dp).clickable {
                                if (hasCameraPermission) showBarcodeDialog = true else cameraPermission.launch(Manifest.permission.CAMERA)
                            },
                            shape = RoundedCornerShape(17.dp),
                            color = adaptSurface(Color(0xFFFFF1F3)),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.QrCodeScanner, null, tint = adaptInk(Color(0xFFEF3340)), modifier = Modifier.size(30.dp))
                            }
                        }
                        Surface(
                            modifier = Modifier.size(58.dp).clickable {
                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (language.code == "ar") "ar-EG" else language.code)
                                    putExtra(RecognizerIntent.EXTRA_PROMPT, tr(language, "قل اسم المنتج", "Say the product name", "Dites le nom du produit"))
                                }
                                runCatching { voiceLauncher.launch(intent) }.onFailure {
                                    Toast.makeText(context, tr(language, "البحث الصوتي غير متاح على هذا الجهاز", "Voice search is unavailable", "Recherche vocale indisponible"), Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(17.dp),
                            color = adaptSurface(Color(0xFFFFF1F3)),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Mic, null, tint = adaptInk(Color(0xFFEF3340)), modifier = Modifier.size(31.dp))
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.cardColors(containerColor = adaptSurface(Color(0xFFECFFF8))),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Text(
                                searchedQuery.ifBlank { tr(language, "ابحث عن أي منتج", "Search for any product", "Rechercher un produit") },
                                fontWeight = FontWeight.Black,
                                fontSize = 27.sp,
                                color = adaptInk(Color(0xFF0F172A)),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (searchedQuery.isNotBlank() && cheapest?.priceEgp != null)
                                    tr(language, "أفضل سعر حالي: ${formatCurrencyValue(cheapest.priceEgp)} ج.م", "Best current price: EGP ${formatCurrencyValue(cheapest.priceEgp)}", "Meilleur prix: ${formatCurrencyValue(cheapest.priceEgp)} EGP")
                                else tr(language, "Amazon مصر • noon • Jumia", "Amazon Egypt • noon • Jumia", "Amazon Égypte • noon • Jumia"),
                                color = adaptInk(Color(0xFF667085)),
                                fontSize = 14.sp,
                            )
                            FilledTonalButton(
                                onClick = {
                                    query = ""
                                    searchedQuery = ""
                                    results = emptyList()
                                    errorMessage = null
                                },
                                enabled = searchedQuery.isNotBlank(),
                                shape = RoundedCornerShape(22.dp),
                            ) {
                                Text(tr(language, "تغيير المنتج", "Change product", "Changer le produit"), fontWeight = FontWeight.Bold)
                            }
                        }
                        Surface(
                            modifier = Modifier.size(158.dp),
                            shape = CircleShape,
                            color = adaptSurface(Color(0xFFDDFBF0)),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("📱", fontSize = 88.sp)
                            }
                        }
                    }
                }
            }

            if (searchedQuery.isNotBlank()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            tr(language, "ترتيب النتائج:", "Sort results:", "Trier:"),
                            fontWeight = FontWeight.Black,
                            fontSize = 18.sp,
                            color = adaptInk(Color(0xFF111827)),
                        )
                        Surface(
                            modifier = Modifier.weight(1f).height(54.dp).clickable { sortMode = PriceSortMode.CHEAPEST },
                            shape = RoundedCornerShape(24.dp),
                            color = if (sortMode == PriceSortMode.CHEAPEST) adaptInk(Color(0xFFFA3443)) else Color.White,
                            shadowElevation = 2.dp,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    tr(language, "الأقل سعرًا", "Lowest price", "Prix le plus bas"),
                                    fontWeight = FontWeight.Bold,
                                    color = if (sortMode == PriceSortMode.CHEAPEST) Color.White else adaptInk(Color(0xFF111827)),
                                )
                            }
                        }
                        Surface(
                            modifier = Modifier.weight(1f).height(54.dp).clickable { sortMode = PriceSortMode.FASTEST },
                            shape = RoundedCornerShape(24.dp),
                            color = if (sortMode == PriceSortMode.FASTEST) adaptInk(Color(0xFFFA3443)) else Color.White,
                            shadowElevation = 2.dp,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    tr(language, "الأسرع توصيلًا", "Fastest delivery", "Livraison rapide"),
                                    fontWeight = FontWeight.Bold,
                                    color = if (sortMode == PriceSortMode.FASTEST) Color.White else adaptInk(Color(0xFF111827)),
                                )
                            }
                        }
                    }
                }

                items(sortedResults) { result ->
                    ClassicPriceStoreResultCard(result = result, language = language) {
                        openWeb(context, result.productUrl)
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = adaptSurface(Color(0xFFF5F7FB))),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("ⓘ", color = adaptInk(Color(0xFF2584FF)), fontWeight = FontWeight.Black, fontSize = 31.sp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(tr(language, "أسعار مباشرة", "Live prices", "Prix en direct"), fontWeight = FontWeight.Black, fontSize = 17.sp)
                            Text(
                                errorMessage ?: tr(
                                    language,
                                    "الأسعار والتوفر قد يتغيران؛ يفضل مراجعة المتجر الرسمي قبل الشراء.",
                                    "Prices and availability can change; check the official store before buying.",
                                    "Les prix et la disponibilité peuvent changer; vérifiez la boutique officielle.",
                                ),
                                color = adaptInk(Color(0xFF667085)),
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        if ((searchedQuery.ifBlank { query }).isBlank()) {
                            Toast.makeText(context, tr(language, "ابحث عن المنتج أولًا", "Search for a product first", "Recherchez d’abord un produit"), Toast.LENGTH_SHORT).show()
                        } else showAlertDialog = true
                    },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFA233A)),
                ) {
                    Icon(Icons.Default.Notifications, null, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(tr(language, "إنشاء تنبيه سعر", "Create price alert", "Créer une alerte"), fontWeight = FontWeight.Black, fontSize = 19.sp)
                }
            }

            if (currentAlert != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = adaptSurface(Color(0xFFFFF3F4))),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                tr(language, "تنبيه نشط عند ${formatCurrencyValue(currentAlert.targetEgp)} ج.م", "Active alert at EGP ${formatCurrencyValue(currentAlert.targetEgp)}", "Alerte active à ${formatCurrencyValue(currentAlert.targetEgp)} EGP"),
                                modifier = Modifier.weight(1f),
                                fontSize = 13.sp,
                            )
                            TextButton(onClick = {
                                ProductPriceAlertStore.delete(context, currentAlert.id)
                                alerts = ProductPriceAlertStore.load(context)
                            }) { Text(tr(language, "حذف", "Delete", "Supprimer")) }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(18.dp)) }
        }
    }
}

private enum class PriceSortMode { CHEAPEST, FASTEST }

@Composable
private fun ClassicPriceStoreResultCard(
    result: StorePriceResult,
    language: com.abfilepro.app.ui.i18n.AppLanguage,
    onOpen: () -> Unit,
) {
    val bg = when (result.store) {
        com.abfilepro.app.assistant.RetailStore.AMAZON -> adaptSurface(Color(0xFFFFF8E9))
        com.abfilepro.app.assistant.RetailStore.NOON -> adaptSurface(Color(0xFFEDFFF8))
        com.abfilepro.app.assistant.RetailStore.JUMIA -> adaptSurface(Color(0xFFEEF8FF))
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = adaptSurface(bg)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(.8f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(result.store.arabicName, fontWeight = FontWeight.Black, fontSize = 23.sp, color = adaptInk(Color(0xFF111827)))
                Text("›", fontWeight = FontWeight.Black, fontSize = 28.sp, color = adaptInk(Color(0xFF111827)))
            }
            Column(modifier = Modifier.weight(1.15f), horizontalAlignment = Alignment.CenterHorizontally) {
                if (result.priceEgp != null) {
                    Text(L("${formatCurrencyValue(result.priceEgp)} ج.م"), color = adaptInk(Color(0xFFF52D3C)), fontWeight = FontWeight.Black, fontSize = 24.sp)
                } else {
                    Text(tr(language, "السعر غير متاح", "Price unavailable", "Prix indisponible"), color = adaptInk(Color(0xFF667085)), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Text(
                    result.deliveryText ?: tr(language, "راجع التوصيل في المتجر", "Check delivery in store", "Vérifiez la livraison"),
                    fontSize = 12.sp,
                    color = adaptInk(Color(0xFF344054)),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Text(tr(language, "السعر والتوفر من المتجر", "Price & availability from store", "Prix et disponibilité en boutique"), fontSize = 10.sp, color = adaptInk(Color(0xFF667085)))
            }
            Button(
                onClick = onOpen,
                modifier = Modifier.weight(.9f).height(52.dp),
                shape = RoundedCornerShape(17.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFA3443)),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text(tr(language, "فتح المتجر", "Open store", "Ouvrir"), fontWeight = FontWeight.Black, fontSize = 14.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ExternalActionCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = adaptSurface(Color.White)), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Bold); Text(subtitle, color = adaptInk(Color(0xFF64748B)), fontSize = 12.sp) }
            Icon(Icons.Default.OpenInNew, null, tint = adaptInk(Color(0xFF3F7AE0)))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrayerModuleScreen(
    settings: PrayerSettings,
    onBack: () -> Unit,
    onSettings: (PrayerSettings) -> Unit,
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val deepGreen = Color(0xFF075E54)
    val ink = adaptInk(Color(0xFF14213B))
    val muted = adaptInk(Color(0xFF687585))
    val softGreen = adaptSurface(Color(0xFFE4F7F2))
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val applyGpsLocation: (Location) -> Unit = { location ->
        resolvePrayerCityName(context, location) { resolvedCity ->
            onSettings(settings.copy(
                cityId = "device",
                latitude = location.latitude,
                longitude = location.longitude,
                zoneId = ZoneId.systemDefault().id,
                cityName = resolvedCity.ifBlank { "موقعي الحالي" },
                useDeviceLocation = true,
                autoGps = true,
            ))
            Toast.makeText(context, L("تم ربط موقعك: ${resolvedCity.ifBlank { "موقعي الحالي" }}"), Toast.LENGTH_SHORT).show()
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) {
            refreshPrayerGpsLocation(context, applyGpsLocation) {
                Toast.makeText(context, L("فعّل خدمة الموقع ثم اضغط «استخدم موقعي» مرة أخرى"), Toast.LENGTH_LONG).show()
                runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            }
        }
    }
    val useMyLocation: () -> Unit = {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) refreshPrayerGpsLocation(context, applyGpsLocation) {
            Toast.makeText(context, L("فعّل خدمة الموقع ثم أعد المحاولة"), Toast.LENGTH_LONG).show()
            runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        } else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    // Keep existing GPS refresh and permission behavior: this is a visual-only patch.
    LaunchedEffect(settings.autoGps) {
        if (settings.autoGps) {
            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (fine || coarse) refreshPrayerGpsLocation(context, applyGpsLocation) { }
            else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    val zone = remember(settings.zoneId) { runCatching { ZoneId.of(settings.zoneId) }.getOrDefault(ZoneId.systemDefault()) }
    var clock by remember(zone) { mutableStateOf(ZonedDateTime.now(zone)) }
    LaunchedEffect(zone) {
        while (true) {
            clock = ZonedDateTime.now(zone)
            kotlinx.coroutines.delay(30_000)
        }
    }
    val date = clock.toLocalDate()
    val offset = zone.rules.getOffset(date.atStartOfDay(zone).toInstant()).totalSeconds / 3600.0
    val times = remember(date, settings) {
        PrayerTimesCalculator.calculate(date, settings.latitude, settings.longitude, offset, settings.method, settings.asrSchool, settings.adjustmentsMinutes)
    }
    val nextPrayer = PrayerName.entries.filter { it != PrayerName.SUNRISE }
        .firstOrNull { prayer -> times[prayer]?.isAfter(clock.toLocalTime()) == true } ?: PrayerName.FAJR
    val tomorrow = times[nextPrayer]?.isAfter(clock.toLocalTime()) != true
    val nextDate = if (tomorrow) date.plusDays(1) else date
    val nextTime = if (tomorrow) {
        val tomorrowOffset = zone.rules.getOffset(nextDate.atStartOfDay(zone).toInstant()).totalSeconds / 3600.0
        remember(nextDate, settings) {
            PrayerTimesCalculator.calculate(nextDate, settings.latitude, settings.longitude, tomorrowOffset, settings.method, settings.asrSchool, settings.adjustmentsMinutes)[PrayerName.FAJR]
        }
    } else times[nextPrayer]
    val timeLeft = java.time.Duration.between(clock, ZonedDateTime.of(nextDate, nextTime ?: java.time.LocalTime.MIDNIGHT, zone))
        .seconds.coerceAtLeast(0)
    val timeLeftText = "%02d:%02d:%02d".format(Locale.US, timeLeft / 3600, (timeLeft % 3600) / 60, timeLeft % 60)
    val city = if (settings.cityId == "device") settings.cityName.ifBlank { tr(language, "موقعي الحالي", "Current location", "Position actuelle") }
        else cityLabel(settings.cityId, language)
    val shape = RoundedCornerShape(22.dp)

    Scaffold(
        containerColor = adaptSurface(Color(0xFFF6F8FE)),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(tr(language, "مواقيت الصلاة والتنبيهات", "Prayer times & alerts", "Prières et rappels"),
                            color = ink, fontWeight = FontWeight.Black, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(tr(language, "الصلاة القادمة، المواقيت اليومية، وإعدادات التنبيه", "Next prayer, daily times and alerts", "Prochaine prière, horaires et rappels"),
                            fontSize = 10.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onBack, modifier = Modifier.padding(horizontal = 5.dp)) {
                        Icon(Icons.Default.ArrowBack, tr(language, "رجوع", "Back", "Retour"))
                    }
                },
                actions = {
                    Surface(color = softGreen, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(end = 12.dp)) {
                        Icon(AssistantModule.PRAYER.presentation(language).icon, null, tint = deepGreen,
                            modifier = Modifier.padding(10.dp).size(24.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = adaptSurface(Color(0xFFFBFEFF))),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                // Distinctive hero card, based on the supplied prayer-page reference.
                Box(Modifier.fillMaxWidth().height(185.dp).background(deepGreen, shape)) {
                    Image(
                        painter = painterResource(com.abfilepro.app.R.drawable.prayer_mosque_banner),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.align(Alignment.CenterStart).fillMaxHeight().width(220.dp),
                    )
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Transparent, deepGreen.copy(alpha = .28f), deepGreen.copy(alpha = .75f)))))
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(horizontalAlignment = Alignment.End) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.Notifications, null, tint = Color(0xFFC9EDE6), modifier = Modifier.size(17.dp))
                                Text(tr(language, "الصلاة القادمة", "Next prayer", "Prochaine prière"),
                                    color = Color(0xFFD0E7E0), fontSize = 12.sp)
                            }
                            Text(prayerLabel(nextPrayer, language), color = Color.White,
                                fontSize = 30.sp, fontWeight = FontWeight.Black)
                            Text("$city • $nextDate", color = Color(0xFFE0EFEB), fontSize = 12.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(tr(language, "متبقي: ", "Remaining: ", "Temps restant : ") + timeLeftText,
                                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Button(
                            onClick = useMyLocation,
                            shape = RoundedCornerShape(30.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = adaptSurface(Color.White), contentColor = deepGreen),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 1.dp),
                            modifier = Modifier.height(41.dp),
                        ) {
                            Icon(Icons.Default.LocationOn, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(tr(language, "استخدم موقعي", "Use my location", "Utiliser ma position"), fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = adaptSurface(Color.White)), shape = shape) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrayerUiSectionHeading(tr(language, "اختر المدينة", "Choose city", "Choisir une ville"), Icons.Default.LocationOn, deepGreen)
                        CityPreset.ALL.chunked(3).forEach { rowCities ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                rowCities.forEach { preset ->
                                    val selected = settings.cityId == preset.id
                                    Surface(
                                        onClick = { onSettings(settings.copy(
                                            cityId = preset.id, latitude = preset.latitude, longitude = preset.longitude,
                                            zoneId = preset.zoneId, cityName = cityLabel(preset.id, language),
                                            useDeviceLocation = false, autoGps = false,
                                        )) },
                                        modifier = Modifier.weight(1f).height(46.dp),
                                        shape = RoundedCornerShape(13.dp),
                                        color = if (selected) softGreen else adaptSurface(Color(0xFFF9FBFE)),
                                        border = BorderStroke(1.dp, if (selected) deepGreen else adaptSurface(Color(0xFFD5DCE3))),
                                    ) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text(cityLabel(preset.id, language), fontSize = 13.sp, fontWeight = if (selected) FontWeight.Black else FontWeight.Medium,
                                                color = if (selected) deepGreen else ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(horizontal = 2.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = adaptSurface(Color.White)), shape = shape) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocationOn, null, tint = deepGreen, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(tr(language, "GPS تلقائي حسب مكانك", "Automatic location (GPS)", "GPS automatique"),
                                modifier = Modifier.weight(1f), color = ink, fontWeight = FontWeight.Black)
                            Switch(checked = settings.autoGps, onCheckedChange = { enabled ->
                                onSettings(settings.copy(autoGps = enabled, useDeviceLocation = if (enabled) true else settings.useDeviceLocation))
                            }, colors = SwitchDefaults.colors(checkedTrackColor = deepGreen))
                        }
                        Text(tr(language, "عند فتح مواقيت الصلاة يتم تحديث الموقع تلقائيًا وحساب المواقيت للمكان الحالي.",
                            "When opening prayer times, your location and daily times update automatically.",
                            "Les horaires se mettent à jour selon votre position à l’ouverture."),
                            color = muted, fontSize = 12.sp, lineHeight = 19.sp)
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = adaptSurface(Color.White)), shape = shape) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Notifications, null, tint = deepGreen, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(tr(language, "صوت تنبيه الصلاة", "Prayer alert sound", "Son du rappel"),
                                modifier = Modifier.weight(1f), color = ink, fontWeight = FontWeight.Black)
                            listOf(10, 20, 28, 16, 24, 12, 19).forEach { tall ->
                                Box(Modifier.padding(horizontal = 1.dp).width(3.dp).height(tall.dp)
                                    .background(adaptSurface(Color(0xFFB8DBDE)), RoundedCornerShape(3.dp)))
                            }
                        }
                        Text(tr(language,
                            "يستخدم التطبيق نغمة المنبّه الحالية في الهاتف. تم إخفاء أسماء القرّاء لأنها لم تكن ملفات صوت فعلية داخل التطبيق.",
                            "The app uses your phone's alarm tone. Reciter choices are hidden until real audio is available.",
                            "L’application utilise la sonnerie d’alarme du téléphone."),
                            color = muted, fontSize = 12.sp, lineHeight = 19.sp)
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = adaptSurface(Color.White)), shape = shape) {
                    Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrayerUiSectionHeading(tr(language, "طريقة الحساب", "Calculation method", "Méthode de calcul"), Icons.Default.CheckCircle, deepGreen)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            listOf(PrayerMethod.EGYPTIAN, PrayerMethod.MUSLIM_WORLD_LEAGUE).forEach { method ->
                                val selected = settings.method == method
                                Surface(onClick = { onSettings(settings.copy(method = method)) },
                                    modifier = Modifier.weight(1f).height(43.dp), shape = RoundedCornerShape(22.dp),
                                    color = if (selected) deepGreen else adaptSurface(Color(0xFFF9FBFE)),
                                    border = BorderStroke(1.dp, if (selected) deepGreen else adaptSurface(Color(0xFFD4DDE4)))) {
                                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                        if (selected) {
                                            Icon(Icons.Default.CheckCircle, null, tint = Color.White, modifier = Modifier.size(17.dp))
                                            Spacer(Modifier.width(4.dp))
                                        }
                                        Text(if (method == PrayerMethod.EGYPTIAN) tr(language, "المصرية", "Egyptian", "Égyptienne")
                                            else tr(language, "رابطة العالم", "World League", "Ligue mondiale"),
                                            color = if (selected) Color.White else ink, fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp, maxLines = 1)
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            listOf(AsrSchool.STANDARD, AsrSchool.HANAFI).forEach { school ->
                                FilterChip(selected = settings.asrSchool == school, onClick = { onSettings(settings.copy(asrSchool = school)) },
                                    label = { Text(if (school == AsrSchool.STANDARD) tr(language, "عصر عادي", "Standard Asr", "Asr standard")
                                        else tr(language, "حنفي", "Hanafi", "Hanafite")) })
                            }
                        }
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 1.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(tr(language, "مواقيت الصلاة اليوم", "Today's prayer times", "Horaires du jour"),
                        color = ink, fontWeight = FontWeight.Black, fontSize = 19.sp)
                    Text("$city  •  $date", color = muted, fontSize = 12.sp)
                }
            }
            items(PrayerName.entries, key = { it.storageId }) { prayer ->
                val enabled = prayer in settings.enabledAlerts
                val isNext = prayer == nextPrayer && !tomorrow
                val adjustment = settings.adjustmentsMinutes[prayer] ?: 0
                Card(colors = CardDefaults.cardColors(containerColor = adaptSurface(if (isNext) softGreen else Color.White)),
                    shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(prayerLabel(prayer, language), color = ink, fontWeight = FontWeight.Black, fontSize = 16.sp)
                                if (isNext) Text(tr(language, "الصلاة القادمة", "Next prayer", "Prochaine prière"), color = deepGreen, fontSize = 11.sp)
                                else if (prayer == PrayerName.SUNRISE) Text(tr(language, "موعد الشروق", "Sunrise", "Lever du soleil"), color = muted, fontSize = 11.sp)
                            }
                            Text(times[prayer]?.toString() ?: "--:--", color = deepGreen,
                                fontWeight = FontWeight.Black, fontSize = 23.sp)
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = enabled,
                                enabled = prayer != PrayerName.SUNRISE,
                                colors = SwitchDefaults.colors(checkedTrackColor = deepGreen),
                                onCheckedChange = { checked ->
                                    if (checked && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                    val alerts = if (checked) settings.enabledAlerts + prayer else settings.enabledAlerts - prayer
                                    val manager = context.getSystemService(AlarmManager::class.java)
                                    val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager?.canScheduleExactAlarms() == true
                                    onSettings(settings.copy(enabledAlerts = alerts, exactAlarms = settings.exactAlarms || canExact))
                                },
                            )
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(tr(language, "ضبط الدقائق", "Adjust minutes", "Ajustement"),
                                color = muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = {
                                onSettings(settings.copy(adjustmentsMinutes = settings.adjustmentsMinutes + (prayer to (adjustment - 1).coerceAtLeast(-30))))
                            }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Default.Remove, tr(language, "ناقص دقيقة", "Minus minute", "Moins une minute"),
                                    tint = deepGreen, modifier = Modifier.size(19.dp))
                            }
                            Text("${if (adjustment > 0) "+" else ""}$adjustment",
                                color = ink, fontWeight = FontWeight.Bold, fontSize = 12.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.width(31.dp))
                            IconButton(onClick = {
                                onSettings(settings.copy(adjustmentsMinutes = settings.adjustmentsMinutes + (prayer to (adjustment + 1).coerceAtMost(30))))
                            }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Default.Add, tr(language, "زائد دقيقة", "Plus minute", "Plus une minute"),
                                    tint = deepGreen, modifier = Modifier.size(19.dp))
                            }
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = adaptSurface(Color.White)), shape = shape) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        PrayerUiSectionHeading(tr(language, "إعدادات تنبيهات الصلاة", "Prayer alert settings", "Réglages des rappels"), Icons.Default.Notifications, deepGreen)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr(language, "تنبيه مبكر", "Early reminder", "Rappel anticipé"), modifier = Modifier.weight(1f), color = ink, fontWeight = FontWeight.Bold)
                            Text("${settings.preAlertMinutes} ${tr(language, "دقيقة", "min", "min")}", color = muted)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            listOf(0, 5, 10, 15).forEach { minutes ->
                                FilterChip(selected = settings.preAlertMinutes == minutes,
                                    onClick = { onSettings(settings.copy(preAlertMinutes = minutes)) }, label = { Text(minutes.toString()) })
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr(language, "تنبيه دقيق (اختياري)", "Exact alarm (optional)", "Alarme exacte (facultative)"),
                                modifier = Modifier.weight(1f), color = ink)
                            Switch(checked = settings.exactAlarms, colors = SwitchDefaults.colors(checkedTrackColor = deepGreen),
                                onCheckedChange = { checked ->
                                    if (checked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        val manager = context.getSystemService(AlarmManager::class.java)
                                        if (manager?.canScheduleExactAlarms() != true) {
                                            runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }
                                            return@Switch
                                        }
                                    }
                                    onSettings(settings.copy(exactAlarms = checked))
                                })
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = adaptSurface(Color.White)), shape = shape) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrayerUiSectionHeading(tr(language, "متابعة الصلاة بعد الموعد", "Prayer follow-up", "Suivi de la prière"), Icons.Default.CheckCircle, deepGreen)
                        Text(tr(language, "بعد الصلاة يسألك التطبيق: هل صليت؟ نعم / لا. لو لم ترد أو اخترت لا يعيد التذكير.",
                            "After prayer, the app asks if you prayed. It repeats the reminder if you answer no or don't respond.",
                            "Après la prière, l’application demande une confirmation et peut rappeler."), color = muted, fontSize = 12.sp, lineHeight = 19.sp)
                        Text(tr(language, "السؤال بعد", "Ask after", "Question après"), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            listOf(10, 20, 30).forEach { minutes ->
                                FilterChip(selected = settings.prayerCheckMinutes == minutes, onClick = { onSettings(settings.copy(prayerCheckMinutes = minutes)) },
                                    label = { Text(tr(language, "بعد $minutes د", "$minutes min", "$minutes min")) })
                            }
                        }
                        Text(tr(language, "إعادة التذكير", "Repeat reminder", "Répéter le rappel"), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            listOf(5, 10, 15).forEach { minutes ->
                                FilterChip(selected = settings.repeatNudgeMinutes == minutes, onClick = { onSettings(settings.copy(repeatNudgeMinutes = minutes)) },
                                    label = { Text(tr(language, "تكرار $minutes د", "$minutes min", "$minutes min")) })
                            }
                        }
                    }
                }
            }
            item {
                Text(tr(language, "المواقيت محسوبة فلكيًا دون إنترنت. راجع جهة الأوقاف المحلية عند اختلاف طريقة الحساب.",
                    "Times are calculated offline. Check your local authority when calculation conventions differ.",
                    "Horaires calculés hors ligne. Vérifiez l’autorité locale si nécessaire."),
                    color = muted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(9.dp))
            }
        }
    }
}

@Composable
private fun PrayerUiSectionHeading(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, color = adaptInk(Color(0xFF14213B)), fontSize = 17.sp, fontWeight = FontWeight.Black)
    }
}

private fun pickDateTime(context: Context, initialMillis: Long, onSelected: (Long) -> Unit) {
    val initial = Instant.ofEpochMilli(initialMillis).atZone(ZoneId.systemDefault())
    DatePickerDialog(context, { _, year, month, day ->
        TimePickerDialog(context, { _, hour, minute ->
            onSelected(ZonedDateTime.of(year, month + 1, day, hour, minute, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli())
        }, initial.hour, initial.minute, false).show()
    }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
}

internal fun formatDateTime(millis: Long, languageCode: String): String {
    val locale = when (languageCode) { "ar" -> Locale("ar"); "fr" -> Locale.FRENCH; else -> Locale.ENGLISH }
    return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy • h:mm a", locale))
}

private fun formatAmount(value: Double): String = if (value % 1.0 == 0.0) "%,.0f".format(Locale.US, value) else "%,.2f".format(Locale.US, value)

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(intent, null))
}

private fun insertCalendarEvent(context: Context, entry: AssistantEntry) {
    val begin = entry.dueAt ?: System.currentTimeMillis()
    val intent = Intent(Intent.ACTION_INSERT).setData(android.provider.CalendarContract.Events.CONTENT_URI)
        .putExtra(android.provider.CalendarContract.Events.TITLE, entry.title)
        .putExtra(android.provider.CalendarContract.Events.DESCRIPTION, entry.details)
        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, begin + 3_600_000)
    runCatching { context.startActivity(intent) }
}

private fun openUri(context: Context, value: String, mime: String) {
    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        .onFailure { Toast.makeText(context, L("تعذر فتح الملف"), Toast.LENGTH_SHORT).show() }
}

private fun openWeb(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { Toast.makeText(context, L("تعذر فتح الرابط"), Toast.LENGTH_SHORT).show() }
}

private fun launchPackageOrStore(context: Context, packageName: String, search: String) {
    val launch = context.packageManager.getLaunchIntentForPackage(packageName)
    if (launch != null) {
        context.startActivity(launch)
        return
    }
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=${Uri.encode(search)}"))
    if (market.resolveActivity(context.packageManager) != null) context.startActivity(market)
    else openWeb(context, "https://play.google.com/store/search?q=${Uri.encode(search)}&c=apps")
}

private fun resolvePrayerCityName(context: Context, location: Location, result: (String) -> Unit) {
    val geocoder = Geocoder(context, Locale("ar"))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        runCatching {
            geocoder.getFromLocation(location.latitude, location.longitude, 1) { addresses ->
                val a = addresses.firstOrNull()
                val name = a?.locality ?: a?.subAdminArea ?: a?.adminArea ?: a?.featureName ?: "موقعي الحالي"
                result(name)
            }
        }.onFailure { result("موقعي الحالي") }
    } else {
        Thread {
            val name = runCatching {
                @Suppress("DEPRECATION")
                val a = geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
                a?.locality ?: a?.subAdminArea ?: a?.adminArea ?: a?.featureName ?: "موقعي الحالي"
            }.getOrDefault("موقعي الحالي")
            Handler(Looper.getMainLooper()).post { result(name) }
        }.start()
    }
}

@Suppress("MissingPermission")
private fun bestLastKnownLocation(context: Context): Location? {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!fine && !coarse) return null
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    return manager.getProviders(true).mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }.maxByOrNull { it.time }
}

@Suppress("MissingPermission")
private fun refreshPrayerGpsLocation(context: Context, onLocation: (Location) -> Unit, onFailure: () -> Unit) {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!fine && !coarse) { onFailure(); return }

    val manager = context.getSystemService(LocationManager::class.java) ?: run { onFailure(); return }
    val providers = buildList {
        if (fine && runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)) add(LocationManager.GPS_PROVIDER)
        if (runCatching { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)) add(LocationManager.NETWORK_PROVIDER)
    }.distinct()

    if (providers.isEmpty()) {
        bestLastKnownLocation(context)?.let(onLocation) ?: onFailure()
        return
    }

    var delivered = false
    val handler = Handler(Looper.getMainLooper())
    lateinit var listener: LocationListener
    fun finish(location: Location?) {
        if (delivered) return
        delivered = true
        runCatching { manager.removeUpdates(listener) }
        handler.removeCallbacksAndMessages(null)
        if (location != null) onLocation(location) else bestLastKnownLocation(context)?.let(onLocation) ?: onFailure()
    }

    listener = LocationListener { location ->
        if (location.latitude.isFinite() && location.longitude.isFinite() && location.accuracy <= 5000f) {
            finish(location)
        }
    }

    var requested = false
    providers.forEach { provider ->
        runCatching {
            manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            requested = true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                manager.getCurrentLocation(provider, CancellationSignal(), context.mainExecutor) { location ->
                    if (location != null) finish(location)
                }
            }
        }
    }

    if (!requested) {
        finish(bestLastKnownLocation(context))
        return
    }

    handler.postDelayed({ finish(bestLastKnownLocation(context)) }, 12_000L)
}
