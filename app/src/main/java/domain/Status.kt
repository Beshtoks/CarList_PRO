package com.carlist.pro.domain

/**
 * Queue item status.
 *
 * ACTIVE  = status != SERVICE
 * PASSIVE = status == SERVICE
 */
enum class Status {
    NONE,       // default "standard"
    SERVICE,    // passive
    JURNIEKS    // active
}