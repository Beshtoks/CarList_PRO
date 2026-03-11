package com.carlist.pro.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.carlist.pro.data.DriverRegistryStore
import com.carlist.pro.data.QueueStateStore
import com.carlist.pro.data.sync.FirebaseSyncRepository
import com.carlist.pro.data.sync.NetworkMonitor
import com.carlist.pro.domain.QueueItem
import com.carlist.pro.domain.QueueManager
import com.carlist.pro.domain.Status
import com.carlist.pro.domain.TransportInfo
import com.carlist.pro.domain.TransportType
import com.carlist.pro.domain.sync.RemoteQueueSnapshot
import com.carlist.pro.domain.sync.SyncOffer
import com.carlist.pro.domain.sync.SyncState

class MainViewModel(app: Application) : AndroidViewModel(app), FirebaseSyncRepository.Callback {

    private val queueManager = QueueManager()
    private val registryStore = DriverRegistryStore(app.applicationContext)
    private val queueStateStore = QueueStateStore(app.applicationContext)
    private val networkMonitor = NetworkMonitor(app.applicationContext)
    private val syncRepository = FirebaseSyncRepository(app.applicationContext)

    private val _queueItems = MutableLiveData<List<QueueItem>>(emptyList())
    val queueItems: LiveData<List<QueueItem>> = _queueItems

    private val _registryUiTick = MutableLiveData<Long>(0L)
    val registryUiTick: LiveData<Long> = _registryUiTick

    private val _syncState = MutableLiveData<SyncState>(SyncState.Off)
    val syncState: LiveData<SyncState> = _syncState

    private val _syncPanelText = MutableLiveData("SYNC OFF")
    val syncPanelText: LiveData<String> = _syncPanelText

    private val _syncMessage = MutableLiveData<String?>(null)
    val syncMessage: LiveData<String?> = _syncMessage

    private val _syncOffer = MutableLiveData<SyncOffer?>(null)
    val syncOffer: LiveData<SyncOffer?> = _syncOffer

    private var syncEnabled = false
    private var activeRowIndex: Int = 0
    private var lastCommitFailedRow: Int? = null

    init {
        val snapshot = queueStateStore.load()

        if (snapshot.isNotEmpty()) {
            queueManager.restoreFromSnapshot(
                snapshot = snapshot,
                isNumberAllowedByRegistry = { n -> registryStore.isAllowed(n) }
            )

            queueManager.validateAgainstRegistry { registryStore.isAllowed(it) }
        }

        networkMonitor.start { isOnline ->
            if (syncEnabled && !isOnline) {
                syncEnabled = false
                syncRepository.stop()
                setSyncState(SyncState.NoNetwork)
                _syncMessage.postValue("No network connection")
                return@start
            }

            if (!syncEnabled) {
                refreshSyncPanelState()
            }
        }

        publishSnapshot()
        refreshSyncPanelState()
    }

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

    fun removeByNumber(number: Int): QueueManager.OperationResult {
        val res = queueManager.removeByNumber(number)
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

    fun validateQueueAgainstRegistry(): QueueManager.ValidationResult {
        val result = queueManager.validateAgainstRegistry { registryStore.isAllowed(it) }
        publishSnapshot()
        return result
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

        val underlineNew = if (numbers.size == activeRowIndex) {
            UnderlineState.BLUE
        } else {
            UnderlineState.NONE
        }

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
            refreshSyncPanelState()
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

        if (oldNumber != null && oldNumber != newNumber) {
            queueManager.replaceNumber(
                oldNumber = oldNumber,
                newNumber = newNumber,
                isNumberAllowedByRegistry = { n -> registryStore.isAllowed(n) }
            )

            publishSnapshot()
        }

        lastCommitFailedRow = null
        activeRowIndex = (position + 1).coerceAtLeast(0)

        tickRegistry()
        refreshSyncPanelState()
        return CommitResult.OK
    }

    fun sortRegistryNumbersForClose() {
        registryStore.sortNumbersAscending()
        activeRowIndex = 0
        lastCommitFailedRow = null
        tickRegistry()
        refreshSyncPanelState()
    }

    fun onRegistryCategoryAction(number: Int, action: DriverRegistryAdapter.CategoryAction) {
        when (action) {
            DriverRegistryAdapter.CategoryAction.BUS ->
                setRegistryTransportType(number, TransportType.BUS)

            DriverRegistryAdapter.CategoryAction.VAN ->
                setRegistryTransportType(number, TransportType.VAN)

            DriverRegistryAdapter.CategoryAction.MY_CAR ->
                toggleRegistryMyCar(number)

            DriverRegistryAdapter.CategoryAction.CLEAR ->
                clearRegistryCategories(number)
        }
    }

    fun setRegistryTransportType(number: Int, type: TransportType) {
        registryStore.setTransportType(number, type)
        tickRegistry()
    }

    fun toggleRegistryMyCar(number: Int) {
        registryStore.toggleMyCar(number)
        tickRegistry()
        refreshSyncPanelState()
    }

    fun clearRegistryCategories(number: Int) {
        registryStore.clearCategories(number)
        tickRegistry()
        refreshSyncPanelState()
    }

    fun onServerPanelLongPress() {
        if (syncEnabled) {
            stopSync()
            return
        }

        if (!networkMonitor.isCurrentlyOnline()) {
            setSyncState(SyncState.NoNetwork)
            _syncMessage.value = "No network connection"
            return
        }

        if (registryStore.getMyCar() == null) {
            setSyncState(SyncState.NeedMyCar)
            _syncMessage.value = "Для синхронизации сначала укажи личный номер в категории MY CAR."
            return
        }

        syncEnabled = true
        setSyncState(SyncState.Connecting)
        syncRepository.start(this)
    }

    fun onSyncMessageShown() {
        _syncMessage.value = null
    }

    override fun onSyncStateChanged(state: SyncState) {
        setSyncState(state)
    }

    override fun onRemoteSnapshot(snapshot: RemoteQueueSnapshot) {
        val offer = SyncOffer(
            snapshot = snapshot,
            secondsRemaining = snapshot.offerSecondsRemaining()
        )
        _syncOffer.postValue(offer)
    }

    override fun onBlocked(reason: String) {
        _syncMessage.postValue(reason)
    }

    override fun onCleared() {
        syncRepository.stop()
        networkMonitor.stop()
        super.onCleared()
    }

    private fun stopSync() {
        syncEnabled = false
        syncRepository.stop()
        setSyncState(SyncState.Off)
    }

    private fun tickRegistry() {
        _registryUiTick.value = (_registryUiTick.value ?: 0L) + 1L
    }

    private fun publishSnapshot() {
        val snap = queueManager.snapshot()
        _queueItems.value = snap
        queueStateStore.save(snap)
    }

    private fun refreshSyncPanelState() {
        if (syncEnabled) return

        val nextState = when {
            !networkMonitor.isCurrentlyOnline() -> SyncState.NoNetwork
            registryStore.getMyCar() == null -> SyncState.NeedMyCar
            else -> SyncState.Off
        }

        setSyncState(nextState)
    }

    private fun setSyncState(state: SyncState) {
        _syncState.postValue(state)
        _syncPanelText.postValue(renderSyncText(state))
    }

    private fun renderSyncText(state: SyncState): String {
        return when (state) {
            SyncState.Off -> "SYNC OFF"
            SyncState.Connecting -> "CONNECTING..."
            SyncState.NeedMyCar -> "SET MY CAR"
            SyncState.OnlineFree -> "SYNC ON"
            SyncState.NoNetwork -> "NO NETWORK"
            is SyncState.OfferAvailable -> "OFFER ${state.secondsLeft}s"
            is SyncState.LockedByMe -> "CHANGE LIST ${state.secondsLeft}s"
            is SyncState.LockedByOther -> {
                val owner = state.ownerNumber?.toString() ?: "?"
                "CHANGE $owner ${state.secondsLeft}s"
            }
        }
    }
}