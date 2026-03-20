package com.carlist.pro.ui.adapter

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

class QueueTouchHelperCallback(
    private val onMoveForDrag: (from: Int, to: Int) -> Unit,
    private val onSwipedRight: (position: Int) -> Unit,
    private val onDragStateChanged: (dragging: Boolean) -> Unit,
    private val onDragEnded: (from: Int, to: Int) -> Unit,
    private val isQueueReadOnly: () -> Boolean
) : ItemTouchHelper.Callback() {

    private var dragFrom: Int = RecyclerView.NO_POSITION
    private var dragTo: Int = RecyclerView.NO_POSITION

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int {
        val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
        val swipeFlags = ItemTouchHelper.RIGHT
        return makeMovementFlags(dragFlags, swipeFlags)
    }

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)

        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
            dragFrom = viewHolder.bindingAdapterPosition
            dragTo = dragFrom
            onDragStateChanged(true)
        }
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        if (isQueueReadOnly()) return false

        val from = viewHolder.bindingAdapterPosition
        val to = target.bindingAdapterPosition

        if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false

        onMoveForDrag(from, to)
        dragTo = to
        return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val pos = viewHolder.bindingAdapterPosition
        if (pos != RecyclerView.NO_POSITION) {
            onSwipedRight(pos)
        }
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)

        onDragStateChanged(false)

        if (!isQueueReadOnly() &&
            dragFrom != RecyclerView.NO_POSITION &&
            dragTo != RecyclerView.NO_POSITION &&
            dragFrom != dragTo
        ) {
            onDragEnded(dragFrom, dragTo)
        }

        dragFrom = RecyclerView.NO_POSITION
        dragTo = RecyclerView.NO_POSITION
    }

    override fun isLongPressDragEnabled(): Boolean = !isQueueReadOnly()

    override fun isItemViewSwipeEnabled(): Boolean = true
}