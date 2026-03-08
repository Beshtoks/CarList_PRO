package com.carlist.pro.data

import android.content.Context
import android.content.SharedPreferences
import com.carlist.pro.domain.QueueItem
import com.carlist.pro.domain.Status

class QueueStateStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(queue: List<QueueItem>) {

        try {

            if (queue.isEmpty()) {
                prefs.edit()
                    .remove(KEY_QUEUE)
                    .remove(KEY_QUEUE_BACKUP)
                    .apply()
                return
            }

            val normalized = queue
                .filter { it.number in 1..99 }
                .distinctBy { it.number }

            val raw = normalized.joinToString(";") {
                "${it.number}:${it.status.name}"
            }

            val old = prefs.getString(KEY_QUEUE, null)

            val editor = prefs.edit()

            if (!old.isNullOrBlank()) {
                editor.putString(KEY_QUEUE_BACKUP, old)
            }

            editor.putString(KEY_QUEUE, raw)
            editor.apply()

        } catch (_: Exception) {
            // приложение не должно падать
        }
    }

    fun load(): List<QueueItem> {

        val primary = readSnapshot(KEY_QUEUE)

        if (primary.isNotEmpty()) {
            return primary
        }

        val backup = readSnapshot(KEY_QUEUE_BACKUP)

        if (backup.isNotEmpty()) {

            save(backup)

            return backup
        }

        clearBrokenData()

        return emptyList()
    }

    fun clear() {

        try {
            prefs.edit()
                .remove(KEY_QUEUE)
                .remove(KEY_QUEUE_BACKUP)
                .apply()
        } catch (_: Exception) {
        }
    }

    private fun readSnapshot(key: String): List<QueueItem> {

        return try {

            val raw = prefs.getString(key, "") ?: ""

            if (raw.isBlank()) return emptyList()

            val result = mutableListOf<QueueItem>()
            val seen = mutableSetOf<Int>()

            raw.split(";").forEach { part ->

                val pieces = part.split(":")
                if (pieces.size != 2) return@forEach

                val number = pieces[0].toIntOrNull() ?: return@forEach
                if (number !in 1..99) return@forEach
                if (!seen.add(number)) return@forEach

                val status = try {
                    Status.valueOf(pieces[1])
                } catch (_: Exception) {
                    Status.NONE
                }

                result.add(
                    QueueItem(
                        number = number,
                        status = status
                    )
                )
            }

            result

        } catch (_: Exception) {

            emptyList()
        }
    }

    private fun clearBrokenData() {

        try {
            prefs.edit()
                .remove(KEY_QUEUE)
                .remove(KEY_QUEUE_BACKUP)
                .apply()
        } catch (_: Exception) {
        }
    }

    companion object {

        private const val PREFS_NAME = "queue_state_store"

        private const val KEY_QUEUE = "queue_snapshot"

        private const val KEY_QUEUE_BACKUP = "queue_snapshot_backup"
    }
}