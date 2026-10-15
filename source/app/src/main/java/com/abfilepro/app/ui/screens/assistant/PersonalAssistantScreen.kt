package com.abfilepro.app.ui.screens.assistant

import android.widget.Toast

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.abfilepro.app.assistant.AssistantCategory
import com.abfilepro.app.assistant.AssistantDomain
import com.abfilepro.app.assistant.AssistantEntry
import com.abfilepro.app.assistant.AssistantModule
import com.abfilepro.app.assistant.AssistantRepository
import com.abfilepro.app.assistant.FamilyCloudSyncManager
import com.abfilepro.app.assistant.PrayerName
import com.abfilepro.app.assistant.PrayerSettings
import com.abfilepro.app.assistant.PrayerTimesCalculator
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalAssistantScreen(
    onBack: () -> Unit,
    onOpenScanner: () -> Unit,
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val repository = remember(context) { AssistantRepository(context) }
    val familyCloudSync = remember(context, repository) { FamilyCloudSyncManager(context, repository) }
    var entries by remember { mutableStateOf(repository.loadEntries()) }
    // P20: any change made by an alarm, the recorder service or sync refreshes this screen at once.
    val storeRevision by repository.revision.collectAsState()
    LaunchedEffect(storeRevision) { entries = repository.loadEntries() }
    var prayerSettings by remember { mutableStateOf(repository.loadPrayerSettings()) }
    var selectedModule by remember { mutableStateOf<AssistantModule?>(null) }
    var selectedCategory by remember { mutableStateOf(AssistantCategory.ALL) }
    var showQuickAdd by remember { mutableStateOf(false) }
    var quickTitle by remember { mutableStateOf("") }
    var quickModule by remember { mutableStateOf(AssistantModule.TASKS) }
    // Alpha89: keep the home position when a module is opened and the user returns.
    // Both states are deliberately remembered before the selectedModule branch.
    val arabicHomeScrollState = rememberScrollState()
    val nativeGridState = rememberLazyGridState()

    // P17: a pre-P17 automatic family link stays paused until the user decides once.
    var legacyFamilyPrompt by remember {
        mutableStateOf(com.abfilepro.app.assistant.FamilyLinkStore.load(context).syncState ==
            com.abfilepro.app.assistant.FamilySyncState.LEGACY_PAUSED)
    }
    var legacyBusy by remember { mutableStateOf(false) }
    val legacyScope = androidx.compose.runtime.rememberCoroutineScope()
    if (legacyFamilyPrompt) {
        AlertDialog(
            onDismissRequest = { if (!legacyBusy) legacyFamilyPrompt = false },
            title = { Text(tr(language, "مزامنة العائلة", "Family sync", "Synchro famille"), fontWeight = FontWeight.Black) },
            text = {
                Text(tr(language,
                    "النسخ القديمة كانت بتشغّل مزامنة العائلة لوحدها. وقفناها لحد ما تختار. لو بتشارك طلبات العائلة أو التسوق مع حد، اختار «تكمّل». مشاركة الأدوية بقت مقفولة إلا لو فعّلتها من إعداد العائلة.",
                    "Older versions turned family sync on by themselves. It is paused until you choose. If you share family requests or shopping with someone, choose Keep. Sharing medicines is now off unless you turn it on in Family settings.",
                    "Les anciennes versions activaient la synchro famille seules. Elle est en pause jusqu'à votre choix."))
            },
            confirmButton = {
                Button(enabled = !legacyBusy, onClick = {
                    legacyBusy = true
                    legacyScope.launch {
                        withContext(Dispatchers.IO) { familyCloudSync.confirmLegacyFamily(shareHealth = false) }
                        legacyBusy = false; legacyFamilyPrompt = false
                    }
                }) { Text(tr(language, "تكمّل", "Keep", "Continuer")) }
            },
            dismissButton = {
                TextButton(enabled = !legacyBusy, onClick = {
                    legacyBusy = true
                    legacyScope.launch {
                        val ok = withContext(Dispatchers.IO) { runCatching { familyCloudSync.leaveFamily(deleteMyData = true) }.getOrDefault(false) }
                        legacyBusy = false
                        if (ok) legacyFamilyPrompt = false
                        else Toast.makeText(context, tr(language, "تعذر الاتصال. حاول تاني من إعداد العائلة.", "Could not connect. Try again from Family settings.", "Connexion impossible."), Toast.LENGTH_LONG).show()
                    }
                }) { Text(tr(language, "مش بستخدمها - امسح بياناتي", "Not used - delete my data", "Non utilisée - supprimer")) }
            },
        )
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            val cloudEntries = withContext(Dispatchers.IO) { familyCloudSync.sync() }
            if (cloudEntries != null) {
                entries = cloudEntries
                com.abfilepro.app.assistant.HealthAlarmScheduler.rescheduleAll(context, cloudEntries)
            }
            delay(15_000)
        }
    }

    if (showQuickAdd) {
        val quickCategory = selectedCategory.takeUnless { it == AssistantCategory.ALL } ?: AssistantCategory.PERSONAL
        AlertDialog(
            onDismissRequest = { showQuickAdd = false; quickTitle = "" },
            title = { Text(tr(language, "إضافة سريعة", "Quick add", "Ajout rapide"), fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(AssistantModule.TASKS, AssistantModule.NOTES).forEach { module ->
                            val active = quickModule == module
                            AssistChip(
                                onClick = { quickModule = module },
                                label = { Text(module.presentation(language).title, maxLines = 1) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = if (active) Color(0xFF173B65) else Color(0xFFF1F5F9),
                                    labelColor = if (active) Color.White else Color(0xFF334155),
                                ),
                                border = null,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    OutlinedTextField(
                        value = quickTitle,
                        onValueChange = { quickTitle = it.take(240) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(if (quickModule == AssistantModule.TASKS) tr(language, "اسم المهمة", "Task name", "Nom de la tâche") else tr(language, "عنوان الملاحظة", "Note title", "Titre de la note")) },
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = quickTitle.isNotBlank(),
                    onClick = {
                        entries = repository.add(entries, quickModule, quickTitle, category = quickCategory)
                        quickTitle = ""
                        showQuickAdd = false
                    },
                ) { Text(tr(language, "حفظ", "Save", "Enregistrer")) }
            },
            dismissButton = { TextButton(onClick = { showQuickAdd = false; quickTitle = "" }) { Text(tr(language, "إلغاء", "Cancel", "Annuler")) } },
        )
    }

    BackHandler {
        if (selectedModule != null) selectedModule = null else onBack()
    }

    selectedModule?.let { module ->
        // Alpha98 P4: consume the device status-bar inset once at the module host.
        // Keeps BOTH top navigation buttons fully below the phone's clock/camera cutout,
        // and prevents child screens using statusBarsPadding from adding it twice.
        // This is visual-only: navigation destinations and module data stay unchanged.
        Column(Modifier.fillMaxSize().background(Color(0xFFF7F9FC)).statusBarsPadding()) {
            AssistantModuleNavigationStrip(
                onBackToModules = { selectedModule = null },
                onAppHome = onBack,
            )
            Box(Modifier.weight(1f)) {
                AssistantModuleScreen(
                    module = module,
                    entries = if (module == AssistantModule.SHOPPING) entries else entries.filter { it.module == module },
                    prayerSettings = prayerSettings,
                    defaultCategory = selectedCategory.takeUnless { it == AssistantCategory.ALL } ?: AssistantCategory.PERSONAL,
                    onBack = { selectedModule = null },
                    onOpenScanner = onOpenScanner,
                    onAdd = { title, details, category, dueAt, amount, secondary ->
                        entries = repository.add(entries, module, title, details, category, dueAt, amount, secondary)
                        if (module == AssistantModule.HEALTH) {
                            entries.firstOrNull()?.let { com.abfilepro.app.assistant.HealthAlarmScheduler.sync(context, it) }
                        }
                        if (module == AssistantModule.DOCUMENTS) {
                            entries.firstOrNull()?.let { com.abfilepro.app.assistant.DocumentRenewalAlarmScheduler.sync(context, it) }
                        }
                        if (module == AssistantModule.CALENDAR) {
                            entries.firstOrNull()?.let { latest ->
                                val rawAmount = latest.amount ?: 0.0
                                val reminderEnabled = rawAmount > -1000.0
                                val decodedLead = if (reminderEnabled) rawAmount else kotlin.math.abs(rawAmount + 1000.0)
                                val normalized = latest.copy(enabled = reminderEnabled, amount = decodedLead)
                                entries = repository.update(entries, normalized)
                                com.abfilepro.app.assistant.TaskAlarmScheduler.sync(context, normalized)
                            }
                        }
                    },
                    onAddTask = { title, details, category, dueAt, reminder ->
                        entries = repository.add(entries, AssistantModule.TASKS, title, details, category, dueAt, null, "", enabled = reminder)
                        entries.firstOrNull()?.let { com.abfilepro.app.assistant.TaskAlarmScheduler.sync(context, it) }
                    },
                    onUpdate = { entry ->
                        entries = repository.update(entries, entry)
                        if (entry.module == AssistantModule.TASKS || entry.module == AssistantModule.CALENDAR) {
                            com.abfilepro.app.assistant.TaskAlarmScheduler.sync(context, entry)
                        }
                        if (entry.module == AssistantModule.HEALTH) {
                            com.abfilepro.app.assistant.HealthAlarmScheduler.sync(context, entry)
                        }
                        if (entry.module == AssistantModule.DOCUMENTS) {
                            com.abfilepro.app.assistant.DocumentRenewalAlarmScheduler.sync(context, entry)
                        }
                    },
                    onDelete = { id ->
                        if (module == AssistantModule.TASKS || module == AssistantModule.CALENDAR) {
                            com.abfilepro.app.assistant.TaskAlarmScheduler.cancel(context, id)
                        }
                        if (module == AssistantModule.HEALTH) {
                            com.abfilepro.app.assistant.HealthAlarmScheduler.cancel(context, id)
                        }
                        if (module == AssistantModule.DOCUMENTS) {
                            com.abfilepro.app.assistant.DocumentRenewalAlarmScheduler.cancel(context, id)
                        }
                        entries = repository.delete(entries, id)
                    },
                    onPrayerSettings = { value ->
                        prayerSettings = value
                        repository.savePrayerSettings(value)
                        com.abfilepro.app.assistant.PrayerAlarmScheduler.reschedule(context, value)
                    },
                )
            }
        }
        return
    }

    // Alpha89: restore the user-approved Arabic visual identity while keeping Alpha88 logic live.
    // The reference artwork is only the visual shell; filters, summary, prayer, quick-add and cards are operational.
    if (language.code == "ar") {
        AssistantReferenceHome(
            prayerSettings = prayerSettings,
            entries = entries,
            selectedCategory = selectedCategory,
            scrollState = arabicHomeScrollState,
            onCategoryChange = { selectedCategory = it },
            onBack = onBack,
            onOpenModule = { selectedModule = it },
            onQuickAdd = { showQuickAdd = true },
            onUpdateEntry = { entry ->
                entries = repository.update(entries, entry)
                if (entry.module == AssistantModule.TASKS || entry.module == AssistantModule.CALENDAR) {
                    com.abfilepro.app.assistant.TaskAlarmScheduler.sync(context, entry)
                }
            },
        )
        return
    }

    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = ZonedDateTime.now()
            delay(30_000)
        }
    }
    val summary = remember(entries, now.toLocalDate()) { AssistantDomain.summary(entries) }
    val modules = remember(selectedCategory) { AssistantDomain.modulesFor(selectedCategory) }
    val locale = when (language.code) { "ar" -> Locale("ar"); "fr" -> Locale.FRENCH; else -> Locale.ENGLISH }



    Scaffold(
        containerColor = Color(0xFFF7F9FC),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(tr(language, "المساعد الشخصي", "Personal Assistant", "Assistant personnel"), fontWeight = FontWeight.Black)
                        Text(
                            now.format(DateTimeFormatter.ofPattern("h:mm a", locale)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f),
                        )
                    }
                },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onBack, modifier = Modifier.padding(start = 6.dp)) {
                        Icon(Icons.Default.ArrowBack, tr(language, "رجوع", "Back", "Retour"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showQuickAdd = true },
                containerColor = Color(0xFF173B65),
                contentColor = Color.White,
            ) { Icon(Icons.Default.Add, tr(language, "إضافة سريعة", "Quick add", "Ajout rapide")) }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = nativeGridState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        tr(language, "ملخص اليوم", "Today's summary", "Résumé du jour"),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        now.toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TodaySummaryCard(
                        tasks = summary.openTasks,
                        appointments = summary.appointmentsToday,
                        family = summary.pendingFamilyRequests,
                        medications = summary.activeMedicationReminders,
                    )
                    TodayPrayerCard(
                        now = now,
                        settings = prayerSettings,
                        onClick = { selectedModule = AssistantModule.PRAYER },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistantCategory.entries.forEach { category ->
                            val selected = selectedCategory == category
                            AssistChip(
                                onClick = { selectedCategory = category },
                                label = { Text(categoryLabel(category, language), fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = if (selected) Color(0xFF173B65) else Color.White,
                                    labelColor = if (selected) Color.White else Color(0xFF334155),
                                ),
                                border = null,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Text(
                        tr(language, "الأقسام", "Sections", "Sections"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
            items(modules, key = { it.storageId }) { module ->
                AssistantModuleCard(module = module, onClick = { selectedModule = module })
            }
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) { Spacer(Modifier.height(18.dp)) }
        }
    }
}

@Composable
private fun TodaySummaryCard(tasks: Int, appointments: Int, family: Int, medications: Int) {
    val language = LocalAppLanguage.current
    val metrics = listOf(
        tasks to tr(language, "مهام", "Tasks", "Tâches"),
        appointments to tr(language, "مواعيد", "Appointments", "Rendez-vous"),
        family to tr(language, "طلبات", "Requests", "Demandes"),
        medications to tr(language, "أدوية", "Medication", "Médicaments"),
    )
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            metrics.forEach { (value, label) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text(value.toString(), fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color(0xFF173B65))
                    Text(label, fontSize = 11.sp, color = Color(0xFF64748B), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun TodayPrayerCard(now: ZonedDateTime, settings: PrayerSettings, onClick: () -> Unit) {
    val language = LocalAppLanguage.current
    val zone = remember(settings.zoneId) { runCatching { java.time.ZoneId.of(settings.zoneId) }.getOrDefault(now.zone) }
    val localNow = now.withZoneSameInstant(zone)
    val times = remember(localNow.toLocalDate(), settings) {
        val offset = zone.rules.getOffset(localNow.toLocalDate().atStartOfDay(zone).toInstant()).totalSeconds / 3600.0
        PrayerTimesCalculator.calculate(
            LocalDate.now(zone), settings.latitude, settings.longitude, offset,
            settings.method, settings.asrSchool, settings.adjustmentsMinutes,
        )
    }
    val next = PrayerName.entries.firstOrNull { prayer -> times[prayer]?.isAfter(localNow.toLocalTime()) == true }
        ?: PrayerName.FAJR
    val nextTime = times[next]
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Row(
            Modifier
                .background(Brush.horizontalGradient(listOf(Color(0xFF075E54), Color(0xFF0C8B70))))
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(shape = CircleShape, color = Color.White.copy(alpha = .16f)) {
                Icon(Icons.Default.NotificationsActive, null, tint = Color.White, modifier = Modifier.padding(11.dp).size(26.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(tr(language, "الصلاة القادمة", "Next prayer", "Prochaine prière"), color = Color.White.copy(alpha = .78f), fontSize = 12.sp)
                Text("${prayerLabel(next, language)}  ${nextTime ?: "--:--"}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text(cityLabel(settings.cityId, language), color = Color.White.copy(alpha = .76f), fontSize = 12.sp)
            }
            Icon(Icons.Default.KeyboardArrowLeft, null, tint = Color.White)
        }
    }
}

@Composable
private fun AssistantModuleCard(module: AssistantModule, onClick: () -> Unit) {
    val language = LocalAppLanguage.current
    val presentation = module.presentation(language)
    Card(
        modifier = Modifier.fillMaxWidth().height(154.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(23.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(15.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Surface(shape = RoundedCornerShape(15.dp), color = presentation.color.copy(alpha = .12f)) {
                Icon(presentation.icon, null, tint = presentation.color, modifier = Modifier.padding(10.dp).size(27.dp))
            }
            Column {
                Text(presentation.title, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
                Spacer(Modifier.height(4.dp))
                Text(presentation.subtitle, color = Color(0xFF64748B), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
            }
        }
    }
}

// Shared, lightweight top navigation; never modifies a module or its saved data.
@Composable
private fun AssistantModuleNavigationStrip(
    onBackToModules: () -> Unit,
    onAppHome: () -> Unit,
) {
    val language = LocalAppLanguage.current
    Surface(color = Color(0xFFF7F9FC), shadowElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistantTopStripButton(
                text = tr(language, "رجوع للمساعد", "Assistant", "Assistant"),
                icon = Icons.Default.ArrowBack,
                onClick = onBackToModules,
                modifier = Modifier.weight(1f),
                primary = false,
            )
            AssistantTopStripButton(
                text = tr(language, "الرئيسية", "Home", "Accueil"),
                icon = Icons.Default.Home,
                onClick = onAppHome,
                modifier = Modifier.weight(1f),
                primary = true,
            )
        }
    }
}
