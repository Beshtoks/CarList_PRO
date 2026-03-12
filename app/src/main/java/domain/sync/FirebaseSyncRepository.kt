package com.carlist.pro.data.sync

import android.content.Context
import android.provider.Settings
import com.carlist.pro.domain.QueueItem
import com.carlist.pro.domain.Status
import com.carlist.pro.domain.sync.RemoteQueueSnapshot
import com.carlist.pro.domain.sync.SyncState
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions

class FirebaseSyncRepository(
    private val context: Context
) {

    interface Callback {
        fun onSyncStateChanged(state: SyncState)
        fun onRemoteSnapshot(snapshot: RemoteQueueSnapshot)
        fun onBlocked(reason: String)
    }

    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private val roomRef by lazy { firestore.collection(ROOMS_COLLECTION).document(DEFAULT_ROOM_ID) }

    private var callback: Callback? = null
    private var started = false
    private var listenerRegistration: ListenerRegistration? = null

    private var latestSnapshot: RemoteQueueSnapshot = RemoteQueueSnapshot()

    private val deviceId: String by lazy {
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown-device"
    }

    fun start(callback: Callback) {
        this.callback = callback

        if (started) {
            callback.onSyncStateChanged(resolveState(latestSnapshot))
            return
        }

        started = true
        callback.onSyncStateChanged(SyncState.Connecting)

        ensureRoomExists()
        attachListener()
    }

    fun stop() {
        releaseOwnLock()

        listenerRegistration?.remove()
        listenerRegistration = null

        started = false
        callback = null
        latestSnapshot = RemoteQueueSnapshot()
    }

    fun isStarted(): Boolean = started

    fun currentRoomId(): String = DEFAULT_ROOM_ID

    fun currentDeviceId(): String = deviceId

    fun isEditableNow(): Boolean {
        val now = System.currentTimeMillis()
        val owner = latestSnapshot.lockOwnerDeviceId
        val lockUntil = latestSnapshot.lockUntilMs
        return owner.isNullOrBlank() || lockUntil <= now || owner == deviceId
    }

    fun requestLatestSnapshot() {
        if (!started) return

        roomRef.get()
            .addOnSuccessListener { document ->
                val snapshot = parseSnapshot(document)
                latestSnapshot = snapshot
                callback?.onRemoteSnapshot(snapshot)
                callback?.onSyncStateChanged(resolveState(snapshot))
            }
            .addOnFailureListener {
                callback?.onBlocked("Не удалось прочитать список с сервера.")
            }
    }

    fun pingServer(onResult: (Boolean) -> Unit) {
        roomRef.get()
            .addOnSuccessListener {
                onResult(true)
            }
            .addOnFailureListener {
                onResult(false)
            }
    }

    fun pushSnapshot(queue: List<QueueItem>, authorNumber: Int?) {
        if (!started) return

        if (authorNumber == null) {
            callback?.onBlocked("Для синхронизации сначала укажи личный номер MY CAR.")
            return
        }

        val now = System.currentTimeMillis()
        val queueMaps = queue.map { item ->
            mapOf(
                FIELD_NUMBER to item.number,
                FIELD_STATUS to item.status.name
            )
        }

        firestore.runTransaction { transaction ->
            val snapshot = transaction.get(roomRef)

            val lockOwnerDeviceId = snapshot.getString(FIELD_LOCK_OWNER_DEVICE_ID)
            val lockUntilMs = snapshot.getLong(FIELD_LOCK_UNTIL_MS) ?: 0L

            val canEdit = lockOwnerDeviceId.isNullOrBlank() ||
                    lockUntilMs <= now ||
                    lockOwnerDeviceId == deviceId

            if (!canEdit) {
                throw IllegalStateException("LOCKED_BY_OTHER")
            }

            val data = hashMapOf<String, Any>(
                FIELD_QUEUE to queueMaps,
                FIELD_LAST_AUTHOR_NUMBER to authorNumber,
                FIELD_UPDATED_AT_MS to now,
                FIELD_OFFER_EXPIRES_AT_MS to now + OFFER_TTL_MS,
                FIELD_LOCK_OWNER_DEVICE_ID to deviceId,
                FIELD_LOCK_OWNER_NUMBER to authorNumber,
                FIELD_LOCK_UNTIL_MS to now + LOCK_TTL_MS,
                FIELD_UPDATED_AT to FieldValue.serverTimestamp()
            )

            transaction.set(roomRef, data, SetOptions.merge())
        }.addOnSuccessListener {
            callback?.onSyncStateChanged(
                SyncState.LockedByMe(
                    ownerNumber = authorNumber,
                    secondsLeft = (LOCK_TTL_MS / 1000L).toInt()
                )
            )
        }.addOnFailureListener { error ->
            if (error.message?.contains("LOCKED_BY_OTHER") == true) {
                callback?.onBlocked("Список сейчас изменяет другой телефон.")
                callback?.onSyncStateChanged(resolveState(latestSnapshot))
            } else {
                callback?.onBlocked("Не удалось отправить список на сервер.")
            }
        }
    }

    private fun ensureRoomExists() {
        roomRef.get()
            .addOnSuccessListener { document ->
                if (document.exists()) return@addOnSuccessListener

                val data = hashMapOf<String, Any>(
                    FIELD_QUEUE to emptyList<Map<String, Any>>(),
                    FIELD_LAST_AUTHOR_NUMBER to 0,
                    FIELD_UPDATED_AT_MS to 0L,
                    FIELD_OFFER_EXPIRES_AT_MS to 0L,
                    FIELD_LOCK_OWNER_DEVICE_ID to "",
                    FIELD_LOCK_OWNER_NUMBER to 0,
                    FIELD_LOCK_UNTIL_MS to 0L,
                    FIELD_UPDATED_AT to FieldValue.serverTimestamp()
                )

                roomRef.set(data, SetOptions.merge())
            }
            .addOnFailureListener {
                callback?.onBlocked("Не удалось подготовить комнату синхронизации.")
            }
    }

    private fun attachListener() {
        listenerRegistration?.remove()

        listenerRegistration = roomRef.addSnapshotListener { document, error ->
            if (!started) return@addSnapshotListener

            if (error != null) {
                callback?.onBlocked("Ошибка соединения с сервером.")
                callback?.onSyncStateChanged(SyncState.Off)
                return@addSnapshotListener
            }

            if (document == null || !document.exists()) {
                latestSnapshot = RemoteQueueSnapshot()
                callback?.onSyncStateChanged(SyncState.OnlineFree)
                return@addSnapshotListener
            }

            val snapshot = parseSnapshot(document)
            latestSnapshot = snapshot

            callback?.onRemoteSnapshot(snapshot)
            callback?.onSyncStateChanged(resolveState(snapshot))
        }
    }

    private fun parseSnapshot(document: DocumentSnapshot): RemoteQueueSnapshot {
        val rawQueue = document.get(FIELD_QUEUE) as? List<*>
        val parsedQueue = parseQueue(rawQueue)

        return RemoteQueueSnapshot(
            queue = parsedQueue,
            authorNumber = document.getLong(FIELD_LAST_AUTHOR_NUMBER)?.toInt(),
            updatedAtMs = document.getLong(FIELD_UPDATED_AT_MS) ?: 0L,
            offerExpiresAtMs = document.getLong(FIELD_OFFER_EXPIRES_AT_MS) ?: 0L,
            lockOwnerDeviceId = document.getString(FIELD_LOCK_OWNER_DEVICE_ID),
            lockOwnerNumber = document.getLong(FIELD_LOCK_OWNER_NUMBER)?.toInt(),
            lockUntilMs = document.getLong(FIELD_LOCK_UNTIL_MS) ?: 0L
        )
    }

    private fun parseQueue(rawQueue: List<*>?): List<QueueItem> {
        if (rawQueue.isNullOrEmpty()) return emptyList()

        return rawQueue.mapNotNull { rawItem ->
            val map = rawItem as? Map<*, *> ?: return@mapNotNull null

            val number = when (val value = map[FIELD_NUMBER]) {
                is Long -> value.toInt()
                is Int -> value
                is Double -> value.toInt()
                else -> null
            } ?: return@mapNotNull null

            val statusName = map[FIELD_STATUS]?.toString().orEmpty()
            val status = parseStatus(statusName)

            QueueItem(
                number = number,
                status = status
            )
        }
    }

    private fun parseStatus(statusName: String): Status {
        return enumValues<Status>().firstOrNull { it.name == statusName }
            ?: enumValues<Status>().first()
    }

    private fun resolveState(snapshot: RemoteQueueSnapshot): SyncState {
        val now = System.currentTimeMillis()
        val lockOwner = snapshot.lockOwnerDeviceId
        val secondsLeft = ((snapshot.lockUntilMs - now).coerceAtLeast(0L) / 1000L).toInt()

        return when {
            lockOwner.isNullOrBlank() || snapshot.lockUntilMs <= now -> SyncState.OnlineFree
            lockOwner == deviceId -> SyncState.LockedByMe(
                ownerNumber = snapshot.lockOwnerNumber,
                secondsLeft = secondsLeft
            )
            else -> SyncState.LockedByOther(
                ownerNumber = snapshot.lockOwnerNumber,
                secondsLeft = secondsLeft
            )
        }
    }

    private fun releaseOwnLock() {
        if (!started) return
        if (latestSnapshot.lockOwnerDeviceId != deviceId) return

        roomRef.set(
            mapOf(
                FIELD_LOCK_OWNER_DEVICE_ID to "",
                FIELD_LOCK_OWNER_NUMBER to 0,
                FIELD_LOCK_UNTIL_MS to 0L
            ),
            SetOptions.merge()
        )
    }

    companion object {
        private const val ROOMS_COLLECTION = "rooms"
        private const val DEFAULT_ROOM_ID = "default_room"

        private const val FIELD_QUEUE = "queue"
        private const val FIELD_NUMBER = "number"
        private const val FIELD_STATUS = "status"

        private const val FIELD_LAST_AUTHOR_NUMBER = "lastAuthorNumber"
        private const val FIELD_UPDATED_AT_MS = "updatedAtMs"
        private const val FIELD_OFFER_EXPIRES_AT_MS = "offerExpiresAtMs"

        private const val FIELD_LOCK_OWNER_DEVICE_ID = "lockOwnerDeviceId"
        private const val FIELD_LOCK_OWNER_NUMBER = "lockOwnerNumber"
        private const val FIELD_LOCK_UNTIL_MS = "lockUntilMs"

        private const val FIELD_UPDATED_AT = "updatedAt"

        private const val LOCK_TTL_MS = 10_000L
        private const val OFFER_TTL_MS = 15_000L
    }
}