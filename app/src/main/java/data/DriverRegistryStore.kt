package com.carlist.pro.data

import android.content.Context
import android.content.SharedPreferences
import com.carlist.pro.domain.TransportInfo
import com.carlist.pro.domain.TransportType

class DriverRegistryStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAllowedNumbers(): List<Int> {
        val raw = prefs.getStringSet(KEY_NUMBERS, emptySet()).orEmpty()
        val nums = raw.mapNotNull { it.toIntOrNull() }.filter { it in 1..99 }.distinct()
        return nums.sorted()
    }

    fun isAllowed(number: Int): Boolean {
        if (number !in 1..99) return false
        val raw = prefs.getStringSet(KEY_NUMBERS, emptySet()).orEmpty()
        return raw.contains(number.toString())
    }

    /**
     * Sets / replaces a number in registry.
     * If oldNumber is null -> add new.
     */
    fun upsertNumber(oldNumber: Int?, newNumber: Int): Result<Unit> {
        if (newNumber !in 1..99) return Result.failure(IllegalArgumentException("Invalid number"))
        val current = getAllowedNumbers().toMutableList()

        if (current.contains(newNumber) && newNumber != oldNumber) {
            return Result.failure(IllegalStateException("Duplicate number"))
        }

        if (oldNumber != null) {
            current.remove(oldNumber)
            // keep categories when number is changed? safer: migrate categories
            val oldInfo = getInfo(oldNumber)
            clearCategories(oldNumber)
            setInfo(newNumber, oldInfo)
        }

        if (!current.contains(newNumber)) current.add(newNumber)

        saveNumbers(current)
        return Result.success(Unit)
    }

    fun removeNumber(number: Int) {
        val current = getAllowedNumbers().toMutableList()
        current.remove(number)
        saveNumbers(current)
        clearCategories(number)
    }

    fun getInfo(number: Int): TransportInfo {
        val typeRaw = prefs.getString(keyType(number), TransportType.NONE.name) ?: TransportType.NONE.name
        val type = runCatching { TransportType.valueOf(typeRaw) }.getOrDefault(TransportType.NONE)
        val my = prefs.getBoolean(keyMyCar(number), false)
        return TransportInfo(type, my)
    }

    fun setInfo(number: Int, info: TransportInfo) {
        prefs.edit()
            .putString(keyType(number), info.transportType.name)
            .putBoolean(keyMyCar(number), info.isMyCar)
            .apply()
    }

    fun setTransportType(number: Int, type: TransportType) {
        prefs.edit().putString(keyType(number), type.name).apply()
    }

    fun toggleMyCar(number: Int) {
        val now = prefs.getBoolean(keyMyCar(number), false)
        prefs.edit().putBoolean(keyMyCar(number), !now).apply()
    }

    fun clearCategories(number: Int) {
        prefs.edit()
            .remove(keyType(number))
            .remove(keyMyCar(number))
            .apply()
    }

    private fun saveNumbers(numbers: List<Int>) {
        val set = numbers.map { it.toString() }.toSet()
        prefs.edit().putStringSet(KEY_NUMBERS, set).apply()
    }

    private fun keyType(number: Int) = "type_$number"
    private fun keyMyCar(number: Int) = "mycar_$number"

    companion object {
        private const val PREFS_NAME = "driver_registry_store"
        private const val KEY_NUMBERS = "allowed_numbers"
    }
}