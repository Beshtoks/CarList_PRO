package com.carlist.pro.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueManagerTest {

    @Test
    fun add_valid_number_added_to_queue() {
        val manager = QueueManager()

        val result = manager.addNumber(12)

        assertTrue(result is QueueManager.AddResult.Added)
        assertEquals(listOf(12), snapshotNumbers(manager))
    }

    @Test
    fun add_invalid_number_rejected() {
        val manager = QueueManager()

        val result = manager.addNumber(0)

        assertEquals(QueueManager.AddResult.InvalidNumber, result)
        assertTrue(manager.snapshot().isEmpty())
    }

    @Test
    fun add_duplicate_number_rejected() {
        val manager = QueueManager()

        manager.addNumber(12)
        val result = manager.addNumber(12)

        assertEquals(QueueManager.AddResult.DuplicateInQueue, result)
        assertEquals(listOf(12), snapshotNumbers(manager))
    }

    @Test
    fun add_number_not_in_registry_rejected() {
        val manager = QueueManager()

        val result = manager.addNumber(
            number = 12,
            isNumberAllowedByRegistry = { false }
        )

        assertEquals(QueueManager.AddResult.NotInRegistry, result)
        assertTrue(manager.snapshot().isEmpty())
    }

    @Test
    fun remove_by_number_removes_existing_item() {
        val manager = QueueManager()

        manager.addNumber(12)
        manager.addNumber(25)

        val result = manager.removeByNumber(12)

        assertEquals(QueueManager.OperationResult.Success, result)
        assertEquals(listOf(25), snapshotNumbers(manager))
    }

    @Test
    fun remove_at_invalid_index_returns_error() {
        val manager = QueueManager()

        manager.addNumber(12)

        val result = manager.removeAt(5)

        assertEquals(QueueManager.OperationResult.InvalidIndex, result)
        assertEquals(listOf(12), snapshotNumbers(manager))
    }

    @Test
    fun move_reorders_queue() {
        val manager = QueueManager()

        manager.addNumber(12)
        manager.addNumber(25)
        manager.addNumber(40)

        val result = manager.move(2, 0)

        assertEquals(QueueManager.OperationResult.Success, result)
        assertEquals(listOf(40, 12, 25), snapshotNumbers(manager))
    }

    @Test
    fun service_cannot_stay_at_position_zero_when_active_exists() {
        val manager = QueueManager()

        manager.addNumber(12)
        manager.addNumber(25)
        manager.setStatus(12, Status.SERVICE)

        assertEquals(listOf(25, 12), snapshotNumbers(manager))
        assertEquals(Status.NONE, manager.snapshot()[0].status)
        assertEquals(Status.SERVICE, manager.snapshot()[1].status)
    }

    @Test
    fun service_can_stay_alone_at_position_zero_when_no_active_exists() {
        val manager = QueueManager()

        manager.addNumber(12)
        manager.setStatus(12, Status.SERVICE)

        assertEquals(listOf(12), snapshotNumbers(manager))
        assertEquals(Status.SERVICE, manager.snapshot()[0].status)
    }

    @Test
    fun replace_number_preserves_position_and_status() {
        val manager = QueueManager()

        manager.addNumber(12)
        manager.addNumber(25)
        manager.setStatus(12, Status.JURNIEKS)

        val result = manager.replaceNumber(
            oldNumber = 12,
            newNumber = 33
        )

        assertEquals(QueueManager.OperationResult.Success, result)

        val snapshot = manager.snapshot()
        assertEquals(listOf(33, 25), snapshot.map { it.number })
        assertEquals(Status.JURNIEKS, snapshot[0].status)
    }

    @Test
    fun replace_number_rejects_duplicate_target() {
        val manager = QueueManager()

        manager.addNumber(12)
        manager.addNumber(25)

        val result = manager.replaceNumber(
            oldNumber = 12,
            newNumber = 25
        )

        assertEquals(QueueManager.OperationResult.DuplicateInQueue, result)
        assertEquals(listOf(12, 25), snapshotNumbers(manager))
    }

    @Test
    fun replace_number_rejects_target_not_in_registry() {
        val manager = QueueManager()

        manager.addNumber(12)

        val result = manager.replaceNumber(
            oldNumber = 12,
            newNumber = 33,
            isNumberAllowedByRegistry = { it != 33 }
        )

        assertEquals(QueueManager.OperationResult.NotInRegistry, result)
        assertEquals(listOf(12), snapshotNumbers(manager))
    }

    @Test
    fun validate_against_registry_removes_missing_numbers() {
        val manager = QueueManager()

        manager.addNumber(12)
        manager.addNumber(25)
        manager.addNumber(40)

        val result = manager.validateAgainstRegistry { number ->
            number == 12 || number == 40
        }

        assertEquals(listOf(25), result.removedNumbers)
        assertEquals(2, result.finalSize)
        assertEquals(listOf(12, 40), snapshotNumbers(manager))
    }

    @Test
    fun restore_from_snapshot_skips_duplicates_invalid_and_not_allowed_numbers() {
        val manager = QueueManager()

        val snapshot = listOf(
            QueueItem(12, Status.NONE),
            QueueItem(12, Status.SERVICE),
            QueueItem(150, Status.NONE),
            QueueItem(25, Status.JURNIEKS),
            QueueItem(40, Status.SERVICE)
        )

        val result = manager.restoreFromSnapshot(
            snapshot = snapshot,
            isNumberAllowedByRegistry = { it != 25 }
        )

        assertEquals(QueueManager.OperationResult.Success, result)

        val restored = manager.snapshot()
        assertEquals(listOf(12, 40), restored.map { it.number })
        assertEquals(Status.NONE, restored[0].status)
        assertEquals(Status.SERVICE, restored[1].status)
    }

    @Test
    fun restore_from_snapshot_auto_fixes_position_zero() {
        val manager = QueueManager()

        val snapshot = listOf(
            QueueItem(12, Status.SERVICE),
            QueueItem(25, Status.NONE),
            QueueItem(40, Status.SERVICE)
        )

        manager.restoreFromSnapshot(snapshot)

        val restored = manager.snapshot()
        assertEquals(listOf(25, 12, 40), restored.map { it.number })
        assertEquals(Status.NONE, restored[0].status)
    }

    @Test
    fun clear_empties_queue() {
        val manager = QueueManager()

        manager.addNumber(12)
        manager.addNumber(25)

        val result = manager.clear()

        assertEquals(QueueManager.OperationResult.Success, result)
        assertTrue(manager.snapshot().isEmpty())
    }

    private fun snapshotNumbers(manager: QueueManager): List<Int> {
        return manager.snapshot().map { it.number }
    }
}