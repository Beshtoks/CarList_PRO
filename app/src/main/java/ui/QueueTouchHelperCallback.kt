package com.carlist.pro.ui

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

class QueueTouchHelperCallback(
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onSwipedRight: (index: Int) -> Unit
) : ItemTouchHelper.SimpleCallback(
    ItemTouchHelper.UP or ItemTouchHelper.DOWN,
    ItemTouchHelper.RIGHT
) {

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        val from = viewHolder.bindingAdapterPosition
        val to = target.bindingAdapterPosition
        if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false

        onMove(from, to)
        return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val pos = viewHolder.bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION) return

        if (direction == ItemTouchHelper.RIGHT) {
            onSwipedRight(pos)
        }
    }

    override fun isLongPressDragEnabled(): Boolean = false
}