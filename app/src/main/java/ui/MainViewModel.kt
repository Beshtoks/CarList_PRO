package com.carlist.pro.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.carlist.pro.domain.Category
import com.carlist.pro.domain.QueueItem
import com.carlist.pro.domain.QueueManager
import com.carlist.pro.domain.Status

class MainViewModel : ViewModel() {

    private val queueManager = QueueManager()

    private val _queueItems = MutableLiveData<List<QueueItem>>(queueManager.snapshot())
    val queueItems: LiveData<List<QueueItem>> = _queueItems

    private fun publish() {
        _queueItems.value = queueManager.snapshot()
    }

    // ---- API for UI layer (will be used later) ----

    fun addNumber(number: Int): QueueManager.AddResult {
        val result = queueManager.addNumber(number = number, category = Category.NONE)
        publish()
        return result
    }

    fun removeAt(index: Int): QueueManager.OperationResult {
        val result = queueManager.removeAt(index)
        publish()
        return result
    }

    fun move(from: Int, to: Int): QueueManager.OperationResult {
        val result = queueManager.move(from, to)
        publish()
        return result
    }

    fun setStatus(number: Int, status: Status): QueueManager.OperationResult {
        val result = queueManager.setStatus(number, status)
        publish()
        return result
    }

    fun clear(): QueueManager.OperationResult {
        val result = queueManager.clear()
        publish()
        return result
    }
}