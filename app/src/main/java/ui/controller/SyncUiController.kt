package com.carlist.pro.ui.controller

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog as AppCompatAlertDialog
import androidx.core.content.ContextCompat
import com.carlist.pro.domain.sync.SyncOffer
import com.carlist.pro.sync.SyncForegroundService
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SyncUiController(
    private val context: Context,
    private val onAcceptSyncOffer: () -> Unit,
    private val onDeclineSyncOffer: () -> Unit,
    private val onNetworkAlertAcknowledged: () -> Unit
) {

    private var syncOfferDialog: AppCompatAlertDialog? = null
    private var syncOfferHandled = false
    private var currentSyncOffer: SyncOffer? = null

    private var networkAlertDialog: AlertDialog? = null
    private var networkAlertAcknowledged = false

    fun showSyncOfferDialog(offer: SyncOffer) {
        currentSyncOffer = offer
        syncOfferHandled = false

        if (syncOfferDialog == null) {
            syncOfferDialog = MaterialAlertDialogBuilder(context)
                .setTitle("SYNC OFFER")
                .setMessage(buildSyncOfferText(offer))
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
                    currentSyncOffer = null
                    onDeclineSyncOffer()
                }
            }
        } else {
            syncOfferDialog?.setMessage(buildSyncOfferText(offer))
        }

        if (syncOfferDialog?.isShowing != true) {
            syncOfferDialog?.show()
        }
    }

    fun dismissSyncOfferDialogOnly() {
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
    }

    fun dismissNetworkAlertDialogOnly() {
        networkAlertDialog?.dismiss()
        networkAlertDialog = null
    }

    fun release() {
        syncOfferDialog?.dismiss()
        syncOfferDialog = null
        networkAlertDialog?.dismiss()
        networkAlertDialog = null
    }

    private fun buildSyncOfferText(offer: SyncOffer): String {
        val authorText = offer.snapshot.authorNumber?.let { "№$it" } ?: "неизвестно"
        val updatedAtMs = offer.snapshot.updatedAtMs
        val minutesAgo = if (updatedAtMs > 0L) {
            ((System.currentTimeMillis() - updatedAtMs).coerceAtLeast(0L) / 60_000L).toInt()
        } else {
            0
        }

        return "Последний список выложил $authorText.\n" +
                "Выложен $minutesAgo мин. назад.\n" +
                "Принять список?\n" +
                "Осталось: ${offer.secondsRemaining.coerceAtLeast(0)} сек."
    }

    private fun dismissSyncOfferDialog(accepted: Boolean) {
        if (syncOfferHandled) return

        syncOfferHandled = true
        currentSyncOffer = null

        if (accepted) {
            onAcceptSyncOffer()
        } else {
            onDeclineSyncOffer()
        }

        syncOfferDialog?.dismiss()
        syncOfferDialog = null
    }

    private fun acknowledgeNetworkAlert() {
        if (networkAlertAcknowledged) return

        networkAlertAcknowledged = true
        silenceForegroundServiceAlert()
        onNetworkAlertAcknowledged()
        dismissNetworkAlertDialogOnly()
    }

    private fun silenceForegroundServiceAlert() {
        val intent = Intent(context, SyncForegroundService::class.java).apply {
            action = SyncForegroundService.ACTION_SILENCE_ALERT
        }
        ContextCompat.startForegroundService(context, intent)
    }
}