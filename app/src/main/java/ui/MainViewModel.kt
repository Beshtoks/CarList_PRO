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

    // -------------------------
    // Queue
    // -------------------------

    private val queueManager = QueueManager()

    private val _queueItems = MutableLiveData<List<QueueItem>>(queueManager.snapshot())
    val queueItems: LiveData<List<QueueItem>> = _queueItems

    /**
     * UI-friendly result for manual input.
     * (оставлено совместимо с теми версиями MainActivity, где это уже используется)
     */
    sealed class AddUiResult {
        data object Added : AddUiResult()
        data object Error : AddUiResult()
    }

    /**
     * Основной добавлятор, который используют разные версии MainActivity:
     * - если передали null/не число -> ошибка
     * - 1..99
     * - запрещаем, если нет в реестре (если реестр НЕ пустой)
     * - запрещаем дубликат в очереди
     */
    fun addNumber(numberOrNull: Int?): AddUiResult {
        val number = numberOrNull ?: return AddUiResult.Error

        val result = queueManager.addNumber(
            number = number,
            isNumberAllowedByRegistry = { n ->
                // Если реестр пустой — не блокируем (тех. режим ещё не заполнен).
                // Если реестр НЕ пустой — требуем присутствие.
                if (_allowedNumbers.isEmpty()) true else _allowedNumbers.contains(n)
            }
        )

        publishSnapshot()

        return when (result) {
            is QueueManager.AddResult.Added -> AddUiResult.Added
            QueueManager.AddResult.InvalidNumber,
            QueueManager.AddResult.DuplicateInQueue,
            QueueManager.AddResult.NotInRegistry -> AddUiResult.Error
        }
    }

    // На всякий случай: если где-то ещё вызывается старый вариант addNumber(Int)
    fun addNumber(number: Int): QueueManager.AddResult {
        val result = queueManager.addNumber(
            number = number,
            isNumberAllowedByRegistry = { n ->
                if (_allowedNumbers.isEmpty()) true else _allowedNumbers.contains(n)
            }
        )
        publishSnapshot()
        return result
    }

    // Совместимость: разные версии MainActivity используют разные имена
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

    /**
     * Drag (совместимость с твоими уже закреплёнными правилами).
     * Во время drag НЕ публикуем LiveData.
     */
    fun moveForDrag(from: Int, to: Int): QueueManager.OperationResult {
        return queueManager.move(from, to)
    }

    /**
     * Если какая-то версия MainActivity вызывает move(from,to) по окончании —
     * делаем move + publishSnapshot.
     */
    fun move(from: Int, to: Int): QueueManager.OperationResult {
        val result = queueManager.move(from, to)
        publishSnapshot()
        return result
    }

    /**
     * Публикуем итоговый список один раз — после отпускания пальца.
     */
    fun commitDrag() {
        publishSnapshot()
    }

    private fun publishSnapshot() {
        _queueItems.value = queueManager.snapshot()
    }

    // -------------------------
    // Registry (Allowed driver numbers + categories)
    // -------------------------

    /**
     * Пока без SharedPreferences (если у тебя уже есть Store — подключишь позже),
     * но методы и сигнатуры сделаны так, чтобы UI/диалог работали стабильно.
     */
    private val _allowedNumbers: MutableList<Int> = mutableListOf()

    private val _transportTypeByNumber: MutableMap<Int, TransportType> = mutableMapOf()
    private val _myCarByNumber: MutableSet<Int> = mutableSetOf()

    private var activeRegistryRow: Int = 0
    private var errorRegistryRow: Int? = null

    private val _registryUiTick = MutableLiveData<Long>(0L)
    val registryUiTick: LiveData<Long> = _registryUiTick

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

        // Всегда держим одну пустую строку внизу
        rows += RegistryRow(
            number = null,
            info = TransportInfo(transportType = TransportType.NONE, isMyCar = false),
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

    /**
     * Возвращает CommitResult.OK либо CommitResult.ERROR_CLEAR (тогда UI очищает поле).
     */
    fun commitRegistryNumber(position: Int, oldNumber: Int?, newText: String): CommitResult {
        activeRegistryRow = position
        errorRegistryRow = null

        val trimmed = newText.trim()

        // ENTER на пустой строке:
        // - если это существующий номер и его стерли -> удалить
        // - если это пустая строка снизу -> ничего не делаем
        if (trimmed.isEmpty()) {
            if (oldNumber != null) {
                removeFromRegistry(oldNumber)
                // при удалении номера из реестра — выкидываем его из очереди (“паровоз”)
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

        // Дубликат в реестре (кроме случая “редактируем тот же номер”)
        val isDuplicate = _allowedNumbers.contains(parsed) && parsed != oldNumber
        if (isDuplicate) {
            errorRegistryRow = position
            tickRegistryUi()
            return CommitResult.ERROR_CLEAR
        }

        // Если редактировали существующий номер — заменить
        if (oldNumber != null) {
            val idx = _allowedNumbers.indexOf(oldNumber)
            if (idx != -1) {
                _allowedNumbers[idx] = parsed

                // перенос категорий со старого номера на новый
                val oldType = _transportTypeByNumber.remove(oldNumber)
                if (oldType != null) _transportTypeByNumber[parsed] = oldType else _transportTypeByNumber.remove(parsed)

                if (_myCarByNumber.remove(oldNumber)) _myCarByNumber.add(parsed)

                // если старый номер был в очереди — заменить напрямую проще удалением + добавлением нельзя (изменит порядок),
                // поэтому просто удаляем старый из очереди.
                // (порядок восстановления очереди — отдельная задача синка, сейчас не трогаем)
                queueManager.removeByNumber(oldNumber)
                publishSnapshot()
            }
        } else {
            // Добавление нового номера (в нижнюю пустую строку)
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

    /**
     * Единая точка обработки long-press menu действий из диалога.
     * (именно её ждёт DriverRegistryDialogFragment из моих файлов)
     */
    fun onRegistryCategoryAction(number: Int, action: DriverRegistryAdapter.CategoryAction) {
        when (action) {
            DriverRegistryAdapter.CategoryAction.BUS -> {
                _transportTypeByNumber[number] = TransportType.BUS
            }
            DriverRegistryAdapter.CategoryAction.VAN -> {
                _transportTypeByNumber[number] = TransportType.VAN
            }
            DriverRegistryAdapter.CategoryAction.MY_CAR -> {
                if (_myCarByNumber.contains(number)) _myCarByNumber.remove(number) else _myCarByNumber.add(number)
            }
            DriverRegistryAdapter.CategoryAction.CLEAR -> {
                _transportTypeByNumber[number] = TransportType.NONE
                _myCarByNumber.remove(number)
            }
        }
        tickRegistryUi()
    }
}