package com.carlist.pro.sync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
            val pingInterval = if (currentlyOffline) {
                PING_INTERVAL_RECOVERY_MS
            } else {
                PING_INTERVAL_ONLINE_MS
            }

            val shouldPing = lastPingAtMs == 0L || now - lastPingAtMs >= pingInterval

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

    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                stopSelf()
                return
            }
        }

        notificationManager = getSystemService(NotificationManager::class.java)
        networkMonitor = NetworkMonitor(applicationContext)
        syncRepository = FirebaseSyncRepository(applicationContext)

        createNotificationChannels()

        startForeground(
            FOREGROUND_NOTIFICATION_ID,
            buildForegroundNotification()
        )

        handler.post(monitorRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        when (intent?.action) {
            ACTION_START_MONITORING, null -> {
                updateForegroundNotification()
                handler.removeCallbacks(monitorRunnable)
                handler.post(monitorRunnable)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacks(monitorRunnable)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun scheduleNextMonitor() {
        if (destroyed) return

        val delay = when {
            pingInFlight -> MONITOR_INTERVAL_IN_FLIGHT_MS
            currentlyOffline -> MONITOR_INTERVAL_OFFLINE_MS
            else -> MONITOR_INTERVAL_ONLINE_MS
        }

        handler.removeCallbacks(monitorRunnable)
        handler.postDelayed(monitorRunnable, delay)
    }

    private fun applyOfflineState() {
        currentlyOffline = true
        updateForegroundNotification()
    }

    private fun applyOnlineState() {
        currentlyOffline = false
        updateForegroundNotification()
    }

    private fun updateForegroundNotification() {
        if (!::notificationManager.isInitialized) return

        notificationManager.notify(
            FOREGROUND_NOTIFICATION_ID,
            buildForegroundNotification()
        )
    }

    private fun buildForegroundNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("CarList PRO")
            .setContentText("Monitoring connection")
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
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

        notificationManager.createNotificationChannel(foregroundChannel)
    }

    companion object {
        const val ACTION_START_MONITORING = "com.carlist.pro.action.START_MONITORING"

        private const val FOREGROUND_CHANNEL_ID = "carlist_foreground_monitor"
        private const val FOREGROUND_NOTIFICATION_ID = 41001

        private const val MONITOR_INTERVAL_IN_FLIGHT_MS = 1_000L
        private const val MONITOR_INTERVAL_OFFLINE_MS = 3_000L
        private const val MONITOR_INTERVAL_ONLINE_MS = 8_000L

        private const val PING_INTERVAL_RECOVERY_MS = 4_000L
        private const val PING_INTERVAL_ONLINE_MS = 20_000L
    }
}