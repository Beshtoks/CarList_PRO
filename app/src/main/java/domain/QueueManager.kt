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
        items[oldIndex] = current.copy(number = newNumber)

        autoFixPosition0()

        return OperationResult.Success
    }

    fun validateAgainstRegistry(
        isNumberAllowedByRegistry: (Int) -> Boolean
    ): ValidationResult {

        val removed = mutableListOf<Int>()

        val iterator = items.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (!isNumberAllowedByRegistry(item.number)) {
                removed.add(item.number)
                iterator.remove()
            }
        }

        autoFixPosition0()

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

        autoFixPosition0()

        return OperationResult.Success
    }

    private fun autoFixPosition0() {

        if (items.isEmpty()) return

        // 🔹 СНАЧАЛА — фикс JURNIEKS
        val first = items[0]
        if (first.status == Status.JURNIEKS) {
            items[0] = first.copy(status = Status.NONE)
        }

        // 🔹 ПЕРЕЧИТЫВАЕМ после изменения
        val updatedFirst = items[0]

        if (!hasAnyActive(excludingIndex = null)) return

        if (!isPassiveStatus(updatedFirst.status)) return

        val activeIndex = items.indexOfFirst { it.isActive }

        if (activeIndex <= 0) return

        val activeItem = items.removeAt(activeIndex)
        items.add(0, activeItem)
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