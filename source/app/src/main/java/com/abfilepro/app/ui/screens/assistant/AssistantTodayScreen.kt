package com.abfilepro.app.ui.screens.assistant

import android.Manifest
import android.app.TimePickerDialog
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abfilepro.app.assistant.AssistantEntry
import com.abfilepro.app.assistant.AssistantModule
import com.abfilepro.app.assistant.AssistantSearch
import com.abfilepro.app.assistant.BillReminderRecords
import com.abfilepro.app.assistant.HealthEntryCodec
import com.abfilepro.app.assistant.QuickAddParser
import com.abfilepro.app.assistant.Recurrence
import com.abfilepro.app.assistant.TodayAgenda
import com.abfilepro.app.assistant.TodayAgendaBuilder
import com.abfilepro.app.assistant.TodayBriefingSettings
import com.abfilepro.app.assistant.TodayItem
import com.abfilepro.app.assistant.TodayKind
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import java.text.DateFormat
import java.util.Date

private val TodayInk = Color(0xFF0B1D3A)

/**
 * P27 «يومي»: everything that matters today in one list — tasks, appointments, medicine doses, bills,
 * documents — plus a one-sentence quick add, a search over every module, and the morning notification.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssistantTodayScreen(
    entries: List<AssistantEntry>,
    onBack: () -> Unit,
    onQuickAdd: (QuickAddParser.Result) -> Unit,
    onUpdate: (AssistantEntry) -> Unit,
    onOpenModule: (AssistantModule) -> Unit,
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    var input by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var briefing by remember { mutableStateOf(TodayBriefingSettings.load(context)) }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        // First visit: the morning summary is switched on once (07:30) and the person can turn it off.
        if (TodayBriefingSettings.seedOnFirstUse(context)) {
            briefing = TodayBriefingSettings.load(context)
            if (Build.VERSION.SDK_INT >= 33) runCatching { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    val bills = remember(entries) { BillReminderRecords.load(context) }
    val agenda = remember(entries, bills) { TodayAgendaBuilder.build(entries, bills, System.currentTimeMillis()) }
    val parsed = remember(input) { if (input.isBlank()) null else QuickAddParser.parse(input, System.currentTimeMillis()) }
    val found = remember(entries, search) { AssistantSearch.search(entries, search) }

    Scaffold(
        containerColor = adaptSurface(Color(0xFFF7F9FC)),
        topBar = {
            TopAppBar(
                title = { Text(L("يومي"), fontWeight = FontWeight.Black) },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onBack, modifier = Modifier.padding(start = 6.dp)) {
                        Icon(Icons.Default.ArrowBack, L("رجوع"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = adaptSurface(Color.White)),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = adaptSurface(Color.White))) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(L("إضافة بجملة واحدة"), fontWeight = FontWeight.Black, color = adaptInk(TodayInk))
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it.take(300) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text(L("مثال: ذكرني بكرة الساعة 5 العصر أكلم أحمد")) },
                        )
                        parsed?.let { result ->
                            Text(previewLine(result), fontSize = 12.sp, color = adaptInk(Color(0xFF475569)))
                        }
                        Button(
                            enabled = parsed != null && parsed.title.isNotBlank(),
                            onClick = { parsed?.let { onQuickAdd(it); input = "" } },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.size(6.dp))
                            Text(L("إضافة"))
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it.take(100) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text(L("بحث في كل أقسام المساعد")) },
                )
            }
            if (search.isNotBlank()) {
                if (found.isEmpty()) item { Text(L("لا توجد نتائج"), color = adaptInk(Color(0xFF64748B))) }
                items(found, key = { "s:" + it.id }) { entry ->
                    Card(
                        Modifier.fillMaxWidth().clickable { onOpenModule(entry.module) },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = adaptSurface(Color.White)),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(entry.title, fontWeight = FontWeight.Bold, color = adaptInk(TodayInk), maxLines = 2)
                            Text(entry.module.presentation(language).title, fontSize = 12.sp, color = adaptInk(Color(0xFF64748B)))
                        }
                    }
                }
            } else {
                item { BriefingCard(briefing, onChange = { briefing = it; TodayBriefingSettings.save(context, it) }, notificationLauncher = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) }
                if (agenda.isEmpty) item { Text(L("مفيش حاجة مستنياك النهارده"), color = adaptInk(Color(0xFF64748B))) }
                section(L("متأخر"), agenda.overdue, Color(0xFFD64545), onUpdate, onOpenModule, entries)
                section(L("النهارده"), agenda.today, Color(0xFF173B65), onUpdate, onOpenModule, entries)
                section(L("قريبًا"), agenda.soon, Color(0xFF64748B), onUpdate, onOpenModule, entries)
                if (agenda.undatedTasks > 0) {
                    item {
                        TextButton(onClick = { onOpenModule(AssistantModule.TASKS) }) {
                            Text(L("مهام بدون موعد") + ": " + agenda.undatedTasks)
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    rows: List<TodayItem>,
    accent: Color,
    onUpdate: (AssistantEntry) -> Unit,
    onOpenModule: (AssistantModule) -> Unit,
    entries: List<AssistantEntry>,
) {
    if (rows.isEmpty()) return
    item(key = "h:$title") { Text(title, fontWeight = FontWeight.Black, fontSize = 16.sp, color = adaptInk(accent)) }
    items(rows, key = { it.key }) { row ->
        TodayRow(row, entries, onUpdate, onOpenModule)
    }
}

@Composable
private fun TodayRow(item: TodayItem, entries: List<AssistantEntry>, onUpdate: (AssistantEntry) -> Unit, onOpenModule: (AssistantModule) -> Unit) {
    val entry = item.entryId?.let { id -> entries.firstOrNull { it.id == id } }
    val module = when (item.kind) {
        TodayKind.TASK -> AssistantModule.TASKS
        TodayKind.EVENT -> AssistantModule.CALENDAR
        TodayKind.MEDICINE, TodayKind.HEALTH_APPOINTMENT -> AssistantModule.HEALTH
        TodayKind.BILL -> AssistantModule.BILLS
        TodayKind.DOCUMENT -> AssistantModule.DOCUMENTS
    }
    Card(
        Modifier.fillMaxWidth().clickable { onOpenModule(module) },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = adaptSurface(Color.White)),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                item.kind == TodayKind.TASK && entry != null ->
                    Checkbox(checked = false, onCheckedChange = { onUpdate(entry.copy(completed = true)) })
                item.kind == TodayKind.MEDICINE && entry != null && !item.done ->
                    TextButton(onClick = {
                        val meta = HealthEntryCodec.parse(entry.secondary)
                        val marked = HealthEntryCodec.markTakenToday(meta)
                        onUpdate(entry.copy(secondary = HealthEntryCodec.encode(marked), dueAt = HealthEntryCodec.nextMedicationTrigger(marked)))
                    }) { Text(L("تم")) }
                item.done -> Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF2E9E6B))
                else -> Spacer(Modifier.size(4.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    fontWeight = FontWeight.Bold,
                    color = adaptInk(TodayInk),
                    maxLines = 2,
                )
                val parts = buildList {
                    kindLabel(item.kind)?.let { add(it) }
                    item.atMillis?.let { add(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))) }
                    if (item.detail.isNotBlank() && item.kind != TodayKind.TASK && item.kind != TodayKind.EVENT) add(item.detail)
                }
                if (parts.isNotEmpty()) Text(parts.joinToString(" • "), fontSize = 12.sp, color = adaptInk(Color(0xFF64748B)))
            }
        }
    }
}

private fun kindLabel(kind: TodayKind): String? = when (kind) {
    TodayKind.TASK -> null
    TodayKind.EVENT -> L("موعد")
    TodayKind.MEDICINE -> L("دواء")
    TodayKind.HEALTH_APPOINTMENT -> L("موعد صحي")
    TodayKind.BILL -> L("فاتورة")
    TodayKind.DOCUMENT -> L("تجديد مستند")
}

private fun previewLine(result: QuickAddParser.Result): String {
    val kind = if (result.calendar) L("موعد") else L("مهمة")
    val whenText = result.dueAt?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: L("بدون موعد")
    val repeat = if (result.recurrence != Recurrence.NONE) " • " + recurrenceLabel(result.recurrence) else ""
    return "$kind: ${result.title} • $whenText$repeat"
}

@Composable
private fun BriefingCard(
    value: TodayBriefingSettings.Value,
    onChange: (TodayBriefingSettings.Value) -> Unit,
    notificationLauncher: () -> Unit,
) {
    val context = LocalContext.current
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = adaptSurface(Color.White))) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(L("ملخص الصباح"), fontWeight = FontWeight.Black, color = adaptInk(TodayInk))
                TextButton(onClick = {
                    TimePickerDialog(context, { _, h, m -> onChange(value.copy(hour = h, minute = m)) }, value.hour, value.minute, true).show()
                }) { Text("%02d:%02d".format(value.hour, value.minute)) }
            }
            Switch(checked = value.enabled, onCheckedChange = { on ->
                onChange(value.copy(enabled = on))
                if (on && Build.VERSION.SDK_INT >= 33) runCatching { notificationLauncher() }
            })
        }
    }
}
