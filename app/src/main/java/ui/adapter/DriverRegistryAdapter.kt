package com.carlist.pro.ui.adapter

import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.PopupWindow
import androidx.recyclerview.widget.RecyclerView
import com.carlist.pro.databinding.ItemRegistryRowBinding
import com.carlist.pro.databinding.PopupRegistryCategoryMenuBinding
import com.carlist.pro.domain.TransportInfo

class DriverRegistryAdapter(
    private val getItems: () -> List<RegistryRow>,
    private val onRowActivated: (position: Int) -> Unit,
    private val onNumberCommitted: (position: Int, oldNumber: Int?, newText: String) -> CommitResult,
    private val onCategoryAction: (number: Int, action: CategoryAction) -> Unit
) : RecyclerView.Adapter<DriverRegistryAdapter.VH>() {

    enum class CategoryAction { BUS, VAN, MY_CAR, CLEAR }

    private var items: List<RegistryRow> = emptyList()

    fun refresh() {
        items = getItems()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemRegistryRowBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return VH(binding)
    }

    override fun getItemCount(): Int {
        if (items.isEmpty()) items = getItems()
        return items.size
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        if (items.isEmpty()) items = getItems()
        holder.bind(items[position], position)
    }

    inner class VH(
        private val binding: ItemRegistryRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: RegistryRow, position: Int) {

            val asText = row.number?.toString().orEmpty()

            forceNumericMode()

            if (binding.etNumber.text?.toString() != asText) {
                binding.etNumber.setText(asText)
                binding.etNumber.setSelection(binding.etNumber.text?.length ?: 0)
            }

            binding.tvLetters.text = row.info.letters()

            val underlineColor = when (row.underline) {
                UnderlineState.NONE -> 0x00000000
                UnderlineState.BLUE -> 0xFF2B6CB0.toInt()
                UnderlineState.RED -> 0xFFC53030.toInt()
            }

            binding.underline.setBackgroundColor(underlineColor)

            binding.root.setOnClickListener {
                onRowActivated(position)
                focusField()
            }

            binding.etNumber.setOnClickListener {
                onRowActivated(position)
                focusField()
            }

            binding.etNumber.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    onRowActivated(position)
                    forceNumericMode()
                }
            }

            binding.etNumber.setOnEditorActionListener { _, actionId, event ->

                val enterPressed =
                    event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                            event.action == KeyEvent.ACTION_DOWN

                val imeCommit =
                    actionId == EditorInfo.IME_ACTION_DONE ||
                            actionId == EditorInfo.IME_ACTION_NEXT

                if (!enterPressed && !imeCommit) {
                    return@setOnEditorActionListener false
                }

                val text = binding.etNumber.text?.toString().orEmpty()

                val result = onNumberCommitted(
                    position,
                    row.number,
                    text
                )

                when (result) {
                    CommitResult.OK -> true
                    CommitResult.ERROR_CLEAR -> {
                        binding.etNumber.setText("")
                        true
                    }
                }
            }

            binding.tvLetters.setOnClickListener {
                onRowActivated(position)
                focusField()

                val num = row.number ?: return@setOnClickListener

                val popupBinding = PopupRegistryCategoryMenuBinding.inflate(
                    LayoutInflater.from(binding.root.context)
                )

                val popup = PopupWindow(
                    popupBinding.root,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    true
                )

                popup.isOutsideTouchable = true
                popup.isFocusable = true
                popup.elevation = 16f

                popupBinding.actionMyCar.setOnClickListener {
                    onCategoryAction(num, CategoryAction.MY_CAR)
                    popup.dismiss()
                }

                popupBinding.actionVan.setOnClickListener {
                    onCategoryAction(num, CategoryAction.VAN)
                    popup.dismiss()
                }

                popupBinding.actionBus.setOnClickListener {
                    onCategoryAction(num, CategoryAction.BUS)
                    popup.dismiss()
                }

                popupBinding.actionClear.setOnClickListener {
                    onCategoryAction(num, CategoryAction.CLEAR)
                    popup.dismiss()
                }

                popup.showAsDropDown(binding.tvLetters)
            }
        }

        fun focusField() {
            forceNumericMode()
            binding.etNumber.isFocusable = true
            binding.etNumber.isFocusableInTouchMode = true
            binding.etNumber.isCursorVisible = true
            binding.etNumber.requestFocus()
            binding.etNumber.setSelection(binding.etNumber.text?.length ?: 0)
        }

        fun getEditText() = binding.etNumber

        private fun forceNumericMode() {
            binding.etNumber.inputType = InputType.TYPE_CLASS_NUMBER
            binding.etNumber.setRawInputType(InputType.TYPE_CLASS_NUMBER)
            binding.etNumber.imeOptions = EditorInfo.IME_ACTION_DONE
            binding.etNumber.setSingleLine(true)
        }
    }
}

data class RegistryRow(
    val number: Int?,
    val info: TransportInfo,
    val underline: UnderlineState
)

enum class UnderlineState {
    NONE,
    BLUE,
    RED
}

enum class CommitResult {
    OK,
    ERROR_CLEAR
}