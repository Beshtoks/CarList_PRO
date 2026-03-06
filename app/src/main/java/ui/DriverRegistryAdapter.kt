package com.carlist.pro.ui

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
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
        return VH(binding, ::activate, ::commit, ::openMenu)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        if (items.isEmpty()) items = getItems()
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int {
        if (items.isEmpty()) items = getItems()
        return items.size
    }

    private fun activate(pos: Int) = onRowActivated(pos)

    private fun commit(pos: Int, old: Int?, text: String): CommitResult =
        onNumberCommitted(pos, old, text)

    private fun openMenu(number: Int, anchor: View) {
        val context = anchor.context
        val menu = androidx.appcompat.widget.PopupMenu(context, anchor)
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

    class VH(
        private val binding: ItemRegistryRowBinding,
        private val onActivate: (pos: Int) -> Unit,
        private val onCommit: (pos: Int, old: Int?, text: String) -> CommitResult,
        private val onLongPressMenu: (number: Int, anchor: View) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: RegistryRow, position: Int) {
            // Letters
            binding.tvLetters.text = row.info.letters()

            // Number
            val asText = row.number?.toString().orEmpty()
            if (binding.etNumber.text?.toString() != asText) {
                binding.etNumber.setText(asText)
            }

            // Underline state
            val color = when (row.underline) {
                UnderlineState.NONE -> 0x00000000
                UnderlineState.BLUE -> 0xFF2B6CB0.toInt()
                UnderlineState.RED -> 0xFFC53030.toInt()
            }
            binding.underline.setBackgroundColor(color)

            binding.root.setOnClickListener {
                onActivate(position)
            }

            // Commit on Enter/Done
            binding.etNumber.setOnEditorActionListener { _, actionId, event ->
                val isEnter =
                    actionId == EditorInfo.IME_ACTION_DONE ||
                            (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)

                if (!isEnter) return@setOnEditorActionListener false

                val res = onCommit(position, row.number, binding.etNumber.text?.toString().orEmpty())
                applyCommitResult(res)
                true
            }

            // Long press -> category menu (only if number exists)
            binding.root.setOnLongClickListener {
                val num = row.number ?: return@setOnLongClickListener true
                onLongPressMenu(num, binding.root)
                true
            }
        }

        private fun applyCommitResult(res: CommitResult) {
            when (res) {
                CommitResult.OK -> { /* UI обновится через tick */ }
                CommitResult.ERROR_CLEAR -> {
                    binding.etNumber.setText("")
                }
            }
        }
    }
}

/** Row model for adapter */
data class RegistryRow(
    val number: Int?,              // null = empty new row
    val info: TransportInfo,
    val underline: UnderlineState
)

enum class UnderlineState { NONE, BLUE, RED }

enum class CommitResult { OK, ERROR_CLEAR }