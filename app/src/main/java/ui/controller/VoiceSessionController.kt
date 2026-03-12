package com.carlist.pro.ui.controller

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.carlist.pro.ui.VoiceInputManager

class VoiceSessionController(
    context: Context,
    private val hasRecordAudioPermission: () -> Boolean,
    private val requestRecordAudioPermission: () -> Unit,
    private val onSessionStarted: () -> Unit,
    private val onSessionStopped: () -> Unit,
    private val onNumberRecognized: (Int) -> Unit,
    private val applyMicOffState: () -> Unit,
    private val applyMicListeningState: () -> Unit,
    private val applyMicNotFoundState: () -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private val micSilenceTimeoutMs = 5_000L

    private var micSessionActive = false

    private val voiceInputManager = VoiceInputManager(
        context = context,
        onListeningStarted = {
            handler.post {
                if (!micSessionActive) return@post
                applyMicListeningState()
                restartMicSilenceTimer()
            }
        },
        onSpeechDetected = {
            handler.post {
                if (!micSessionActive) return@post
                restartMicSilenceTimer()
            }
        },
        onNumberRecognized = { recognizedNumber ->
            handler.post {
                if (!micSessionActive) return@post
                restartMicSilenceTimer()
                onNumberRecognized(recognizedNumber)
            }
        },
        onFailure = {
            handler.post {
                if (!micSessionActive) return@post
                restartMicSilenceTimer()
                scheduleNextVoiceListen()
            }
        }
    )

    private val micSilenceTimeoutRunnable = Runnable {
        if (micSessionActive) {
            stopSession()
        }
    }

    private val micRestoreSayNumberRunnable = Runnable {
        if (micSessionActive) {
            applyMicListeningState()
        }
    }

    init {
        applyMicOffState()
    }

    fun toggle() {
        if (micSessionActive) {
            stopSession()
        } else {
            ensurePermissionAndStart()
        }
    }

    fun onRecordAudioPermissionResult(granted: Boolean) {
        if (granted) {
            startSession()
        }
    }

    fun onNumberAccepted() {
        if (!micSessionActive) return
        applyMicListeningState()
        restartMicSilenceTimer()
        scheduleNextVoiceListen()
    }

    fun onNumberRejected() {
        if (!micSessionActive) return
        applyMicNotFoundState()
        handler.removeCallbacks(micRestoreSayNumberRunnable)
        handler.postDelayed(micRestoreSayNumberRunnable, 500L)
        restartMicSilenceTimer()
        scheduleNextVoiceListen()
    }

    fun release() {
        handler.removeCallbacks(micSilenceTimeoutRunnable)
        handler.removeCallbacks(micRestoreSayNumberRunnable)
        voiceInputManager.release()
        micSessionActive = false
        applyMicOffState()
    }

    private fun ensurePermissionAndStart() {
        if (hasRecordAudioPermission()) {
            startSession()
        } else {
            requestRecordAudioPermission()
        }
    }

    private fun startSession() {
        if (!voiceInputManager.isAvailable()) {
            applyMicOffState()
            return
        }

        micSessionActive = true
        onSessionStarted()
        applyMicListeningState()
        restartMicSilenceTimer()
        scheduleNextVoiceListen()
    }

    private fun stopSession() {
        micSessionActive = false
        onSessionStopped()
        handler.removeCallbacks(micSilenceTimeoutRunnable)
        handler.removeCallbacks(micRestoreSayNumberRunnable)
        voiceInputManager.stopListening()
        applyMicOffState()
    }

    private fun restartMicSilenceTimer() {
        handler.removeCallbacks(micSilenceTimeoutRunnable)
        handler.postDelayed(micSilenceTimeoutRunnable, micSilenceTimeoutMs)
    }

    private fun scheduleNextVoiceListen() {
        if (!micSessionActive) return

        voiceInputManager.stopListening()
        voiceInputManager.startListening()
    }
}