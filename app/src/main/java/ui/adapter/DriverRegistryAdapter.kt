package com.carlist.pro.ui.adapter

import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.carlist.pro.databinding.ItemRegistryRowBinding
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
                val num = row.number ?: return@setOnClickListener

                val popup = PopupMenu(binding.root.context, binding.tvLetters)

                val busTitle = SpannableString("BUS")
                busTitle.setSpan(
                    ForegroundColorSpan(0xFFFFD54F.toInt()),
                    0,
                    busTitle.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                val vanTitle = SpannableString("VAN")
                vanTitle.setSpan(
                    ForegroundColorSpan(0xFF81D4FA.toInt()),
                    0,
                    vanTitle.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                val myCarTitle = SpannableString("MY_CAR")
                myCarTitle.setSpan(
                    ForegroundColorSpan(0xFFA5D6A7.toInt()),
                    0,
                    myCarTitle.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                val clearTitle = SpannableString("CLEAR")
                clearTitle.setSpan(
                    ForegroundColorSpan(0xFFE0E0E0.toInt()),
                    0,
                    clearTitle.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                popup.menu.add(busTitle)
                popup.menu.add(vanTitle)
                popup.menu.add(myCarTitle)
                popup.menu.add(clearTitle)

                popup.setOnMenuItemClickListener { item ->
                    when (item.title.toString()) {
                        "BUS" -> onCategoryAction(num, CategoryAction.BUS)
                        "VAN" -> onCategoryAction(num, CategoryAction.VAN)
                        "MY_CAR" -> onCategoryAction(num, CategoryAction.MY_CAR)
                        "CLEAR" -> onCategoryAction(num, CategoryAction.CLEAR)
                    }

                    true
                }

                popup.show()

                true
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