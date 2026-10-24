package com.abfilepro.app.ui.screens.assistant

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.abfilepro.app.assistant.AssistantCategory
import com.abfilepro.app.assistant.AssistantEntry
import com.abfilepro.app.assistant.AssistantModule
import com.abfilepro.app.assistant.TaskAlarmScheduler
import java.text.DateFormatSymbols
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

private enum class ReminderOffset(val minutes: Int, val label: String) {
    NONE(0, "في الوقت نفسه"),
    M5(5, "قبل 5 دقائق"),
    M15(15, "قبل 15 دقيقة"),
    M30(30, "قبل 30 دقيقة"),
    H1(60, "قبل ساعة")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantCalendarScreen(
    entries: List<AssistantEntry>,
    defaultCategory: AssistantCategory,
    onBack: () -> Unit,
    onAdd: (String, String, AssistantCategory, Long?, Double?, String) -> Unit,
    onUpdate: (AssistantEntry) -> Unit,
    onDelete: (String) -> Unit,
) {
    val today = LocalDate.now()
    var visibleMonth by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf(today) }
    var editing by remember { mutableStateOf<AssistantEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    val monthEntries = entries.filter { it.module == AssistantModule.CALENDAR }
    val selectedEvents = monthEntries.filter { entryDate(it) == selectedDate }.sortedBy { it.dueAt ?: Long.MAX_VALUE }

    if (adding || editing != null) {
        CalendarEventDialog(
            initial = editing,
            defaultCategory = editing?.category ?: defaultCategory,
            presetDate = selectedDate,
            onDismiss = { adding = false; editing = null },
            onSave = { title, location, category, startAt, endAt, reminder, leadMinutes ->
                val old = editing
                if (old == null) {
                    val encodedLead = if (reminder) leadMinutes.toDouble() else -(leadMinutes.toDouble()) - 1000.0
                    onAdd(title, location, category, startAt, encodedLead, endAt?.toString().orEmpty())
                } else {
                    onUpdate(
                        old.copy(
                            title = title.trim(),
                            details = location.trim(),
                            category = category,
                            dueAt = startAt,
                            secondary = endAt?.toString().orEmpty(),
                            amount = leadMinutes.toDouble(),
                            enabled = reminder,
                        )
                    )
                }
                adding = false
                editing = null
            },
        )
    }

    Scaffold(
        containerColor = adaptSurface(Color(0xFFF6F7FB)),
        topBar = {
            TopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(L("التقويم والمواعيد"), fontWeight = FontWeight.Black)
                        Text(L("رتّب يومك واربط مواعيدك بالتنبيهات"), fontSize = 12.sp, color = adaptInk(Color(0xFF64748B)))
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L("رجوع")) } },
                actions = { IconButton(onClick = {}) { Icon(Icons.Default.Settings, L("إعدادات")) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = adaptSurface(Color.White)),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { adding = true },
                containerColor = Color(0xFFF44336),
                contentColor = Color.White,
                modifier = Modifier.navigationBarsPadding(),
            ) { Icon(Icons.Default.Add, L("إضافة موعد")) }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = adaptSurface(Color.White)), shape = RoundedCornerShape(28.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        MonthHeader(visibleMonth, onPrevious = { visibleMonth = visibleMonth.minusMonths(1) }, onNext = { visibleMonth = visibleMonth.plusMonths(1) })
                        CalendarGrid(visibleMonth, selectedDate, monthEntries, onSelect = { selectedDate = it })
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(L("مواعيد اليوم"), fontSize = 18.sp, fontWeight = FontWeight.Black, color = adaptInk(Color(0xFF0F172A)))
                    Text(arabicDate(selectedDate), color = adaptInk(Color(0xFF64748B)), fontSize = 13.sp)
                }
            }
            if (selectedEvents.isEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = adaptSurface(Color.White)), shape = RoundedCornerShape(24.dp)) {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text(L("لا توجد مواعيد في هذا اليوم\nاضغط + لإضافة موعد"), textAlign = TextAlign.Center, color = adaptInk(Color(0xFF64748B)))
                        }
                    }
                }
            } else {
                items(selectedEvents, key = { it.id }) { event ->
                    CalendarEventCard(event = event, onEdit = { editing = event }, onDelete = { onDelete(event.id) })
                }
            }
            item { Spacer(Modifier.height(76.dp)) }
        }
    }
}

@Composable
private fun MonthHeader(month: YearMonth, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        MonthNavButton(text = "‹", onClick = onNext)
        Text(
            text = monthName(month),
            fontWeight = FontWeight.Black,
            fontSize = 22.sp,
            color = adaptInk(Color(0xFF111827)),
        )
        MonthNavButton(text = "›", onClick = onPrevious)
    }
}

@Composable
private fun MonthNavButton(text: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        color = adaptSurface(Color(0xFFF6F6F8)),
    ) { Box(contentAlignment = Alignment.Center) { Text(text, fontSize = 26.sp, color = adaptInk(Color(0xFF111827))) } }
}

@Composable
private fun CalendarGrid(month: YearMonth, selectedDate: LocalDate, entries: List<AssistantEntry>, onSelect: (LocalDate) -> Unit) {
    val firstOfMonth = month.atDay(1)
    val daysInMonth = month.lengthOfMonth()
    val startOffset = (firstOfMonth.dayOfWeek.value + 1) % 7 // السبت=0
    val totalCells = ((startOffset + daysInMonth + 6) / 7) * 7
    val markers = entries.mapNotNull { entryDate(it) }.toSet()
    val weekdayLabels = listOf("السبت", "الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            weekdayLabels.forEach { day ->
                Text(day, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, color = adaptInk(Color(0xFF475569)), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        var index = 0
        repeat(totalCells / 7) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(7) {
                    val dayNumber = index - startOffset + 1
                    val date = if (dayNumber in 1..daysInMonth) month.atDay(dayNumber) else null
                    DayCell(
                        modifier = Modifier.weight(1f),
                        date = date,
                        isSelected = date == selectedDate,
                        isToday = date == LocalDate.now(),
                        hasEvent = date in markers,
                        onClick = { date?.let(onSelect) },
                    )
                    index++
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    modifier: Modifier = Modifier,
    date: LocalDate?,
    isSelected: Boolean,
    isToday: Boolean,
    hasEvent: Boolean,
    onClick: () -> Unit,
) {
    val background = when {
        isSelected -> Brush.linearGradient(listOf(Color(0xFFFF6A3D), Color(0xFFF44336)))
        isToday -> Brush.linearGradient(listOf(adaptSurface(Color(0xFFFFF1EE)), adaptSurface(Color(0xFFFFE0DA))))
        else -> Brush.linearGradient(listOf(adaptSurface(Color(0xFFF8F9FB)), adaptSurface(Color(0xFFF8F9FB))))
    }
    Box(
        modifier = modifier.height(56.dp).clip(RoundedCornerShape(16.dp)).background(background).clickable(enabled = date != null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (date != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(date.dayOfMonth.toString(), color = if (isSelected) Color.White else adaptInk(Color(0xFF111827)), fontWeight = FontWeight.Bold, fontSize = 22.sp)
                if (hasEvent) {
                    Spacer(Modifier.height(2.dp))
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (isSelected) Color.White else Color(0xFFF44336)))
                }
            }
        }
    }
}

@Composable
private fun CalendarEventCard(event: AssistantEntry, onEdit: () -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val startAt = event.dueAt ?: System.currentTimeMillis()
    val startCal = Calendar.getInstance().apply { timeInMillis = startAt }
    val endAt = event.secondary.toLongOrNull() ?: (startAt + 60*60*1000)
    val endCal = Calendar.getInstance().apply { timeInMillis = endAt }
    val chipColor = if (event.category == AssistantCategory.WORK) adaptSurface(Color(0xFFFFE6E6)) else adaptSurface(Color(0xFFE8FFF2))
    val chipText = if (event.category == AssistantCategory.WORK) adaptInk(Color(0xFFD32F2F)) else adaptInk(Color(0xFF148F5A))
    val cardTint = if (event.category == AssistantCategory.WORK) adaptSurface(Color(0xFFFFF6F5)) else adaptSurface(Color(0xFFF4FFF8))
    Card(colors = CardDefaults.cardColors(containerColor = cardTint), shape = RoundedCornerShape(26.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 98.dp, height = 88.dp).clip(RoundedCornerShape(20.dp)).background(if (event.category == AssistantCategory.WORK) adaptSurface(Color(0xFFEAF2FF)) else adaptSurface(Color(0xFFEAFBF4))), contentAlignment = Alignment.Center) {
                Icon(if (event.category == AssistantCategory.WORK) Icons.Default.BusinessCenter else Icons.Default.Person, null, tint = if (event.category == AssistantCategory.WORK) adaptInk(Color(0xFF3973D6)) else adaptInk(Color(0xFF19A566)), modifier = Modifier.size(42.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(color = chipColor, shape = RoundedCornerShape(999.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(if (event.category == AssistantCategory.WORK) Icons.Default.BusinessCenter else Icons.Default.Person, null, tint = chipText, modifier = Modifier.size(14.dp))
                        Text(if (event.category == AssistantCategory.WORK) "عمل" else "شخصي", color = chipText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(event.title, fontWeight = FontWeight.Black, fontSize = 18.sp, color = adaptInk(Color(0xFF111827)))
                if (event.details.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.LocationOn, null, tint = adaptInk(Color(0xFF64748B)), modifier = Modifier.size(16.dp))
                        Text(event.details, color = adaptInk(Color(0xFF64748B)), fontSize = 13.sp)
                    }
                }
                if (event.enabled) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.NotificationsActive, null, tint = adaptInk(Color(0xFFF44336)), modifier = Modifier.size(16.dp))
                        Text(reminderCaption((event.amount ?: 0.0).toInt()), color = adaptInk(Color(0xFFF44336)), fontSize = 12.sp)
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(34.dp).clip(CircleShape).clickable { expanded = true }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.MoreVert, null, tint = adaptInk(Color(0xFF111827)))
                }
                Icon(Icons.Default.AccessTime, null, tint = adaptInk(Color(0xFFF44336)), modifier = Modifier.size(20.dp))
                Text(formatTime(startCal), fontWeight = FontWeight.Bold, color = adaptInk(Color(0xFF111827)), fontSize = 14.sp)
                Text("—", color = adaptInk(Color(0xFFF44336)))
                Text(formatTime(endCal), fontWeight = FontWeight.Bold, color = adaptInk(Color(0xFF111827)), fontSize = 14.sp)
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text(L("تعديل")) }, onClick = { expanded = false; onEdit() }, leadingIcon = { Icon(Icons.Default.Edit, null) })
                    DropdownMenuItem(text = { Text(L("حذف")) }, onClick = { expanded = false; onDelete() }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) })
                }
            }
        }
    }
}

@Composable
private fun CalendarEventDialog(
    initial: AssistantEntry?,
    defaultCategory: AssistantCategory,
    presetDate: LocalDate,
    onDismiss: () -> Unit,
    onSave: (String, String, AssistantCategory, Long?, Long?, Boolean, Int) -> Unit,
) {
    val context = LocalContext.current
    var title by remember(initial) { mutableStateOf(initial?.title.orEmpty()) }
    var location by remember(initial) { mutableStateOf(initial?.details.orEmpty()) }
    var category by remember(initial) { mutableStateOf(initial?.category ?: defaultCategory) }
    var startAt by remember(initial) { mutableStateOf(initial?.dueAt ?: localDateToMillis(presetDate, 10, 0)) }
    var endAt by remember(initial) { mutableStateOf(initial?.secondary?.toLongOrNull() ?: (startAt + 60*60*1000)) }
    var reminderEnabled by remember(initial) { mutableStateOf(initial?.enabled ?: true) }
    var reminderOffset by remember(initial) { mutableStateOf(ReminderOffset.entries.firstOrNull { it.minutes == ((initial?.amount ?: 15.0).toInt()) } ?: ReminderOffset.M15) }
    var permissionMessage by remember { mutableStateOf("") }
    var showReminderMenu by remember { mutableStateOf(false) }

    val exactAlarmLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val exactGranted = TaskAlarmScheduler.hasExactAlarmAccess(context)
        reminderEnabled = exactGranted && hasNotificationPermission(context)
        permissionMessage = if (reminderEnabled) "" else "فعّل السماح بالمنبهات الدقيقة لتشغيل تنبيه الموعد."
    }
    fun requestExactAlarmAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || TaskAlarmScheduler.hasExactAlarmAccess(context)) {
            reminderEnabled = true
            permissionMessage = ""
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply { data = Uri.parse("package:${context.packageName}") }
        runCatching { exactAlarmLauncher.launch(intent) }.onFailure {
            permissionMessage = L("افتح إعدادات التطبيق واسمح بالمنبهات الدقيقة.")
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:${context.packageName}") })
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestExactAlarmAccess() else {
            reminderEnabled = false
            permissionMessage = L("لا يمكن تشغيل التنبيه بدون السماح بالإشعارات.")
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "إضافة موعد" else "تعديل الموعد", fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it.take(240) }, label = { Text(L("عنوان الموعد")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(location, { location = it.take(240) }, label = { Text(L("المكان")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(category == AssistantCategory.PERSONAL, { category = AssistantCategory.PERSONAL }, { Text(L("شخصي")) })
                    FilterChip(category == AssistantCategory.WORK, { category = AssistantCategory.WORK }, { Text(L("عمل")) })
                }
                OutlinedButton(onClick = { pickDateTime(context, startAt) { startAt = it; if (endAt <= startAt) endAt = startAt + 60*60*1000 } }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.DateRange, null)
                    Spacer(Modifier.size(6.dp))
                    Text(L("بداية الموعد: ${formatDateTime(startAt)}"))
                }
                OutlinedButton(onClick = { pickDateTime(context, endAt) { endAt = it } }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.AccessTime, null)
                    Spacer(Modifier.size(6.dp))
                    Text(L("نهاية الموعد: ${formatDateTime(endAt)}"))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.NotificationsActive, null, tint = adaptInk(Color(0xFFF44336)))
                    Spacer(Modifier.size(8.dp))
                    Text(L("تنبيه للموعد"), Modifier.weight(1f))
                    Switch(checked = reminderEnabled, onCheckedChange = { checked ->
                        if (!checked) {
                            reminderEnabled = false
                            permissionMessage = ""
                        } else if (!hasNotificationPermission(context)) {
                            if (Build.VERSION.SDK_INT >= 33) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else requestExactAlarmAccess()
                        } else {
                            requestExactAlarmAccess()
                        }
                    })
                }
                if (reminderEnabled) {
                    Box {
                        OutlinedButton(onClick = { showReminderMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(reminderOffset.label)
                        }
                        DropdownMenu(expanded = showReminderMenu, onDismissRequest = { showReminderMenu = false }) {
                            ReminderOffset.entries.forEach { option ->
                                DropdownMenuItem(text = { Text(option.label) }, onClick = { reminderOffset = option; showReminderMenu = false })
                            }
                        }
                    }
                }
                if (permissionMessage.isNotBlank()) {
                    Text(permissionMessage, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val usableReminder = reminderEnabled && hasNotificationPermission(context) && TaskAlarmScheduler.hasExactAlarmAccess(context)
                onSave(title, location, category, startAt, endAt, usableReminder, if (usableReminder) reminderOffset.minutes else 0)
            }, enabled = title.isNotBlank() && endAt > startAt) { Text(L("حفظ")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("إلغاء")) } },
    )
}

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun pickDateTime(context: Context, initial: Long, selected: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = initial }
    DatePickerDialog(
        context,
        { _, y, m, d ->
            TimePickerDialog(
                context,
                { _, h, min ->
                    selected(Calendar.getInstance().apply {
                        set(y, m, d, h, min, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis)
                },
                c.get(Calendar.HOUR_OF_DAY),
                c.get(Calendar.MINUTE),
                false,
            ).show()
        },
        c.get(Calendar.YEAR),
        c.get(Calendar.MONTH),
        c.get(Calendar.DAY_OF_MONTH),
    ).show()
}

private fun entryDate(entry: AssistantEntry): LocalDate? = entry.dueAt?.let {
    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
}

private fun formatTime(calendar: Calendar): String {
    val hour = calendar.get(Calendar.HOUR)
    val minute = calendar.get(Calendar.MINUTE)
    val displayHour = if (hour == 0) 12 else hour
    val amPm = if (calendar.get(Calendar.AM_PM) == Calendar.AM) "ص" else "م"
    return "%d:%02d %s".format(displayHour, minute, amPm)
}

private fun formatDateTime(millis: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    return "${c.get(Calendar.DAY_OF_MONTH)}/${c.get(Calendar.MONTH)+1}/${c.get(Calendar.YEAR)} ${formatTime(c)}"
}

private fun arabicDate(date: LocalDate): String {
    val names = DateFormatSymbols(Locale("ar")).weekdays
    val dayName = names[(date.dayOfWeek.value % 7) + 1]
    val monthName = DateFormatSymbols(Locale("ar")).months[date.monthValue - 1]
    return "$dayName، ${date.dayOfMonth} $monthName ${date.year}"
}

private fun monthName(month: YearMonth): String {
    val monthName = DateFormatSymbols(Locale("ar")).months[month.monthValue - 1]
    return "$monthName ${month.year}"
}

private fun localDateToMillis(date: LocalDate, hour: Int, minute: Int): Long =
    Calendar.getInstance().apply {
        set(date.year, date.monthValue - 1, date.dayOfMonth, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

private fun reminderCaption(minutes: Int): String = when (minutes) {
    60 -> "التنبيه قبل ساعة"
    30 -> "التنبيه قبل 30 دقيقة"
    15 -> "التنبيه قبل 15 دقيقة"
    5 -> "التنبيه قبل 5 دقائق"
    else -> "التنبيه في وقت الموعد"
}
