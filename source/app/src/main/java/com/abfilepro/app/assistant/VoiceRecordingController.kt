package com.abfilepro.app.assistant

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * P19 (AS-12): the recording lives outside the screen.
 *
 * Before P19 the MediaRecorder belonged to the Compose screen: leaving the screen, rotating, a theme
 * change or the system recreating the activity deleted the recording in progress, and with no
 * foreground service the microphone went silent when the phone was locked. Now the recorder is
 * process-wide, kept alive by [VoiceRecordingService] (type "microphone"), and every finished file
 * gets a small sidecar so it can be listed again even if the app was closed before it was saved.
 * New files use AAC/ADTS, which stays playable up to the last written frame if the process dies.
 */
object VoiceRecordingController {
    enum class Phase { IDLE, RECORDING, PAUSED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val file: File? = null,
        val accumulatedMs: Long = 0L,
        val runningSince: Long = 0L,
        val quality: String = "normal",
    ) {
        fun elapsedMs(now: Long = SystemClock.elapsedRealtime()): Long =
            accumulatedMs + if (phase == Phase.RECORDING) now - runningSince else 0L
    }

    data class Finished(val file: File, val durationMs: Long, val quality: String)

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()
    private var recorder: MediaRecorder? = null

    fun recordingsDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "voice_recordings").apply { mkdirs() }

    @Synchronized
    fun start(context: Context, bitrate: Int, sampleRate: Int, quality: String): Boolean {
        if (mutableState.value.phase != Phase.IDLE) return false
        val app = context.applicationContext
        val file = File(recordingsDir(app), "AB_VOICE_${System.currentTimeMillis()}.aac")
        @Suppress("DEPRECATION")
        val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(app) else MediaRecorder()
        val ok = runCatching {
            created.setAudioSource(MediaRecorder.AudioSource.MIC)
            created.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            created.setAudioEncodingBitRate(bitrate)
            created.setAudioSamplingRate(sampleRate)
            created.setOutputFile(file.absolutePath)
            created.prepare()
            created.start()
        }.isSuccess
        if (!ok) {
            runCatching { created.release() }
            file.delete()
            return false
        }
        recorder = created
        mutableState.value = State(Phase.RECORDING, file, 0L, SystemClock.elapsedRealtime(), quality)
        writeSidecar(file, 0L, quality, finished = false)
        runCatching {
            ContextCompat.startForegroundService(app, Intent(app, VoiceRecordingService::class.java))
        }
        return true
    }

    @Synchronized
    fun pause(): Boolean {
        val current = mutableState.value
        if (current.phase != Phase.RECORDING) return false
        if (runCatching { recorder?.pause() }.isFailure) return false
        mutableState.value = current.copy(phase = Phase.PAUSED, accumulatedMs = current.elapsedMs(), runningSince = 0L)
        return true
    }

    @Synchronized
    fun resume(): Boolean {
        val current = mutableState.value
        if (current.phase != Phase.PAUSED) return false
        if (runCatching { recorder?.resume() }.isFailure) return false
        mutableState.value = current.copy(phase = Phase.RECORDING, runningSince = SystemClock.elapsedRealtime())
        return true
    }

    /** Stops and keeps the file. Returns null when nothing usable was recorded. */
    @Synchronized
    fun stop(context: Context): Finished? {
        val current = mutableState.value
        if (current.phase == Phase.IDLE) return null
        val duration = current.elapsedMs()
        val stopped = runCatching { recorder?.stop() }.isSuccess
        runCatching { recorder?.release() }
        recorder = null
        mutableState.value = State()
        stopService(context)
        val file = current.file ?: return null
        if (!file.isFile || file.length() == 0L || (!stopped && file.length() < 1024)) {
            file.delete(); sidecar(file).delete()
            return null
        }
        writeSidecar(file, duration, current.quality, finished = true)
        return Finished(file, duration, current.quality)
    }

    @Synchronized
    fun discard(context: Context) {
        val file = mutableState.value.file
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        mutableState.value = State()
        stopService(context)
        file?.delete()
        file?.let { sidecar(it).delete() }
    }

    /** Recordings on disk that no assistant entry points to (stopped from the notification, or app closed). */
    fun unsavedRecordings(context: Context, referencedNames: Set<String>): List<Finished> {
        val active = mutableState.value.file?.name
        return recordingsDir(context).listFiles().orEmpty()
            .filter { it.isFile && (it.extension == "aac" || it.extension == "m4a") && it.length() > 0L }
            .filter { it.name != active && it.name !in referencedNames }
            .sortedBy { it.lastModified() }
            .map { file ->
                val meta = runCatching { JSONObject(sidecar(file).readText()) }.getOrNull()
                Finished(file, meta?.optLong("durationMs", 0L) ?: 0L, meta?.optString("quality", "normal") ?: "normal")
            }
    }

    fun forgetSidecar(file: File) { sidecar(file).delete() }

    private fun sidecar(file: File) = File(file.parentFile, ".${file.name}.json")

    private fun writeSidecar(file: File, durationMs: Long, quality: String, finished: Boolean) {
        runCatching {
            sidecar(file).writeText(JSONObject().put("durationMs", durationMs).put("quality", quality).put("finished", finished).toString())
        }
    }

    private fun stopService(context: Context) {
        val app = context.applicationContext
        runCatching { app.stopService(Intent(app, VoiceRecordingService::class.java)) }
    }
}
