package com.carlist.pro.domain

class QueueManager {

    sealed class AddResult {
        data class Added(val index: Int) : AddResult()
        data object InvalidNumber : AddResult()
        data object DuplicateInQueue : AddResult()
        data object NotInRegistry : AddResult()
    }

    sealed class OperationResult {
        data object Success : OperationResult()
        data object InvalidIndex : OperationResult()
        data object NumberNotFound : OperationResult()
        data object InvalidMove : OperationResult()
        data object DuplicateInQueue : OperationResult()
        data object InvalidNumber : OperationResult()
        data object NotInRegistry : OperationResult()
    }

    data class ValidationResult(
        val removedNumbers: List<Int>,
        val finalSize: Int
    )

    private val items: MutableList<QueueItem> = mutableListOf()
    private val undoHistory = ArrayDeque<List<QueueItem>>()
    private var dragSnapshot: List<QueueItem>? = null

    fun snapshot(): List<QueueItem> = items.toList()

    fun size(): Int = items.size

    fun clear(): OperationResult {
        if (items.isEmpty()) return OperationResult.Success
        saveUndoSnapshot(items.toList())
        items.clear()
        dragSnapshot = null
        return OperationResult.Success
    }

    fun addNumber(
        number: Int,
        category: Category = Category.NONE,
        isNumberAllowedByRegistry: ((Int) -> Boolean)? = null
    ): AddResult {

        if (number !in 1..99) return AddResult.InvalidNumber
        if (items.any { it.number == number }) return AddResult.DuplicateInQueue

        if (isNumberAllowedByRegistry != null && !isNumberAllowedByRegistry(number)) {
            return AddResult.NotInRegistry
        }

        saveUndoSnapshot(items.toList())
        items.add(QueueItem(number = number, status = Status.NONE))

        normalizeQueueAfterStructuralChange()

        val idx = items.indexOfFirst { it.number == number }
        return AddResult.Added(index = idx)
    }

    fun removeAt(index: Int): OperationResult {
        if (index !in items.indices) return OperationResult.InvalidIndex

        saveUndoSnapshot(items.toList())
        items.removeAt(index)
        normalizeQueueAfterStructuralChange()

        return OperationResult.Success
    }

    fun removeByNumber(number: Int): OperationResult {
        val idx = items.indexOfFirst { it.number == number }
        if (idx == -1) return OperationResult.NumberNotFound

        saveUndoSnapshot(items.toList())
        items.removeAt(idx)
        normalizeQueueAfterStructuralChange()

        return OperationResult.Success
    }

    fun beginDragUndo() {
        if (dragSnapshot == null) {
            dragSnapshot = items.toList()
        }
    }

    fun move(from: Int, to: Int): OperationResult {

        if (from !in items.indices || to !in items.indices) return OperationResult.InvalidIndex
        if (from == to) return OperationResult.Success

        val moving = items[from]

        var target = to
        if (isPassiveStatus(moving.status) && to == 0 && hasAnyActive(excludingIndex = from)) {
            target = 1.coerceAtMost(items.size - 1)
        }

        items.removeAt(from)

        val safeTarget = target.coerceIn(0, items.size)
        items.add(safeTarget, moving)

        normalizeQueueAfterStructuralChange()

        return OperationResult.Success
    }

    fun commitDragUndo() {
        val beforeDrag = dragSnapshot
        dragSnapshot = null

        if (beforeDrag != null && beforeDrag != items.toList()) {
            saveUndoSnapshot(beforeDrag)
        }
    }

    fun cancelDragUndo() {
        dragSnapshot = null
    }

    fun setStatus(number: Int, status: Status): OperationResult {

        val idx = items.indexOfFirst { it.number == number }
        if (idx == -1) return OperationResult.NumberNotFound

        val current = items[idx]

        if (current.status == status) {
            normalizeQueueAfterStructuralChange()
            return OperationResult.Success
        }

        saveUndoSnapshot(items.toList())
        items[idx] = current.copy(status = status)

        normalizeQueueAfterStructuralChange()

        return OperationResult.Success
    }

    fun replaceNumber(
        oldNumber: Int,
        newNumber: Int,
        isNumberAllowedByRegistry: ((Int) -> Boolean)? = null
    ): OperationResult {

        if (newNumber !in 1..99) return OperationResult.InvalidNumber

        val oldIndex = items.indexOfFirst { it.number == oldNumber }
        if (oldIndex == -1) return OperationResult.NumberNotFound

        val duplicateIndex = items.indexOfFirst { it.number == newNumber }
        if (duplicateIndex != -1 && duplicateIndex != oldIndex) {
            return OperationResult.DuplicateInQueue
        }

        if (isNumberAllowedByRegistry != null && !isNumberAllowedByRegistry(newNumber)) {
            return OperationResult.NotInRegistry
        }

        val current = items[oldIndex]
        if (current.number == newNumber) return OperationResult.Success

        saveUndoSnapshot(items.toList())
        items[oldIndex] = current.copy(number = newNumber)

        normalizeQueueAfterStructuralChange()

        return OperationResult.Success
    }

    fun validateAgainstRegistry(
        isNumberAllowedByRegistry: (Int) -> Boolean
    ): ValidationResult {

        val before = items.toList()
        val removed = mutableListOf<Int>()

        val iterator = items.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (!isNumberAllowedByRegistry(item.number)) {
                removed.add(item.number)
                iterator.remove()
            }
        }

        normalizeQueueAfterStructuralChange()

        if (removed.isNotEmpty()) {
            saveUndoSnapshot(before)
        }

        return ValidationResult(
            removedNumbers = removed,
            finalSize = items.size
        )
    }

    fun restoreFromSnapshot(
        snapshot: List<QueueItem>,
        isNumberAllowedByRegistry: ((Int) -> Boolean)? = null
    ): OperationResult {

        val restored = mutableListOf<QueueItem>()
        val seen = mutableSetOf<Int>()

        for (item in snapshot) {
            val number = item.number

            if (number !in 1..99) continue
            if (seen.contains(number)) continue
            if (isNumberAllowedByRegistry != null && !isNumberAllowedByRegistry(number)) continue

            restored.add(item)
            seen.add(number)
        }

        items.clear()
        items.addAll(restored)
        dragSnapshot = null

        normalizeQueueAfterStructuralChange()

        return OperationResult.Success
    }

    fun undo(): Boolean {
        val previous = undoHistory.removeLastOrNull() ?: return false

        items.clear()
        items.addAll(previous)
        dragSnapshot = null
        normalizeQueueAfterStructuralChange()
        return true
    }

    fun clearUndoHistory() {
        undoHistory.clear()
        dragSnapshot = null
    }

    private fun saveUndoSnapshot(snapshot: List<QueueItem>) {
        if (snapshot == items.toList()) return

        val last = undoHistory.lastOrNull()
        if (last == snapshot) return

        undoHistory.addLast(snapshot)
        while (undoHistory.size > 50) {
            undoHistory.removeFirst()
        }
    }

    private fun normalizeQueueAfterStructuralChange() {
        if (items.isEmpty()) return

        val first = items[0]
        if (first.status == Status.JURNIEKS) {
            items[0] = first.copy(status = Status.NONE)
        }

        val updatedFirst = items[0]

        if (!isPassiveStatus(updatedFirst.status)) return

        val activeIndex = items.indexOfFirst { it.isActive }

        if (activeIndex <= 0) return

        val activeItem = items.removeAt(activeIndex)
        items.add(0, activeItem)

        val firstAfterMove = items[0]
        if (firstAfterMove.status == Status.JURNIEKS) {
            items[0] = firstAfterMove.copy(status = Status.NONE)
        }
    }

    private fun isPassiveStatus(status: Status): Boolean {
        return status == Status.SERVICE || status == Status.OFFICE
    }

    private fun hasAnyActive(excludingIndex: Int?): Boolean {
        return items.anyIndexed { idx, item ->
            if (excludingIndex != null && idx == excludingIndex) {
                false
            } else {
                item.isActive
            }
        }
    }

    private inline fun <T> List<T>.anyIndexed(predicate: (index: Int, item: T) -> Boolean): Boolean {
        for (i in indices) {
            if (predicate(i, this[i])) return true
        }
        return false
    }
}