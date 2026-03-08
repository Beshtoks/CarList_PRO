package com.carlist.pro.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
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

            when (item.status) {
                Status.NONE -> {
                    binding.cardSurface.setBackgroundColor(0xFFE6D29C.toInt())
                    binding.statusSmallText.visibility = View.GONE
                    binding.statusSmallText.text = ""
                }

                Status.SERVICE -> {
                    binding.cardSurface.setBackgroundColor(0xFFFF91E7.toInt())
                    binding.statusSmallText.visibility = View.VISIBLE
                    binding.statusSmallText.text = "Service"
                    binding.statusSmallText.setTextColor(0xFFFFE3FA.toInt())
                }

                Status.OFFICE -> {
                    binding.cardSurface.setBackgroundColor(0xFFFF91E7.toInt())
                    binding.statusSmallText.visibility = View.VISIBLE
                    binding.statusSmallText.text = "Office"
                    binding.statusSmallText.setTextColor(0xFFFFE3FA.toInt())
                }

                Status.JURNIEKS -> {
                    binding.cardSurface.setBackgroundColor(0xFF0FDFFF.toInt())
                    binding.statusSmallText.visibility = View.GONE
                    binding.statusSmallText.text = ""
                }
            }

            binding.numberText.setTextColor(0xFF0B3D0B.toInt())
            binding.categoryLetterText.setTextColor(0xFF3B2A18.toInt())
            binding.numberText.setShadowLayer(0f, 0f, 0f, 0)
            binding.categoryLetterText.setShadowLayer(0f, 0f, 0f, 0)
            binding.statusSmallText.setShadowLayer(0f, 0f, 0f, 0)

            val info = infoProvider?.invoke(item.number) ?: TransportInfo()

            val categoryLetter = buildCategoryLetter(info)

            if (categoryLetter.isEmpty()) {
                binding.categoryLetterText.visibility = View.GONE
                binding.categoryLetterText.text = ""
            } else {
                binding.categoryLetterText.visibility = View.VISIBLE
                binding.categoryLetterText.text = categoryLetter
            }

            binding.cardRoot.cardElevation = 0f
            binding.cardRoot.translationZ = 0f

            if (info.isMyCar) {
                binding.cardRoot.strokeWidth = dpToPx(3f)
                binding.cardRoot.strokeColor = 0xFFFFB300.toInt()
            } else {
                binding.cardRoot.strokeWidth = dpToPx(1f)
                binding.cardRoot.strokeColor = 0x55000000
            }

            binding.cardRoot.setOnClickListener {
                onCardShortTap?.invoke(item, binding.cardRoot)
            }
        }

        private fun buildCategoryLetter(info: TransportInfo): String {
            return when (info.transportType) {
                TransportType.BUS -> "B"
                TransportType.VAN -> "V"
                else -> ""
            }
        }

        private fun dpToPx(dp: Float): Int {
            val density = binding.root.resources.displayMetrics.density
            return (dp * density + 0.5f).toInt()
        }
    }
}