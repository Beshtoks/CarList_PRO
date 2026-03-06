package com.carlist.pro.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.carlist.pro.data.DriverRegistryStore
import com.carlist.pro.domain.QueueItem
import com.carlist.pro.domain.QueueManager
import com.carlist.pro.domain.Status
import com.carlist.pro.domain.TransportInfo
import com.carlist.pro.domain.TransportType

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val queueManager = QueueManager()
    private val registryStore = DriverRegistryStore(app.applicationContext)

    private val _queueItems = MutableLiveData<List<QueueItem>>(queueManager.snapshot())
    val queueItems: LiveData<List<QueueItem>> = _queueItems

    private val _registryUiTick = MutableLiveData<Long>(0L)
    val registryUiTick: LiveData<Long> = _registryUiTick

    private var activeRowIndex: Int = 0
    private var lastCommitFailedRow: Int? = null

    fun addNumber(numberOrNull: Int?): QueueManager.AddResult {
        val number = numberOrNull ?: return QueueManager.AddResult.InvalidNumber

        if (number !in 1..99) return QueueManager.AddResult.InvalidNumber

        val result = queueManager.addNumber(
            number = number,
            isNumberAllowedByRegistry = { n -> registryStore.isAllowed(n) }
        )
        publishSnapshot()
        return result
    }

    fun removeAt(index: Int): QueueManager.OperationResult {
        val res = queueManager.removeAt(index)
        publishSnapshot()
        return res
    }

    fun setStatus(number: Int, status: Status): QueueManager.OperationResult {
        val res = queueManager.setStatus(number, status)
        publishSnapshot()
        return res
    }

    fun clear(): QueueManager.OperationResult {
        val res = queueManager.clear()
        publishSnapshot()
        return res
    }

    fun moveForDrag(from: Int, to: Int): QueueManager.OperationResult {
        return queueManager.move(from, to)
    }

    fun commitDrag() {
        publishSnapshot()
    }

    fun getTransportInfo(number: Int): TransportInfo {
        return registryStore.getInfo(number)
    }

    fun findMyCarPosition(queue: List<QueueItem>): Int? {
        val idx = queue.indexOfFirst { registryStore.getInfo(it.number).isMyCar }
        return if (idx == -1) null else idx + 1
    }

    fun getRegistryRows(): List<RegistryRow> {
        val numbers = registryStore.getAllowedNumbers()

        val rows = mutableListOf<RegistryRow>()
        numbers.forEachIndexed { idx, n ->
            val underline = when {
                lastCommitFailedRow == idx -> UnderlineState.RED
                idx == activeRowIndex -> UnderlineState.BLUE
                else -> UnderlineState.NONE
            }
            rows.add(
                RegistryRow(
                    number = n,
                    info = registryStore.getInfo(n),
                    underline = underline
                )
            )
        }

        val underlineNew = if (numbers.size == activeRowIndex) UnderlineState.BLUE else UnderlineState.NONE
        rows.add(
            RegistryRow(
                number = null,
                info = TransportInfo(TransportType.NONE, false),
                underline = underlineNew
            )
        )

        return rows
    }

    fun setRegistryActiveRow(pos: Int) {
        activeRowIndex = pos.coerceAtLeast(0)
        lastCommitFailedRow = null
        // ВАЖНО:
        // тут специально НЕ вызываем tickRegistry(),
        // чтобы простой тап по строке не перерисовывал весь RecyclerView
        // и не сбрасывал только что поставленный курсор.
    }

    fun getRegistryActiveRow(): Int = activeRowIndex

    fun commitRegistryNumber(position: Int, oldNumber: Int?, newText: String): CommitResult {
        val trimmed = newText.trim()
        val newNumber = trimmed.toIntOrNull()

        if (trimmed.isBlank()) {
            if (oldNumber != null) {
                registryStore.removeNumber(oldNumber)
                queueManager.removeByNumber(oldNumber)
                publishSnapshot()
            }
            lastCommitFailedRow = null
            activeRowIndex = position.coerceAtLeast(0)
            tickRegistry()
            return CommitResult.OK
        }

        if (newNumber == null || newNumber !in 1..99) {
            lastCommitFailedRow = position
            tickRegistry()
            return CommitResult.ERROR_CLEAR
        }

        val res = registryStore.upsertNumber(oldNumber, newNumber)
        if (res.isFailure) {
            lastCommitFailedRow = position
            tickRegistry()
            return CommitResult.ERROR_CLEAR
        }

        lastCommitFailedRow = null
        activeRowIndex = (position + 1).coerceAtLeast(0)

        tickRegistry()
        return CommitResult.OK
    }

    fun onRegistryCategoryAction(number: Int, action: DriverRegistryAdapter.CategoryAction) {
        when (action) {
            DriverRegistryAdapter.CategoryAction.BUS -> setRegistryTransportType(number, TransportType.BUS)
            DriverRegistryAdapter.CategoryAction.VAN -> setRegistryTransportType(number, TransportType.VAN)
            DriverRegistryAdapter.CategoryAction.MY_CAR -> toggleRegistryMyCar(number)
            DriverRegistryAdapter.CategoryAction.CLEAR -> clearRegistryCategories(number)
        }
    }

    fun setRegistryTransportType(number: Int, type: TransportType) {
        registryStore.setTransportType(number, type)
        tickRegistry()
    }

    fun toggleRegistryMyCar(number: Int) {
        registryStore.toggleMyCar(number)
        tickRegistry()
    }

    fun clearRegistryCategories(number: Int) {
        registryStore.clearCategories(number)
        tickRegistry()
    }

    private fun tickRegistry() {
        _registryUiTick.value = (_registryUiTick.value ?: 0L) + 1L
    }

    private fun publishSnapshot() {
        _queueItems.value = queueManager.snapshot()
    }
}