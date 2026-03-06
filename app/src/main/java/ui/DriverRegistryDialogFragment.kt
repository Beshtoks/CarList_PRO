package com.carlist.pro.ui

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class DriverRegistryDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // Твой текущий UI техменю остаётся как есть (я его не перестраиваю тут).
        // Важно сейчас только: фокус и IME должны принадлежать диалогу.

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("TECHNICAL MENU")
            .setView(RecyclerView(requireContext()))
            .setPositiveButton("OK") { _, _ -> dismissAllowingStateLoss() }
            .create()

        // Раз ты хочешь, чтобы меню "лезло под клавиатуру" — оставляем NOTHNG.
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        )

        return dialog
    }

    override fun onStart() {
        super.onStart()

        // КЛЮЧ: принудительно отдаём фокус первому EditText внутри диалога, если он есть,
        // и просим IME открыться уже для него.
        dialog?.window?.decorView?.post {

            val firstEdit = dialog?.window?.decorView?.findViewById<EditText>(android.R.id.edit)
            // (Если у тебя EditText не android.R.id.edit — ниже есть универсальный поиск)
            val target = firstEdit ?: findFirstEditText(dialog?.window?.decorView)

            target?.let { et ->
                et.requestFocus()
                val imm = requireContext().getSystemService(InputMethodManager::class.java)
                imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            (resources.displayMetrics.heightPixels * 0.72f).toInt()
        )
    }

    private fun findFirstEditText(root: android.view.View?): EditText? {
        if (root == null) return null
        if (root is EditText) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findFirstEditText(root.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as? MainActivity)?.onTechnicalMenuClosed()
    }
}