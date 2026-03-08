package com.carlist.pro.domain

/**
 * Queue item status.
 *
 * ACTIVE  = status != SERVICE && status != OFFICE
 * PASSIVE = status == SERVICE || status == OFFICE
 */
enum class Status {
    NONE,       // default "standard"
    SERVICE,    // passive
    OFFICE,     // passive
    JURNIEKS    // active
}