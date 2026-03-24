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
                isNumberAllowedByRegistry = { n -> registryStore.isAllowed(n) }
            )

            queueManager.validateAgainstRegistry { registryStore.isAllowed(it) }
        }

        publishSnapshot(false)
    }

    fun publishSnapshot(pushToServer: Boolean) {
        val snap = queueManager.snapshot()
        onQueuePublished(snap)
        queueStateStore.save(snap)

        if (pushToServer) {
            onPushSnapshotRequested(snap)
        }
    }

    fun applyRemoteQueue(remoteQueue: List<QueueItem>) {
        queueManager.restoreFromSnapshot(
            snapshot = remoteQueue,
            isNumberAllowedByRegistry = { true }
        )

        publishSnapshot(false)
    }
}