package com.carlist.pro.ui.controller

import com.carlist.pro.data.DriverRegistryStore
import com.carlist.pro.domain.QueueManager
import com.carlist.pro.domain.Status

class QueueEditController(
    private val queueManager: QueueManager,
    private val registryStore: DriverRegistryStore,
    private val isQueueEditingBlocked: () -> Boolean,
    private val onBlockedMessage: (String) -> Unit,
    private val publishSnapshot: (Boolean) -> Unit
) {

    private companion object {
        const val BLOCKED_MESSAGE = "List is currently being edited by another device."
    }

    fun addNumber(numberOrNull: Int?): QueueManager.AddResult {
        val blocked = guardQueueEditing()
        if (blocked != null) return blocked

        val number = numberOrNull ?: return QueueManager.AddResult.InvalidNumber
        if (number !in 1..99) return QueueManager.AddResult.InvalidNumber

        val result = queueManager.addNumber(
            number = number,
            isNumberAllowedByRegistry = { n -> registryStore.isAllowed(n) }
        )

        publishSnapshot(true)
        return result
    }

    fun removeAt(index: Int): QueueManager.OperationResult {
        val blocked = guardQueueEditingOperation()
        if (blocked != null) return blocked

        val res = queueManager.removeAt(index)
        publishSnapshot(true)
        return res
    }

    fun removeByNumber(number: Int): QueueManager.OperationResult {
        val blocked = guardQueueEditingOperation()
        if (blocked != null) return blocked

        val res = queueManager.removeByNumber(number)
        publishSnapshot(true)
        return res
    }

    fun setStatus(number: Int, status: Status): QueueManager.OperationResult {
        val blocked = guardQueueEditingOperation()
        if (blocked != null) return blocked

        val res = queueManager.setStatus(number, status)
        publishSnapshot(true)
        return res
    }

    fun clear(): QueueManager.OperationResult {
        val blocked = guardQueueEditingOperation()
        if (blocked != null) return blocked

        val res = queueManager.clear()
        publishSnapshot(true)
        return res
    }

    fun moveForDrag(from: Int, to: Int): QueueManager.OperationResult {
        val blocked = guardQueueEditingOperation()
        if (blocked != null) return blocked

        return queueManager.move(from, to)
    }

    fun commitDrag() {
        if (isQueueEditingBlocked()) {
            queueManager.cancelDragUndo()
            return
        }
        queueManager.commitDragUndo()
        publishSnapshot(true)
    }

    fun validateQueueAgainstRegistry(): QueueManager.ValidationResult {
        val result = queueManager.validateAgainstRegistry { registryStore.isAllowed(it) }
        publishSnapshot(true)
        return result
    }

    private fun guardQueueEditing(): QueueManager.AddResult? {
        if (!isQueueEditingBlocked()) return null
        onBlockedMessage(BLOCKED_MESSAGE)
        return QueueManager.AddResult.DuplicateInQueue
    }

    private fun guardQueueEditingOperation(): QueueManager.OperationResult? {
        if (!isQueueEditingBlocked()) return null
        onBlockedMessage(BLOCKED_MESSAGE)
        return QueueManager.OperationResult.InvalidMove
    }
}