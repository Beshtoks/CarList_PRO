package com.carlist.pro.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

class VoiceInputManager(
    private val context: Context,
    private val onListeningStarted: () -> Unit,
    private val onSpeechDetected: () -> Unit,
    private val onNumberRecognized: (Int) -> Unit,
    private val onFailure: () -> Unit
) {

    private var speechRecognizer: SpeechRecognizer? = null

    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    fun startListening() {
        if (!isAvailable()) {
            onFailure()
            return
        }

        stopListening()

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {

                override fun onReadyForSpeech(params: Bundle?) {
                    onListeningStarted()
                }

                override fun onBeginningOfSpeech() {
                    onSpeechDetected()
                }

                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit

                override fun onError(error: Int) {
                    onFailure()
                }

                override fun onResults(results: Bundle?) {
                    val matches = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        .orEmpty()

                    val number = extractNumber(matches)
                    if (number != null) {
                        onNumberRecognized(number)
                    } else {
                        onFailure()
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }

        speechRecognizer?.startListening(intent)
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    fun release() {
        stopListening()
    }

    private fun extractNumber(matches: List<String>): Int? {
        for (raw in matches) {
            val direct = raw.trim().toIntOrNull()
            if (direct != null && direct in 1..99) {
                return direct
            }

            val tokenized = Regex("""\d+""")
                .findAll(raw)
                .mapNotNull { it.value.toIntOrNull() }
                .firstOrNull { it in 1..99 }

            if (tokenized != null) {
                return tokenized
            }
        }
        return null
    }
}