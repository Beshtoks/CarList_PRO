package com.carlist.pro.ui.controller

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog as AppCompatAlertDialog
import androidx.core.content.ContextCompat
import com.carlist.pro.R
import com.carlist.pro.databinding.DialogSyncOfferBinding
import com.carlist.pro.domain.sync.SyncOffer
import com.carlist.pro.sync.SyncForegroundService

class SyncUiController(
    private val context: Context,
    private val onAcceptSyncOffer: () -> Unit,
    private val onDeclineSyncOffer: () -> Unit,
    private val onNetworkAlertAcknowledged: () -> Unit,
    private val onConfirmUpload: () -> Unit = {},
    private val onCancelUpload: () -> Unit = {}
) {

    enum class SyncDialogMode {
        DOWNLOAD,
        UPLOAD
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var syncOfferDialog: AppCompatAlertDialog? = null
    private var syncOfferBinding: DialogSyncOfferBinding? = null
    private var syncOfferHandled = false
    private var syncOfferInitialSeconds = 0
    private var syncOfferStartElapsedMs = 0L
    private var currentDialogMode: SyncDialogMode = SyncDialogMode.UPLOAD

    private val syncOfferCountdownRunnable = object : Runnable {
        override fun run() {
            val binding = syncOfferBinding ?: return
            val dialog = syncOfferDialog ?: return
            if (!dialog.isShowing) return

            val remaining = getRemainingSyncOfferSeconds()
            binding.tvSecondsLeft.text = "$remaining sec"

            if (remaining <= 0) {
                dismissUploadDialog(accepted = false)
                return
            }

            mainHandler.postDelayed(this, 1000L)
        }
    }

    private var networkAlertDialog: AlertDialog? = null
    private var networkAlertAcknowledged = false

    fun showSyncOfferDialog(offer: SyncOffer) {
        dismissSyncOfferDialogOnly()
    }

    fun showUploadConfirmDialog(
        currentListSize: Int,
        secondsRemaining: Int = 15
    ) {
        currentDialogMode = SyncDialogMode.UPLOAD
        syncOfferHandled = false
        syncOfferInitialSeconds = secondsRemaining.coerceAtLeast(0)
        syncOfferStartElapsedMs = SystemClock.elapsedRealtime()

        if (syncOfferDialog == null || syncOfferBinding == null) {
            createSyncOfferDialog()
        }

        bindUploadOffer(currentListSize)
        restartSyncOfferCountdown()

        if (syncOfferDialog?.isShowing != true) {
            syncOfferDialog?.show()
            syncOfferDialog?.window?.let { window ->
                window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                val width = (context.resources.displayMetrics.widthPixels * 0.88f).toInt()
                window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    fun dismissSyncOfferDialogOnly() {
        stopSyncOfferCountdown()
        syncOfferHandled = true
        syncOfferDialog?.dismiss()
        syncOfferDialog = null
        syncOfferBinding = null
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
        stopSyncOfferCountdown()
        syncOfferDialog?.dismiss()
        syncOfferDialog = null
        syncOfferBinding = null

        networkAlertDialog?.dismiss()
        networkAlertDialog = null
    }

    private fun createSyncOfferDialog() {
        val binding = DialogSyncOfferBinding.inflate(LayoutInflater.from(context))
        syncOfferBinding = binding

        binding.btnNo.setOnClickListener {
            dismissUploadDialog(accepted = false)
        }

        binding.btnDownload.setOnClickListener {
            dismissUploadDialog(accepted = true)
        }

        val dialog = AppCompatAlertDialog.Builder(context)
            .setView(binding.root)
            .create()

        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener {
            stopSyncOfferCountdown()

            if (!syncOfferHandled) {
                syncOfferHandled = true
                onCancelUpload()
            }
        }

        syncOfferDialog = dialog
    }

    private fun bindUploadOffer(currentListSize: Int) {
        val binding = syncOfferBinding ?: return

        binding.ivSyncIcon.setImageResource(R.drawable.ic_sync_offer_cloud_up)
        binding.tvTitle.text = "SYNC UPLOAD"
        binding.tvSubtitle.text = "Current local version"
        binding.tvByLabel.text = "LIST"
        binding.tvByValue.text = currentListSize.toString()
        binding.tvMinutesValue.text = "CURRENT LIST"
        binding.tvMinutesLabel.text = "THIS DEVICE"
        binding.tvQuestion.text = "Upload the list?"
        binding.tvSecondsLeft.visibility = View.VISIBLE
        binding.tvSecondsLeft.text = "${syncOfferInitialSeconds.coerceAtLeast(0)} sec"
        binding.btnNo.text = "NO"
        binding.btnDownload.text = "YES"

        binding.root.post {
            applyGradientText(binding.tvTitle, 0xFF86D8FF.toInt(), 0xFFE56CFF.toInt())
            applyGradientText(binding.tvMinutesValue, 0xFFAAC8FF.toInt(), 0xFFE783FF.toInt())
            applyGradientText(binding.tvByValue, 0xFF8ED7FF.toInt(), 0xFFE783FF.toInt())
        }
    }

    private fun applyGradientText(textView: TextView, startColor: Int, endColor: Int) {
        val text = textView.text?.toString().orEmpty()
        if (text.isEmpty()) return

        val width = textView.paint.measureText(text)
        if (width <= 0f) return

        textView.paint.shader = LinearGradient(
            0f,
            0f,
            width,
            0f,
            startColor,
            endColor,
            Shader.TileMode.CLAMP
        )
        textView.invalidate()
    }

    private fun restartSyncOfferCountdown() {
        stopSyncOfferCountdown()
        mainHandler.post(syncOfferCountdownRunnable)
    }

    private fun stopSyncOfferCountdown() {
        mainHandler.removeCallbacks(syncOfferCountdownRunnable)
    }

    private fun getRemainingSyncOfferSeconds(): Int {
        val elapsedSeconds =
            ((SystemClock.elapsedRealtime() - syncOfferStartElapsedMs) / 1000L).toInt()
        return (syncOfferInitialSeconds - elapsedSeconds).coerceAtLeast(0)
    }

    private fun dismissUploadDialog(accepted: Boolean) {
        if (syncOfferHandled) return

        syncOfferHandled = true
        stopSyncOfferCountdown()

        if (accepted) {
            onConfirmUpload()
        } else {
            onCancelUpload()
        }

        syncOfferDialog?.dismiss()
        syncOfferDialog = null
        syncOfferBinding = null
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