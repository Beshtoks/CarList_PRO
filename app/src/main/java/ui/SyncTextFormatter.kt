package com.carlist.pro.ui

import com.carlist.pro.domain.sync.SyncState

object SyncTextFormatter {

    fun format(state: SyncState): String {
        return when (state) {
            SyncState.Off -> "SYNC --"
            SyncState.Connecting -> "SYNC --"
            SyncState.NoNetwork -> "SYNC --"
            SyncState.NeedMyCar -> "SYNC --"
            SyncState.OnlineFree -> "SYNC --"
            is SyncState.LockedByMe -> "SYNC --"
            is SyncState.LockedByOther -> "SYNC --"
            is SyncState.OfferAvailable -> "SYNC --"
        }
    }
}