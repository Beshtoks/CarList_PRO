package com.carlist.pro.domain

/**
 * Domain model for the queue list.
 *
 * Categories (BUS/VAN/MY_CAR) are NOT stored here long-term.
 * They will be provided from TransportStore later.
 */
data class QueueItem(
    val number: Int,
    val status: Status = Status.NONE
) {
    val isActive: Boolean
        get() = status != Status.SERVICE
}