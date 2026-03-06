package com.carlist.pro.ui

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
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
        val binding = ItemRegistryRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        if (items.isEmpty()) items = getItems()
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int {
        if (items.isEmpty()) items = getItems()
        return items.size
    }

    inner class VH(private val binding: ItemRegistryRowBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: RegistryRow, position: Int) {

            binding.tvLetters.text = row.info.letters()

            val txt = row.number?.toString().orEmpty()
            if (binding.etNumber.text?.toString() != txt) {
                binding.etNumber.setText(txt)
                binding.etNumber.setSelection(binding.etNumber.text?.length ?: 0)
            }

            val underlineColor = when (row.underline) {
                UnderlineState.NONE -> 0x33000000
                UnderlineState.BLUE -> 0xFF2B6CB0.toInt()
                UnderlineState.RED -> 0xFFC53030.toInt()
            }
            binding.underline.setBackgroundColor(underlineColor)

            binding.root.setOnClickListener {
                onRowActivated(position)
                focusField()
            }

            binding.etNumber.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onRowActivated(position)
            }

            binding.etNumber.setOnEditorActionListener { _, actionId, event ->
                val isEnter =
                    actionId == EditorInfo.IME_ACTION_DONE ||
                            actionId == EditorInfo.IME_ACTION_NEXT ||
                            (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)

                if (!isEnter) return@setOnEditorActionListener false

                val res = onNumberCommitted(position, row.number, binding.etNumber.text?.toString().orEmpty())
                if (res == CommitResult.ERROR_CLEAR) {
                    binding.etNumber.setText("")
                    focusField()
                }
                true
            }

            binding.root.setOnLongClickListener {
                val number = row.number ?: return@setOnLongClickListener true
                showCategoryMenu(number, binding.root)
                true
            }
        }

        fun focusField() {
            binding.etNumber.requestFocus()
            binding.etNumber.setSelection(binding.etNumber.text?.length ?: 0)
        }

        fun getEditText(): EditText = binding.etNumber

        private fun showCategoryMenu(number: Int, anchor: View) {
            val menu = PopupMenu(anchor.context, anchor)
            menu.menu.add("BUS")
            menu.menu.add("VAN")
            menu.menu.add("MY_CAR")
            menu.menu.add("CLEAR")

            menu.setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "BUS" -> onCategoryAction(number, CategoryAction.BUS)
                    "VAN" -> onCategoryAction(number, CategoryAction.VAN)
                    "MY_CAR" -> onCategoryAction(number, CategoryAction.MY_CAR)
                    "CLEAR" -> onCategoryAction(number, CategoryAction.CLEAR)
                }
                true
            }
            menu.show()
        }
    }
}

data class RegistryRow(
    val number: Int?,
    val info: TransportInfo,
    val underline: UnderlineState
)

enum class UnderlineState { NONE, BLUE, RED }

enum class CommitResult { OK, ERROR_CLEAR }

private fun TransportInfo.letters(): String {
    val sb = StringBuilder()
    when {
        transportType.name == "BUS" -> sb.append("B")
        transportType.name == "VAN" -> sb.append("V")
    }
    if (isMyCar) sb.append("M")
    return if (sb.isEmpty()) "-" else sb.toString()
}