package com.abfilepro.app.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.abfilepro.app.MainActivity
import com.abfilepro.app.R

/** P19: keeps the microphone recording alive while the screen is off or the app is in the background. */
class VoiceRecordingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // The finished file is kept and listed again the next time the recorder opens.
            VoiceRecordingController.stop(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (VoiceRecordingController.state.value.phase == VoiceRecordingController.Phase.IDLE) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel(this)
        val open = PendingIntent.getActivity(this, 7_701, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 7_702, Intent(this, VoiceRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ab_logo_icon)
            .setContentTitle(ReminderText.pick(this, "تسجيل صوتي جاري", "Recording in progress", "Enregistrement en cours"))
            .setContentText(ReminderText.pick(this, "التسجيل مستمر حتى لو قفلت الشاشة", "Keeps recording with the screen off", "Continue écran éteint"))
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis() - VoiceRecordingController.state.value.elapsedMs())
            .setContentIntent(open)
            .addAction(0, ReminderText.pick(this, "إيقاف وحفظ", "Stop and save", "Arrêter et enregistrer"), stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type) }
            .onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            ReminderText.pick(context, "التسجيل الصوتي", "Voice recording", "Enregistrement vocal"), NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        const val ACTION_STOP = "com.abfilepro.app.voice.STOP"
        private const val CHANNEL_ID = "assistant_voice_recording_v19"
        private const val NOTIFICATION_ID = 7_700
    }
}
