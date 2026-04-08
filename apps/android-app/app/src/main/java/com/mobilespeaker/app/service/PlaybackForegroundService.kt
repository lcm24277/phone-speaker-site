package com.mobilespeaker.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.mobilespeaker.app.diagnostics.CrashDiagnostics
import com.mobilespeaker.app.stream.StreamRuntime

class PlaybackForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        CrashDiagnostics.recordEvent(category = "service", action = "foreground_onCreate")
        runCatching {
            ensureChannel()
            startForeground(NOTIFY_ID, buildNotification("Mobile Speaker 正在播放"))
            StreamRuntime.get(this).startForegroundPlayback()
        }.onFailure {
            CrashDiagnostics.captureHandledException("PlaybackForegroundService.onCreate", it)
            throw it
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CrashDiagnostics.recordEvent(category = "service", action = "foreground_onStartCommand")
        return START_STICKY
    }

    override fun onDestroy() {
        CrashDiagnostics.recordEvent(category = "service", action = "foreground_onDestroy")
        runCatching {
            StreamRuntime.get(this).stopForegroundPlayback()
        }.onFailure {
            CrashDiagnostics.captureHandledException("PlaybackForegroundService.onDestroy", it)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Mobile Speaker Playback",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                Notification.Builder(this)
            }
        return builder
            .setContentTitle("Mobile Speaker")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "mobile_speaker_playback"
        private const val NOTIFY_ID = 18732
    }
}
