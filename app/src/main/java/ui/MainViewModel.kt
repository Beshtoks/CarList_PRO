package com.carlist.pro.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.carlist.pro.domain.QueueItem
import com.carlist.pro.domain.QueueManager
import com.carlist.pro.domain.Status
import com.carlist.pro.domain.TransportInfo
import com.carlist.pro.domain.TransportType

class MainViewModel : ViewModel() {

    private val queueManager = QueueManager()

    private val _queueItems = MutableLiveData<List<QueueItem>>(queueManager.snapshot())
    val queueItems: LiveData<List<QueueItem>> = _queueItems

    private val _allowedNumbers: MutableList<Int> = mutableListOf()
    private val _transportTypeByNumber: MutableMap<Int, TransportType> = mutableMapOf()
    private val _myCarByNumber: MutableSet<Int> = mutableSetOf()

    private var activeRegistryRow: Int = 0
    private var errorRegistryRow: Int? = null

    private val _registryUiTick = MutableLiveData<Long>(0L)
    val registryUiTick: LiveData<Long> = _registryUiTick

    fun addNumber(number: Int?): QueueManager.AddResult {
        val parsed = number ?: return QueueManager.AddResult.InvalidNumber

        val result = queueManager.addNumber(
            number = parsed,
            isNumberAllowedByRegistry = { n ->
                if (_allowedNumbers.isEmpty()) true else _allowedNumbers.contains(n)
            }
        )

        publishSnapshot()
        return result
    }

    fun deleteAt(index: Int): QueueManager.OperationResult {
        val result = queueManager.removeAt(index)
        publishSnapshot()
        return result
    }

    fun removeAt(index: Int): QueueManager.OperationResult = deleteAt(index)

    fun clear(): QueueManager.OperationResult {
        val result = queueManager.clear()
        publishSnapshot()
        return result
    }

    fun setStatus(number: Int, status: Status): QueueManager.OperationResult {
        val result = queueManager.setStatus(number, status)
        publishSnapshot()
        return result
    }

    fun moveForDrag(from: Int, to: Int): QueueManager.OperationResult {
        return queueManager.move(from, to)
    }

    fun move(from: Int, to: Int): QueueManager.OperationResult {
        val result = queueManager.move(from, to)
        publishSnapshot()
        return result
    }

    fun commitDrag() {
        publishSnapshot()
    }

    private fun publishSnapshot() {
        _queueItems.value = queueManager.snapshot()
    }

    private fun tickRegistryUi() {
        _registryUiTick.value = (_registryUiTick.value ?: 0L) + 1L
    }

    fun getRegistryRows(): List<RegistryRow> {
        val rows = mutableListOf<RegistryRow>()

        for ((index, number) in _allowedNumbers.withIndex()) {
            rows += RegistryRow(
                number = number,
                info = TransportInfo(
                    transportType = _transportTypeByNumber[number] ?: TransportType.NONE,
                    isMyCar = _myCarByNumber.contains(number)
                ),
                underline = underlineForRow(index)
            )
        }

        rows += RegistryRow(
            number = null,
            info = TransportInfo(
                transportType = TransportType.NONE,
                isMyCar = false
            ),
            underline = underlineForRow(rows.size)
        )

        return rows
    }

    private fun underlineForRow(pos: Int): UnderlineState {
        return when {
            errorRegistryRow == pos -> UnderlineState.RED
            activeRegistryRow == pos -> UnderlineState.BLUE
            else -> UnderlineState.NONE
        }
    }

    fun setRegistryActiveRow(position: Int) {
        activeRegistryRow = position
        errorRegistryRow = null
        tickRegistryUi()
    }

    fun commitRegistryNumber(position: Int, oldNumber: Int?, newText: String): CommitResult {
        activeRegistryRow = position
        errorRegistryRow = null

        val trimmed = newText.trim()

        if (trimmed.isEmpty()) {
            if (oldNumber != null) {
                removeFromRegistry(oldNumber)
                queueManager.removeByNumber(oldNumber)
                publishSnapshot()
            }
            tickRegistryUi()
            return CommitResult.OK
        }

        val parsed = trimmed.toIntOrNull()
        if (parsed == null || parsed !in 1..99) {
            errorRegistryRow = position
            tickRegistryUi()
            return CommitResult.ERROR_CLEAR
        }

        val isDuplicate = _allowedNumbers.contains(parsed) && parsed != oldNumber
        if (isDuplicate) {
            errorRegistryRow = position
            tickRegistryUi()
            return CommitResult.ERROR_CLEAR
        }

        if (oldNumber != null) {
            val idx = _allowedNumbers.indexOf(oldNumber)
            if (idx != -1) {
                _allowedNumbers[idx] = parsed

                val oldType = _transportTypeByNumber.remove(oldNumber)
                if (oldType != null) {
                    _transportTypeByNumber[parsed] = oldType
                } else {
                    _transportTypeByNumber.remove(parsed)
                }

                if (_myCarByNumber.remove(oldNumber)) {
                    _myCarByNumber.add(parsed)
                }

                queueManager.removeByNumber(oldNumber)
                publishSnapshot()
            }
        } else {
            _allowedNumbers.add(parsed)
        }

        tickRegistryUi()
        return CommitResult.OK
    }

    private fun removeFromRegistry(number: Int) {
        _allowedNumbers.remove(number)
        _transportTypeByNumber.remove(number)
        _myCarByNumber.remove(number)
    }

    fun onRegistryCategoryAction(number: Int, action: DriverRegistryAdapter.CategoryAction) {
        when (action) {
            DriverRegistryAdapter.CategoryAction.BUS -> {
                _transportTypeByNumber[number] = TransportType.BUS
            }

            DriverRegistryAdapter.CategoryAction.VAN -> {
                _transportTypeByNumber[number] = TransportType.VAN
            }

            DriverRegistryAdapter.CategoryAction.MY_CAR -> {
                if (_myCarByNumber.contains(number)) {
                    _myCarByNumber.remove(number)
                } else {
                    _myCarByNumber.add(number)
                }
            }

            DriverRegistryAdapter.CategoryAction.CLEAR -> {
                _transportTypeByNumber[number] = TransportType.NONE
                _myCarByNumber.remove(number)
            }
        }
        tickRegistryUi()
    }

    fun getTransportInfo(number: Int): TransportInfo {
        return TransportInfo(
            transportType = _transportTypeByNumber[number] ?: TransportType.NONE,
            isMyCar = _myCarByNumber.contains(number)
        )
    }

    fun findMyCarPosition(queue: List<QueueItem>): Int? {
        val index = queue.indexOfFirst { _myCarByNumber.contains(it.number) }
        return if (index == -1) null else index + 1
    }
}