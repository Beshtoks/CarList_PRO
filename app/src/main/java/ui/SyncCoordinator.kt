package com.carlist.pro.ui.sync

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.carlist.pro.data.DriverRegistryStore
import com.carlist.pro.data.sync.FirebaseSyncRepository
import com.carlist.pro.data.sync.NetworkMonitor
import com.carlist.pro.domain.QueueItem
import com.carlist.pro.domain.sync.RemoteQueueSnapshot
import com.carlist.pro.domain.sync.SyncOffer
import com.carlist.pro.domain.sync.SyncState

class SyncCoordinator(
    private val networkMonitor: NetworkMonitor,
    private val syncRepository: FirebaseSyncRepository,
    private val registryStore: DriverRegistryStore,
    private val queueSnapshotProvider: () -> List<QueueItem>,
    private val onApplyRemoteQueue: (List<QueueItem>) -> Unit,
    private val onSyncStateChanged: (SyncState) -> Unit,
    private val onSyncPanelTextChanged: (String) -> Unit,
    private val onSyncMessage: (String) -> Unit,
    private val onSyncOfferChanged: (SyncOffer?) -> Unit,
    private val onNetworkAlertVisible: (Boolean) -> Unit
) {

    private enum class OperationMode {
        IDLE,
        REFRESH,
        DOWNLOAD,
        UPLOAD
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var latestRemoteSnapshot: RemoteQueueSnapshot? = null
    private var lastKnownOnline: Boolean? = null
    private var currentOperation: OperationMode = OperationMode.IDLE
    private var operationInProgress = false
    private var networkAlertVisible = false

    private var pendingUploadAuthorNumber: Int? = null
    private var pendingUploadQueue: List<QueueItem> = emptyList()
    private var uploadCommandSent = false

    // Даём Android короткое время подтвердить сеть на старте,
    // чтобы не показывать ложное предупреждение сразу после запуска.
    private var startupNetworkGraceUntilMs: Long = 0L

    private val repositoryCallback = object : FirebaseSyncRepository.Callback {

        override fun onSyncStateChanged(state: SyncState) {
            when (currentOperation) {
                OperationMode.IDLE -> refreshUiState()
                OperationMode.REFRESH,
                OperationMode.DOWNLOAD,
                OperationMode.UPLOAD -> {
                    val uiState = if (!networkMonitor.isCurrentlyOnline()) {
                        SyncState.NoNetwork
                    } else {
                        SyncState.Connecting
                    }
                    setUiState(uiState)
                }
            }
        }

        override fun onRemoteSnapshot(snapshot: RemoteQueueSnapshot) {
            latestRemoteSnapshot = snapshot
            updatePanelText()

            when (currentOperation) {
                OperationMode.REFRESH -> {
                    finishOperation()
                }

                OperationMode.DOWNLOAD -> {
                    if (!isSnapshotUsable(snapshot)) {
                        onSyncMessage("ACTUAL LIST NOT AVAILABLE")
                        finishOperation()
                        return
                    }

                    val localQueue = queueSnapshotProvider()
                    if (snapshot.queue == localQueue) {
                        onSyncMessage("LIST IS ALREADY CURRENT")
                        finishOperation()
                        return
                    }

                    onApplyRemoteQueue(snapshot.queue)
                    onSyncMessage("LIST DOWNLOADED")
                    finishOperation()
                }

                OperationMode.UPLOAD -> {
                    if (!uploadCommandSent) return
                    if (!doesSnapshotMatchPendingUpload(snapshot)) return

                    onSyncMessage("LIST UPLOADED")
                    finishOperation()
                }

                OperationMode.IDLE -> {
                    refreshUiState()
                }
            }
        }

        override fun onBlocked(reason: String) {
            when (currentOperation) {
                OperationMode.REFRESH -> {
                    if (reason == "ACTUAL LIST NOT AVAILABLE") {
                        latestRemoteSnapshot = null
                        updatePanelText()
                    }
                    finishOperation()
                }

                OperationMode.DOWNLOAD,
                OperationMode.UPLOAD -> {
                    if (reason.isNotBlank()) {
                        onSyncMessage(reason)
                    }
                    if (reason == "ACTUAL LIST NOT AVAILABLE") {
                        latestRemoteSnapshot = null
                        updatePanelText()
                    }
                    finishOperation()
                }

                OperationMode.IDLE -> {
                    if (reason.isNotBlank()) {
                        onSyncMessage(reason)
                    }
                }
            }
        }
    }

    private val networkStatePollRunnable = object : Runnable {
        override fun run() {
            val onlineNow = networkMonitor.isCurrentlyOnline()

            if (lastKnownOnline == null || lastKnownOnline != onlineNow) {
                handleNetworkChanged(onlineNow)
            }

            mainHandler.postDelayed(this, NETWORK_POLL_INTERVAL_MS)
        }
    }

    private val panelRefreshRunnable = object : Runnable {
        override fun run() {
            updatePanelText()
            mainHandler.postDelayed(this, PANEL_REFRESH_INTERVAL_MS)
        }
    }

    fun initialize() {
        startupNetworkGraceUntilMs = SystemClock.elapsedRealtime() + STARTUP_NETWORK_GRACE_MS

        networkMonitor.start { isOnline ->
            handleNetworkChanged(isOnline)
        }

        startNetworkStatePolling()
        startPanelRefresh()
        onSyncOfferChanged(null)

        val onlineNow = networkMonitor.isCurrentlyOnline()
        lastKnownOnline = onlineNow

        if (!onlineNow) {
            refreshUiState(ignoreOfflineDuringStartupGrace = true)

            if (!isWithinStartupNetworkGrace()) {
                networkAlertVisible = true
                onNetworkAlertVisible(true)
            }
            updatePanelText()
            return
        }

        refreshUiState()
        refreshRemoteInfoSilently()
    }

    fun onServerPanelLongPress() {
        // no action
    }

    fun onServerPanelClick() {
        if (!networkMonitor.isCurrentlyOnline()) {
            showNoNetworkAlert()
            return
        }

        if (operationInProgress) return

        onSyncOfferChanged(null)
        startOperation(OperationMode.DOWNLOAD)

        mainHandler.postDelayed({
            if (currentOperation != OperationMode.DOWNLOAD) return@postDelayed
            syncRepository.requestLatestSnapshot()
        }, OPERATION_START_DELAY_MS)
    }

    fun onServerPanelDoubleTapUpload() {
        if (!canUploadCurrentQueue()) return

        if (!networkMonitor.isCurrentlyOnline()) {
            showNoNetworkAlert()
            return
        }

        if (operationInProgress) return

        val authorNumber = registryStore.getMyCar()
        val queue = queueSnapshotProvider()

        if (authorNumber == null || queue.isEmpty()) {
            if (authorNumber == null) {
                onSyncMessage("SET MY CAR FIRST")
            } else {
                onSyncMessage("EMPTY LIST - UPLOAD NOT POSSIBLE")
            }
            return
        }

        onSyncOfferChanged(null)
        pendingUploadAuthorNumber = authorNumber
        pendingUploadQueue = queue
        uploadCommandSent = false

        startOperation(OperationMode.UPLOAD)

        mainHandler.postDelayed({
            if (currentOperation != OperationMode.UPLOAD) return@postDelayed

            uploadCommandSent = true
            syncRepository.pushSnapshot(
                queue = queue,
                authorNumber = authorNumber
            )

            mainHandler.postDelayed({
                if (currentOperation != OperationMode.UPLOAD) return@postDelayed
                if (!uploadCommandSent) return@postDelayed
                syncRepository.requestLatestSnapshot()
            }, UPLOAD_REFRESH_REQUEST_DELAY_MS)
        }, OPERATION_START_DELAY_MS)
    }

    fun canUploadCurrentQueue(): Boolean {
        val queue = queueSnapshotProvider()

        if (queue.isEmpty()) {
            onSyncMessage("EMPTY LIST - UPLOAD NOT POSSIBLE")
            return false
        }

        if (registryStore.getMyCar() == null) {
            onSyncMessage("SET MY CAR FIRST")
            return false
        }

        return true
    }

    fun acceptSyncOffer() {
        onSyncOfferChanged(null)
    }

    fun declineSyncOffer() {
        onSyncOfferChanged(null)
    }

    fun clearSyncOffer() {
        onSyncOfferChanged(null)
    }

    fun onNetworkAlertAcknowledged() {
        networkAlertVisible = false
        onNetworkAlertVisible(false)

        if (!networkMonitor.isCurrentlyOnline()) {
            setUiState(SyncState.NoNetwork)
            return
        }

        refreshUiState()
        refreshRemoteInfoSilently()
    }

    fun onLocalSnapshotChanged(snapshot: List<QueueItem>) {
        // No live sync in the new model.
    }

    fun isQueueEditingBlocked(): Boolean {
        return false
    }

    fun refreshPanelState() {
        refreshUiState()
        if (networkMonitor.isCurrentlyOnline() && !operationInProgress) {
            refreshRemoteInfoSilently()
        }
    }

    fun release() {
        stopNetworkStatePolling()
        stopPanelRefresh()
        onSyncOfferChanged(null)
        currentOperation = OperationMode.IDLE
        operationInProgress = false
        clearPendingUploadState()
        syncRepository.stop()
        networkMonitor.stop()
    }

    private fun refreshRemoteInfoSilently() {
        if (!networkMonitor.isCurrentlyOnline()) {
            updatePanelText()
            return
        }

        if (operationInProgress) return

        startOperation(OperationMode.REFRESH)

        mainHandler.postDelayed({
            if (currentOperation != OperationMode.REFRESH) return@postDelayed
            syncRepository.requestLatestSnapshot()
        }, OPERATION_START_DELAY_MS)
    }

    private fun startOperation(mode: OperationMode) {
        currentOperation = mode
        operationInProgress = true
        setUiState(SyncState.Connecting)
        syncRepository.start(repositoryCallback)
    }

    private fun finishOperation() {
        currentOperation = OperationMode.IDLE
        operationInProgress = false
        clearPendingUploadState()
        syncRepository.stop()
        refreshUiState()
    }

    private fun refreshUiState(ignoreOfflineDuringStartupGrace: Boolean = false) {
        val offlineButGraceActive =
            !networkMonitor.isCurrentlyOnline() &&
                    ignoreOfflineDuringStartupGrace &&
                    isWithinStartupNetworkGrace()

        val state = if (!offlineButGraceActive && !networkMonitor.isCurrentlyOnline()) {
            SyncState.NoNetwork
        } else {
            SyncState.Off
        }
        setUiState(state)
    }

    private fun handleNetworkChanged(isOnline: Boolean) {
        lastKnownOnline = isOnline

        if (!isOnline) {
            operationInProgress = false
            currentOperation = OperationMode.IDLE
            onSyncOfferChanged(null)
            clearPendingUploadState()
            syncRepository.stop()

            if (isWithinStartupNetworkGrace()) {
                refreshUiState(ignoreOfflineDuringStartupGrace = true)
                return
            }

            if (!networkAlertVisible) {
                networkAlertVisible = true
                onNetworkAlertVisible(true)
            }

            setUiState(SyncState.NoNetwork)
            return
        }

        // Сеть подтверждена — стартовый grace больше не нужен.
        startupNetworkGraceUntilMs = 0L

        if (networkAlertVisible) {
            networkAlertVisible = false
            onNetworkAlertVisible(false)
        }

        refreshUiState()

        if (!operationInProgress) {
            refreshRemoteInfoSilently()
        }
    }

    private fun showNoNetworkAlert() {
        if (!networkAlertVisible) {
            networkAlertVisible = true
            onNetworkAlertVisible(true)
        }
        setUiState(SyncState.NoNetwork)
    }

    private fun isSnapshotUsable(
        snapshot: RemoteQueueSnapshot,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val author = snapshot.authorNumber
        if (author == null || author !in 1..99) return false
        if (snapshot.queue.isEmpty()) return false
        if (snapshot.updatedAtMs <= 0L) return false

        val ageMs = (nowMs - snapshot.updatedAtMs).coerceAtLeast(0L)
        return ageMs <= SERVER_LIST_TTL_MS
    }

    private fun doesSnapshotMatchPendingUpload(snapshot: RemoteQueueSnapshot): Boolean {
        val author = pendingUploadAuthorNumber ?: return false
        if (snapshot.authorNumber != author) return false
        if (snapshot.queue != pendingUploadQueue) return false
        if (!isSnapshotUsable(snapshot)) return false
        return true
    }

    private fun clearPendingUploadState() {
        uploadCommandSent = false
        pendingUploadAuthorNumber = null
        pendingUploadQueue = emptyList()
    }

    private fun startNetworkStatePolling() {
        stopNetworkStatePolling()
        mainHandler.post(networkStatePollRunnable)
    }

    private fun stopNetworkStatePolling() {
        mainHandler.removeCallbacks(networkStatePollRunnable)
    }

    private fun startPanelRefresh() {
        stopPanelRefresh()
        mainHandler.post(panelRefreshRunnable)
    }

    private fun stopPanelRefresh() {
        mainHandler.removeCallbacks(panelRefreshRunnable)
    }

    private fun setUiState(state: SyncState) {
        onSyncStateChanged(state)
        updatePanelText()
    }

    private fun updatePanelText() {
        onSyncPanelTextChanged(formatPanelText())
    }

    private fun formatPanelText(nowMs: Long = System.currentTimeMillis()): String {
        val snapshot = latestRemoteSnapshot ?: return PANEL_NO_LIST

        val updatedAtMs = snapshot.updatedAtMs
        val author = snapshot.authorNumber

        if (updatedAtMs <= 0L) return PANEL_NO_LIST
        if (snapshot.queue.isEmpty()) return PANEL_NO_LIST
        if (author == null || author !in 1..99) return PANEL_NO_LIST

        val ageMs = (nowMs - updatedAtMs).coerceAtLeast(0L)
        if (ageMs > SERVER_LIST_TTL_MS) return PANEL_NO_LIST

        val minutes = (ageMs / 60_000L).toInt()
        return "SYNC $author · ${minutes}m"
    }

    private fun isWithinStartupNetworkGrace(): Boolean {
        return startupNetworkGraceUntilMs != 0L &&
                SystemClock.elapsedRealtime() < startupNetworkGraceUntilMs
    }

    companion object {
        private const val NETWORK_POLL_INTERVAL_MS = 1_000L
        private const val PANEL_REFRESH_INTERVAL_MS = 30_000L
        private const val OPERATION_START_DELAY_MS = 350L
        private const val UPLOAD_REFRESH_REQUEST_DELAY_MS = 900L
        private const val SERVER_LIST_TTL_MS = 300 * 60 * 1000L
        private const val PANEL_NO_LIST = "SYNC --"
        private const val STARTUP_NETWORK_GRACE_MS = 2500L
    }
}