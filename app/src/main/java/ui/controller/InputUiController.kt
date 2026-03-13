package com.carlist.pro.ui.controller

import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView

class InputUiController {

    private var manualInputMode = false

    fun setupInputPanel(numberInput: EditText, inputPanel: View) {
        numberInput.isLongClickable = false
        numberInput.setTextIsSelectable(false)
        numberInput.isClickable = false
        numberInput.isFocusable = false
        numberInput.isFocusableInTouchMode = false
        numberInput.isCursorVisible = false

        inputPanel.setOnLongClickListener { true }

        numberInput.setOnClickListener(null)
        numberInput.setOnLongClickListener { true }
    }

    fun startManualInputMode(
        numberInput: EditText,
        imm: InputMethodManager
    ) {
        manualInputMode = true

        numberInput.isFocusable = true
        numberInput.isFocusableInTouchMode = true
        numberInput.isCursorVisible = true
        numberInput.requestFocus()
        numberInput.setSelection(numberInput.text?.length ?: 0)

        imm.showSoftInput(numberInput, InputMethodManager.SHOW_IMPLICIT)
    }

    fun lockManualInput(numberInput: EditText) {
        manualInputMode = false
        numberInput.clearFocus()
        numberInput.isFocusable = false
        numberInput.isFocusableInTouchMode = false
        numberInput.isCursorVisible = false
    }

    fun isManualInputMode(): Boolean = manualInputMode

    fun applyInputVisualState(
        isTechMenuOpen: Boolean,
        isCountdownActive: Boolean,
        imeVisible: Boolean,
        inputHint: TextView,
        numberInput: EditText
    ) {
        if (isTechMenuOpen) return

        if (isCountdownActive) {
            inputHint.visibility = View.VISIBLE
            numberInput.isCursorVisible = false
            return
        }

        if (imeVisible && manualInputMode) {
            inputHint.visibility = View.INVISIBLE
            numberInput.isCursorVisible = true
        } else {
            inputHint.visibility = View.VISIBLE
            numberInput.isCursorVisible = false
            inputHint.text = "NR"
        }
    }
}