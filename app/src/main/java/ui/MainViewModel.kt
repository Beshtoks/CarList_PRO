package com.carlist.pro.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.carlist.pro.domain.QueueItem
import com.carlist.pro.domain.QueueManager
import com.carlist.pro.domain.Status

class MainViewModel : ViewModel() {

    private val queueManager = QueueManager()

    private val _queueItems = MutableLiveData<List<QueueItem>>(queueManager.snapshot())
    val queueItems: LiveData<List<QueueItem>> = _queueItems

    fun addNumber(number: Int): QueueManager.AddResult {
        val result = queueManager.addNumber(number = number)
        publishSnapshot()
        return result
    }

    fun removeAt(index: Int): QueueManager.OperationResult {
        val result = queueManager.removeAt(index)
        publishSnapshot()
        return result
    }

    fun setStatus(number: Int, status: Status): QueueManager.OperationResult {
        val result = queueManager.setStatus(number, status)
        publishSnapshot()
        return result
    }

    fun clear(): QueueManager.OperationResult {
        val result = queueManager.clear()
        publishSnapshot()
        return result
    }

    /**
     * ВАЖНО:
     * Во время drag НЕ публикуем LiveData, иначе ItemTouchHelper срывает перетаскивание.
     */
    fun moveForDrag(from: Int, to: Int): QueueManager.OperationResult {
        return queueManager.move(from, to)
    }

    /**
     * Публикуем итоговый список один раз — после отпускания пальца.
     */
    fun commitDrag() {
        publishSnapshot()
    }

    private fun publishSnapshot() {
        _queueItems.value = queueManager.snapshot()
    }
}