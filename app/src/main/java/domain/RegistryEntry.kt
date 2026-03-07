package com.carlist.pro.domain

data class RegistryEntry(
    val number: Int,
    val transportType: TransportType = TransportType.NONE,
    val isMyCar: Boolean = false
)