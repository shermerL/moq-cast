package com.example.moqandroid.publish

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.example.moqandroid.R
import kotlinx.coroutines.CompletableDeferred

class ScreenCaptureService : Service() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundService(
            relayUrl = intent?.getStringExtra(EXTRA_RELAY_URL).orEmpty(),
            broadcastName = intent?.getStringExtra(EXTRA_BROADCAST_NAME).orEmpty(),
        )
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        markStopped()
        super.onDestroy()
    }

    private fun startForegroundService(relayUrl: String, broadcastName: String) {
        val text = buildString {
            append("broadcast=")
            append(broadcastName.ifEmpty { "unknown" })
            if (relayUrl.isNotEmpty()) {
                append("\nrelay=")
                append(relayUrl)
            }
        }

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Moq screen publish")
            .setContentText("broadcast=${broadcastName.ifEmpty { "unknown" }}")
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_moq)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        markForeground()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen publish",
            NotificationManager.IMPORTANCE_LOW,
        )
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "moq_screen_publish"
        private const val EXTRA_RELAY_URL = "relay_url"
        private const val EXTRA_BROADCAST_NAME = "broadcast_name"
        private const val NOTIFICATION_ID = 1002

        @Volatile
        private var foregroundReady = CompletableDeferred<Unit>()

        fun start(context: Context, relayUrl: String, broadcastName: String) {
            foregroundReady = CompletableDeferred()
            val intent = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RELAY_URL, relayUrl)
                .putExtra(EXTRA_BROADCAST_NAME, broadcastName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            markStopped()
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }

        suspend fun awaitForeground() {
            foregroundReady.await()
        }

        private fun markForeground() {
            if (!foregroundReady.isCompleted) foregroundReady.complete(Unit)
        }

        private fun markStopped() {
            if (foregroundReady.isCompleted) foregroundReady = CompletableDeferred()
        }
    }
}
