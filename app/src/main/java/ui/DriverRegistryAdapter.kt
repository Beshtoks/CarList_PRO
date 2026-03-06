package com.carlist.pro.ui

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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

            binding.etNumber.imeOptions = EditorInfo.IME_ACTION_DONE
            binding.etNumber.setSingleLine(true)

            binding.root.setOnClickListener {
                onRowActivated(position)
                focusField()
                showKeyboard()
            }

            binding.etNumber.setOnClickListener {
                onRowActivated(position)
                focusField()
                showKeyboard()
            }

            binding.etNumber.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    onRowActivated(position)
                    showKeyboard()
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
                    CommitResult.OK -> {
                        // UI обновится через registryUiTick
                    }

                    CommitResult.ERROR_CLEAR -> {
                        binding.etNumber.setText("")
                        focusField()
                        showKeyboard()
                    }
                }

                true
            }

            binding.root.setOnLongClickListener {
                val num = row.number ?: return@setOnLongClickListener true

                val popup = PopupMenu(binding.root.context, binding.root)

                popup.menu.add("BUS")
                popup.menu.add("VAN")
                popup.menu.add("MY_CAR")
                popup.menu.add("CLEAR")

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
            binding.etNumber.isFocusable = true
            binding.etNumber.isFocusableInTouchMode = true
            binding.etNumber.isCursorVisible = true
            binding.etNumber.requestFocus()
            binding.etNumber.setSelection(binding.etNumber.text?.length ?: 0)
        }

        fun getEditText() = binding.etNumber

        private fun showKeyboard() {
            binding.etNumber.post {
                val imm = binding.etNumber.context
                    .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(binding.etNumber, InputMethodManager.SHOW_IMPLICIT)
            }
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