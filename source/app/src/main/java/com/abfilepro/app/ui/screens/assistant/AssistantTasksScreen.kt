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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.abfilepro.app.assistant.AssistantCategory
import com.abfilepro.app.assistant.AssistantEntry
import com.abfilepro.app.assistant.TaskAlarmScheduler
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

private enum class TaskTab { TODAY, UPCOMING, COMPLETED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssistantTasksScreen(
    entries: List<AssistantEntry>,
    defaultCategory: AssistantCategory,
    onBack: () -> Unit,
    onAddTask: (String, String, AssistantCategory, Long?, Boolean) -> Unit,
    onUpdate: (AssistantEntry) -> Unit,
    onDelete: (String) -> Unit,
) {
    var tab by remember { mutableStateOf(TaskTab.TODAY) }
    var editing by remember { mutableStateOf<AssistantEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val day = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val start = day.timeInMillis
    val end = start + 86_400_000L
    val visible = entries.filter {
        when (tab) {
            TaskTab.TODAY -> !it.completed && (it.dueAt == null || it.dueAt in start until end)
            TaskTab.UPCOMING -> !it.completed && (it.dueAt ?: 0L) >= end
            TaskTab.COMPLETED -> it.completed
        }
    }.sortedBy { it.dueAt ?: Long.MAX_VALUE }

    if (adding || editing != null) {
        TaskEditorDialog(
            initial = editing,
            defaultCategory = editing?.category ?: defaultCategory,
            onDismiss = { adding = false; editing = null },
            onSave = { title, details, category, dueAt, reminder ->
                val old = editing
                if (old == null) {
                    onAddTask(title, details, category, dueAt, reminder)
                } else {
                    onUpdate(
                        old.copy(
                            title = title.trim(),
                            details = details.trim(),
                            category = category,
                            dueAt = dueAt,
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
        containerColor = adaptSurface(Color(0xFFF7F8FC)),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(L("المهام والتنبيهات"), fontWeight = FontWeight.Black)
                        Text(L("نظّم يومك ولا تنسَ موعدًا"), fontSize = 12.sp, color = adaptInk(Color(0xFF64748B)))
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L("رجوع")) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = adaptSurface(Color.White)),
            )
        },
        floatingActionButton = {
            AssistantExtendedFabButton(
                text = L("إضافة مهمة"),
                icon = Icons.Default.Add,
                onClick = { adding = true },
                containerColor = Color(0xFFEF5350),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TaskSegmentTabs(
                current = tab,
                onSelect = { tab = it },
            )
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (tab == TaskTab.COMPLETED) "لا توجد مهام مكتملة" else "لا توجد مهام هنا\nاضغط + لإضافة مهمة",
                        color = adaptInk(Color(0xFF64748B)),
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(visible, key = { it.id }) { task ->
                        Card(colors = CardDefaults.cardColors(containerColor = adaptSurface(Color.White)), shape = RoundedCornerShape(20.dp)) {
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Top) {
                                IconButton(onClick = { onUpdate(task.copy(completed = !task.completed)) }) {
                                    Icon(
                                        if (task.completed) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                        "الحالة",
                                        tint = if (task.completed) adaptInk(Color(0xFF0F9D72)) else adaptInk(Color(0xFF94A3B8)),
                                    )
                                }
                                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                    Text(task.title, fontWeight = FontWeight.Bold, color = if (task.completed) adaptInk(Color(0xFF94A3B8)) else adaptInk(Color(0xFF1E293B)))
                                    if (task.details.isNotBlank()) Text(task.details, fontSize = 13.sp, color = adaptInk(Color(0xFF64748B)))
                                    task.dueAt?.let {
                                        Text(
                                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)),
                                            fontSize = 12.sp,
                                            color = adaptInk(Color(0xFF3F7AE0)),
                                        )
                                    }
                                    if (!task.completed && task.enabled && task.dueAt != null) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.NotificationsActive, null, Modifier.size(15.dp), tint = adaptInk(Color(0xFFEF5350)))
                                            Spacer(Modifier.width(4.dp))
                                            Text(L("التنبيه مفعّل"), fontSize = 11.sp, color = adaptInk(Color(0xFFEF5350)))
                                        }
                                    }
                                }
                                Column {
                                    IconButton(onClick = { editing = task }) { Icon(Icons.Default.Edit, L("تعديل"), tint = adaptInk(Color(0xFF3F7AE0))) }
                                    IconButton(onClick = { onDelete(task.id) }) { Icon(Icons.Default.DeleteOutline, L("حذف"), tint = adaptInk(Color(0xFFE57373))) }
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@Composable
private fun TaskSegmentTabs(
    current: TaskTab,
    onSelect: (TaskTab) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        listOf(
            TaskTab.TODAY to L("اليوم"),
            TaskTab.UPCOMING to L("القادمة"),
            TaskTab.COMPLETED to L("المكتملة"),
        ).forEach { (tab, label) ->
            AssistantPillTabButton(
                label = label,
                selected = current == tab,
                onClick = { onSelect(tab) },
                modifier = Modifier.weight(1f),
                selectedContainerColor = adaptSurface(Color(0xFFECE7F6)),
                selectedContentColor = adaptInk(Color(0xFF071124)),
                unselectedContainerColor = adaptSurface(Color.White),
                unselectedContentColor = adaptInk(Color(0xFF071124)),
                showCheckmark = true,
            )
        }
    }
}

@Composable
private fun TaskEditorDialog(
    initial: AssistantEntry?,
    defaultCategory: AssistantCategory,
    onDismiss: () -> Unit,
    onSave: (String, String, AssistantCategory, Long?, Boolean) -> Unit,
) {
    val context = LocalContext.current
    var title by remember(initial) { mutableStateOf(initial?.title.orEmpty()) }
    var details by remember(initial) { mutableStateOf(initial?.details.orEmpty()) }
    var category by remember(initial) { mutableStateOf(initial?.category ?: defaultCategory) }
    var dueAt by remember(initial) { mutableStateOf(initial?.dueAt ?: (System.currentTimeMillis() + 3_600_000L)) }
    var reminder by remember(initial) { mutableStateOf((initial?.enabled ?: true) && hasNotificationPermission(context) && TaskAlarmScheduler.hasExactAlarmAccess(context)) }
    var permissionMessage by remember(initial) { mutableStateOf("") }

    val exactAlarmLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val exactGranted = TaskAlarmScheduler.hasExactAlarmAccess(context)
        reminder = exactGranted && hasNotificationPermission(context)
        permissionMessage = if (reminder) "" else "فعّل السماح بالمنبهات والتذكيرات الدقيقة لتشغيل التنبيه."
    }

    fun requestExactAlarmAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || TaskAlarmScheduler.hasExactAlarmAccess(context)) {
            reminder = true
            permissionMessage = ""
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        runCatching { exactAlarmLauncher.launch(intent) }.onFailure {
            permissionMessage = L("افتح إعدادات التطبيق واسمح بالمنبهات والتذكيرات الدقيقة.")
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            )
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestExactAlarmAccess()
        else {
            reminder = false
            permissionMessage = L("لا يمكن تشغيل التنبيه بدون السماح بالإشعارات.")
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "إضافة مهمة" else "تعديل المهمة", fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it.take(240) }, label = { Text(L("اسم المهمة")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(details, { details = it.take(4000) }, label = { Text(L("تفاصيل (اختياري)")) }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(category == AssistantCategory.PERSONAL, { category = AssistantCategory.PERSONAL }, { Text(L("شخصي")) })
                    FilterChip(category == AssistantCategory.WORK, { category = AssistantCategory.WORK }, { Text(L("عمل")) })
                }
                OutlinedButton(onClick = { pickTaskDateTime(context, dueAt) { dueAt = it } }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Schedule, null)
                    Spacer(Modifier.width(6.dp))
                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(dueAt)))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.NotificationsActive, null, tint = adaptInk(Color(0xFFEF5350)))
                    Spacer(Modifier.width(8.dp))
                    Text(L("تنبيه في موعد المهمة"), Modifier.weight(1f))
                    Switch(
                        reminder,
                        { checked ->
                            if (!checked) {
                                reminder = false
                                permissionMessage = ""
                            } else if (!hasNotificationPermission(context)) {
                                if (Build.VERSION.SDK_INT >= 33) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else requestExactAlarmAccess()
                            } else {
                                requestExactAlarmAccess()
                            }
                        },
                    )
                }
                if (permissionMessage.isNotBlank()) {
                    Text(permissionMessage, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val usableReminder = reminder && hasNotificationPermission(context) && TaskAlarmScheduler.hasExactAlarmAccess(context)
                    onSave(title, details, category, dueAt, usableReminder)
                },
                enabled = title.isNotBlank(),
            ) { Text(L("حفظ")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("إلغاء")) } },
    )
}

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun pickTaskDateTime(context: Context, initial: Long, selected: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = initial }
    DatePickerDialog(
        context,
        { _, y, m, d ->
            TimePickerDialog(
                context,
                { _, h, min ->
                    selected(
                        Calendar.getInstance().apply {
                            set(y, m, d, h, min, 0)
                            set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                    )
                },
                c.get(Calendar.HOUR_OF_DAY),
                c.get(Calendar.MINUTE),
                true,
            ).show()
        },
        c.get(Calendar.YEAR),
        c.get(Calendar.MONTH),
        c.get(Calendar.DAY_OF_MONTH),
    ).show()
}
