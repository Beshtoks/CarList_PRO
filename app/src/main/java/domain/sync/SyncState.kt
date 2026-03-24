package com.carlist.pro.domain.sync

sealed interface SyncState {

    data object Off : SyncState

    data object Connecting : SyncState

    data object NoNetwork : SyncState

    data object NeedMyCar : SyncState

    data object OnlineFree : SyncState

    data class LockedByMe(
        val ownerNumber: Int?,
        val secondsLeft: Int
    ) : SyncState

    data class LockedByOther(
        val ownerNumber: Int?,
        val secondsLeft: Int
    ) : SyncState

    data class OfferAvailable(
        val authorNumber: Int?,
        val secondsLeft: Int
    ) : SyncState
}