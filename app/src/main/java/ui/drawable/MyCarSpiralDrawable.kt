package com.carlist.pro.ui.drawable

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import kotlin.math.sin

class MyCarSpiralDrawable : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val wavePaintLight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
    }

    private val wavePaintDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22000000
    }

    private var phase = 0f

    private val waveWidth = 140f
    private val amplitude = 22f
    private val frequency = 0.045f

    private val animator = ValueAnimator.ofFloat(0f, waveWidth).apply {
        duration = 1000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidateSelf()
        }
    }

    init {
        animator.start()
    }

    override fun draw(canvas: Canvas) {
        val rect = bounds
        val width = rect.width().toFloat()
        val height = rect.height().toFloat()

        val baseGradient = LinearGradient(
            0f,
            0f,
            width,
            height,
            intArrayOf(
                0xFFF6E8B8.toInt(),
                0xFFEADFA3.toInt(),
                0xFFDCCB7A.toInt()
            ),
            null,
            Shader.TileMode.CLAMP
        )

        paint.shader = baseGradient
        canvas.drawRect(rect, paint)

        var x = -waveWidth + phase
        while (x < width + waveWidth) {
            drawWave(
                canvas = canvas,
                startX = x,
                height = height,
                paint = wavePaintLight,
                phaseShift = 0f
            )

            drawWave(
                canvas = canvas,
                startX = x + waveWidth / 2f,
                height = height,
                paint = wavePaintDark,
                phaseShift = 0.7f
            )

            x += waveWidth
        }
    }

    private fun drawWave(
        canvas: Canvas,
        startX: Float,
        height: Float,
        paint: Paint,
        phaseShift: Float
    ) {
        val path = Path()
        path.moveTo(startX, 0f)

        var y = 0f
        while (y <= height) {
            val wave = sin((y * frequency) + (phase / waveWidth) * 6f + phaseShift) * amplitude
            path.lineTo(startX + wave.toFloat(), y)
            y += 1f
        }

        path.lineTo(startX + waveWidth, height)

        y = height
        while (y >= 0f) {
            val wave = sin((y * frequency) + (phase / waveWidth) * 6f + phaseShift) * amplitude
            path.lineTo(startX + waveWidth + wave.toFloat(), y)
            y -= 1f
        }

        path.close()
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}