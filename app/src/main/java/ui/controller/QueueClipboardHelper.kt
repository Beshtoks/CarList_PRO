package com.carlist.pro.ui.controller

import android.content.ClipData
import android.content.ClipboardManager
import com.carlist.pro.domain.QueueItem
import com.carlist.pro.domain.Status
import com.carlist.pro.domain.TransportInfo
import com.carlist.pro.domain.TransportType

class QueueClipboardHelper(
    private val clipboardManager: ClipboardManager,
    private val transportInfoProvider: (Int) -> TransportInfo
) {

    fun autoCopyIfQueueChanged(
        list: List<QueueItem>,
        lastAutoCopiedText: String
    ): String {
        val text = buildQueueText(list)

        if (text.isBlank()) return lastAutoCopiedText
        if (text == lastAutoCopiedText) return lastAutoCopiedText

        copyQueueText(text)
        return text
    }

    fun copyQueueOnce(list: List<QueueItem>): String {
        val text = buildQueueText(list)
        copyQueueText(text)
        return text
    }

    private fun copyQueueText(text: String) {
        clipboardManager.setPrimaryClip(
            ClipData.newPlainText("CarList_PRO Queue", text)
        )
    }

    private fun buildQueueText(list: List<QueueItem>): String {
        if (list.isEmpty()) return ""

        val sb = StringBuilder()

        list.forEachIndexed { index, item ->
            val lineIndex = index + 1
            sb.append(lineIndex).append(". ").append(item.number)

            val info = transportInfoProvider(item.number)
            val categoryLetters = buildCategoryLetters(info.transportType, info.isMyCar)
            if (categoryLetters.isNotEmpty()) {
                sb.append(" (").append(categoryLetters).append(")")
            }

            when (item.status) {
                Status.SERVICE -> sb.append(" SERVICE")
                Status.OFFICE -> sb.append(" OFFICE")
                Status.JURNIEKS -> sb.append(" JURNIEKS")
                Status.NONE -> {}
            }

            if (index != list.lastIndex) sb.append('\n')
        }

        return sb.toString()
    }

    private fun buildCategoryLetters(
        transportType: TransportType,
        isMyCar: Boolean
    ): String {
        val sb = StringBuilder()

        when (transportType) {
            TransportType.BUS -> sb.append("B")
            TransportType.VAN -> sb.append("V")
            TransportType.NONE -> {}
        }

        if (isMyCar) {
            if (sb.isNotEmpty()) sb.append("+")
            sb.append("MY")
        }

        return sb.toString()
    }
}