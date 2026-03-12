package com.carlist.pro.ui.sync

import com.carlist.pro.domain.sync.SyncState

object SyncTextFormatter {

    fun format(state: SyncState): String {
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