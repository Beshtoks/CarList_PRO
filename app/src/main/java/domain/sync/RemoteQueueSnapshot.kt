package com.carlist.pro.domain.sync

import com.carlist.pro.domain.QueueItem

data class RemoteQueueSnapshot(
    val queue: List<QueueItem> = emptyList(),
    val authorNumber: Int? = null,
    val updatedAtMs: Long = 0L,
    val offerExpiresAtMs: Long = 0L,
    val lockOwnerDeviceId: String? = null,
    val lockOwnerNumber: Int? = null,
    val lockUntilMs: Long = 0L
) {
    fun offerSecondsRemaining(nowMs: Long = System.currentTimeMillis()): Int {
        if (offerExpiresAtMs <= 0L) return 0
        return ((offerExpiresAtMs - nowMs).coerceAtLeast(0L) / 1000L).toInt()
    }

    fun lockSecondsRemaining(nowMs: Long = System.currentTimeMillis()): Int {
        if (lockUntilMs <= 0L) return 0
        return ((lockUntilMs - nowMs).coerceAtLeast(0L) / 1000L).toInt()
    }
}