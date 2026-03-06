package com.carlist.pro.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.carlist.pro.R
import com.carlist.pro.databinding.ItemQueueCardBinding
import com.carlist.pro.domain.QueueItem
import com.carlist.pro.domain.Status
import com.carlist.pro.domain.TransportInfo
import com.carlist.pro.domain.TransportType

class QueueAdapter(
    private val transportInfoProvider: ((Int) -> TransportInfo)? = null,
    private val onCardShortTap: ((item: QueueItem, anchor: View) -> Unit)? = null
) : RecyclerView.Adapter<QueueAdapter.VH>() {

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
        holder.bind(items[position], transportInfoProvider, onCardShortTap)
    }

    override fun getItemCount(): Int = items.size

    class VH(
        private val binding: ItemQueueCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            item: QueueItem,
            infoProvider: ((Int) -> TransportInfo)?,
            onCardShortTap: ((item: QueueItem, anchor: View) -> Unit)?
        ) {
            binding.numberText.text = item.number.toString()

            val bgColor = when (item.status) {
                Status.NONE -> 0xFFB7E5A5.toInt()      // light green
                Status.SERVICE -> 0xFFF5B7B1.toInt()   // light pink
                Status.JURNIEKS -> 0xFFAED6F1.toInt()  // light blue
            }

            binding.cardRoot.setCardBackgroundColor(bgColor)

            // Очень тёмно-зелёный цвет номера
            binding.numberText.setTextColor(0xFF0B3D0B.toInt())

            val info = infoProvider?.invoke(item.number) ?: TransportInfo()

            when (info.transportType) {
                TransportType.BUS -> {
                    binding.transportIcon.visibility = View.VISIBLE
                    binding.transportIcon.setImageResource(R.drawable.ic_directions_bus)
                }
                TransportType.VAN -> {
                    binding.transportIcon.visibility = View.VISIBLE
                    binding.transportIcon.setImageResource(R.drawable.ic_airport_shuttle)
                }
                TransportType.NONE -> {
                    binding.transportIcon.visibility = View.GONE
                }
            }

            binding.transportIcon.setColorFilter(Color.BLACK)

            if (info.isMyCar) {
                binding.cardRoot.strokeWidth = dpToPx(3f)
                binding.cardRoot.strokeColor = 0xFFFFB300.toInt()
            } else {
                binding.cardRoot.strokeWidth = 0
            }

            binding.cardRoot.setOnClickListener {
                onCardShortTap?.invoke(item, binding.cardRoot)
            }
        }

        private fun dpToPx(dp: Float): Int {
            val density = binding.root.resources.displayMetrics.density
            return (dp * density + 0.5f).toInt()
        }
    }
}