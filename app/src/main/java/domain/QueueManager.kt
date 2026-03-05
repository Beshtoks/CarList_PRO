package com.carlist.pro.domain

/**
 * Domain-only queue rules engine.
 *
 * HARD RULE:
 * If at least one ACTIVE exists anywhere in the list:
 * index 0 must be ACTIVE.
 *
 * PASSIVE = status == SERVICE
 * ACTIVE  = status != SERVICE
 *
 * Auto-fix must run after any mutation:
 * add, remove, move, status change.
 */
class QueueManager {

    sealed class AddResult {
        data class Added(val index: Int) : AddResult()
        data object InvalidNumber : AddResult()
        data object DuplicateInQueue : AddResult()
        // Registry validation comes later; kept here for future integration.
        data object NotInRegistry : AddResult()
    }

    sealed class OperationResult {
        data object Success : OperationResult()
        data object InvalidIndex : OperationResult()
        data object NumberNotFound : OperationResult()
        data object InvalidMove : OperationResult()
    }

    private val items: MutableList<QueueItem> = mutableListOf()

    fun snapshot(): List<QueueItem> = items.toList()

    fun size(): Int = items.size

    fun clear(): OperationResult {
        items.clear()
        return OperationResult.Success
    }

    fun addNumber(
        number: Int,
        // kept for signature compatibility with future steps; not used here
        category: Category = Category.NONE,
        // kept for signature compatibility with future steps; not used here
        isNumberAllowedByRegistry: ((Int) -> Boolean)? = null
    ): AddResult {
        if (number !in 1..99) return AddResult.InvalidNumber
        if (items.any { it.number == number }) return AddResult.DuplicateInQueue

        if (isNumberAllowedByRegistry != null && !isNumberAllowedByRegistry(number)) {
            return AddResult.NotInRegistry
        }

        // Default new items are ACTIVE (Status.NONE)
        items.add(QueueItem(number = number, status = Status.NONE))

        // Auto-fix after add
        autoFixPosition0()

        // Return index of this number after fix (it may move to index 0 in special cases later)
        val idx = items.indexOfFirst { it.number == number }
        return AddResult.Added(index = idx)
    }

    fun removeAt(index: Int): OperationResult {
        if (index !in items.indices) return OperationResult.InvalidIndex

        items.removeAt(index)

        // Auto-fix after remove
        autoFixPosition0()

        return OperationResult.Success
    }

    fun removeByNumber(number: Int): OperationResult {
        val idx = items.indexOfFirst { it.number == number }
        if (idx == -1) return OperationResult.NumberNotFound

        items.removeAt(idx)

        autoFixPosition0()
        return OperationResult.Success
    }

    /**
     * Move item from -> to (drag & drop).
     *
     * Special drag rule:
     * If the user drags a SERVICE item to index 0 while at least one ACTIVE exists:
     * the dragged SERVICE must end up at index 1, not 0.
     *
     * Then we run auto-fix to guarantee index 0 is ACTIVE.
     */
    fun move(from: Int, to: Int): OperationResult {
        if (from !in items.indices || to !in items.indices) return OperationResult.InvalidIndex
        if (from == to) return OperationResult.Success

        val moving = items[from]

        var target = to
        if (moving.status == Status.SERVICE && to == 0 && hasAnyActive(excludingIndex = from)) {
            // force it to land at index 1
            target = 1.coerceAtMost(items.size - 1)
        }

        // Remove first
        items.removeAt(from)

        // If removing shifts indices, adjust target
        val adjustedTarget = when {
            from < target -> target - 1
            else -> target
        }.coerceIn(0, items.size)

        // Insert
        items.add(adjustedTarget, moving)

        // Auto-fix after move
        autoFixPosition0()

        return OperationResult.Success
    }

    fun setStatus(number: Int, status: Status): OperationResult {
        val idx = items.indexOfFirst { it.number == number }
        if (idx == -1) return OperationResult.NumberNotFound

        val current = items[idx]
        if (current.status == status) {
            // Still treat as mutation attempt; but domain-wise list doesn't change.
            // We still run auto-fix for safety (deterministic).
            autoFixPosition0()
            return OperationResult.Success
        }

        items[idx] = current.copy(status = status)

        // Auto-fix after status change
        autoFixPosition0()

        return OperationResult.Success
    }

    /**
     * Stable "extract-and-insert-at-0" fix:
     * If any ACTIVE exists and index 0 is SERVICE:
     * move the first ACTIVE below (lowest index > 0) to index 0.
     * Do not change relative order of all other items.
     */
    private fun autoFixPosition0() {
        if (items.isEmpty()) return

        if (!hasAnyActive(excludingIndex = null)) {
            // All SERVICE => index 0 may be SERVICE
            return
        }

        val first = items[0]
        if (first.status != Status.SERVICE) return // already ACTIVE

        // Find first ACTIVE below
        val activeIndex = items.indexOfFirst { it.isActive }
        // activeIndex cannot be 0 here because items[0] is SERVICE
        if (activeIndex <= 0) return

        val activeItem = items.removeAt(activeIndex)
        items.add(0, activeItem)
    }

    private fun hasAnyActive(excludingIndex: Int?): Boolean {
        return items.anyIndexed { idx, item ->
            if (excludingIndex != null && idx == excludingIndex) false else item.isActive
        }
    }

    private inline fun <T> List<T>.anyIndexed(predicate: (index: Int, item: T) -> Boolean): Boolean {
        for (i in indices) {
            if (predicate(i, this[i])) return true
        }
        return false
    }
}