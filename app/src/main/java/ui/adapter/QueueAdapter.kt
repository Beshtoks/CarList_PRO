package com.carlist.pro.ui.adapter

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
        holder.bind(
            item = items[position],
            queuePosition = position + 1,
            infoProvider = transportInfoProvider,
            onCardShortTap = onCardShortTap
        )
    }

    override fun getItemCount(): Int = items.size

    class VH(
        private val binding: ItemQueueCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            item: QueueItem,
            queuePosition: Int,
            infoProvider: ((Int) -> TransportInfo)?,
            onCardShortTap: ((item: QueueItem, anchor: View) -> Unit)?
        ) {
            val info = infoProvider?.invoke(item.number) ?: TransportInfo()
            val visualState = resolveVisualState(item, info)

            binding.positionText.text = queuePosition.toString()
            binding.positionText.setTextColor(0xFF3A1700.toInt())
            binding.numberText.text = item.number.toString()

            binding.leftPanel.setBackgroundResource(R.drawable.bg_queue_left_panel_gold_strict)
            binding.diagonalDivider.setBackgroundColor(Color.TRANSPARENT)

            when (visualState) {
                VisualState.STANDARD -> {
                    binding.cardSurface.setBackgroundResource(R.drawable.bg_queue_card_standard_3d)
                    binding.numberText.setBackgroundColor(0xFFFFDAB9.toInt())
                    binding.numberText.setTextColor(0xFF090500.toInt())
                    binding.categoryLetterText.setTextColor(0xFF090500.toInt())
                    binding.statusSmallText.setTextColor(0xFF090500.toInt())
                    binding.cardRoot.strokeWidth = dpToPx(1.5f)
                    binding.cardRoot.strokeColor = 0xFFE7B84D.toInt()
                }

                VisualState.MY_CAR -> {
                    binding.cardSurface.setBackgroundResource(R.drawable.bg_queue_card_my_car_3d)
                    binding.numberText.setBackgroundColor(Color.TRANSPARENT)
                    binding.numberText.setTextColor(0xFF4A2A00.toInt())
                    binding.categoryLetterText.setTextColor(0xFF4A2A00.toInt())
                    binding.statusSmallText.setTextColor(0xFF4A2A00.toInt())
                    binding.cardRoot.strokeWidth = dpToPx(1.5f)
                    binding.cardRoot.strokeColor = 0xFFFFE4C4.toInt()
                }

                VisualState.SERVICE -> {
                    binding.cardSurface.setBackgroundResource(R.drawable.bg_queue_card_service_3d)
                    binding.numberText.setBackgroundColor(Color.TRANSPARENT)
                    binding.numberText.setTextColor(0xFFFFFFFF.toInt())
                    binding.categoryLetterText.setTextColor(0xFFFFFFFF.toInt())
                    binding.statusSmallText.setTextColor(0xFFFFFFFF.toInt())
                    binding.cardRoot.strokeWidth = dpToPx(1.5f)
                    binding.cardRoot.strokeColor = 0xFFFF97D8.toInt()
                }

                VisualState.JURNIEKS -> {
                    binding.cardSurface.setBackgroundResource(R.drawable.bg_queue_card_jurnieks_3d)
                    binding.numberText.setBackgroundColor(Color.TRANSPARENT)
                    binding.numberText.setTextColor(0xFFFFFFFF.toInt())
                    binding.categoryLetterText.setTextColor(0xFFFFFFFF.toInt())
                    binding.statusSmallText.setTextColor(0xFFFFFFFF.toInt())
                    binding.cardRoot.strokeWidth = dpToPx(1.5f)
                    binding.cardRoot.strokeColor = 0xFF9FD8FF.toInt()
                }
            }

            binding.numberText.setShadowLayer(
                2f,
                0f,
                1f,
                0x55000000
            )

            binding.positionText.setShadowLayer(
                3f,
                0f,
                1f,
                0x55000000
            )

            binding.categoryLetterText.setShadowLayer(
                2f,
                0f,
                1f,
                0x55000000
            )

            binding.statusSmallText.setShadowLayer(
                2f,
                0f,
                1f,
                0x55000000
            )

            val categoryLetter = buildCategoryLetter(info)

            when (item.status) {
                Status.SERVICE -> {
                    binding.statusSmallText.visibility = View.VISIBLE
                    binding.statusSmallText.text = "SERVICE"
                    binding.categoryLetterText.visibility = View.GONE
                    binding.categoryLetterText.text = ""
                }

                Status.OFFICE -> {
                    binding.statusSmallText.visibility = View.VISIBLE
                    binding.statusSmallText.text = "OFFICE"
                    binding.categoryLetterText.visibility = View.GONE
                    binding.categoryLetterText.text = ""
                }

                else -> {
                    binding.statusSmallText.visibility = View.GONE
                    binding.statusSmallText.text = ""

                    if (categoryLetter.isEmpty()) {
                        binding.categoryLetterText.visibility = View.GONE
                        binding.categoryLetterText.text = ""
                    } else {
                        binding.categoryLetterText.visibility = View.VISIBLE
                        binding.categoryLetterText.text = categoryLetter
                    }
                }
            }

            binding.cardRoot.setOnClickListener {
                onCardShortTap?.invoke(item, binding.cardRoot)
            }
        }

        private fun resolveVisualState(item: QueueItem, info: TransportInfo): VisualState {
            return when (item.status) {
                Status.SERVICE,
                Status.OFFICE -> VisualState.SERVICE
                Status.JURNIEKS -> VisualState.JURNIEKS
                Status.NONE -> if (info.isMyCar) VisualState.MY_CAR else VisualState.STANDARD
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

        private enum class VisualState {
            STANDARD,
            MY_CAR,
            SERVICE,
            JURNIEKS
        }
    }
}