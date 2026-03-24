package com.carlist.pro.ui.controller

import com.carlist.pro.data.DriverRegistryStore
import com.carlist.pro.data.QueueStateStore
import com.carlist.pro.domain.QueueItem
import com.carlist.pro.domain.QueueManager

class QueueStateCoordinator(
    private val queueManager: QueueManager,
    private val queueStateStore: QueueStateStore,
    private val registryStore: DriverRegistryStore,
    private val onQueuePublished: (List<QueueItem>) -> Unit,
    private val onPushSnapshotRequested: (List<QueueItem>) -> Unit
) {

    fun initializeFromStorage() {
        val snapshot = queueStateStore.load()

        if (snapshot.isNotEmpty()) {
            // 🔴 ВАЖНО:
            // локально сохранённый список при старте восстанавливаем целиком,
            // без фильтрации по registry.
            // Иначе на чистой установке скачанный список пропадает после перезапуска.
            queueManager.restoreFromSnapshot(
                snapshot = snapshot,
                isNumberAllowedByRegistry = { true }
            )
        }

        queueManager.clearUndoHistory()
        publishSnapshot(false)
    }

    fun publishSnapshot(pushToServer: Boolean) {
        val snapshot = queueManager.snapshot()

        onQueuePublished(snapshot)
        queueStateStore.save(snapshot)

        if (pushToServer) {
            onPushSnapshotRequested(snapshot)
        }
    }

    fun applyRemoteQueue(remoteQueue: List<QueueItem>) {
        val currentSnapshot = queueManager.snapshot()

        // Если это эхо нашего же собственного состояния — ничего не делаем,
        // чтобы не сбивать undo history.
        if (currentSnapshot == remoteQueue) {
            return
        }

        // 🔴 Серверный список тоже принимаем целиком,
        // без фильтрации по локальному registry.
        queueManager.restoreFromSnapshot(
            snapshot = remoteQueue,
            isNumberAllowedByRegistry = { true }
        )

        // После реального внешнего списка undo-историю очищаем,
        // потому что это уже новая внешняя база.
        queueManager.clearUndoHistory()
        publishSnapshot(false)
    }
}