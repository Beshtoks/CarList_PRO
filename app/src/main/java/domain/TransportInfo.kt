package com.carlist.pro.domain

data class TransportInfo(
    val transportType: TransportType = TransportType.NONE,
    val isMyCar: Boolean = false
) {
    fun letters(): String {
        val bOrV = when (transportType) {
            TransportType.BUS -> "B"
            TransportType.VAN -> "V"
            TransportType.NONE -> ""
        }
        val m = if (isMyCar) "M" else ""
        val res = bOrV + m
        return if (res.isBlank()) "-" else res
    }
}