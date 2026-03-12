package com.carlist.pro.ui.controller

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog as AppCompatAlertDialog
import com.carlist.pro.domain.sync.SyncOffer
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SyncUiController(
    private val context: Context,
    private val onAcceptSyncOffer: () -> Unit,
    private val onDeclineSyncOffer: () -> Unit,
    private val onNetworkAlertAcknowledged: () -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())

    private var syncOfferDialog: AppCompatAlertDialog? = null
    private var syncOfferHandled = false
    private var currentSyncOffer: SyncOffer? = null
    private var syncOfferSecondsLeft = 0

    private var networkAlertDialog: AlertDialog? = null
    private var networkAlertAcknowledged = false

    private val syncOfferCountdownRunnable = object : Runnable {
        override fun run() {
            val offer = currentSyncOffer ?: return

            if (syncOfferSecondsLeft <= 0) {
                dismissSyncOfferDialog(accepted = false)
                return
            }

            updateSyncOfferDialogText(offer, syncOfferSecondsLeft)
            syncOfferSecondsLeft -= 1
            handler.postDelayed(this, 1000L)
        }
    }

    private val networkVibrationRunnable = object : Runnable {
        override fun run() {
            if (networkAlertDialog?.isShowing != true || networkAlertAcknowledged) return

            vibrateNetworkPulse()
            handler.postDelayed(this, 4_000L)
        }
    }

    fun showSyncOfferDialog(offer: SyncOffer) {
        currentSyncOffer = offer
        syncOfferHandled = false
        syncOfferSecondsLeft = offer.secondsRemaining.coerceAtLeast(0)

        if (syncOfferDialog == null) {
            syncOfferDialog = MaterialAlertDialogBuilder(context)
                .setTitle("SYNC OFFER")
                .setMessage("")
                .setCancelable(false)
                .setPositiveButton("YES") { _, _ ->
                    dismissSyncOfferDialog(accepted = true)
                }
                .setNegativeButton("NO") { _, _ ->
                    dismissSyncOfferDialog(accepted = false)
                }
                .create()

            syncOfferDialog?.setOnDismissListener {
                if (!syncOfferHandled) {
                    syncOfferHandled = true
                    handler.removeCallbacks(syncOfferCountdownRunnable)
                    currentSyncOffer = null
                    onDeclineSyncOffer()
                }
            }
        }

        updateSyncOfferDialogText(offer, syncOfferSecondsLeft)

        if (syncOfferDialog?.isShowing != true) {
            syncOfferDialog?.show()
        }

        handler.removeCallbacks(syncOfferCountdownRunnable)
        handler.post(syncOfferCountdownRunnable)
    }

    fun dismissSyncOfferDialogOnly() {
        handler.removeCallbacks(syncOfferCountdownRunnable)
        currentSyncOffer = null
        syncOfferHandled = true
        syncOfferDialog?.dismiss()
        syncOfferDialog = null
    }

    fun showNetworkAlertDialog() {
        if (networkAlertDialog?.isShowing == true) return

        networkAlertAcknowledged = false

        val density = context.resources.displayMetrics.density

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                (24 * density).toInt(),
                (20 * density).toInt(),
                (24 * density).toInt(),
                (20 * density).toInt()
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18f * density
                setColor(0xFF1A001F.toInt())
                setStroke((1 * density).toInt(), 0xFF5A1A2A.toInt())
            }
        }

        val titleView = TextView(context).apply {
            text = "WARNING"
            setTextColor(0xFFFF4444.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val messageView = TextView(context).apply {
            text = "No network connection"
            setTextColor(0xFFFF4444.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(0, (10 * density).toInt(), 0, 0)
        }

        container.addView(
            titleView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        container.addView(
            messageView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        container.setOnClickListener {
            acknowledgeNetworkAlert()
        }

        val dialog = AlertDialog.Builder(context)
            .setView(container)
            .create()

        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnKeyListener { _, _, _ -> true }

        networkAlertDialog = dialog
        dialog.show()

        handler.removeCallbacks(networkVibrationRunnable)
        handler.post(networkVibrationRunnable)
    }

    fun dismissNetworkAlertDialogOnly() {
        handler.removeCallbacks(networkVibrationRunnable)
        stopVibration()
        networkAlertDialog?.dismiss()
        networkAlertDialog = null
    }

    fun release() {
        handler.removeCallbacks(syncOfferCountdownRunnable)
        handler.removeCallbacks(networkVibrationRunnable)
        stopVibration()
        syncOfferDialog?.dismiss()
        syncOfferDialog = null
        networkAlertDialog?.dismiss()
        networkAlertDialog = null
    }

    private fun updateSyncOfferDialogText(offer: SyncOffer, secondsLeft: Int) {
        val authorText = offer.snapshot.authorNumber?.let { "№$it" } ?: "неизвестно"
        val updatedAtMs = offer.snapshot.updatedAtMs
        val minutesAgo = if (updatedAtMs > 0L) {
            ((System.currentTimeMillis() - updatedAtMs).coerceAtLeast(0L) / 60_000L).toInt()
        } else {
            0
        }

        val dialog = syncOfferDialog ?: return

        dialog.setTitle("SYNC OFFER")
        dialog.setMessage(
            "Последний список выложил $authorText.\n" +
                    "Выложен $minutesAgo мин. назад.\n" +
                    "Принять список?\n" +
                    "Осталось: ${secondsLeft.coerceAtLeast(0)} сек."
        )
    }

    private fun dismissSyncOfferDialog(accepted: Boolean) {
        if (syncOfferHandled) return

        syncOfferHandled = true
        handler.removeCallbacks(syncOfferCountdownRunnable)

        val dialog = syncOfferDialog
        currentSyncOffer = null

        if (accepted) {
            onAcceptSyncOffer()
        } else {
            onDeclineSyncOffer()
        }

        dialog?.dismiss()
        syncOfferDialog = null
    }

    private fun acknowledgeNetworkAlert() {
        if (networkAlertAcknowledged) return

        networkAlertAcknowledged = true
        handler.removeCallbacks(networkVibrationRunnable)
        stopVibration()
        onNetworkAlertAcknowledged()
        dismissNetworkAlertDialogOnly()
    }

    private fun vibrateNetworkPulse() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(Vibrator::class.java)
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    3_000L,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(3_000L)
        }
    }

    private fun stopVibration() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(Vibrator::class.java)
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        vibrator.cancel()
    }
}