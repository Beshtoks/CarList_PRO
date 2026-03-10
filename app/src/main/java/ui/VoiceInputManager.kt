package com.carlist.pro.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
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

                    Log.d("VoiceInputManager", "VOICE MATCHES = $matches")

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
                RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH
            )

            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")

            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)

            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)

            putExtra(
                RecognizerIntent.EXTRA_CALLING_PACKAGE,
                context.packageName
            )
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

            val normalized = raw
                .lowercase(Locale.getDefault())
                .replace('ё','е')
                .trim()

            // специальное правило для "Sims 4"
            if (normalized.contains("sims 4")) {
                return 74
            }

            // вытаскиваем цифры
            val digits = Regex("\\d")
                .findAll(normalized)
                .map { it.value }
                .toList()

            if (digits.size >= 2) {
                val number = (digits[0] + digits[1]).toInt()
                if (number in 1..99) return number
            }

            if (digits.size == 1) {
                val number = digits[0].toInt()
                if (number in 1..99) return number
            }

            val words = normalized.split(" ")

            if (words.size >= 2) {

                val first = wordToDigit(words[0])
                val second = wordToDigit(words[1])

                if (first != null && second != null) {
                    val number = (first.toString() + second.toString()).toInt()
                    if (number in 1..99) return number
                }
            }

            if (words.size == 1) {

                val digit = wordToDigit(words[0])

                if (digit != null) {
                    return digit
                }
            }
        }

        return null
    }

    private fun wordToDigit(word: String): Int? {

        return when (word) {

            "ноль","нуль" -> 0
            "один","одна" -> 1
            "два","две" -> 2
            "три" -> 3
            "четыре" -> 4
            "пять" -> 5
            "шесть" -> 6
            "семь","сем","сэм" -> 7
            "восемь" -> 8
            "девять" -> 9

            else -> null
        }
    }
}