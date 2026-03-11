package com.carlist.pro.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
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

    private val mainHandler = Handler(Looper.getMainLooper())

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

    private var pendingOfferRequest = false
    private var latestRemoteSnapshot: RemoteQueueSnapshot? = null

    private val syncCountdownRunnable = object : Runnable {
        override fun run() {
            updateSyncStateFromLatestSnapshot()
            if (shouldKeepSyncCountdownRunning()) {
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

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
                pendingOfferRequest = false
                latestRemoteSnapshot = null
                stopSyncCountdown()
                _syncOffer.postValue(null)
                setSyncState(SyncState.NoNetwork)
                _syncMessage.postValue("No network connection")
                return@start
            }

            if (!syncEnabled) {
                refreshSyncPanelState()
            }
        }

        publishSnapshot(pushToServer = false)
        refreshSyncPanelState()
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

        publishSnapshot(pushToServer = true)
        return result
    }

    fun removeAt(index: Int): QueueManager.OperationResult {
        val blocked = guardQueueEditingOperation()
        if (blocked != null) return blocked

        val res = queueManager.removeAt(index)
        publishSnapshot(pushToServer = true)
        return res
    }

    fun removeByNumber(number: Int): QueueManager.OperationResult {
        val blocked = guardQueueEditingOperation()
        if (blocked != null) return blocked

        val res = queueManager.removeByNumber(number)
        publishSnapshot(pushToServer = true)
        return res
    }

    fun setStatus(number: Int, status: Status): QueueManager.OperationResult {
        val blocked = guardQueueEditingOperation()
        if (blocked != null) return blocked

        val res = queueManager.setStatus(number, status)
        publishSnapshot(pushToServer = true)
        return res
    }

    fun clear(): QueueManager.OperationResult {
        val blocked = guardQueueEditingOperation()
        if (blocked != null) return blocked

        val res = queueManager.clear()
        publishSnapshot(pushToServer = true)
        return res
    }

    fun moveForDrag(from: Int, to: Int): QueueManager.OperationResult {
        val blocked = guardQueueEditingOperation()
        if (blocked != null) return blocked

        return queueManager.move(from, to)
    }

    fun commitDrag() {
        if (isQueueEditingBlocked()) return
        publishSnapshot(pushToServer = true)
    }

    fun validateQueueAgainstRegistry(): QueueManager.ValidationResult {
        val result = queueManager.validateAgainstRegistry { registryStore.isAllowed(it) }
        publishSnapshot(pushToServer = true)
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
                publishSnapshot(pushToServer = true)
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

            publishSnapshot(pushToServer = true)
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
        pendingOfferRequest = false
        _syncOffer.value = null
        latestRemoteSnapshot = null
        stopSyncCountdown()
        setSyncState(SyncState.Connecting)
        syncRepository.start(this)
    }

    fun onServerPanelClick() {
        if (!syncEnabled) return
        pendingOfferRequest = true
        syncRepository.requestLatestSnapshot()
    }

    fun acceptSyncOffer() {
        val offer = _syncOffer.value ?: return
        applyRemoteQueue(offer.snapshot.queue)
        _syncOffer.value = null
        pendingOfferRequest = false
        updateSyncStateFromLatestSnapshot()
    }

    fun declineSyncOffer() {
        _syncOffer.value = null
        pendingOfferRequest = false
        updateSyncStateFromLatestSnapshot()
    }

    fun clearSyncOffer() {
        _syncOffer.value = null
        pendingOfferRequest = false
    }

    fun onSyncMessageShown() {
        _syncMessage.value = null
    }

    override fun onSyncStateChanged(state: SyncState) {
        if (_syncOffer.value != null) {
            return
        }

        when (state) {
            is SyncState.LockedByMe,
            is SyncState.LockedByOther -> startSyncCountdown()
            else -> {
                if (!shouldKeepSyncCountdownRunning()) {
                    stopSyncCountdown()
                }
            }
        }

        if (latestRemoteSnapshot != null) {
            updateSyncStateFromLatestSnapshot()
        } else {
            setSyncState(state)
        }
    }

    override fun onRemoteSnapshot(snapshot: RemoteQueueSnapshot) {
        latestRemoteSnapshot = snapshot

        if (pendingOfferRequest) {
            pendingOfferRequest = false

            val remoteQueue = snapshot.queue
            val localQueue = queueManager.snapshot()

            if (remoteQueue != localQueue) {
                val offer = SyncOffer(
                    snapshot = snapshot,
                    secondsRemaining = SYNC_OFFER_SECONDS
                )
                _syncOffer.postValue(offer)
                setSyncState(
                    SyncState.OfferAvailable(
                        authorNumber = snapshot.authorNumber,
                        secondsLeft = SYNC_OFFER_SECONDS
                    )
                )
                return
            }
        }

        updateSyncStateFromLatestSnapshot()
    }

    override fun onBlocked(reason: String) {
        pendingOfferRequest = false
        _syncMessage.postValue(reason)
    }

    override fun onCleared() {
        stopSyncCountdown()
        syncRepository.stop()
        networkMonitor.stop()
        super.onCleared()
    }

    private fun stopSync() {
        syncEnabled = false
        pendingOfferRequest = false
        latestRemoteSnapshot = null
        stopSyncCountdown()
        syncRepository.stop()
        _syncOffer.value = null
        setSyncState(SyncState.Off)
    }

    private fun tickRegistry() {
        _registryUiTick.value = (_registryUiTick.value ?: 0L) + 1L
    }

    private fun publishSnapshot(pushToServer: Boolean) {
        val snap = queueManager.snapshot()
        _queueItems.value = snap
        queueStateStore.save(snap)

        if (pushToServer && syncEnabled) {
            val myCarNumber = registryStore.getMyCar()
            syncRepository.pushSnapshot(
                queue = snap,
                authorNumber = myCarNumber
            )
        }
    }

    private fun applyRemoteQueue(remoteQueue: List<QueueItem>) {
        queueManager.restoreFromSnapshot(
            snapshot = remoteQueue,
            isNumberAllowedByRegistry = { n -> registryStore.isAllowed(n) }
        )

        queueManager.validateAgainstRegistry { registryStore.isAllowed(it) }
        publishSnapshot(pushToServer = false)
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

    private fun updateSyncStateFromLatestSnapshot() {
        if (!syncEnabled) return

        val snapshot = latestRemoteSnapshot
        if (snapshot == null) {
            setSyncState(SyncState.OnlineFree)
            return
        }

        val now = System.currentTimeMillis()
        val lockOwner = snapshot.lockOwnerDeviceId
        val secondsLeft = ((snapshot.lockUntilMs - now).coerceAtLeast(0L) / 1000L).toInt()

        val state = when {
            lockOwner.isNullOrBlank() || snapshot.lockUntilMs <= now -> SyncState.OnlineFree
            lockOwner == syncRepository.currentDeviceId() -> {
                SyncState.LockedByMe(
                    ownerNumber = snapshot.lockOwnerNumber,
                    secondsLeft = secondsLeft
                )
            }
            else -> {
                SyncState.LockedByOther(
                    ownerNumber = snapshot.lockOwnerNumber,
                    secondsLeft = secondsLeft
                )
            }
        }

        setSyncState(state)

        if (!shouldKeepSyncCountdownRunning()) {
            stopSyncCountdown()
        }
    }

    private fun startSyncCountdown() {
        stopSyncCountdown()
        mainHandler.post(syncCountdownRunnable)
    }

    private fun stopSyncCountdown() {
        mainHandler.removeCallbacks(syncCountdownRunnable)
    }

    private fun shouldKeepSyncCountdownRunning(): Boolean {
        if (!syncEnabled) return false
        val snapshot = latestRemoteSnapshot ?: return false
        val now = System.currentTimeMillis()
        return !snapshot.lockOwnerDeviceId.isNullOrBlank() && snapshot.lockUntilMs > now
    }

    private fun isQueueEditingBlocked(): Boolean {
        return syncEnabled && !syncRepository.isEditableNow()
    }

    private fun guardQueueEditing(): QueueManager.AddResult? {
        if (!isQueueEditingBlocked()) return null
        _syncMessage.postValue("Список сейчас изменяет другой телефон.")
        return QueueManager.AddResult.DuplicateInQueue
    }

    private fun guardQueueEditingOperation(): QueueManager.OperationResult? {
        if (!isQueueEditingBlocked()) return null
        _syncMessage.postValue("Список сейчас изменяет другой телефон.")
        return QueueManager.OperationResult.InvalidMove
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

    companion object {
        private const val SYNC_OFFER_SECONDS = 15
    }
}