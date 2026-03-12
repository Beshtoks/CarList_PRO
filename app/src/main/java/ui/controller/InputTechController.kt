package com.carlist.pro.ui.controller

import android.os.Handler
import android.os.Looper

class InputTechController(
    private val onStartManualInputMode: () -> Unit,
    private val onLockManualInput: () -> Unit,
    private val onApplyInputVisualState: (imeVisible: Boolean) -> Unit,
    private val onShowCountdownValue: (text: String) -> Unit,
    private val onOpenTechnicalMenu: () -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())

    private var techTapCount = 0
    private var techFirstTapAtMs = 0L
    private var techCountdownActive = false

    private val techTapTimeoutRunnable = Runnable {
        if (techTapCount > 0 || techCountdownActive) {
            resetTechTapSequence()
            onApplyInputVisualState(false)
        }
    }

    fun onInputPanelReleased() {
        resetTechTapSequence()
        onStartManualInputMode()
    }

    fun onCounterPanelReleased() {
        handleTechTaps()
    }

    fun onOutsideReleased(imeVisibleNow: Boolean) {
        if (techTapCount > 0 || techCountdownActive) {
            resetTechTapSequence()
            onApplyInputVisualState(imeVisibleNow)
        }
    }

    fun onKeyboardHidden() {
        onLockManualInput()
    }

    fun onTechnicalMenuClosed(imeVisibleNow: Boolean) {
        resetTechTapSequence()
        onApplyInputVisualState(imeVisibleNow)
    }

    fun isCountdownActive(): Boolean = techCountdownActive

    fun reset() {
        resetTechTapSequence()
    }

    fun release() {
        handler.removeCallbacks(techTapTimeoutRunnable)
    }

    private fun handleTechTaps() {
        val now = System.currentTimeMillis()

        if (techTapCount == 0) {
            techFirstTapAtMs = now
        } else if (now - techFirstTapAtMs > 5_000L) {
            resetTechTapSequence()
            techFirstTapAtMs = now
        }

        techTapCount++

        handler.removeCallbacks(techTapTimeoutRunnable)
        handler.postDelayed(techTapTimeoutRunnable, 5_000L)

        when (techTapCount) {
            1 -> Unit

            2 -> {
                techCountdownActive = true
                onShowCountdownValue("3")
                onApplyInputVisualState(true)
            }

            3 -> onShowCountdownValue("2")
            4 -> onShowCountdownValue("1")

            5 -> {
                handler.removeCallbacks(techTapTimeoutRunnable)
                onShowCountdownValue("OPEN")
                techTapCount = 0
                techCountdownActive = false
                onOpenTechnicalMenu()
            }
        }
    }

    private fun resetTechTapSequence() {
        handler.removeCallbacks(techTapTimeoutRunnable)
        techTapCount = 0
        techFirstTapAtMs = 0L
        techCountdownActive = false
    }
}