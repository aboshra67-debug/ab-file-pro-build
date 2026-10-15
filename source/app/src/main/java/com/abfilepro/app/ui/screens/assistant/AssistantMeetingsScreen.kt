package com.abfilepro.app.ui.screens.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.core.content.ContextCompat
import com.abfilepro.app.assistant.AssistantCategory
import com.abfilepro.app.assistant.AssistantEntry
import com.abfilepro.app.assistant.AssistantModule
import com.abfilepro.app.assistant.MeetingMinutesStore
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import java.io.File

@Composable
internal fun AssistantMeetingsScreen(
    entries: List<AssistantEntry>,
    defaultCategory: AssistantCategory,
    onBack: () -> Unit,
    onAdd: (String, String, AssistantCategory, Long?, Double?, String) -> Unit,
    onUpdate: (AssistantEntry) -> Unit,
    onDelete: (String) -> Unit,
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val store = remember { MeetingMinutesStore(context) }
    var showEditor by remember { mutableStateOf(false) }
    var editingMeeting by remember { mutableStateOf<AssistantEntry?>(null) }
    var targetMeeting by remember { mutableStateOf<AssistantEntry?>(null) }
    var attachmentRefresh by remember { mutableStateOf(0) }

    val recordAudio = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val meeting = targetMeeting
        val uri = result.data?.data
        if (meeting != null && uri != null && store.importAudio(meeting.id, uri) != null) {
            attachmentRefresh++
            Toast.makeText(context, if (language.code == "ar") "تم حفظ التسجيل داخل الاجتماع" else "Recording saved to meeting", Toast.LENGTH_SHORT).show()
        }
        targetMeeting = null
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val meeting = targetMeeting
        if (success && meeting != null && store.commitCapture(meeting.id) != null) {
            attachmentRefresh++
            Toast.makeText(context, if (language.code == "ar") "تم حفظ صورة المحضر" else "Minutes photo saved", Toast.LENGTH_SHORT).show()
        } else {
            store.discardPending()
        }
        targetMeeting = null
    }

    fun launchCamera(meeting: AssistantEntry) {
        targetMeeting = meeting
        runCatching { takePicture.launch(store.createCaptureUri(meeting.id)) }
            .onFailure {
                store.discardPending()
                targetMeeting = null
                Toast.makeText(context, if (language.code == "ar") "تعذر فتح الكاميرا" else "Could not open camera", Toast.LENGTH_SHORT).show()
            }
    }

    val requestCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val meeting = targetMeeting
        if (granted && meeting != null) launchCamera(meeting)
        else if (!granted) {
            targetMeeting = null
            Toast.makeText(context, if (language.code == "ar") "اسمح للكاميرا لتصوير المحضر" else "Camera permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    fun captureMinutes(meeting: AssistantEntry) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera(meeting)
        } else {
            targetMeeting = meeting
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    fun recordMeeting(meeting: AssistantEntry) {
        val intent = Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
        if (intent.resolveActivity(context.packageManager) != null) {
            targetMeeting = meeting
            recordAudio.launch(intent)
        } else {
            Toast.makeText(context, if (language.code == "ar") "لا يوجد مسجل صوت مثبت" else "No recorder app is installed", Toast.LENGTH_LONG).show()
        }
    }

    if (showEditor) {
        EntryEditorDialog(
            module = AssistantModule.MEETINGS,
            defaultCategory = defaultCategory,
            showDue = true,
            showAmount = false,
            initialEntry = editingMeeting,
            onDismiss = { showEditor = false; editingMeeting = null },
            onSave = { title, details, category, dueAt, amount, secondary ->
                val existing = editingMeeting
                if (existing == null) {
                    onAdd(title, details, category, dueAt, amount, secondary)
                } else {
                    onUpdate(existing.copy(title = title.trim(), details = details.trim(), category = category, dueAt = dueAt))
                }
                showEditor = false
                editingMeeting = null
            },
        )
    }

    val now = System.currentTimeMillis()
    val upcoming = entries.filter { (it.dueAt ?: Long.MAX_VALUE) >= now }.sortedBy { it.dueAt ?: Long.MAX_VALUE }
    val previous = entries.filter { it.dueAt != null && it.dueAt < now }.sortedByDescending { it.dueAt }

    ModuleScaffold(
        module = AssistantModule.MEETINGS,
        onBack = onBack,
        floatingActionButton = {
            FloatingActionButton(onClick = { editingMeeting = null; showEditor = true }, containerColor = Color(0xFFE62E3B)) {
                Icon(Icons.Default.Add, null, tint = Color.White)
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1F3)),
                ) {
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        Text(if (language.code == "ar") "الاجتماعات والمحاضر" else "Meetings & minutes", fontSize = 24.sp, fontWeight = FontWeight.Black)
                        Text(
                            if (language.code == "ar") "أضف اجتماعًا، ثم صوّر المحضر من داخل الاجتماع واحفظه مرتبطًا به." else "Add a meeting, then photograph and keep its minutes attached.",
                            color = Color(0xFF64748B), fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { editingMeeting = null; showEditor = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE62E3B)),
                        ) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text(if (language.code == "ar") "إضافة اجتماع" else "Add meeting") }
                    }
                }
            }

            item { SectionTitle(if (language.code == "ar") "الاجتماعات القادمة" else "Upcoming meetings", upcoming.size) }
            if (upcoming.isEmpty()) item { EmptyMeetingCard(if (language.code == "ar") "لا توجد اجتماعات قادمة" else "No upcoming meetings") }
            items(upcoming, key = { "u_${it.id}" }) { meeting ->
                MeetingOperationalCard(meeting, store, attachmentRefresh, { captureMinutes(meeting) }, { recordMeeting(meeting) }, { editingMeeting = meeting; showEditor = true }, { store.deleteMeeting(meeting.id); onDelete(meeting.id) }, language.code)
            }

            item { SectionTitle(if (language.code == "ar") "محاضر سابقة" else "Previous minutes", previous.size) }
            if (previous.isEmpty()) item { EmptyMeetingCard(if (language.code == "ar") "لا توجد محاضر سابقة" else "No previous minutes") }
            items(previous, key = { "p_${it.id}" }) { meeting ->
                MeetingOperationalCard(meeting, store, attachmentRefresh, { captureMinutes(meeting) }, { recordMeeting(meeting) }, { editingMeeting = meeting; showEditor = true }, { store.deleteMeeting(meeting.id); onDelete(meeting.id) }, language.code)
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun SectionTitle(title: String, count: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Black)
        Text(count.toString(), color = Color(0xFF64748B), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EmptyMeetingCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
        Text(text, modifier = Modifier.fillMaxWidth().padding(18.dp), color = Color(0xFF64748B))
    }
}

@Composable
private fun MeetingOperationalCard(
    meeting: AssistantEntry,
    store: MeetingMinutesStore,
    refresh: Int,
    onCapture: () -> Unit,
    onRecord: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    languageCode: String,
) {
    val context = LocalContext.current
    val attachments = remember(meeting.id, refresh) { store.loadPaths(meeting.id) }
    val recordings = remember(meeting.id, refresh) { store.loadAudioPaths(meeting.id) }
    var note by remember(meeting.id, refresh) { mutableStateOf(store.loadNote(meeting.id)) }
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Event, null, tint = Color(0xFF3F7AE0))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(meeting.title, fontWeight = FontWeight.Black, fontSize = 17.sp)
                    meeting.dueAt?.let { Text(formatDateTime(it, languageCode), color = Color(0xFF3F7AE0), fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
            if (meeting.details.isNotBlank()) Text(meeting.details, color = Color(0xFF64748B), fontSize = 13.sp)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, if (languageCode == "ar") "تعديل الاجتماع" else "Edit meeting", tint = Color(0xFF3F7AE0)) }
                IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, if (languageCode == "ar") "حذف الاجتماع" else "Delete meeting", tint = Color(0xFFE62E3B)) }
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 7,
                label = { Text(if (languageCode == "ar") "نوّتة / محضر الاجتماع" else "Meeting notes") },
                placeholder = { Text(if (languageCode == "ar") "اكتب ملاحظات الاجتماع هنا..." else "Write meeting notes here...") },
            )
            Button(
                onClick = {
                    store.saveNote(meeting.id, note)
                    Toast.makeText(context, if (languageCode == "ar") "تم حفظ النوّتة" else "Notes saved", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF173B65)),
            ) {
                Icon(Icons.Default.Save, null); Spacer(Modifier.width(6.dp)); Text(if (languageCode == "ar") "حفظ النوّتة" else "Save notes")
            }

            Button(
                onClick = onRecord,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
            ) {
                Icon(Icons.Default.Mic, null); Spacer(Modifier.width(6.dp)); Text(if (languageCode == "ar") "بدء تسجيل الاجتماع" else "Record meeting")
            }
            recordings.forEachIndexed { index, path ->
                Button(
                    onClick = { openAudio(context, store.audioUriForPath(path)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F9D72)),
                ) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (languageCode == "ar") "تشغيل تسجيل ${index + 1}" else "Play recording ${index + 1}")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCapture, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE62E3B))) {
                    Icon(Icons.Default.CameraAlt, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (languageCode == "ar") "تصوير المحضر" else "Photograph minutes")
                }
            }
            attachments.forEachIndexed { index, path ->
                Button(
                    onClick = { openImage(context, store.uriForPath(path)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEEF7FF), contentColor = Color(0xFF173B65)),
                ) {
                    Icon(Icons.Default.OpenInNew, null); Spacer(Modifier.width(6.dp)); Text(if (languageCode == "ar") "فتح صورة المحضر ${index + 1}" else "Open minutes photo ${index + 1}")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Description, null, tint = Color(0xFF0F9D72))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (languageCode == "ar") "صور المحضر: ${attachments.size}" else "Minutes photos: ${attachments.size}",
                    color = Color(0xFF475569), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private fun openImage(context: android.content.Context, uri: Uri) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/jpeg")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }.onFailure {
        Toast.makeText(context, "تعذر فتح صورة المحضر", Toast.LENGTH_SHORT).show()
    }
}


private fun openAudio(context: android.content.Context, uri: Uri) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "audio/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }.onFailure {
        Toast.makeText(context, "تعذر تشغيل التسجيل", Toast.LENGTH_SHORT).show()
    }
}
