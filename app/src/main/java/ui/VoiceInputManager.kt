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
                RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
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
            val normalized = normalizeRaw(raw)

            val directCompactDigits = normalized
                .replace(" ", "")
                .replace("-", "")

            val direct = directCompactDigits.toIntOrNull()
            if (direct != null && direct in 1..99) {
                return direct
            }

            val digitSequence = parseAsDigitSequence(normalized)
            if (digitSequence != null) {
                return digitSequence
            }

            val russianWordsNumber = parseRussianWordsNumber(normalized)
            if (russianWordsNumber != null) {
                return russianWordsNumber
            }

            val tokenizedDigits = Regex("""\d+""")
                .findAll(normalized)
                .mapNotNull { it.value.toIntOrNull() }
                .toList()

            if (tokenizedDigits.isNotEmpty()) {
                if (tokenizedDigits.size >= 2 &&
                    tokenizedDigits[0] in 0..9 &&
                    tokenizedDigits[1] in 0..9
                ) {
                    val joined = "${tokenizedDigits[0]}${tokenizedDigits[1]}".toIntOrNull()
                    if (joined != null && joined in 1..99) {
                        return joined
                    }
                }

                val first = tokenizedDigits.firstOrNull { it in 1..99 }
                if (first != null) {
                    return first
                }
            }
        }

        return null
    }

    private fun normalizeRaw(raw: String): String {
        return raw
            .lowercase(Locale.getDefault())
            .replace('ё', 'е')
            .replace(Regex("""[,_;:]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun parseAsDigitSequence(text: String): Int? {
        val digitWordMap = mapOf(
            "ноль" to "0",
            "нуль" to "0",
            "один" to "1",
            "одна" to "1",
            "два" to "2",
            "три" to "3",
            "четыре" to "4",
            "пять" to "5",
            "шесть" to "6",
            "семь" to "7",
            "восемь" to "8",
            "девять" to "9"
        )

        val prepared = text
            .replace("-", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (prepared.isEmpty()) return null

        val tokens = prepared.split(" ")
        val digits = mutableListOf<String>()

        for (token in tokens) {
            when {
                token in digitWordMap -> digits += digitWordMap.getValue(token)
                token.length == 1 && token[0].isDigit() -> digits += token
                else -> return null
            }
        }

        if (digits.isEmpty()) return null

        val joined = digits.joinToString("").toIntOrNull() ?: return null
        return joined.takeIf { it in 1..99 }
    }

    private fun parseRussianWordsNumber(text: String): Int? {
        val units = mapOf(
            "ноль" to 0,
            "нуль" to 0,
            "один" to 1,
            "одна" to 1,
            "два" to 2,
            "три" to 3,
            "четыре" to 4,
            "пять" to 5,
            "шесть" to 6,
            "семь" to 7,
            "восемь" to 8,
            "девять" to 9
        )

        val teens = mapOf(
            "десять" to 10,
            "одиннадцать" to 11,
            "двенадцать" to 12,
            "тринадцать" to 13,
            "четырнадцать" to 14,
            "пятнадцать" to 15,
            "шестнадцать" to 16,
            "семнадцать" to 17,
            "восемнадцать" to 18,
            "девятнадцать" to 19
        )

        val tens = mapOf(
            "двадцать" to 20,
            "тридцать" to 30,
            "сорок" to 40,
            "пятьдесят" to 50,
            "шестьдесят" to 60,
            "семьдесят" to 70,
            "восемьдесят" to 80,
            "девяносто" to 90
        )

        val prepared = text
            .replace("-", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (prepared.isEmpty()) return null

        val tokens = prepared.split(" ")

        if (tokens.size == 1) {
            val one = tokens[0]
            val value = teens[one] ?: tens[one] ?: units[one]
            return value?.takeIf { it in 1..99 }
        }

        if (tokens.size == 2) {
            val first = tokens[0]
            val second = tokens[1]

            val tensValue = tens[first]
            val unitValue = units[second]

            if (tensValue != null && unitValue != null) {
                val value = tensValue + unitValue
                return value.takeIf { it in 1..99 }
            }
        }

        return null
    }
}