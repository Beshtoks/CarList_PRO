package com.carlist.pro.ui.adapter

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
            binding.numberText.text = item.number.toString()

            when (visualState) {
                VisualState.STANDARD -> {
                    binding.cardSurface.setBackgroundResource(R.drawable.bg_queue_card_standard_3d)
                    binding.leftPanel.background = createLeftPanelDrawable(
                        startColor = 0xFF180B10.toInt(),
                        endColor = 0xFF0F0910.toInt(),
                        strokeColor = 0x00000000
                    )
                    binding.diagonalDivider.setBackgroundColor(0x88322A35.toInt())
                    binding.positionText.setTextColor(0xFFFF5B5B.toInt())
                    binding.numberText.setTextColor(Color.WHITE)
                    binding.categoryLetterText.setTextColor(0xFFFFD0D0.toInt())
                    binding.statusSmallText.setTextColor(0xFFFFD0D0.toInt())
                    binding.cardRoot.strokeWidth = dpToPx(1f)
                    binding.cardRoot.strokeColor = 0xAA7B2C35.toInt()
                }

                VisualState.MY_CAR -> {
                    binding.cardSurface.setBackgroundResource(R.drawable.bg_queue_card_my_car_3d)
                    binding.leftPanel.background = createLeftPanelDrawable(
                        startColor = 0xFF261706.toInt(),
                        endColor = 0xFF181003.toInt(),
                        strokeColor = 0x00000000
                    )
                    binding.diagonalDivider.setBackgroundColor(0x99FFD36A.toInt())
                    binding.positionText.setTextColor(0xFFFFD87A.toInt())
                    binding.numberText.setTextColor(0xFFFFE7A8.toInt())
                    binding.categoryLetterText.setTextColor(0xFFFFE7A8.toInt())
                    binding.statusSmallText.setTextColor(0xFFFFE7A8.toInt())
                    binding.cardRoot.strokeWidth = dpToPx(1.5f)
                    binding.cardRoot.strokeColor = 0xFFD9A73F.toInt()
                }

                VisualState.SERVICE -> {
                    binding.cardSurface.setBackgroundResource(R.drawable.bg_queue_card_service_3d)
                    binding.leftPanel.background = createLeftPanelDrawable(
                        startColor = 0xFF220716.toInt(),
                        endColor = 0xFF150510.toInt(),
                        strokeColor = 0x00000000
                    )
                    binding.diagonalDivider.setBackgroundColor(0x99FF6ADB.toInt())
                    binding.positionText.setTextColor(0xFFFF67D8.toInt())
                    binding.numberText.setTextColor(0xFFFFD9F8.toInt())
                    binding.categoryLetterText.setTextColor(0xFFFFCBF2.toInt())
                    binding.statusSmallText.setTextColor(0xFFFFCBF2.toInt())
                    binding.cardRoot.strokeWidth = dpToPx(1.5f)
                    binding.cardRoot.strokeColor = 0xFFCF3FB3.toInt()
                }

                VisualState.JURNIEKS -> {
                    binding.cardSurface.setBackgroundResource(R.drawable.bg_queue_card_jurnieks_3d)
                    binding.leftPanel.background = createLeftPanelDrawable(
                        startColor = 0xFF071327.toInt(),
                        endColor = 0xFF05101E.toInt(),
                        strokeColor = 0x00000000
                    )
                    binding.diagonalDivider.setBackgroundColor(0x9985D9FF.toInt())
                    binding.positionText.setTextColor(0xFF4DB7FF.toInt())
                    binding.numberText.setTextColor(0xFFCDEFFF.toInt())
                    binding.categoryLetterText.setTextColor(0xFFBDE7FF.toInt())
                    binding.statusSmallText.setTextColor(0xFFBDE7FF.toInt())
                    binding.cardRoot.strokeWidth = dpToPx(1.5f)
                    binding.cardRoot.strokeColor = 0xFF218DDF.toInt()
                }
            }

            binding.numberText.setShadowLayer(
                10f,
                0f,
                1f,
                0x66000000
            )
            binding.positionText.setShadowLayer(
                8f,
                0f,
                1f,
                0x66000000
            )
            binding.categoryLetterText.setShadowLayer(
                6f,
                0f,
                1f,
                0x55000000
            )
            binding.statusSmallText.setShadowLayer(
                6f,
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

            binding.cardRoot.cardElevation = 0f
            binding.cardRoot.translationZ = 0f

            binding.cardRoot.setOnClickListener {
                onCardShortTap?.invoke(item, binding.cardRoot)
            }
        }

        private fun resolveVisualState(item: QueueItem, info: TransportInfo): VisualState {
            return when (item.status) {
                Status.SERVICE,
                Status.OFFICE -> VisualState.SERVICE

                Status.JURNIEKS -> VisualState.JURNIEKS

                Status.NONE -> {
                    if (info.isMyCar) {
                        VisualState.MY_CAR
                    } else {
                        VisualState.STANDARD
                    }
                }
            }
        }

        private fun buildCategoryLetter(info: TransportInfo): String {
            return when (info.transportType) {
                TransportType.BUS -> "B"
                TransportType.VAN -> "V"
                else -> ""
            }
        }

        private fun createLeftPanelDrawable(
            startColor: Int,
            endColor: Int,
            strokeColor: Int
        ): GradientDrawable {
            return GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(startColor, endColor)
            ).apply {
                cornerRadii = floatArrayOf(
                    dpToPxF(5f), dpToPxF(5f),
                    0f, 0f,
                    0f, 0f,
                    dpToPxF(5f), dpToPxF(5f)
                )
                setStroke(dpToPx(0f), strokeColor)
            }
        }

        private fun dpToPx(dp: Float): Int {
            val density = binding.root.resources.displayMetrics.density
            return (dp * density + 0.5f).toInt()
        }

        private fun dpToPxF(dp: Float): Float {
            val density = binding.root.resources.displayMetrics.density
            return dp * density
        }

        private enum class VisualState {
            STANDARD,
            MY_CAR,
            SERVICE,
            JURNIEKS
        }
    }
}