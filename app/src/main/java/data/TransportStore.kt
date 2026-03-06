package com.carlist.pro.data

import android.content.Context
import android.content.SharedPreferences
import com.carlist.pro.domain.TransportInfo
import com.carlist.pro.domain.TransportType

class TransportStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getInfo(number: Int): TransportInfo {
        return TransportInfo(
            transportType = getTransportType(number),
            isMyCar = isMyCar(number)
        )
    }

    fun getTransportType(number: Int): TransportType {
        val raw = prefs.getString(keyType(number), TransportType.NONE.name) ?: TransportType.NONE.name
        return runCatching { TransportType.valueOf(raw) }.getOrDefault(TransportType.NONE)
    }

    fun setTransportType(number: Int, type: TransportType) {
        prefs.edit().putString(keyType(number), type.name).apply()
    }

    fun isMyCar(number: Int): Boolean {
        return prefs.getBoolean(keyMyCar(number), false)
    }

    fun setMyCar(number: Int, enabled: Boolean) {
        prefs.edit().putBoolean(keyMyCar(number), enabled).apply()
    }

    fun clear(number: Int) {
        prefs.edit()
            .remove(keyType(number))
            .remove(keyMyCar(number))
            .apply()
    }

    private fun keyType(number: Int) = "type_$number"
    private fun keyMyCar(number: Int) = "mycar_$number"

    companion object {
        private const val PREFS_NAME = "transport_store"
    }
}