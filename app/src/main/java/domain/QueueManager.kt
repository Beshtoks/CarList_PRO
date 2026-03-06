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
        category: Category = Category.NONE,
        isNumberAllowedByRegistry: ((Int) -> Boolean)? = null
    ): AddResult {

        if (number !in 1..99) return AddResult.InvalidNumber
        if (items.any { it.number == number }) return AddResult.DuplicateInQueue

        if (isNumberAllowedByRegistry != null && !isNumberAllowedByRegistry(number)) {
            return AddResult.NotInRegistry
        }

        items.add(QueueItem(number = number, status = Status.NONE))

        autoFixPosition0()

        val idx = items.indexOfFirst { it.number == number }
        return AddResult.Added(index = idx)
    }

    fun removeAt(index: Int): OperationResult {
        if (index !in items.indices) return OperationResult.InvalidIndex

        items.removeAt(index)
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
     */
    fun move(from: Int, to: Int): OperationResult {

        if (from !in items.indices || to !in items.indices) return OperationResult.InvalidIndex
        if (from == to) return OperationResult.Success

        val moving = items[from]

        var target = to
        if (moving.status == Status.SERVICE && to == 0 && hasAnyActive(excludingIndex = from)) {
            target = 1.coerceAtMost(items.size - 1)
        }

        items.removeAt(from)

        // Вставляем прямо в целевую позицию
        val safeTarget = target.coerceIn(0, items.size)

        items.add(safeTarget, moving)

        autoFixPosition0()

        return OperationResult.Success
    }

    fun setStatus(number: Int, status: Status): OperationResult {

        val idx = items.indexOfFirst { it.number == number }
        if (idx == -1) return OperationResult.NumberNotFound

        val current = items[idx]

        if (current.status == status) {
            autoFixPosition0()
            return OperationResult.Success
        }

        items[idx] = current.copy(status = status)

        autoFixPosition0()

        return OperationResult.Success
    }

    private fun autoFixPosition0() {

        if (items.isEmpty()) return

        if (!hasAnyActive(excludingIndex = null)) return

        val first = items[0]

        if (first.status != Status.SERVICE) return

        val activeIndex = items.indexOfFirst { it.isActive }

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