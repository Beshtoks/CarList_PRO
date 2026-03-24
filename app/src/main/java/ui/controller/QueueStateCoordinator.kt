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
            queueManager.restoreFromSnapshot(
                snapshot = snapshot,
                isNumberAllowedByRegistry = { number ->
                    registryStore.isAllowed(number)
                }
            )

            queueManager.validateAgainstRegistry { number ->
                registryStore.isAllowed(number)
            }
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

        // 🔴 КЛЮЧЕВОЙ ФИКС:
        // если удалённый снимок равен текущему локальному состоянию,
        // это, скорее всего, эхо нашего же собственного обновления.
        // В этом случае НЕ трогаем очередь и НЕ чистим undo history.
        if (currentSnapshot == remoteQueue) {
            return
        }

        queueManager.restoreFromSnapshot(
            snapshot = remoteQueue,
            isNumberAllowedByRegistry = { number ->
                registryStore.isAllowed(number)
            }
        )

        queueManager.validateAgainstRegistry { number ->
            registryStore.isAllowed(number)
        }

        // Чистим undo только когда реально применили ЧУЖОЕ/НОВОЕ состояние,
        // а не собственное локальное эхо.
        queueManager.clearUndoHistory()
        publishSnapshot(false)
    }
}