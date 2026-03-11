package com.carlist.pro.data.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.carlist.pro.domain.QueueItem
import com.carlist.pro.domain.sync.RemoteQueueSnapshot
import com.carlist.pro.domain.sync.SyncState
import com.google.firebase.firestore.FirebaseFirestore

class FirebaseSyncRepository(
    private val context: Context
) {

    interface Callback {
        fun onSyncStateChanged(state: SyncState)
        fun onRemoteSnapshot(snapshot: RemoteQueueSnapshot)
        fun onBlocked(reason: String)
    }

    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private val handler = Handler(Looper.getMainLooper())

    private var callback: Callback? = null
    private var started = false

    private val roomId: String = "default_room"

    private val deviceId: String by lazy {
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown-device"
    }

    private val connectRunnable = Runnable {
        if (!started) return@Runnable

        runCatching {
            firestore.collection("rooms").document(roomId)
        }.onSuccess {
            callback?.onSyncStateChanged(SyncState.OnlineFree)
        }.onFailure {
            callback?.onBlocked("Firebase initialization error")
            callback?.onSyncStateChanged(SyncState.Off)
        }
    }

    fun start(callback: Callback) {
        this.callback = callback

        if (started) {
            callback.onSyncStateChanged(SyncState.OnlineFree)
            return
        }

        started = true
        callback.onSyncStateChanged(SyncState.Connecting)

        handler.removeCallbacks(connectRunnable)
        handler.postDelayed(connectRunnable, 350L)
    }

    fun stop() {
        started = false
        handler.removeCallbacks(connectRunnable)
        callback = null
    }

    fun isStarted(): Boolean = started

    fun currentRoomId(): String = roomId

    fun currentDeviceId(): String = deviceId

    fun requestLatestSnapshot() {
        if (!started) return

        callback?.onRemoteSnapshot(
            RemoteQueueSnapshot(
                queue = emptyList(),
                authorNumber = null,
                updatedAtMs = System.currentTimeMillis(),
                offerExpiresAtMs = 0L,
                lockOwnerDeviceId = null,
                lockOwnerNumber = null,
                lockUntilMs = 0L
            )
        )
    }

    fun pushSnapshot(queue: List<QueueItem>) {
        if (!started) return

        // Реальная отправка в Firestore будет добавлена на следующем этапе.
        // Здесь оставлен безопасный каркас, чтобы проект уже собрался с Firebase.
        @Suppress("UNUSED_VARIABLE")
        val ignored = queue
    }
}