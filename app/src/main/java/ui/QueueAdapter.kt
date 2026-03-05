package com.carlist.pro.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.carlist.pro.databinding.ItemQueueCardBinding
import com.carlist.pro.domain.QueueItem
import com.carlist.pro.domain.Status

class QueueAdapter(
    private val dragStart: (RecyclerView.ViewHolder) -> Unit
) : ListAdapter<QueueItem, QueueAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<QueueItem>() {
        override fun areItemsTheSame(oldItem: QueueItem, newItem: QueueItem): Boolean {
            return oldItem.number == newItem.number
        }

        override fun areContentsTheSame(oldItem: QueueItem, newItem: QueueItem): Boolean {
            return oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemQueueCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return VH(binding, dragStart)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(
        private val binding: ItemQueueCardBinding,
        private val dragStart: (RecyclerView.ViewHolder) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            // Long press -> start drag
            binding.cardRoot.setOnLongClickListener {
                dragStart(this)
                true
            }
        }

        fun bind(item: QueueItem) {
            binding.numberText.text = item.number.toString()

            // Colors: basic, per spec (light green / pink / blue)
            val bg = when (item.status) {
                Status.NONE -> 0xFFCFEFD0.toInt()     // light green
                Status.SERVICE -> 0xFFF3C7D3.toInt()  // light pink
                Status.JURNIEKS -> 0xFFBFDDF6.toInt() // light blue
            }
            binding.cardRoot.setCardBackgroundColor(bg)
        }
    }
}