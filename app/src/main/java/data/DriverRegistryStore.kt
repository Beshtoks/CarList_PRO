package com.carlist.pro.data

import android.content.Context
import android.content.SharedPreferences
import com.carlist.pro.domain.TransportInfo
import com.carlist.pro.domain.TransportType

class DriverRegistryStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAllowedNumbers(): List<Int> {
        return readNumbers()
    }

    fun isAllowed(number: Int): Boolean {
        if (number !in 1..99) return false
        return readNumbers().contains(number)
    }

    /**
     * If oldNumber is null -> append new number to the end.
     * If oldNumber exists -> replace it in the same position.
     */
    fun upsertNumber(oldNumber: Int?, newNumber: Int): Result<Unit> {
        if (newNumber !in 1..99) {
            return Result.failure(IllegalArgumentException("Invalid number"))
        }

        val current = readNumbers().toMutableList()

        if (current.contains(newNumber) && newNumber != oldNumber) {
            return Result.failure(IllegalStateException("Duplicate number"))
        }

        if (oldNumber == null) {
            if (!current.contains(newNumber)) {
                current.add(newNumber)
            }
            saveNumbers(current)
            return Result.success(Unit)
        }

        val oldIndex = current.indexOf(oldNumber)
        if (oldIndex == -1) {
            return Result.failure(IllegalStateException("Old number not found"))
        }

        val oldInfo = getInfo(oldNumber)
        clearCategories(oldNumber)

        current[oldIndex] = newNumber
        saveNumbers(current)
        setInfo(newNumber, oldInfo)

        return Result.success(Unit)
    }

    fun removeNumber(number: Int) {
        val current = readNumbers().toMutableList()
        val index = current.indexOf(number)
        if (index != -1) {
            current.removeAt(index)
            saveNumbers(current)
        }
        clearCategories(number)
    }

    fun sortNumbersAscending() {
        val sorted = readNumbers()
            .sorted()
            .distinct()

        saveNumbers(sorted)
    }

    fun getInfo(number: Int): TransportInfo {
        val typeRaw = prefs.getString(
            keyType(number),
            TransportType.NONE.name
        ) ?: TransportType.NONE.name

        val type = runCatching {
            TransportType.valueOf(typeRaw)
        }.getOrDefault(TransportType.NONE)

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
        prefs.edit()
            .putString(keyType(number), type.name)
            .apply()
    }

    fun toggleMyCar(number: Int) {
        val now = prefs.getBoolean(keyMyCar(number), false)
        prefs.edit()
            .putBoolean(keyMyCar(number), !now)
            .apply()
    }

    fun clearCategories(number: Int) {
        prefs.edit()
            .remove(keyType(number))
            .remove(keyMyCar(number))
            .apply()
    }

    private fun readNumbers(): List<Int> {
        val raw = prefs.getString(KEY_NUMBERS_ORDERED, "").orEmpty()
        if (raw.isBlank()) return emptyList()

        return raw.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..99 }
            .distinct()
    }

    private fun saveNumbers(numbers: List<Int>) {
        val normalized = numbers
            .filter { it in 1..99 }
            .distinct()
            .joinToString(",")

        prefs.edit()
            .putString(KEY_NUMBERS_ORDERED, normalized)
            .apply()
    }

    private fun keyType(number: Int) = "type_$number"
    private fun keyMyCar(number: Int) = "mycar_$number"

    companion object {
        private const val PREFS_NAME = "driver_registry_store"
        private const val KEY_NUMBERS_ORDERED = "allowed_numbers_ordered"
    }
}