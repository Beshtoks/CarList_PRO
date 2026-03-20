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
import com.carlist.pro.ui.drawable.MyCarSpiralDrawable

class QueueAdapter(
    private val transportInfoProvider: ((Int) -> TransportInfo)? = null,
    private val onCardShortTap: ((item: QueueItem, anchor: View) -> Unit)? = null
) : RecyclerView.Adapter<QueueAdapter.VH>() {

    private val items: MutableList<QueueItem> = mutableListOf()

    // 🔴 контроль анимации
    private var lastAnimatedNumber: Int? = null

    // 🔴 запоминаем прошлые статусы
    private val lastStatuses = mutableMapOf<Int, Status>()

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
        val item = items[position]

        val previousStatus = lastStatuses[item.number]
        lastStatuses[item.number] = item.status

        holder.bind(
            item = item,
            queuePosition = position + 1,
            infoProvider = transportInfoProvider,
            onCardShortTap = onCardShortTap
        )

        // 🔴 АНИМАЦИЯ ТОЛЬКО ДЛЯ БЫВШЕГО JURNIEKS
        if (
            position == 0 &&
            previousStatus == Status.JURNIEKS &&
            item.status == Status.NONE &&
            lastAnimatedNumber != item.number
        ) {

            lastAnimatedNumber = item.number

            val root = holder.itemView
            val originalItem = item

            root.post {

                val states = listOf(
                    Status.JURNIEKS,
                    Status.NONE,
                    Status.JURNIEKS,
                    Status.NONE,
                    Status.NONE
                )

                states.forEachIndexed { index, status ->
                    root.postDelayed({

                        if (bindingAdapterPositionInvalid(holder)) return@postDelayed

                        val tempItem = originalItem.copy(status = status)

                        holder.bind(
                            item = tempItem,
                            queuePosition = 1,
                            infoProvider = transportInfoProvider,
                            onCardShortTap = onCardShortTap
                        )

                    }, index * 80L)
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    private fun bindingAdapterPositionInvalid(holder: VH): Boolean {
        val pos = holder.bindingAdapterPosition
        return pos == RecyclerView.NO_POSITION || pos != 0
    }

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

            binding.positionText.text = queuePosition.toString()
            binding.positionText.setTextColor(0xFFA0522D.toInt())

            binding.numberText.text = item.number.toString()

            if (info.isMyCar) {
                binding.leftPanel.setBackgroundResource(R.drawable.bg_queue_left_panel_mycar)
            } else {
                binding.leftPanel.setBackgroundResource(R.drawable.bg_queue_left_panel_gold_strict)
            }

            binding.diagonalDivider.setBackgroundColor(Color.TRANSPARENT)

            when (resolveVisualState(item, info)) {

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
                    binding.cardSurface.background = MyCarSpiralDrawable()

                    binding.numberText.setBackgroundColor(Color.TRANSPARENT)
                    binding.numberText.setTextColor(0xFF090500.toInt())

                    binding.categoryLetterText.setTextColor(0xFF808000.toInt())
                    binding.statusSmallText.setTextColor(0xFF808000.toInt())

                    binding.cardRoot.strokeWidth = dpToPx(1.5f)
                    binding.cardRoot.strokeColor = 0xFFFFE4C4.toInt()
                }

                VisualState.SERVICE -> {
                    binding.cardSurface.setBackgroundResource(R.drawable.bg_queue_card_service_3d)

                    binding.numberText.setBackgroundColor(Color.TRANSPARENT)
                    binding.numberText.setTextColor(0xFF800080.toInt())
                    binding.categoryLetterText.setTextColor(0xFF800080.toInt())
                    binding.statusSmallText.setTextColor(0xFF800080.toInt())

                    binding.cardRoot.strokeWidth = dpToPx(1.5f)
                    binding.cardRoot.strokeColor = 0xFFFF97D8.toInt()
                }

                VisualState.JURNIEKS -> {
                    binding.cardSurface.setBackgroundResource(R.drawable.bg_queue_card_jurnieks_3d)

                    binding.numberText.setBackgroundColor(Color.TRANSPARENT)
                    binding.numberText.setTextColor(0xFF000080.toInt())
                    binding.categoryLetterText.setTextColor(0xFF000080.toInt())
                    binding.statusSmallText.setTextColor(0xFF000080.toInt())

                    binding.cardRoot.strokeWidth = dpToPx(1.5f)
                    binding.cardRoot.strokeColor = 0xFF9FD8FF.toInt()
                }
            }

            binding.numberText.setShadowLayer(2f, 0f, 1f, 0x55000000)
            binding.positionText.setShadowLayer(3f, 0f, 1f, 0x55000000)
            binding.categoryLetterText.setShadowLayer(2f, 0f, 1f, 0x55000000)
            binding.statusSmallText.setShadowLayer(2f, 0f, 1f, 0x55000000)

            val categoryLetter = buildCategoryLetter(info)

            when (item.status) {
                Status.SERVICE -> {
                    binding.statusSmallText.visibility = View.VISIBLE
                    binding.statusSmallText.text = "SERVICE"
                }

                Status.OFFICE -> {
                    binding.statusSmallText.visibility = View.VISIBLE
                    binding.statusSmallText.text = "OFFICE"
                }

                else -> {
                    binding.statusSmallText.visibility = View.GONE
                }
            }

            if (categoryLetter.isEmpty()) {
                binding.categoryLetterText.visibility = View.GONE
            } else {
                binding.categoryLetterText.visibility = View.VISIBLE
                binding.categoryLetterText.text = categoryLetter
            }

            binding.cardRoot.setOnClickListener {
                onCardShortTap?.invoke(item, binding.cardRoot)
            }
        }

        private fun resolveVisualState(item: QueueItem, info: TransportInfo): VisualState {
            return when (item.status) {
                Status.SERVICE, Status.OFFICE -> VisualState.SERVICE
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