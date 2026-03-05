package com.carlist.pro.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.carlist.pro.databinding.ItemQueueCardBinding
import com.carlist.pro.domain.QueueItem
import com.carlist.pro.domain.Status

class QueueAdapter : RecyclerView.Adapter<QueueAdapter.VH>() {

    private val items: MutableList<QueueItem> = mutableListOf()

    fun submitItems(newItems: List<QueueItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getItemAt(position: Int): QueueItem = items[position]

    fun getItemsSnapshot(): List<QueueItem> = items.toList()

    fun moveForDrag(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices) return
        if (from == to) return

        val moved = items.removeAt(from)
        items.add(to, moved)
        notifyItemMoved(from, to)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemQueueCardBinding.inflate(inflater, parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class VH(private val binding: ItemQueueCardBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: QueueItem) {
            binding.numberText.text = item.number.toString()

            val bg = when (item.status) {
                Status.NONE -> 0xFFB7E5A5.toInt()     // light green
                Status.SERVICE -> 0xFFF5B7B1.toInt()  // light pink
                Status.JURNIEKS -> 0xFFAED6F1.toInt() // light blue
            }

            binding.cardRoot.setCardBackgroundColor(bg)
            binding.numberText.setTextColor(0xFF000000.toInt())
        }
    }
}