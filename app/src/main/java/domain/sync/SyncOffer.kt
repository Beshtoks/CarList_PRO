package com.carlist.pro.domain.sync

data class SyncOffer(
    val snapshot: RemoteQueueSnapshot,
    val secondsRemaining: Int
)