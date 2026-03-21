package com.carlist.pro.ui.controller

import com.carlist.pro.data.DriverRegistryStore
import com.carlist.pro.domain.QueueManager
import com.carlist.pro.domain.TransportInfo
import com.carlist.pro.domain.TransportType
import com.carlist.pro.ui.adapter.CommitResult
import com.carlist.pro.ui.adapter.DriverRegistryAdapter
import com.carlist.pro.ui.adapter.RegistryRow
import com.carlist.pro.ui.adapter.UnderlineState

class RegistryController(
    private val registryStore: DriverRegistryStore,
    private val queueManager: QueueManager,
    private val publishSnapshot: (Boolean) -> Unit,
    private val tickRegistry: () -> Unit,
    private val refreshSyncPanelState: () -> Unit
) {

    private var activeRowIndex: Int = 0
    private var lastCommitFailedRow: Int? = null

    fun getRegistryRows(): List<RegistryRow> {
        val numbers = registryStore.getAllowedNumbers()
        val rows = mutableListOf<RegistryRow>()

        numbers.forEachIndexed { idx, n ->
            val underline = when {
                lastCommitFailedRow == idx -> UnderlineState.RED
                idx == activeRowIndex -> UnderlineState.BLUE
                else -> UnderlineState.NONE
            }

            rows.add(
                RegistryRow(
                    number = n,
                    info = registryStore.getInfo(n),
                    underline = underline
                )
            )
        }

        val underlineNew = if (numbers.size == activeRowIndex) {
            UnderlineState.BLUE
        } else {
            UnderlineState.NONE
        }

        rows.add(
            RegistryRow(
                number = null,
                info = TransportInfo(TransportType.NONE, false),
                underline = underlineNew
            )
        )

        return rows
    }

    fun setRegistryActiveRow(pos: Int) {
        activeRowIndex = pos.coerceAtLeast(0)
        lastCommitFailedRow = null
    }

    fun getRegistryActiveRow(): Int = activeRowIndex

    fun commitRegistryNumber(position: Int, oldNumber: Int?, newText: String): CommitResult {
        val trimmed = newText.trim()
        val newNumber = trimmed.toIntOrNull()

        if (trimmed.isBlank()) {
            if (oldNumber != null) {
                registryStore.removeNumber(oldNumber)
                queueManager.removeByNumber(oldNumber)
                publishSnapshot(true)
            }

            lastCommitFailedRow = null
            activeRowIndex = position.coerceAtLeast(0)

            tickRegistry()
            refreshSyncPanelState()
            return CommitResult.OK
        }

        if (newNumber == null || newNumber !in 1..99) {
            lastCommitFailedRow = position
            // ❗ УБРАЛИ tickRegistry()
            return CommitResult.ERROR_CLEAR
        }

        val res = registryStore.upsertNumber(oldNumber, newNumber)

        if (res.isFailure) {
            lastCommitFailedRow = position
            // ❗ УБРАЛИ tickRegistry()
            return CommitResult.ERROR_CLEAR
        }

        if (oldNumber != null && oldNumber != newNumber) {
            queueManager.replaceNumber(
                oldNumber = oldNumber,
                newNumber = newNumber,
                isNumberAllowedByRegistry = { n -> registryStore.isAllowed(n) }
            )
            publishSnapshot(true)
        }

        lastCommitFailedRow = null
        activeRowIndex = (position + 1).coerceAtLeast(0)

        tickRegistry()
        refreshSyncPanelState()
        return CommitResult.OK
    }

    fun sortRegistryNumbersForClose() {
        registryStore.sortNumbersAscending()
        activeRowIndex = 0
        lastCommitFailedRow = null
        tickRegistry()
        refreshSyncPanelState()
    }

    fun onRegistryCategoryAction(number: Int, action: DriverRegistryAdapter.CategoryAction) {
        when (action) {
            DriverRegistryAdapter.CategoryAction.BUS ->
                setRegistryTransportType(number, TransportType.BUS)

            DriverRegistryAdapter.CategoryAction.VAN ->
                setRegistryTransportType(number, TransportType.VAN)

            DriverRegistryAdapter.CategoryAction.MY_CAR ->
                toggleRegistryMyCar(number)

            DriverRegistryAdapter.CategoryAction.CLEAR ->
                clearRegistryCategories(number)
        }
    }

    fun setRegistryTransportType(number: Int, type: TransportType) {
        registryStore.setTransportType(number, type)
        tickRegistry()
    }

    fun toggleRegistryMyCar(number: Int) {
        registryStore.toggleMyCar(number)
        tickRegistry()
        refreshSyncPanelState()
    }

    fun clearRegistryCategories(number: Int) {
        registryStore.clearCategories(number)
        tickRegistry()
        refreshSyncPanelState()
    }
}