package com.carlist.pro.ui

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.carlist.pro.databinding.DialogDriverRegistryBinding
import kotlin.math.roundToInt

class DriverRegistryDialogFragment : DialogFragment() {

    private var _binding: DialogDriverRegistryBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: DriverRegistryAdapter
    private lateinit var viewModel: MainViewModel

    private var suppressNextRegistryAutoFocus = false

    companion object {
        private val DEFAULT_REGISTRY_NUMBERS = listOf(
            2, 3, 5, 8, 10, 11, 12, 13, 14, 15, 17, 19,
            21, 22, 23, 25, 26, 29, 30, 31, 32, 37, 38, 40, 45,
            48, 49, 53, 57, 64, 66, 69, 73, 74, 76, 77, 78, 79,
            80, 84, 89, 92, 96, 99
        )
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogDriverRegistryBinding.inflate(LayoutInflater.from(requireContext()))

        val main = activity as MainActivity
        viewModel = main.getMainViewModel()

        ensureDefaultRegistryLoaded()

        adapter = DriverRegistryAdapter(
            getItems = { viewModel.getRegistryRows() },
            onRowActivated = { pos -> viewModel.setRegistryActiveRow(pos) },
            onNumberCommitted = { pos, old, txt ->
                viewModel.commitRegistryNumber(pos, old, txt)
            },
            onCategoryAction = { number, action ->
                suppressNextRegistryAutoFocus = true
                viewModel.onRegistryCategoryAction(number, action)
            }
        )

        binding.registryRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.registryRecycler.adapter = adapter

        binding.btnOk.setOnClickListener {
            viewModel.sortRegistryNumbersForClose()
            adapter.refresh()
            dismissAllowingStateLoss()
        }

        viewModel.registryUiTick.observe(this) {
            adapter.refresh()

            if (suppressNextRegistryAutoFocus) {
                suppressNextRegistryAutoFocus = false
                return@observe
            }

            focusActiveFieldAndShowKeyboard()
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setView(binding.root)
            .create()

        dialog.setCanceledOnTouchOutside(true)

        return dialog
    }

    override fun onStart() {
        super.onStart()

        dialog?.window?.let { w ->
            val width = (resources.displayMetrics.widthPixels * 0.84f).roundToInt()
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

            w.attributes = w.attributes.apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(24)
            }

            w.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            )

            w.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }

        binding.registryRecycler.post {
            adapter.refresh()
            focusActiveFieldAndShowKeyboard()
        }
    }

    private fun ensureDefaultRegistryLoaded() {
        val rows = viewModel.getRegistryRows()

        val hasAnyNumber = rows.any { it.number != null }
        if (hasAnyNumber) return

        DEFAULT_REGISTRY_NUMBERS.forEachIndexed { index, number ->
            val oldNumber = rows.getOrNull(index)?.number
            viewModel.commitRegistryNumber(
                index,
                oldNumber,
                number.toString()
            )
        }

        viewModel.setRegistryActiveRow(0)
    }

    private fun focusActiveFieldAndShowKeyboard() {
        val targetPosition = viewModel.getRegistryActiveRow().coerceAtLeast(0)

        binding.registryRecycler.post {
            binding.registryRecycler.scrollToPosition(targetPosition)

            binding.registryRecycler.post {
                val holder =
                    binding.registryRecycler.findViewHolderForAdapterPosition(targetPosition)
                            as? DriverRegistryAdapter.VH
                        ?: return@post

                val edit = holder.getEditText()

                edit.inputType = InputType.TYPE_CLASS_NUMBER
                edit.setRawInputType(InputType.TYPE_CLASS_NUMBER)
                edit.imeOptions = EditorInfo.IME_ACTION_DONE

                holder.focusField()

                edit.postDelayed({
                    if (!isAdded) return@postDelayed

                    edit.inputType = InputType.TYPE_CLASS_NUMBER
                    edit.setRawInputType(InputType.TYPE_CLASS_NUMBER)
                    edit.imeOptions = EditorInfo.IME_ACTION_DONE
                    edit.setSelection(edit.text?.length ?: 0)

                    val imm = requireContext().getSystemService(InputMethodManager::class.java)
                    imm.restartInput(edit)
                    imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
                }, 120L)
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as? MainActivity)?.onTechnicalMenuClosed()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
}