package com.carlist.pro.ui.sync

import android.os.Handler
import android.os.Looper
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

    private val mainHandler = Handler(Looper.getMainLooper())

    private var syncEnabled = false
    private var pendingOfferRequest = false
    private var latestRemoteSnapshot: RemoteQueueSnapshot? = null

    private var networkAlertActive = false
    private var shouldRestoreSyncAfterNetworkReturn = false
    private var heartbeatMisses = 0
    private var lastKnownOnline: Boolean? = null
    private var currentOffer: SyncOffer? = null

    private val repositoryCallback = object : FirebaseSyncRepository.Callback {

        override fun onSyncStateChanged(state: SyncState) {
            if (currentOffer != null || networkAlertActive) {
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
                val localQueue = queueSnapshotProvider()

                if (remoteQueue != localQueue) {
                    val offer = SyncOffer(
                        snapshot = snapshot,
                        secondsRemaining = SYNC_OFFER_SECONDS
                    )
                    setCurrentOffer(offer)
                    setSyncState(
                        SyncState.OfferAvailable(
                            authorNumber = snapshot.authorNumber,
                            secondsLeft = SYNC_OFFER_SECONDS
                        )
                    )
                    return
                }
            }

            if (!networkAlertActive) {
                updateSyncStateFromLatestSnapshot()
            }
        }

        override fun onBlocked(reason: String) {
            pendingOfferRequest = false
            onSyncMessage(reason)
        }
    }

    private val syncCountdownRunnable = object : Runnable {
        override fun run() {
            updateSyncStateFromLatestSnapshot()
            if (shouldKeepSyncCountdownRunning()) {
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    private val syncHeartbeatRunnable = object : Runnable {
        override fun run() {
            if (!syncEnabled) return

            val onlineNow = networkMonitor.isCurrentlyOnline()
            if (!onlineNow) {
                handleNetworkChanged(false)
                return
            }

            syncRepository.pingServer { ok ->
                if (!syncEnabled) return@pingServer

                if (ok) {
                    heartbeatMisses = 0
                } else {
                    heartbeatMisses += 1
                    if (heartbeatMisses >= HEARTBEAT_FAIL_LIMIT) {
                        handleSyncConnectionLost()
                        return@pingServer
                    }
                }

                if (syncEnabled) {
                    mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
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

    fun initialize() {
        networkMonitor.start { isOnline ->
            handleNetworkChanged(isOnline)
        }

        startNetworkStatePolling()

        val onlineNow = networkMonitor.isCurrentlyOnline()
        lastKnownOnline = onlineNow

        if (!onlineNow) {
            networkAlertActive = true
            onNetworkAlertVisible(true)
            setSyncState(SyncState.NoNetwork)
        } else {
            refreshSyncPanelState()
        }
    }

    fun onServerPanelLongPress() {
        if (syncEnabled) {
            stopSync()
            return
        }

        if (!networkMonitor.isCurrentlyOnline()) {
            networkAlertActive = true
            onNetworkAlertVisible(true)
            setSyncState(SyncState.NoNetwork)
            return
        }

        if (registryStore.getMyCar() == null) {
            setSyncState(SyncState.NeedMyCar)
            onSyncMessage("Для синхронизации сначала укажи личный номер в категории MY CAR.")
            return
        }

        shouldRestoreSyncAfterNetworkReturn = false
        syncEnabled = true
        pendingOfferRequest = false
        setCurrentOffer(null)
        latestRemoteSnapshot = null
        stopSyncCountdown()
        startSyncHeartbeat()
        setSyncState(SyncState.Connecting)
        syncRepository.start(repositoryCallback)
    }

    fun onServerPanelClick() {
        if (!syncEnabled) return
        pendingOfferRequest = true
        syncRepository.requestLatestSnapshot()
    }

    fun acceptSyncOffer() {
        val offer = currentOffer ?: return
        onApplyRemoteQueue(offer.snapshot.queue)
        setCurrentOffer(null)
        pendingOfferRequest = false
        updateSyncStateFromLatestSnapshot()
    }

    fun declineSyncOffer() {
        setCurrentOffer(null)
        pendingOfferRequest = false
        updateSyncStateFromLatestSnapshot()
    }

    fun clearSyncOffer() {
        setCurrentOffer(null)
        pendingOfferRequest = false
    }

    fun onNetworkAlertAcknowledged() {
        networkAlertActive = false
        onNetworkAlertVisible(false)

        if (networkMonitor.isCurrentlyOnline() && shouldRestoreSyncAfterNetworkReturn) {
            reconnectAfterNetworkReturn()
        } else if (!networkMonitor.isCurrentlyOnline()) {
            setSyncState(SyncState.NoNetwork)
        } else {
            refreshSyncPanelState()
        }
    }

    fun onLocalSnapshotChanged(snapshot: List<QueueItem>) {
        if (!syncEnabled) return
        if (snapshot.isEmpty()) return

        syncRepository.pushSnapshot(
            queue = snapshot,
            authorNumber = registryStore.getMyCar()
        )
    }

    fun isQueueEditingBlocked(): Boolean {
        return syncEnabled && !syncRepository.isEditableNow()
    }

    fun refreshPanelState() {
        refreshSyncPanelState()
    }

    fun release() {
        stopSyncCountdown()
        stopSyncHeartbeat()
        stopNetworkStatePolling()
        syncRepository.stop()
        networkMonitor.stop()
    }

    private fun setCurrentOffer(offer: SyncOffer?) {
        currentOffer = offer
        onSyncOfferChanged(offer)
    }

    private fun handleNetworkChanged(isOnline: Boolean) {
        lastKnownOnline = isOnline

        if (!isOnline) {
            if (!networkAlertActive) {
                networkAlertActive = true
                onNetworkAlertVisible(true)
            }

            if (syncEnabled) {
                handleSyncConnectionLost()
            } else {
                setSyncState(SyncState.NoNetwork)
            }
            return
        }

        if (networkAlertActive) {
            networkAlertActive = false
            onNetworkAlertVisible(false)
        }

        if (shouldRestoreSyncAfterNetworkReturn) {
            reconnectAfterNetworkReturn()
            return
        }

        refreshSyncPanelState()
    }

    private fun handleSyncConnectionLost() {
        shouldRestoreSyncAfterNetworkReturn = true
        syncEnabled = false
        pendingOfferRequest = false
        latestRemoteSnapshot = null
        heartbeatMisses = 0
        stopSyncCountdown()
        stopSyncHeartbeat()
        setCurrentOffer(null)
        syncRepository.stop()
        setSyncState(SyncState.NoNetwork)
    }

    private fun reconnectAfterNetworkReturn() {
        if (!networkMonitor.isCurrentlyOnline()) {
            setSyncState(SyncState.NoNetwork)
            return
        }

        if (registryStore.getMyCar() == null) {
            shouldRestoreSyncAfterNetworkReturn = false
            refreshSyncPanelState()
            return
        }

        shouldRestoreSyncAfterNetworkReturn = false
        syncEnabled = true
        pendingOfferRequest = false
        latestRemoteSnapshot = null
        heartbeatMisses = 0
        stopSyncCountdown()
        startSyncHeartbeat()

        setSyncState(SyncState.Connecting)
        syncRepository.start(repositoryCallback)
    }

    private fun startSyncHeartbeat() {
        heartbeatMisses = 0
        stopSyncHeartbeat()
        mainHandler.postDelayed(syncHeartbeatRunnable, HEARTBEAT_INTERVAL_MS)
    }

    private fun stopSyncHeartbeat() {
        mainHandler.removeCallbacks(syncHeartbeatRunnable)
    }

    private fun startNetworkStatePolling() {
        stopNetworkStatePolling()
        mainHandler.post(networkStatePollRunnable)
    }

    private fun stopNetworkStatePolling() {
        mainHandler.removeCallbacks(networkStatePollRunnable)
    }

    private fun stopSync() {
        shouldRestoreSyncAfterNetworkReturn = false
        syncEnabled = false
        pendingOfferRequest = false
        latestRemoteSnapshot = null
        heartbeatMisses = 0
        stopSyncCountdown()
        stopSyncHeartbeat()
        syncRepository.stop()
        setCurrentOffer(null)
        setSyncState(SyncState.Off)
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

    private fun setSyncState(state: SyncState) {
        onSyncStateChanged(state)
        onSyncPanelTextChanged(SyncTextFormatter.format(state))
    }

    companion object {
        private const val SYNC_OFFER_SECONDS = 15
        private const val HEARTBEAT_INTERVAL_MS = 3_000L
        private const val HEARTBEAT_FAIL_LIMIT = 1
        private const val NETWORK_POLL_INTERVAL_MS = 1_000L
    }
}
