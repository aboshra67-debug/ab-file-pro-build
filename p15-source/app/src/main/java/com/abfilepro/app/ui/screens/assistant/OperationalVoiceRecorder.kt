package com.abfilepro.app.ui.screens.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.abfilepro.app.assistant.AssistantCategory
import com.abfilepro.app.assistant.AssistantEntry
import com.abfilepro.app.assistant.AssistantModule
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.TimeUnit

private enum class VoiceQuality(val labelAr: String, val labelEn: String, val bitrate: Int, val sampleRate: Int) {
    NORMAL("عادي", "Normal", 96_000, 44_100),
    HIGH("عالي", "High", 192_000, 48_000),
}

private enum class RecorderPhase { IDLE, RECORDING, PAUSED }

@Composable
internal fun OperationalVoiceRecorderScreen(
    entries: List<AssistantEntry>,
    defaultCategory: AssistantCategory,
    onBack: () -> Unit,
    onAdd: (String, String, AssistantCategory, Long?, Double?, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    var quality by remember { mutableStateOf(VoiceQuality.NORMAL) }
    var phase by remember { mutableStateOf(RecorderPhase.IDLE) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var currentFile by remember { mutableStateOf<File?>(null) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var runningSince by remember { mutableLongStateOf(0L) }
    var liveElapsedMs by remember { mutableLongStateOf(0L) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playingEntryId by remember { mutableStateOf<String?>(null) }

    fun qualityLabel(q: VoiceQuality): String = when (language.code) {
        "ar" -> q.labelAr
        else -> q.labelEn
    }

    fun releaseRecorder() {
        runCatching { recorder?.release() }
        recorder = null
    }

    fun startRecording() {
        if (phase != RecorderPhase.IDLE) return
        val dir = File(context.getExternalFilesDir(null), "voice_recordings").apply { mkdirs() }
        val file = File(dir, "AB_VOICE_${System.currentTimeMillis()}.m4a")
        val newRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(quality.bitrate)
            setAudioSamplingRate(quality.sampleRate)
            setOutputFile(file.absolutePath)
        }
        runCatching {
            newRecorder.prepare()
            newRecorder.start()
        }.onSuccess {
            recorder = newRecorder
            currentFile = file
            elapsedMs = 0L
            liveElapsedMs = 0L
            runningSince = SystemClock.elapsedRealtime()
            phase = RecorderPhase.RECORDING
        }.onFailure {
            runCatching { newRecorder.release() }
            file.delete()
            Toast.makeText(context, tr(language, "تعذر بدء التسجيل", "Could not start recording", "Impossible de démarrer"), Toast.LENGTH_LONG).show()
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording()
        else Toast.makeText(context, tr(language, "اسمح للتطبيق باستخدام الميكروفون لتشغيل التسجيل", "Allow microphone access to record", "Autorisez le microphone"), Toast.LENGTH_LONG).show()
    }

    fun requestStart() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun pauseRecording() {
        if (phase != RecorderPhase.RECORDING) return
        runCatching {
            recorder?.pause()
            elapsedMs += SystemClock.elapsedRealtime() - runningSince
            liveElapsedMs = elapsedMs
            phase = RecorderPhase.PAUSED
        }.onFailure {
            Toast.makeText(context, tr(language, "تعذر إيقاف التسجيل مؤقتًا", "Could not pause", "Pause impossible"), Toast.LENGTH_SHORT).show()
        }
    }

    fun resumeRecording() {
        if (phase != RecorderPhase.PAUSED) return
        runCatching {
            recorder?.resume()
            runningSince = SystemClock.elapsedRealtime()
            phase = RecorderPhase.RECORDING
        }.onFailure {
            Toast.makeText(context, tr(language, "تعذر استكمال التسجيل", "Could not resume", "Reprise impossible"), Toast.LENGTH_SHORT).show()
        }
    }

    fun stopRecording(save: Boolean = true) {
        if (phase == RecorderPhase.IDLE) return
        val total = elapsedMs + if (phase == RecorderPhase.RECORDING) SystemClock.elapsedRealtime() - runningSince else 0L
        val file = currentFile
        val stopped = runCatching { recorder?.stop() }.isSuccess
        releaseRecorder()
        phase = RecorderPhase.IDLE
        elapsedMs = 0L
        liveElapsedMs = 0L
        runningSince = 0L
        currentFile = null

        if (!stopped || file == null || !file.exists() || file.length() == 0L) {
            file?.delete()
            Toast.makeText(context, tr(language, "لم يتم حفظ التسجيل", "Recording was not saved", "Enregistrement non sauvegardé"), Toast.LENGTH_SHORT).show()
            return
        }
        if (!save) {
            file.delete()
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val title = tr(language, "تسجيل صوتي", "Voice recording", "Enregistrement vocal") + " " + formatDateTime(System.currentTimeMillis(), language.code)
        val details = "durationMs=$total;quality=${quality.name.lowercase()}"
        onAdd(title, details, defaultCategory, null, null, uri.toString())
        Toast.makeText(context, tr(language, "تم حفظ التسجيل", "Recording saved", "Enregistrement sauvegardé"), Toast.LENGTH_SHORT).show()
    }

    fun stopPlayback() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        playingEntryId = null
    }

    fun playEntry(entry: AssistantEntry) {
        if (playingEntryId == entry.id) {
            stopPlayback()
            return
        }
        stopPlayback()
        val uri = runCatching { Uri.parse(entry.secondary) }.getOrNull() ?: return
        runCatching {
            MediaPlayer().also { mp ->
                player = mp
                playingEntryId = entry.id
                mp.setDataSource(context, uri)
                mp.setOnCompletionListener { stopPlayback() }
                mp.setOnErrorListener { _, _, _ -> stopPlayback(); true }
                mp.prepare()
                mp.start()
            }
        }.onFailure {
            stopPlayback()
            Toast.makeText(context, tr(language, "تعذر تشغيل التسجيل", "Could not play recording", "Lecture impossible"), Toast.LENGTH_SHORT).show()
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        onAdd(
            tr(language, "تسجيل مستورد", "Imported recording", "Enregistrement importé") + " " + formatDateTime(System.currentTimeMillis(), language.code),
            "imported=true",
            defaultCategory,
            null,
            null,
            uri.toString(),
        )
    }

    LaunchedEffect(phase, runningSince) {
        while (phase == RecorderPhase.RECORDING) {
            liveElapsedMs = elapsedMs + (SystemClock.elapsedRealtime() - runningSince)
            delay(200)
        }
        if (phase == RecorderPhase.PAUSED) liveElapsedMs = elapsedMs
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { recorder?.stop() }
            releaseRecorder()
            currentFile?.delete()
            currentFile = null
            stopPlayback()
        }
    }

    ModuleScaffold(AssistantModule.VOICE, onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 14.dp, 16.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                VoiceHeroCard(
                    phase = phase,
                    elapsedMs = liveElapsedMs,
                    onStart = ::requestStart,
                    onPause = ::pauseRecording,
                    onResume = ::resumeRecording,
                    onStop = { stopRecording(true) },
                )
            }

            item {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(tr(language, "جودة التسجيل", "Recording quality", "Qualité d’enregistrement"), fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                            Text(tr(language, "اختر جودة الصوت المناسبة", "Choose the right audio quality", "Choisissez la qualité audio"), color = Color(0xFF8A94A6), fontSize = 12.sp)
                        }
                        VoiceQualityPill(
                            text = qualityLabel(VoiceQuality.NORMAL),
                            selected = quality == VoiceQuality.NORMAL,
                            enabled = phase == RecorderPhase.IDLE,
                            onClick = { quality = VoiceQuality.NORMAL },
                        )
                        VoiceQualityPill(
                            text = qualityLabel(VoiceQuality.HIGH),
                            selected = quality == VoiceQuality.HIGH,
                            enabled = phase == RecorderPhase.IDLE,
                            onClick = { quality = VoiceQuality.HIGH },
                        )
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr(language, "التسجيلات الأخيرة", "Recent recordings", "Enregistrements récents"), fontSize = 21.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                    Text("${entries.size}", color = Color(0xFF7C3AED), fontWeight = FontWeight.Bold)
                }
            }

            if (entries.isEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Mic, null, tint = Color(0xFF7C3AED), modifier = Modifier.size(42.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(tr(language, "ابدأ أول تسجيل صوتي", "Create your first recording", "Créez votre premier enregistrement"), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                items(entries, key = { it.id }) { entry ->
                    VoiceRecordingCard(
                        entry = entry,
                        isPlaying = playingEntryId == entry.id,
                        onPlay = { playEntry(entry) },
                        onShare = { shareAudio(context, entry.secondary) },
                        onDelete = {
                            if (playingEntryId == entry.id) stopPlayback()
                            deleteOwnRecordingIfPossible(context, entry.secondary)
                            onDelete(entry.id)
                        },
                    )
                }
            }

            item {
                OutlinedButton(
                    onClick = { importer.launch(arrayOf("audio/*")) },
                    modifier = Modifier.fillMaxWidth().height(66.dp),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Icon(Icons.Default.FileUpload, null, tint = Color(0xFFEF4444))
                    Spacer(Modifier.width(10.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(tr(language, "استيراد تسجيل", "Import recording", "Importer un enregistrement"), fontWeight = FontWeight.ExtraBold, color = Color(0xFF182033))
                        Text(tr(language, "من ملفات الجهاز", "From device files", "Depuis les fichiers"), fontSize = 11.sp, color = Color(0xFF8A94A6))
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceHeroCard(
    phase: RecorderPhase,
    elapsedMs: Long,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    val language = LocalAppLanguage.current
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xFFFFF6FA), Color(0xFFFFFBFD))))
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier.size(122.dp).clip(CircleShape).background(Color(0xFFF1E7FF)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Mic, null, tint = Color(0xFF7C3AED), modifier = Modifier.size(70.dp))
            }
            VoiceWaveform(active = phase == RecorderPhase.RECORDING)
            if (phase != RecorderPhase.IDLE) {
                Text(formatDuration(elapsedMs), fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color(0xFF111827))
            }
            when (phase) {
                RecorderPhase.IDLE -> {
                    Button(
                        onClick = onStart,
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF233C)),
                    ) {
                        Icon(Icons.Default.Mic, null)
                        Spacer(Modifier.width(9.dp))
                        Text(tr(language, "بدء التسجيل", "Start recording", "Démarrer"), fontWeight = FontWeight.Black, fontSize = 18.sp)
                    }
                }
                RecorderPhase.RECORDING, RecorderPhase.PAUSED -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = if (phase == RecorderPhase.RECORDING) onPause else onResume,
                            modifier = Modifier.weight(1f).height(54.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                        ) {
                            Icon(if (phase == RecorderPhase.RECORDING) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (phase == RecorderPhase.RECORDING) tr(language, "إيقاف مؤقت", "Pause", "Pause") else tr(language, "استكمال", "Resume", "Reprendre"), fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = onStop,
                            modifier = Modifier.weight(1f).height(54.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF233C)),
                        ) {
                            Icon(Icons.Default.Stop, null)
                            Spacer(Modifier.width(6.dp))
                            Text(tr(language, "حفظ وإيقاف", "Stop & save", "Arrêter et sauvegarder"), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceWaveform(active: Boolean) {
    val heights = if (active) listOf(20, 38, 62, 34, 52, 28, 60, 40, 22) else listOf(16, 30, 44, 24, 34, 22, 40, 28, 16)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        heights.forEachIndexed { index, h ->
            Box(
                Modifier.width(6.dp).height(h.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (index % 2 == 0) Color(0xFFFF4D67) else Color(0xFFEF233C).copy(alpha = if (active) 1f else .55f))
            )
        }
    }
}

@Composable
private fun VoiceQualityPill(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(18.dp),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 9.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF233C)),
        ) { Text(text, fontWeight = FontWeight.Bold) }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(18.dp),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 9.dp),
        ) { Text(text, fontWeight = FontWeight.Bold, color = Color(0xFF344054)) }
    }
}

@Composable
private fun VoiceRecordingCard(
    entry: AssistantEntry,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val duration = parseDuration(entry.details)
    val tint = when (entry.id.hashCode().absoluteMod(4)) {
        0 -> Color(0xFFE9FBF5)
        1 -> Color(0xFFEAF5FF)
        2 -> Color(0xFFF4EDFF)
        else -> Color(0xFFFFF7E8)
    }
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = tint)) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(shape = RoundedCornerShape(14.dp), color = Color.White.copy(alpha = .75f)) {
                Icon(Icons.Default.Mic, null, tint = Color(0xFF7C3AED), modifier = Modifier.padding(10.dp).size(26.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(entry.title, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (duration != null) Text(formatDuration(duration), fontSize = 12.sp, color = Color(0xFF667085))
            }
            FilledIconButton(onClick = onPlay, colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(containerColor = Color.White.copy(alpha = .82f))) {
                Icon(if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow, null, tint = Color(0xFF101828))
            }
            IconButton(onClick = onShare) { Icon(Icons.Default.Share, null, tint = Color(0xFF344054)) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, null, tint = Color(0xFFB42318)) }
        }
    }
}

private fun parseDuration(details: String): Long? = Regex("durationMs=(\\d+)").find(details)?.groupValues?.getOrNull(1)?.toLongOrNull()

private fun formatDuration(ms: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0L))
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

private fun shareAudio(context: Context, value: String) {
    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "audio/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
}

private fun deleteOwnRecordingIfPossible(context: Context, value: String) {
    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return
    if (uri.authority != "${context.packageName}.files") return
    val name = uri.lastPathSegment ?: return
    val dir = File(context.getExternalFilesDir(null), "voice_recordings")
    val file = File(dir, name)
    if (file.canonicalFile.parentFile == dir.canonicalFile) runCatching { file.delete() }
}

private fun Int.absoluteMod(divisor: Int): Int {
    val value = this % divisor
    return if (value < 0) value + divisor else value
}
