package com.carlist.pro.ui

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

class SystemFeedback(context: Context) {

    private val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
    private val vibrator = context.getSystemService(Vibrator::class.java)

    fun ok() {
        tone.startTone(ToneGenerator.TONE_PROP_ACK, 120)
        vibrateSafe(30, strong = false)
    }

    fun error() {
        tone.startTone(ToneGenerator.TONE_PROP_NACK, 180)
        vibrateSafe(60, strong = true)
    }

    fun warning() {
        tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 220)
        vibrateSafe(120, strong = true)
    }

    fun release() {
        tone.release()
    }

    private fun vibrateSafe(ms: Long, strong: Boolean) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amp = if (strong) 220 else 120
                v.vibrate(VibrationEffect.createOneShot(ms, amp))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms)
            }
        } catch (_: SecurityException) {
            // If vibration is not permitted, do nothing (never crash).
        } catch (_: Throwable) {
            // Any vendor-specific oddities: ignore safely.
        }
    }
}