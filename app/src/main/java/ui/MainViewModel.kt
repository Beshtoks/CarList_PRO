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
import com.carlist.pro.domain.sync.SyncOffer
import com.carlist.pro.domain.sync.SyncState
import com.carlist.pro.ui.adapter.CommitResult
import com.carlist.pro.ui.adapter.DriverRegistryAdapter
import com.carlist.pro.ui.adapter.RegistryRow
import com.carlist.pro.ui.controller.QueueEditController
import com.carlist.pro.ui.controller.QueueStateCoordinator
import com.carlist.pro.ui.controller.RegistryController
import com.carlist.pro.ui.sync.SyncCoordinator

class MainViewModel(app: Application) : AndroidViewModel(app) {

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

    private val _networkAlertVisible = MutableLiveData(false)
    val networkAlertVisible: LiveData<Boolean> = _networkAlertVisible

    private lateinit var syncCoordinator: SyncCoordinator
    private lateinit var queueStateCoordinator: QueueStateCoordinator
    private lateinit var registryController: RegistryController
    private lateinit var queueEditController: QueueEditController

    init {
        syncCoordinator = SyncCoordinator(
            networkMonitor = networkMonitor,
            syncRepository = syncRepository,
            registryStore = registryStore,
            queueSnapshotProvider = { queueManager.snapshot() },
            onApplyRemoteQueue = { remoteQueue ->
                queueStateCoordinator.applyRemoteQueue(remoteQueue)
            },
            onSyncStateChanged = { state ->
                _syncState.postValue(state)
            },
            onSyncPanelTextChanged = { text ->
                _syncPanelText.postValue(text)
            },
            onSyncMessage = { message ->
                _syncMessage.postValue(message)
            },
            onSyncOfferChanged = { offer ->
                _syncOffer.postValue(offer)
            },
            onNetworkAlertVisible = { visible ->
                _networkAlertVisible.postValue(visible)
            }
        )

        queueStateCoordinator = QueueStateCoordinator(
            queueManager = queueManager,
            queueStateStore = queueStateStore,
            registryStore = registryStore,
            onQueuePublished = { snapshot ->
                _queueItems.value = snapshot
            },
            onPushSnapshotRequested = { snapshot ->
                syncCoordinator.onLocalSnapshotChanged(snapshot)
            }
        )

        registryController = RegistryController(
            registryStore = registryStore,
            queueManager = queueManager,
            publishSnapshot = { pushToServer ->
                queueStateCoordinator.publishSnapshot(pushToServer)
            },
            tickRegistry = { tickRegistry() },
            refreshSyncPanelState = { syncCoordinator.refreshPanelState() }
        )

        queueEditController = QueueEditController(
            queueManager = queueManager,
            registryStore = registryStore,
            isQueueEditingBlocked = { syncCoordinator.isQueueEditingBlocked() },
            onBlockedMessage = { message -> _syncMessage.postValue(message) },
            publishSnapshot = { pushToServer ->
                queueStateCoordinator.publishSnapshot(pushToServer)
            }
        )

        queueStateCoordinator.initializeFromStorage()
        syncCoordinator.initialize()
    }

    fun replaceNumber(oldNumber: Int, newNumber: Int): QueueManager.OperationResult {
        val result = queueManager.replaceNumber(
            oldNumber = oldNumber,
            newNumber = newNumber,
            isNumberAllowedByRegistry = { registryStore.isAllowed(it) }
        )

        if (result is QueueManager.OperationResult.Success) {
            queueStateCoordinator.publishSnapshot(true)
        }

        return result
    }

    fun addNumber(numberOrNull: Int?): QueueManager.AddResult {
        return queueEditController.addNumber(numberOrNull)
    }

    fun removeAt(index: Int): QueueManager.OperationResult {
        return queueEditController.removeAt(index)
    }

    fun removeByNumber(number: Int): QueueManager.OperationResult {
        return queueEditController.removeByNumber(number)
    }

    fun setStatus(number: Int, status: Status): QueueManager.OperationResult {
        return queueEditController.setStatus(number, status)
    }

    fun clear(): QueueManager.OperationResult {
        return queueEditController.clear()
    }

    fun moveForDrag(from: Int, to: Int): QueueManager.OperationResult {
        return queueEditController.moveForDrag(from, to)
    }

    fun commitDrag() {
        queueEditController.commitDrag()
    }

    fun validateQueueAgainstRegistry(): QueueManager.ValidationResult {
        return queueEditController.validateQueueAgainstRegistry()
    }

    fun getTransportInfo(number: Int): TransportInfo {
        return registryStore.getInfo(number)
    }

    fun findMyCarPosition(queue: List<QueueItem>): Int? {
        val idx = queue.indexOfFirst { registryStore.getInfo(it.number).isMyCar }
        return if (idx == -1) null else idx + 1
    }

    fun getRegistryRows(): List<RegistryRow> {
        return registryController.getRegistryRows()
    }

    fun setRegistryActiveRow(pos: Int) {
        registryController.setRegistryActiveRow(pos)
    }

    fun getRegistryActiveRow(): Int {
        return registryController.getRegistryActiveRow()
    }

    fun commitRegistryNumber(position: Int, oldNumber: Int?, newText: String): CommitResult {
        return registryController.commitRegistryNumber(position, oldNumber, newText)
    }

    fun sortRegistryNumbersForClose() {
        registryController.sortRegistryNumbersForClose()
    }

    fun onRegistryCategoryAction(number: Int, action: DriverRegistryAdapter.CategoryAction) {
        registryController.onRegistryCategoryAction(number, action)
    }

    fun onServerPanelLongPress() {
        syncCoordinator.onServerPanelLongPress()
    }

    fun onServerPanelClick() {
        syncCoordinator.onServerPanelClick()
    }

    fun onServerPanelDoubleTapUpload() {
        syncCoordinator.onServerPanelDoubleTapUpload()
    }

    fun canUploadCurrentQueue(): Boolean {
        return syncCoordinator.canUploadCurrentQueue()
    }

    fun getCurrentQueueSize(): Int {
        return queueManager.snapshot().size
    }

    fun hasMyCarConfigured(): Boolean {
        return registryStore.getMyCar() != null
    }

    fun acceptSyncOffer() {
        syncCoordinator.acceptSyncOffer()
    }

    fun declineSyncOffer() {
        syncCoordinator.declineSyncOffer()
    }

    fun clearSyncOffer() {
        syncCoordinator.clearSyncOffer()
    }

    fun onNetworkAlertAcknowledged() {
        syncCoordinator.onNetworkAlertAcknowledged()
    }

    fun onSyncMessageShown() {
        _syncMessage.value = null
    }

    override fun onCleared() {
        syncCoordinator.release()
        super.onCleared()
    }

    private fun tickRegistry() {
        _registryUiTick.value = (_registryUiTick.value ?: 0L) + 1L
    }
}