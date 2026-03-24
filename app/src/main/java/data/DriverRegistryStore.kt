package com.carlist.pro.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.carlist.pro.domain.RegistryEntry
import com.carlist.pro.domain.TransportInfo
import com.carlist.pro.domain.TransportType

class DriverRegistryStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAllowedNumbers(): List<Int> {
        val numbers = readNumbersSafe()
        saveNumbers(numbers)
        normalizeSingleMyCar()
        return numbers
    }

    fun getEntries(): List<RegistryEntry> {
        val numbers = getAllowedNumbers()

        return numbers.map { number ->
            val info = getInfo(number)
            RegistryEntry(
                number = number,
                transportType = info.transportType,
                isMyCar = info.isMyCar
            )
        }
    }

    fun isAllowed(number: Int): Boolean {
        if (number !in 1..99) return false
        return readNumbersSafe().contains(number)
    }

    /**
     * If oldNumber is null -> append new number to the end.
     * If oldNumber exists -> replace it in the same position.
     */
    fun upsertNumber(oldNumber: Int?, newNumber: Int): Result<Unit> {
        if (newNumber !in 1..99) {
            return Result.failure(IllegalArgumentException("Invalid number"))
        }

        return try {
            val current = readNumbersSafe().toMutableList()

            if (current.contains(newNumber) && newNumber != oldNumber) {
                return Result.failure(IllegalStateException("Duplicate number"))
            }

            if (oldNumber == null) {
                if (!current.contains(newNumber)) {
                    current.add(newNumber)
                }
                saveNumbers(current)
                normalizeSingleMyCar()
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
            normalizeSingleMyCar()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "upsertNumber failed: oldNumber=$oldNumber, newNumber=$newNumber", e)
            Result.failure(e)
        }
    }

    fun replaceNumber(oldNumber: Int, newNumber: Int): Result<Unit> {
        return upsertNumber(oldNumber, newNumber)
    }

    fun removeNumber(number: Int) {
        try {
            val current = readNumbersSafe().toMutableList()
            val index = current.indexOf(number)
            if (index != -1) {
                current.removeAt(index)
                saveNumbers(current)
            }
            clearCategories(number)
            normalizeSingleMyCar()
        } catch (e: Exception) {
            Log.e(TAG, "removeNumber failed: number=$number", e)
        }
    }

    fun sortNumbersAscending() {
        try {
            val sorted = readNumbersSafe()
                .sorted()
                .distinct()

            saveNumbers(sorted)
            normalizeSingleMyCar()
        } catch (e: Exception) {
            Log.e(TAG, "sortNumbersAscending failed", e)
        }
    }

    fun getInfo(number: Int): TransportInfo {
        if (number !in 1..99) {
            return TransportInfo(TransportType.NONE, false)
        }

        return try {
            val typeRaw = prefs.getString(
                keyType(number),
                TransportType.NONE.name
            ) ?: TransportType.NONE.name

            val type = runCatching {
                TransportType.valueOf(typeRaw)
            }.getOrDefault(TransportType.NONE)

            val my = prefs.getBoolean(keyMyCar(number), false)

            TransportInfo(type, my)
        } catch (e: Exception) {
            Log.e(TAG, "getInfo failed: number=$number", e)
            TransportInfo(TransportType.NONE, false)
        }
    }

    fun setInfo(number: Int, info: TransportInfo) {
        if (number !in 1..99) return

        try {
            prefs.edit()
                .putString(keyType(number), info.transportType.name)
                .putBoolean(keyMyCar(number), info.isMyCar)
                .apply()

            if (info.isMyCar) {
                enforceSingleMyCar(number)
            }
        } catch (e: Exception) {
            Log.e(TAG, "setInfo failed: number=$number, info=$info", e)
        }
    }

    fun setTransportType(number: Int, type: TransportType) {
        if (number !in 1..99) return

        try {
            prefs.edit()
                .putString(keyType(number), type.name)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "setTransportType failed: number=$number, type=$type", e)
        }
    }

    fun toggleMyCar(number: Int) {
        if (!isAllowed(number)) return

        try {
            val now = prefs.getBoolean(keyMyCar(number), false)

            if (now) {
                prefs.edit()
                    .putBoolean(keyMyCar(number), false)
                    .apply()
                return
            }

            enforceSingleMyCar(number)
        } catch (e: Exception) {
            Log.e(TAG, "toggleMyCar failed: number=$number", e)
        }
    }

    fun setMyCar(number: Int) {
        if (!isAllowed(number)) return
        enforceSingleMyCar(number)
    }

    fun clearMyCar() {
        try {
            val editor = prefs.edit()
            readNumbersSafe().forEach { number ->
                editor.putBoolean(keyMyCar(number), false)
            }
            editor.apply()
        } catch (e: Exception) {
            Log.e(TAG, "clearMyCar failed", e)
        }
    }

    fun getMyCar(): Int? {
        return try {
            readNumbersSafe().firstOrNull { prefs.getBoolean(keyMyCar(it), false) }
        } catch (e: Exception) {
            Log.e(TAG, "getMyCar failed", e)
            null
        }
    }

    fun clearCategories(number: Int) {
        if (number !in 1..99) return

        try {
            prefs.edit()
                .remove(keyType(number))
                .remove(keyMyCar(number))
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "clearCategories failed: number=$number", e)
        }
    }

    private fun enforceSingleMyCar(number: Int) {
        try {
            val editor = prefs.edit()

            readNumbersSafe().forEach { n ->
                editor.putBoolean(keyMyCar(n), n == number)
            }

            editor.apply()
        } catch (e: Exception) {
            Log.e(TAG, "enforceSingleMyCar failed: number=$number", e)
        }
    }

    /**
     * Safety normalization in case older data already has multiple MY_CAR flags.
     * Keeps the first flagged number, clears the rest.
     */
    private fun normalizeSingleMyCar() {
        try {
            val numbers = readNumbersSafe()
            var found = false
            val editor = prefs.edit()

            numbers.forEach { n ->
                val flagged = prefs.getBoolean(keyMyCar(n), false)
                when {
                    flagged && !found -> found = true
                    flagged && found -> editor.putBoolean(keyMyCar(n), false)
                }
            }

            editor.apply()
        } catch (e: Exception) {
            Log.e(TAG, "normalizeSingleMyCar failed", e)
        }
    }

    private fun readNumbersSafe(): List<Int> {
        return try {
            val raw = prefs.getString(KEY_NUMBERS_ORDERED, "").orEmpty()
            if (raw.isBlank()) return emptyList()

            raw.split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 1..99 }
                .distinct()
        } catch (e: Exception) {
            Log.e(TAG, "readNumbersSafe failed", e)
            emptyList()
        }
    }

    private fun saveNumbers(numbers: List<Int>) {
        try {
            val normalized = numbers
                .filter { it in 1..99 }
                .distinct()
                .joinToString(",")

            prefs.edit()
                .putString(KEY_NUMBERS_ORDERED, normalized)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "saveNumbers failed: numbers=$numbers", e)
        }
    }

    private fun keyType(number: Int) = "type_$number"
    private fun keyMyCar(number: Int) = "mycar_$number"

    companion object {
        private const val TAG = "DriverRegistryStore"
        private const val PREFS_NAME = "driver_registry_store"
        private const val KEY_NUMBERS_ORDERED = "allowed_numbers_ordered"
    }
}