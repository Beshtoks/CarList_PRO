package com.carlist.pro.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import com.carlist.pro.R
import com.carlist.pro.data.sync.FirebaseSyncRepository
import com.carlist.pro.data.sync.NetworkMonitor
import com.carlist.pro.ui.MainActivity

class SyncForegroundService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var syncRepository: FirebaseSyncRepository
    private lateinit var notificationManager: NotificationManager

    private var destroyed = false
    private var pingInFlight = false
    private var lastPingAtMs = 0L
    private var currentlyOffline = false
    private var alertAcknowledged = false

    private val monitorRunnable = object : Runnable {
        override fun run() {
            if (destroyed) return

            val systemOnline = networkMonitor.isCurrentlyOnline()
            if (!systemOnline) {
                applyOfflineState()
                scheduleNextMonitor()
                return
            }

            val now = System.currentTimeMillis()
            val shouldPing = !currentlyOffline || now - lastPingAtMs >= PING_INTERVAL_MS

            if (!shouldPing) {
                applyOnlineState()
                scheduleNextMonitor()
                return
            }

            if (pingInFlight) {
                scheduleNextMonitor()
                return
            }

            pingInFlight = true
            syncRepository.pingServer { ok ->
                pingInFlight = false
                lastPingAtMs = System.currentTimeMillis()

                if (ok) {
                    applyOnlineState()
                } else {
                    applyOfflineState()
                }

                scheduleNextMonitor()
            }
        }
    }

    private val vibrationLoopRunnable = object : Runnable {
        override fun run() {
            if (destroyed || !currentlyOffline || alertAcknowledged) return
            vibratePulse()
            handler.postDelayed(this, VIBRATION_CYCLE_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()

        notificationManager = getSystemService(NotificationManager::class.java)
        networkMonitor = NetworkMonitor(applicationContext)
        syncRepository = FirebaseSyncRepository(applicationContext)

        createNotificationChannels()
        startForeground(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification(offline = false))
        handler.post(monitorRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SILENCE_ALERT -> {
                alertAcknowledged = true
                stopVibration()
                notificationManager.cancel(ALERT_NOTIFICATION_ID)
                updateForegroundNotification()
            }

            ACTION_START_MONITORING, null -> {
                updateForegroundNotification()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacks(monitorRunnable)
        handler.removeCallbacks(vibrationLoopRunnable)
        stopVibration()
        notificationManager.cancel(ALERT_NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun scheduleNextMonitor() {
        if (destroyed) return
        handler.removeCallbacks(monitorRunnable)
        handler.postDelayed(monitorRunnable, MONITOR_INTERVAL_MS)
    }

    private fun applyOfflineState() {
        val wasOffline = currentlyOffline
        currentlyOffline = true

        if (!wasOffline) {
            alertAcknowledged = false
            showAlertNotification()
            startVibrationLoop()
        }

        updateForegroundNotification()
    }

    private fun applyOnlineState() {
        val wasOffline = currentlyOffline
        currentlyOffline = false
        alertAcknowledged = false

        if (wasOffline) {
            handler.removeCallbacks(vibrationLoopRunnable)
            stopVibration()
            notificationManager.cancel(ALERT_NOTIFICATION_ID)
        }

        updateForegroundNotification()
    }

    private fun updateForegroundNotification() {
        notificationManager.notify(
            FOREGROUND_NOTIFICATION_ID,
            buildForegroundNotification(offline = currentlyOffline)
        )
    }

    private fun buildForegroundNotification(offline: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = if (offline) {
            "No network connection"
        } else {
            "Monitoring connection"
        }

        return NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("CarList PRO")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showAlertNotification() {
        val openAppIntent = PendingIntent.getActivity(
            this,
            101,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val silenceIntent = PendingIntent.getService(
            this,
            102,
            Intent(this, SyncForegroundService::class.java).apply {
                action = ACTION_SILENCE_ALERT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("CarList PRO")
            .setContentText("No network connection")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("No network connection")
            )
            .setAutoCancel(false)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .addAction(
                android.R.drawable.ic_lock_silent_mode,
                "Silence",
                silenceIntent
            )
            .build()

        notificationManager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun startVibrationLoop() {
        handler.removeCallbacks(vibrationLoopRunnable)
        handler.post(vibrationLoopRunnable)
    }

    private fun vibratePulse() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(Vibrator::class.java)
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    VIBRATION_PULSE_MS,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(VIBRATION_PULSE_MS)
        }
    }

    private fun stopVibration() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(Vibrator::class.java)
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        vibrator.cancel()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val foregroundChannel = NotificationChannel(
            FOREGROUND_CHANNEL_ID,
            "CarList PRO Background Monitor",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Foreground background monitoring"
            setSound(null, null)
            enableVibration(false)
        }

        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "CarList PRO Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Connection loss alerts"
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }

        notificationManager.createNotificationChannel(foregroundChannel)
        notificationManager.createNotificationChannel(alertChannel)
    }

    companion object {
        const val ACTION_START_MONITORING = "com.carlist.pro.action.START_MONITORING"
        const val ACTION_SILENCE_ALERT = "com.carlist.pro.action.SILENCE_ALERT"

        private const val FOREGROUND_CHANNEL_ID = "carlist_foreground_monitor"
        private const val ALERT_CHANNEL_ID = "carlist_alerts"

        private const val FOREGROUND_NOTIFICATION_ID = 41001
        private const val ALERT_NOTIFICATION_ID = 41002

        private const val MONITOR_INTERVAL_MS = 1_000L
        private const val PING_INTERVAL_MS = 3_000L

        private const val VIBRATION_PULSE_MS = 3_000L
        private const val VIBRATION_CYCLE_MS = 4_000L
    }
}