package com.carlist.pro.domain

/**
 * Domain model for the queue list.
 *
 * Categories (BUS/VAN/MY_CAR) are NOT stored here long-term.
 * They are resolved from registry/transport data outside the queue item itself.
 */
data class QueueItem(
    val number: Int,
    val status: Status = Status.NONE
) {
    val isActive: Boolean
        get() = status != Status.SERVICE
}