package com.carlist.pro.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.carlist.pro.R

class UiSoundManager(context: Context) {

    private val soundPool: SoundPool
    private var soundEnabled = true

    private var okSound = 0
    private var errorSound = 0
    private var deleteSound = 0
    private var warningSound = 0
    private var clearSound = 0
    private var syncSound = 0

    init {

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(6)
            .setAudioAttributes(attributes)
            .build()

        okSound = soundPool.load(context, R.raw.ui_ok, 1)
        errorSound = soundPool.load(context, R.raw.ui_error, 1)
        deleteSound = soundPool.load(context, R.raw.ui_delete, 1)
        warningSound = soundPool.load(context, R.raw.ui_warning, 1)
        clearSound = soundPool.load(context, R.raw.ui_clear, 1)
        syncSound = soundPool.load(context, R.raw.ui_sync, 1)
    }

    fun setSoundEnabled(enabled: Boolean) {
        soundEnabled = enabled
    }

    fun playOk() {
        if (!soundEnabled) return
        soundPool.play(okSound, 1f, 1f, 1, 0, 1f)
    }

    fun playError() {
        if (!soundEnabled) return
        soundPool.play(errorSound, 1f, 1f, 1, 0, 1f)
    }

    fun playDelete() {
        if (!soundEnabled) return
        soundPool.play(deleteSound, 1f, 1f, 1, 0, 1f)
    }

    fun playWarning() {
        if (!soundEnabled) return
        soundPool.play(warningSound, 1f, 1f, 1, 0, 1f)
    }

    fun playClear() {
        if (!soundEnabled) return
        soundPool.play(clearSound, 1f, 1f, 1, 0, 1f)
    }

    fun playSync() {
        if (!soundEnabled) return
        soundPool.play(syncSound, 1f, 1f, 1, 0, 1f)
    }

    fun release() {
        soundPool.release()
    }
}