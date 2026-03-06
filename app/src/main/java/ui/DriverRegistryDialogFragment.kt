package com.carlist.pro.ui

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
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

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogDriverRegistryBinding.inflate(LayoutInflater.from(requireContext()))

        val main = activity as MainActivity
        val viewModel = main.getMainViewModel()

        adapter = DriverRegistryAdapter(
            getItems = { viewModel.getRegistryRows() },
            onRowActivated = { pos -> viewModel.setRegistryActiveRow(pos) },
            onNumberCommitted = { pos, old, txt -> viewModel.commitRegistryNumber(pos, old, txt) },
            onCategoryAction = { number, action ->
                // если у тебя в VM уже есть обработчик категорий — оставляем как есть.
                // (Адаптер сам отдаёт action, VM решает что делать)
                viewModel.onRegistryCategoryAction(number, action)
            }
        )

        binding.registryRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.registryRecycler.adapter = adapter

        binding.btnOk.setOnClickListener { dismissAllowingStateLoss() }

        // перерисовка меню при любых изменениях реестра/категорий
        viewModel.registryUiTick.observe(this) { adapter.refresh() }

        val dialog = AlertDialog.Builder(requireContext())
            .setView(binding.root)
            .create()

        dialog.setCanceledOnTouchOutside(true)

        // Важно: меню НЕ ресайзится под клавиатуру — клавиатура перекрывает низ (как ты и просил).
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        )

        return dialog
    }

    override fun onStart() {
        super.onStart()

        dialog?.window?.let { w ->
            // Уже и пониже.
            val width = (resources.displayMetrics.widthPixels * 0.84f).roundToInt()
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

            w.attributes = w.attributes.apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(24)
            }
        }

        // КЛЮЧ: отдаём фокус EditText внутри диалога, чтобы ввод НЕ улетал в главное табло.
        binding.registryRecycler.post {
            adapter.refresh()
            val holder = binding.registryRecycler.findViewHolderForAdapterPosition(0) as? DriverRegistryAdapter.VH
            holder?.focusField()

            val imm = requireContext().getSystemService(InputMethodManager::class.java)
            holder?.getEditText()?.let { et ->
                imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
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