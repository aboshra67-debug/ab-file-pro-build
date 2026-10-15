package com.abfilepro.app.ui.screens.assistant

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.abfilepro.app.assistant.AssistantCategory
import com.abfilepro.app.assistant.AssistantEntry
import com.abfilepro.app.assistant.HealthAlarmScheduler
import com.abfilepro.app.assistant.HealthEntryCodec
import com.abfilepro.app.assistant.HealthEntryMeta
import com.abfilepro.app.assistant.HealthEntryType
import com.abfilepro.app.assistant.HealthOwner
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

private val HealthRed = Color(0xFFFF2D3D)
private val HealthGreen = Color(0xFF17B978)
private val HealthSoftGreen = Color(0xFFE9FFF5)
private val HealthSoftRed = Color(0xFFFFF1F2)
private val HealthInk = Color(0xFF111827)
private val HealthMuted = Color(0xFF667085)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HealthMedicationScreen(
    entries: List<AssistantEntry>,
    selfName: String,
    partnerName: String,
    onBack: () -> Unit,
    onAdd: (String, String, AssistantCategory, Long?, Double?, String) -> Unit,
    onUpdate: (AssistantEntry) -> Unit,
    onDelete: (String) -> Unit,
) {
    val context = LocalContext.current
    var owner by remember { mutableStateOf(HealthOwner.SELF) }
    var showMedicineDialog by remember { mutableStateOf(false) }
    var showAppointmentDialog by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val healthEntries = entries.filter { it.module.storageId == "health" }
    val ownerEntries = healthEntries.filter { HealthEntryCodec.parse(it.secondary).owner == owner }
    val medicines = ownerEntries.filter { HealthEntryCodec.parse(it.secondary).type == HealthEntryType.MEDICATION }
    val appointments = ownerEntries.filter { HealthEntryCodec.parse(it.secondary).type == HealthEntryType.APPOINTMENT }
        .sortedBy { it.dueAt ?: Long.MAX_VALUE }
    val nextMedication = medicines.filter { it.enabled }.minByOrNull { it.dueAt ?: Long.MAX_VALUE }

    fun ensureNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!HealthAlarmScheduler.hasExactAlarmAccess(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)) }
        }
    }

    Scaffold(
        containerColor = Color(0xFFFCFCFD),
        topBar = {
            TopAppBar(
                title = { Text("الصحة والأدوية", fontWeight = FontWeight.Black, fontSize = 28.sp) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                OwnerTabs(
                    selected = owner,
                    selfName = selfName.ifBlank { "أنا" },
                    partnerName = partnerName.ifBlank { "زوجتي" },
                    onSelect = { owner = it },
                )
            }
            item { NextDoseCard(nextMedication, onUpdate) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("جدول اليوم", fontSize = 24.sp, fontWeight = FontWeight.Black, color = HealthInk)
                        Text("أدويتك لليوم", color = HealthMuted)
                    }
                    Surface(shape = RoundedCornerShape(16.dp), color = HealthSoftRed) {
                        Row(Modifier.padding(horizontal = 13.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CalendarMonth, null, tint = HealthRed, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(7.dp))
                            Text(formatTodayArabic(), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
            if (medicines.isEmpty()) {
                item { EmptyHealthCard("لا توجد أدوية مسجلة لهذا الشخص") }
            } else {
                items(medicines.sortedBy { it.dueAt ?: Long.MAX_VALUE }, key = { it.id }) { entry ->
                    MedicationRow(entry = entry, onUpdate = onUpdate, onDelete = onDelete)
                }
            }
            item {
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
                    Text("مواعيد صحية", fontSize = 24.sp, fontWeight = FontWeight.Black, color = HealthInk)
                    Text("مواعيد الطبيب والفحوصات", color = HealthMuted)
                }
            }
            if (appointments.isEmpty()) {
                item { EmptyHealthCard("لا توجد مواعيد صحية قادمة") }
            } else {
                items(appointments, key = { it.id }) { entry ->
                    AppointmentCard(entry = entry, onUpdate = onUpdate, onDelete = onDelete)
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { ensureNotifications(); showMedicineDialog = true },
                        modifier = Modifier.weight(1f).height(62.dp),
                        shape = RoundedCornerShape(17.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = HealthRed),
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.size(7.dp))
                        Text("إضافة دواء", fontWeight = FontWeight.Black, fontSize = 16.sp)
                    }
                    Button(
                        onClick = { ensureNotifications(); showAppointmentDialog = true },
                        modifier = Modifier.weight(1f).height(62.dp),
                        shape = RoundedCornerShape(17.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE9D7FF), contentColor = Color(0xFF5B21B6)),
                    ) {
                        Icon(Icons.Default.CalendarMonth, null)
                        Spacer(Modifier.size(7.dp))
                        Text("إضافة موعد صحي", fontWeight = FontWeight.Black, fontSize = 14.sp)
                    }
                }
            }
            item {
                Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF5FF))) {
                    Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(shape = CircleShape, color = Color(0xFF0D66D0)) {
                            Icon(Icons.Default.HealthAndSafety, null, tint = Color.White, modifier = Modifier.padding(8.dp).size(20.dp))
                        }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                            Text("تنبيه مهم", fontWeight = FontWeight.Black, color = Color(0xFF0D66D0))
                            Text("هذا القسم للتذكير ومتابعة أدويتك ومواعيدك فقط، ولا يقدم تشخيصًا أو علاجًا طبيًا.", color = Color(0xFF476078), fontSize = 12.sp, textAlign = TextAlign.Right)
                        }
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }

    if (showMedicineDialog) {
        AddMedicineDialog(
            owner = owner,
            onDismiss = { showMedicineDialog = false },
            onSave = { name, dose, time ->
                val meta = HealthEntryMeta(HealthEntryType.MEDICATION, owner, time.toString(), "")
                onAdd(name, dose, AssistantCategory.PERSONAL, HealthEntryCodec.nextMedicationTrigger(time), null, HealthEntryCodec.encode(meta))
                showMedicineDialog = false
            },
        )
    }
    if (showAppointmentDialog) {
        AddAppointmentDialog(
            owner = owner,
            onDismiss = { showAppointmentDialog = false },
            onSave = { title, details, at ->
                val meta = HealthEntryMeta(HealthEntryType.APPOINTMENT, owner)
                onAdd(title, details, AssistantCategory.PERSONAL, at, null, HealthEntryCodec.encode(meta))
                showAppointmentDialog = false
            },
        )
    }
}

@Composable
private fun OwnerTabs(selected: HealthOwner, selfName: String, partnerName: String, onSelect: (HealthOwner) -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), color = Color(0xFFF4F4F5), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(4.dp)) {
            listOf(HealthOwner.PARTNER to partnerName, HealthOwner.SELF to selfName).forEach { (value, label) ->
                val active = value == selected
                Surface(
                    modifier = Modifier.weight(1f).clickable { onSelect(value) },
                    shape = RoundedCornerShape(24.dp),
                    color = if (active) HealthRed else Color.Transparent,
                ) {
                    Text(label, modifier = Modifier.padding(vertical = 12.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Black, color = if (active) Color.White else HealthInk)
                }
            }
        }
    }
}

@Composable
private fun NextDoseCard(entry: AssistantEntry?, onUpdate: (AssistantEntry) -> Unit) {
    val meta = entry?.let { HealthEntryCodec.parse(it.secondary) }
    val taken = meta?.let { HealthEntryCodec.takenToday(it) } == true
    Card(shape = RoundedCornerShape(24.dp), elevation = CardDefaults.cardElevation(2.dp)) {
        Row(
            Modifier.background(Brush.horizontalGradient(listOf(Color(0xFFE9FFF5), Color(0xFFF4FFF9)))).fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(shape = RoundedCornerShape(22.dp), color = Color.White) {
                Icon(Icons.Default.Medication, null, tint = HealthRed, modifier = Modifier.padding(20.dp).size(54.dp))
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text("جرعة الدواء القادمة", fontSize = 20.sp, fontWeight = FontWeight.Black, color = HealthInk)
                if (entry == null) {
                    Text("لا توجد جرعة مسجلة", color = HealthMuted)
                } else {
                    Text(entry.title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(formatClock(entry.dueAt), fontSize = 24.sp, fontWeight = FontWeight.Black, color = HealthRed)
                    Button(
                        onClick = {
                            if (meta != null) {
                                val marked = HealthEntryCodec.markTakenToday(meta)
                                onUpdate(entry.copy(secondary = HealthEntryCodec.encode(marked), dueAt = HealthEntryCodec.nextMedicationTrigger(marked), completed = false))
                            }
                        },
                        enabled = !taken,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = HealthSoftGreen, contentColor = HealthGreen, disabledContainerColor = HealthSoftGreen, disabledContentColor = HealthGreen),
                    ) {
                        Icon(Icons.Default.CheckCircle, null)
                        Spacer(Modifier.size(6.dp))
                        Text(if (taken) "تم تناولها" else "تأكيد تناولها", fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }
}

@Composable
private fun MedicationRow(entry: AssistantEntry, onUpdate: (AssistantEntry) -> Unit, onDelete: (String) -> Unit) {
    val meta = HealthEntryCodec.parse(entry.secondary)
    val taken = HealthEntryCodec.takenToday(meta)
    val overdue = !taken && (entry.dueAt ?: Long.MAX_VALUE) < System.currentTimeMillis()
    Card(
        modifier = Modifier.fillMaxWidth().clickable {
            if (!taken) {
                val marked = HealthEntryCodec.markTakenToday(meta)
                onUpdate(entry.copy(secondary = HealthEntryCodec.encode(marked), dueAt = HealthEntryCodec.nextMedicationTrigger(marked)))
            }
        },
        shape = RoundedCornerShape(19.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(16.dp), color = if (overdue) HealthSoftRed else HealthSoftGreen) {
                Icon(Icons.Default.Medication, null, tint = if (overdue) HealthRed else HealthGreen, modifier = Modifier.padding(13.dp).size(32.dp))
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(entry.title, fontSize = 17.sp, fontWeight = FontWeight.Black)
                Text(entry.details.ifBlank { "جرعة مسجلة" }, color = HealthMuted, fontSize = 12.sp)
                Text(
                    if (taken) "✓ تم تناوله اليوم" else if (overdue) "لم يتم تناوله بعد" else "بانتظار موعد الجرعة",
                    color = if (taken) HealthGreen else if (overdue) HealthRed else Color(0xFFB7791F),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(formatClock(entry.dueAt), fontWeight = FontWeight.Black, fontSize = 17.sp)
                TextButton(onClick = { onDelete(entry.id) }) { Text("حذف", color = HealthMuted, fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun AppointmentCard(entry: AssistantEntry, onUpdate: (AssistantEntry) -> Unit, onDelete: (String) -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(1.dp)) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFFE8F3FF)) {
                Icon(Icons.Default.HealthAndSafety, null, tint = Color(0xFF2563EB), modifier = Modifier.padding(14.dp).size(36.dp))
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(entry.title, fontSize = 18.sp, fontWeight = FontWeight.Black)
                if (entry.details.isNotBlank()) Text(entry.details, color = HealthMuted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CalendarMonth, null, tint = Color(0xFF2563EB), modifier = Modifier.size(16.dp))
                    Text(formatDateTime(entry.dueAt), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                if (entry.completed) Text("✓ تم الموعد", color = HealthGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Column {
                OutlinedButton(onClick = { onUpdate(entry.copy(completed = !entry.completed, enabled = entry.completed)) }) {
                    Text(if (entry.completed) "إعادة" else "تم")
                }
                TextButton(onClick = { onDelete(entry.id) }) { Text("حذف", color = HealthMuted, fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun EmptyHealthCard(text: String) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Text(text, modifier = Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center, color = HealthMuted)
    }
}

@Composable
private fun AddMedicineDialog(owner: HealthOwner, onDismiss: () -> Unit, onSave: (String, String, LocalTime) -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var dose by remember { mutableStateOf("") }
    var time by remember { mutableStateOf(LocalTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة دواء", fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (owner == HealthOwner.SELF) "لـ: أنا" else "لـ: زوجتي", color = HealthMuted)
                OutlinedTextField(name, { name = it.take(100) }, label = { Text("اسم الدواء") }, singleLine = true)
                OutlinedTextField(dose, { dose = it.take(120) }, label = { Text("الجرعة – مثال: قرص واحد") }, singleLine = true)
                OutlinedButton(onClick = {
                    TimePickerDialog(context, { _, h, m -> time = LocalTime.of(h, m) }, time.hour, time.minute, false).show()
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Schedule, null)
                    Spacer(Modifier.size(7.dp))
                    Text("موعد الجرعة: ${time.format(DateTimeFormatter.ofPattern("hh:mm a", Locale("ar")))}")
                }
            }
        },
        confirmButton = { Button(onClick = { if (name.isNotBlank()) onSave(name.trim(), dose.trim(), time) }, enabled = name.isNotBlank()) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

@Composable
private fun AddAppointmentDialog(owner: HealthOwner, onDismiss: () -> Unit, onSave: (String, String, Long) -> Unit) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().plusDays(1)) }
    var time by remember { mutableStateOf(LocalTime.of(10, 0)) }
    val at = date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة موعد صحي", fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (owner == HealthOwner.SELF) "لـ: أنا" else "لـ: زوجتي", color = HealthMuted)
                OutlinedTextField(title, { title = it.take(100) }, label = { Text("نوع الموعد – مثال: موعد الطبيب") }, singleLine = true)
                OutlinedTextField(details, { details = it.take(180) }, label = { Text("المكان أو ملاحظات") }, singleLine = true)
                OutlinedButton(onClick = {
                    DatePickerDialog(context, { _, y, m, d -> date = LocalDate.of(y, m + 1, d) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
                }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.CalendarMonth, null); Spacer(Modifier.size(7.dp)); Text(date.toString()) }
                OutlinedButton(onClick = {
                    TimePickerDialog(context, { _, h, m -> time = LocalTime.of(h, m) }, time.hour, time.minute, false).show()
                }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Schedule, null); Spacer(Modifier.size(7.dp)); Text(time.format(DateTimeFormatter.ofPattern("hh:mm a", Locale("ar")))) }
            }
        },
        confirmButton = { Button(onClick = { if (title.isNotBlank()) onSave(title.trim(), details.trim(), at) }, enabled = title.isNotBlank()) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

private fun formatClock(value: Long?): String = value?.let {
    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("hh:mm a", Locale("ar")))
} ?: "--:--"

private fun formatDateTime(value: Long?): String = value?.let {
    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMMM، hh:mm a", Locale("ar")))
} ?: "بدون موعد"

private fun formatTodayArabic(): String {
    val now = LocalDate.now()
    return now.format(DateTimeFormatter.ofPattern("EEEE، d MMMM", Locale("ar")))
}
