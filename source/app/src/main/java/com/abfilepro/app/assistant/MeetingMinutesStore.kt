package com.abfilepro.app.assistant

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small private store for photographed meeting minutes.
 * Files stay inside the app's private files directory and are exposed only through FileProvider.
 */
class MeetingMinutesStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val root = File(appContext.filesDir, "meeting_minutes").apply { mkdirs() }
    private var pendingFile: File? = null

    fun createCaptureUri(meetingId: String): Uri {
        val meetingDir = File(root, safeId(meetingId)).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val file = File(meetingDir, "minutes_$stamp.jpg")
        pendingFile = file
        return FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", file)
    }

    fun commitCapture(meetingId: String): String? {
        val file = pendingFile?.takeIf { it.exists() && it.length() > 0L } ?: return null
        pendingFile = null
        val current = loadPaths(meetingId).toMutableList()
        if (file.absolutePath !in current) current.add(0, file.absolutePath)
        savePaths(meetingId, current)
        return file.absolutePath
    }

    fun discardPending() {
        pendingFile?.let { runCatching { it.delete() } }
        pendingFile = null
    }

    fun loadPaths(meetingId: String): List<String> = loadPathArray(key(meetingId))

    private fun loadPathArray(prefKey: String): List<String> = runCatching {
        val raw = prefs.getString(prefKey, null) ?: return emptyList()
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val path = array.optString(index)
                if (path.isNotBlank() && File(path).exists()) add(path)
            }
        }
    }.getOrDefault(emptyList())

    fun uriForPath(path: String): Uri =
        FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", File(path))

    fun saveNote(meetingId: String, note: String) {
        prefs.edit().putString(noteKey(meetingId), note.trim()).apply()
    }

    fun loadNote(meetingId: String): String = prefs.getString(noteKey(meetingId), "").orEmpty()

    fun importAudio(meetingId: String, source: Uri): String? = runCatching {
        val meetingDir = File(root, safeId(meetingId)).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val file = File(meetingDir, "recording_$stamp.m4a")
        appContext.contentResolver.openInputStream(source)?.use { input ->
            FileOutputStream(file).use { output -> input.copyTo(output) }
        } ?: return null
        if (!file.exists() || file.length() == 0L) return null
        val current = loadAudioPaths(meetingId).toMutableList()
        current.add(0, file.absolutePath)
        saveAudioPaths(meetingId, current)
        file.absolutePath
    }.getOrNull()

    fun loadAudioPaths(meetingId: String): List<String> = loadPathArray(audioKey(meetingId))

    fun deleteMeeting(meetingId: String) {
        runCatching { File(root, safeId(meetingId)).deleteRecursively() }
        prefs.edit()
            .remove(key(meetingId))
            .remove(noteKey(meetingId))
            .remove(audioKey(meetingId))
            .apply()
    }

    fun audioUriForPath(path: String): Uri = uriForPath(path)

    private fun savePaths(meetingId: String, paths: List<String>) {
        val array = JSONArray()
        paths.take(MAX_IMAGES_PER_MEETING).forEach(array::put)
        prefs.edit().putString(key(meetingId), array.toString()).apply()
    }

    private fun saveAudioPaths(meetingId: String, paths: List<String>) {
        val array = JSONArray()
        paths.take(MAX_AUDIO_PER_MEETING).forEach(array::put)
        prefs.edit().putString(audioKey(meetingId), array.toString()).apply()
    }

    private fun key(meetingId: String) = "meeting_${safeId(meetingId)}"
    private fun noteKey(meetingId: String) = "note_${safeId(meetingId)}"
    private fun audioKey(meetingId: String) = "audio_${safeId(meetingId)}"
    private fun safeId(value: String) = value.replace(Regex("[^A-Za-z0-9_-]"), "_").take(120)

    companion object {
        private const val PREFS_NAME = "ab_meeting_minutes"
        private const val MAX_IMAGES_PER_MEETING = 40
        private const val MAX_AUDIO_PER_MEETING = 20
    }
}
