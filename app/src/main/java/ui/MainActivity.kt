package com.carlist.pro.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.carlist.pro.R
import com.carlist.pro.databinding.ActivityMainBinding
import com.carlist.pro.domain.QueueItem
import com.carlist.pro.domain.QueueManager
import com.carlist.pro.domain.Status
import com.carlist.pro.domain.TransportType
import com.carlist.pro.domain.sync.SyncOffer
import com.carlist.pro.domain.sync.SyncState
import com.carlist.pro.sync.SyncForegroundService
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var adapter: QueueAdapter
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var feedback: SystemFeedback
    private lateinit var voiceInputManager: VoiceInputManager

    private var imeVisibleNow = false
    private var autoCopyEnabled = false
    private var isDragging = false

    private var techTapCount = 0
    private var techFirstTapAtMs = 0L
    private var techCountdownActive = false

    private var isTechMenuOpen = false
    private var isManualInputMode = false

    private var lastQueueSize = 0
    private var pendingScrollToBottom = false

    private var lastAutoCopiedText = ""
    private var suppressNextQueueAutoScroll = false
    private var backgroundServiceStarted = false

    private val gestureHandler = Handler(Looper.getMainLooper())

    private var syncOfferDialog: AlertDialog? = null
    private var syncOfferHandled = false
    private var currentSyncOffer: SyncOffer? = null
    private var syncOfferSecondsLeft = 0

    private var networkAlertDialog: AlertDialog? = null
    private var networkAlertAcknowledged = false

    private val techTapTimeoutRunnable = Runnable {
        if (techTapCount > 0 || techCountdownActive) {
            resetTechTapSequence()
            applyInputVisualState(imeVisibleNow)
        }
    }

    private val syncOfferCountdownRunnable = object : Runnable {
        override fun run() {
            val offer = currentSyncOffer ?: return

            if (syncOfferSecondsLeft <= 0) {
                dismissSyncOfferDialog(accepted = false)
                return
            }

            updateSyncOfferDialogText(offer, syncOfferSecondsLeft)
            syncOfferSecondsLeft -= 1
            gestureHandler.postDelayed(this, 1000L)
        }
    }

    private val networkVibrationRunnable = object : Runnable {
        override fun run() {
            if (networkAlertDialog?.isShowing != true || networkAlertAcknowledged) return

            vibrateNetworkPulse()
            gestureHandler.postDelayed(this, 4_000L)
        }
    }

    private var micSessionActive = false
    private val micSilenceTimeoutMs = 5_000L

    private val micSilenceTimeoutRunnable = Runnable {
        if (micSessionActive) {
            stopMicSession()
        }
    }

    private val micRestoreSayNumberRunnable = Runnable {
        if (micSessionActive) {
            setMicButtonListeningState()
        }
    }

    private val recordAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startMicSession()
            } else {
                // Без звука
            }
        }

    private val postNotificationsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            ensureBackgroundMonitorServiceStarted()
        }

    override fun onCreate(savedInstanceState: Bundle?) {

        WindowCompat.setDecorFitsSystemWindows(window, true)

        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ensureNotificationsPermissionAndStartService()

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        feedback = SystemFeedback(this)

        voiceInputManager = VoiceInputManager(
            context = this,
            onListeningStarted = {
                runOnUiThread {
                    if (!micSessionActive) return@runOnUiThread
                    setMicButtonListeningState()
                    restartMicSilenceTimer()
                }
            },
            onSpeechDetected = {
                runOnUiThread {
                    if (!micSessionActive) return@runOnUiThread
                    restartMicSilenceTimer()
                }
            },
            onNumberRecognized = { recognizedNumber ->
                runOnUiThread {
                    if (!micSessionActive) return@runOnUiThread
                    restartMicSilenceTimer()
                    handleVoiceRecognizedNumber(recognizedNumber)
                }
            },
            onFailure = {
                runOnUiThread {
                    if (!micSessionActive) return@runOnUiThread
                    restartMicSilenceTimer()
                    scheduleNextVoiceListen()
                }
            }
        )

        adapter = QueueAdapter(
            transportInfoProvider = { number -> viewModel.getTransportInfo(number) },
            onCardShortTap = { item, anchor ->
                showStatusMenu(anchor, item)
            }
        )

        layoutManager = LinearLayoutManager(this)
        binding.queueRecycler.layoutManager = layoutManager
        binding.queueRecycler.adapter = adapter

        setupInputPanel()
        setupButtons()
        setupImeHandling()
        setupServerPanel()

        viewModel.queueItems.observe(this) { list ->

            val oldSize = lastQueueSize
            val newSize = list.size
            pendingScrollToBottom = newSize > oldSize

            updateMyCarCounter(list)

            if (!isDragging) {
                adapter.submitItems(list)

                if (suppressNextQueueAutoScroll) {
                    suppressNextQueueAutoScroll = false
                } else {
                    binding.queueRecycler.post {
                        when {
                            !isTechMenuOpen && pendingScrollToBottom && list.isNotEmpty() -> {
                                val last = list.lastIndex
                                layoutManager.scrollToPositionWithOffset(last, 0)
                            }

                            !isTechMenuOpen && list.isNotEmpty() -> {
                                ensureMyCarVisible(list)
                            }
                        }
                    }
                }
            }

            lastQueueSize = newSize
        }

        viewModel.syncPanelText.observe(this) { text ->
            binding.infoText.text = text
        }

        viewModel.syncState.observe(this) { state ->
            updateSyncInfoTextColor(state)
        }

        viewModel.syncMessage.observe(this) { message ->
            message ?: return@observe
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            viewModel.onSyncMessageShown()
        }

        viewModel.syncOffer.observe(this) { offer ->
            if (offer == null) {
                dismissSyncOfferDialogOnly()
            } else {
                showSyncOfferDialog(offer)
            }
        }

        viewModel.networkAlertVisible.observe(this) { visible ->
            if (visible) {
                showNetworkAlertDialog()
            } else {
                dismissNetworkAlertDialogOnly()
            }
        }

        binding.numberInput.setOnEditorActionListener { _, actionId, event ->

            val isEnter =
                actionId == EditorInfo.IME_ACTION_DONE ||
                        (event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                                event.action == KeyEvent.ACTION_DOWN)

            if (!isEnter) return@setOnEditorActionListener false

            if (isTechMenuOpen) return@setOnEditorActionListener true
            if (!isManualInputMode) return@setOnEditorActionListener true

            handleManualSubmit()
            true
        }

        val touchHelper = ItemTouchHelper(
            QueueTouchHelperCallback(
                onMoveForDrag = { from, to ->
                    viewModel.moveForDrag(from, to)
                    adapter.moveForDrag(from, to)
                },
                onSwipedRight = { position ->
                    playDeleteSwipeSound()
                    viewModel.removeAt(position)
                    requestAutoCopyIfNeeded()
                },
                onDragStateChanged = { dragging ->
                    isDragging = dragging
                },
                onDragEnded = { _, _ ->
                    viewModel.commitDrag()
                    requestAutoCopyIfNeeded()
                }
            )
        )

        touchHelper.attachToRecyclerView(binding.queueRecycler)

        binding.root.post {
            ViewCompat.requestApplyInsets(window.decorView)
        }

        lockManualInput()
        applyInputVisualState(false)
        setMicButtonOffState()
    }

    override fun onResume() {
        super.onResume()
        ensureNotificationsPermissionAndStartService()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {

        if (!isTechMenuOpen && ev.actionMasked == MotionEvent.ACTION_UP) {
            val releasedInsideInputPanel = isInsideInputPanel(ev.rawX, ev.rawY)
            val releasedInsideCounterPanel = isInsideCounterPanel(ev.rawX, ev.rawY)

            when {
                releasedInsideInputPanel -> {
                    resetTechTapSequence()
                    startManualInputMode()
                }

                releasedInsideCounterPanel -> {
                    handleTechTaps()
                }

                techTapCount > 0 || techCountdownActive -> {
                    resetTechTapSequence()
                    applyInputVisualState(imeVisibleNow)
                }
            }
        }

        return super.dispatchTouchEvent(ev)
    }

    fun getMainViewModel(): MainViewModel = viewModel

    fun onTechnicalMenuClosed() {
        isTechMenuOpen = false
        resetTechTapSequence()
        applyInputVisualState(imeVisibleNow)

        val currentQueue = viewModel.queueItems.value.orEmpty()
        adapter.submitItems(currentQueue)
        updateMyCarCounter(currentQueue)
    }

    private fun ensureNotificationsPermissionAndStartService() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            ensureBackgroundMonitorServiceStarted()
            return
        }

        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED -> {
                ensureBackgroundMonitorServiceStarted()
            }

            else -> {
                postNotificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun ensureBackgroundMonitorServiceStarted() {
        if (backgroundServiceStarted) return

        val intent = Intent(this, SyncForegroundService::class.java).apply {
            action = SyncForegroundService.ACTION_START_MONITORING
        }

        ContextCompat.startForegroundService(this, intent)
        backgroundServiceStarted = true
    }

    private fun setupServerPanel() {
        binding.infoPanel.setOnClickListener {
            viewModel.onServerPanelClick()
        }

        binding.infoPanel.setOnLongClickListener {
            viewModel.onServerPanelLongPress()
            true
        }
    }

    private fun updateSyncInfoTextColor(state: SyncState) {
        val color = when (state) {
            SyncState.OnlineFree -> 0xFF32D74B.toInt()
            is SyncState.LockedByMe -> 0xFF3A86FF.toInt()
            is SyncState.LockedByOther -> 0xFFFF4D4F.toInt()
            SyncState.Connecting -> 0xFF32D74B.toInt()
            SyncState.NoNetwork -> 0xFFFF4D4F.toInt()
            is SyncState.OfferAvailable -> 0xFF32D74B.toInt()
            SyncState.NeedMyCar -> Color.WHITE
            SyncState.Off -> Color.WHITE
        }

        binding.infoText.setTextColor(color)
    }

    private fun showSyncOfferDialog(offer: SyncOffer) {
        currentSyncOffer = offer
        syncOfferHandled = false
        syncOfferSecondsLeft = offer.secondsRemaining.coerceAtLeast(0)

        if (syncOfferDialog == null) {
            syncOfferDialog = MaterialAlertDialogBuilder(this)
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
                    gestureHandler.removeCallbacks(syncOfferCountdownRunnable)
                    currentSyncOffer = null
                    viewModel.declineSyncOffer()
                }
            }
        }

        updateSyncOfferDialogText(offer, syncOfferSecondsLeft)

        if (syncOfferDialog?.isShowing != true) {
            syncOfferDialog?.show()
        }

        gestureHandler.removeCallbacks(syncOfferCountdownRunnable)
        gestureHandler.post(syncOfferCountdownRunnable)
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
        gestureHandler.removeCallbacks(syncOfferCountdownRunnable)

        val dialog = syncOfferDialog
        currentSyncOffer = null

        if (accepted) {
            viewModel.acceptSyncOffer()
        } else {
            viewModel.declineSyncOffer()
        }

        dialog?.dismiss()
        syncOfferDialog = null
    }

    private fun dismissSyncOfferDialogOnly() {
        gestureHandler.removeCallbacks(syncOfferCountdownRunnable)
        currentSyncOffer = null
        syncOfferHandled = true
        syncOfferDialog?.dismiss()
        syncOfferDialog = null
    }

    private fun showNetworkAlertDialog() {
        if (networkAlertDialog?.isShowing == true) return

        networkAlertAcknowledged = false

        val density = resources.displayMetrics.density

        val container = LinearLayout(this).apply {
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

        val titleView = TextView(this).apply {
            text = "WARNING"
            setTextColor(0xFFFF4444.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val messageView = TextView(this).apply {
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

        val dialog = AlertDialog.Builder(this)
            .setView(container)
            .create()

        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnKeyListener { _, _, _ -> true }

        networkAlertDialog = dialog
        dialog.show()

        gestureHandler.removeCallbacks(networkVibrationRunnable)
        gestureHandler.post(networkVibrationRunnable)
    }

    private fun acknowledgeNetworkAlert() {
        if (networkAlertAcknowledged) return

        networkAlertAcknowledged = true
        gestureHandler.removeCallbacks(networkVibrationRunnable)
        stopVibration()
        viewModel.onNetworkAlertAcknowledged()
        dismissNetworkAlertDialogOnly()
    }

    private fun dismissNetworkAlertDialogOnly() {
        gestureHandler.removeCallbacks(networkVibrationRunnable)
        stopVibration()
        networkAlertDialog?.dismiss()
        networkAlertDialog = null
    }

    private fun vibrateNetworkPulse() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(Vibrator::class.java)
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(3_000L, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(3_000L)
        }
    }

    private fun stopVibration() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(Vibrator::class.java)
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        vibrator.cancel()
    }

    private fun setupInputPanel() {

        binding.numberInput.isLongClickable = false
        binding.numberInput.setTextIsSelectable(false)
        binding.numberInput.isClickable = false
        binding.numberInput.isFocusable = false
        binding.numberInput.isFocusableInTouchMode = false
        binding.numberInput.isCursorVisible = false

        binding.inputPanel.setOnLongClickListener { true }

        binding.numberInput.setOnClickListener(null)
        binding.numberInput.setOnLongClickListener { true }
    }

    private fun startManualInputMode() {
        isManualInputMode = true

        binding.numberInput.isFocusable = true
        binding.numberInput.isFocusableInTouchMode = true
        binding.numberInput.isCursorVisible = true
        binding.numberInput.requestFocus()
        binding.numberInput.setSelection(binding.numberInput.text?.length ?: 0)

        val imm = getSystemService(InputMethodManager::class.java)
        imm.showSoftInput(binding.numberInput, InputMethodManager.SHOW_IMPLICIT)

        applyInputVisualState(true)
    }

    private fun lockManualInput() {
        isManualInputMode = false
        binding.numberInput.clearFocus()
        binding.numberInput.isFocusable = false
        binding.numberInput.isFocusableInTouchMode = false
        binding.numberInput.isCursorVisible = false
    }

    private fun handleTechTaps() {

        val now = System.currentTimeMillis()

        if (techTapCount == 0) {
            techFirstTapAtMs = now
        } else if (now - techFirstTapAtMs > 5_000L) {
            resetTechTapSequence()
            techFirstTapAtMs = now
        }

        techTapCount++

        gestureHandler.removeCallbacks(techTapTimeoutRunnable)
        gestureHandler.postDelayed(techTapTimeoutRunnable, 5_000L)

        when (techTapCount) {
            1 -> {}

            2 -> {
                techCountdownActive = true
                binding.inputHint.text = "3"
                applyInputVisualState(true)
            }

            3 -> binding.inputHint.text = "2"
            4 -> binding.inputHint.text = "1"

            5 -> {
                gestureHandler.removeCallbacks(techTapTimeoutRunnable)
                binding.inputHint.text = "OPEN"
                techTapCount = 0
                techCountdownActive = false
                openTechnicalMenu()
            }
        }
    }

    private fun resetTechTapSequence() {
        gestureHandler.removeCallbacks(techTapTimeoutRunnable)
        techTapCount = 0
        techFirstTapAtMs = 0L
        techCountdownActive = false
    }

    private fun openTechnicalMenu() {

        isTechMenuOpen = true

        lockManualInput()
        resetTechTapSequence()

        val imm = getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(binding.numberInput.windowToken, 0)

        DriverRegistryDialogFragment()
            .show(supportFragmentManager, "DriverRegistryDialog")
    }

    private fun showStatusMenu(anchor: View, item: QueueItem) {

        val popup = PopupMenu(this, anchor)

        popup.menu.add(coloredTitle("STANDARD   ${item.number}", 0xFFE6D29C.toInt()))
        popup.menu.add(coloredTitle("SERVICE", 0xFFFF91E7.toInt()))
        popup.menu.add(coloredTitle("OFFICE", 0xFFFF91E7.toInt()))
        popup.menu.add(coloredTitle("JURNIEKS", 0xFF0FDFFF.toInt()))

        popup.setOnMenuItemClickListener { menuItem ->
            suppressNextQueueAutoScroll = true

            when {
                menuItem.title.toString().startsWith("STANDARD") ->
                    viewModel.setStatus(item.number, Status.NONE)

                menuItem.title.toString() == "SERVICE" ->
                    viewModel.setStatus(item.number, Status.SERVICE)

                menuItem.title.toString() == "OFFICE" ->
                    viewModel.setStatus(item.number, Status.OFFICE)

                menuItem.title.toString() == "JURNIEKS" ->
                    viewModel.setStatus(item.number, Status.JURNIEKS)
            }

            requestAutoCopyIfNeeded()
            true
        }

        popup.show()
    }

    private fun coloredTitle(text: String, color: Int): SpannableString {
        return SpannableString(text).apply {
            setSpan(
                ForegroundColorSpan(color),
                0,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun applyInputVisualState(imeVisible: Boolean) {

        if (isTechMenuOpen) return

        if (techCountdownActive) {
            binding.inputHint.visibility = View.VISIBLE
            binding.numberInput.isCursorVisible = false
            return
        }

        if (imeVisible && isManualInputMode) {
            binding.inputHint.visibility = View.INVISIBLE
            binding.numberInput.isCursorVisible = true
        } else {
            binding.inputHint.visibility = View.VISIBLE
            binding.numberInput.isCursorVisible = false
            binding.inputHint.text = "Nr / 🛠"
        }
    }

    private fun setupButtons() {

        updateAutocopyButtonText()

        binding.btnAutocopy.setOnClickListener {
            val list = viewModel.queueItems.value.orEmpty()
            copyQueueOnce(list)
            feedback.ok()
        }

        binding.btnAutocopy.setOnLongClickListener {
            autoCopyEnabled = !autoCopyEnabled
            updateAutocopyButtonText()
            feedback.ok()
            true
        }

        binding.btnClearList.setOnClickListener {
            if (micSessionActive) {
                stopMicSession()
            } else {
                ensureMicPermissionAndStart()
            }
        }

        binding.btnClearList.setOnLongClickListener {
            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle("CLEAR LIST")
                .setMessage("Are you sure?")
                .setPositiveButton("YES") { _, _ ->
                    viewModel.clear()
                    lastAutoCopiedText = ""
                    feedback.ok()
                }
                .setNegativeButton("NO", null)
                .show()

            dialog.window?.setBackgroundDrawable(
                ColorDrawable(0xFF2A0033.toInt())
            )

            dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)
                ?.setTextColor(Color.WHITE)

            dialog.findViewById<TextView>(android.R.id.message)
                ?.setTextColor(Color.WHITE)

            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(0xFFFF8A8A.toInt())
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(0xFFFFFFFF.toInt())

            true
        }
    }

    private fun ensureMicPermissionAndStart() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                startMicSession()
            }

            else -> {
                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun startMicSession() {
        if (!voiceInputManager.isAvailable()) {
            return
        }

        micSessionActive = true
        feedback.setSoundEnabled(false)
        setMicButtonListeningState()
        restartMicSilenceTimer()
        scheduleNextVoiceListen()
    }

    private fun stopMicSession() {
        micSessionActive = false
        feedback.setSoundEnabled(true)
        gestureHandler.removeCallbacks(micSilenceTimeoutRunnable)
        gestureHandler.removeCallbacks(micRestoreSayNumberRunnable)
        voiceInputManager.stopListening()
        setMicButtonOffState()
    }

    private fun restartMicSilenceTimer() {
        gestureHandler.removeCallbacks(micSilenceTimeoutRunnable)
        gestureHandler.postDelayed(micSilenceTimeoutRunnable, micSilenceTimeoutMs)
    }

    private fun scheduleNextVoiceListen() {
        if (!micSessionActive) return

        voiceInputManager.stopListening()
        voiceInputManager.startListening()
    }

    private fun handleVoiceRecognizedNumber(number: Int) {
        val result = viewModel.addNumber(number)

        when (result) {
            is QueueManager.AddResult.Added -> {
                feedback.ok()
                setMicButtonListeningState()
                requestAutoCopyIfNeeded()
            }

            QueueManager.AddResult.DuplicateInQueue -> {
                feedback.error()
                showInputProblemDialog("DUPLICATE NUMBER")
                showMicNotFoundStateTemporarily()
            }

            QueueManager.AddResult.NotInRegistry,
            QueueManager.AddResult.InvalidNumber -> {
                feedback.error()
                showInputProblemDialog("NOT IN REGISTRY")
                showMicNotFoundStateTemporarily()
            }
        }

        if (micSessionActive) {
            restartMicSilenceTimer()
            scheduleNextVoiceListen()
        }
    }

    private fun setMicButtonOffState() {
        binding.btnClearList.text = "mic🔊/CLEAR LIST"
        binding.btnClearList.backgroundTintList = null
        binding.btnClearList.setBackgroundResource(R.drawable.bg_button_clear_red_3d)
    }

    private fun setMicButtonListeningState() {
        binding.btnClearList.text = "say number"
        binding.btnClearList.backgroundTintList = null
        binding.btnClearList.setBackgroundResource(R.drawable.bg_button_clear_green_3d)
    }

    private fun showMicNotFoundStateTemporarily() {
        binding.btnClearList.text = "not found"
        binding.btnClearList.backgroundTintList = null
        binding.btnClearList.setBackgroundResource(R.drawable.bg_button_clear_red_3d)

        gestureHandler.removeCallbacks(micRestoreSayNumberRunnable)
        gestureHandler.postDelayed(micRestoreSayNumberRunnable, 500L)
    }

    private fun updateAutocopyButtonText() {
        binding.btnAutocopy.text = if (autoCopyEnabled) "AUTOCOPY OFF" else "AUTOCOPY ON"
    }

    private fun handleManualSubmit() {

        val text = binding.numberInput.text?.toString()?.trim().orEmpty()
        val number = text.toIntOrNull()

        val result = viewModel.addNumber(number)

        when (result) {
            is QueueManager.AddResult.Added -> {
                feedback.ok()
                binding.numberInput.setText("")
                requestAutoCopyIfNeeded()
            }

            QueueManager.AddResult.DuplicateInQueue -> {
                feedback.error()
                binding.numberInput.setText("")
                showInputProblemDialog("DUPLICATE NUMBER")
            }

            QueueManager.AddResult.NotInRegistry,
            QueueManager.AddResult.InvalidNumber -> {
                feedback.error()
                binding.numberInput.setText("")
                showInputProblemDialog("NOT IN REGISTRY")
            }
        }
    }

    private fun showInputProblemDialog(message: String) {
        if (isFinishing || isDestroyed) return

        val density = resources.displayMetrics.density

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                (24 * density).toInt(),
                (20 * density).toInt(),
                (24 * density).toInt(),
                (18 * density).toInt()
            )

            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18f * density
                setColor(0xFF1A001F.toInt())
                setStroke((1 * density).toInt(), 0xFF3A1A3F.toInt())
            }
        }

        val titleView = TextView(this).apply {
            text = "INPUT ERROR"
            setTextColor(0xFFFF4444.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val messageView = TextView(this).apply {
            text = message
            setTextColor(Color.WHITE)
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

        val dialog = AlertDialog.Builder(this)
            .setView(container)
            .create()

        dialog.setCancelable(false)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()

        gestureHandler.postDelayed({
            if (dialog.isShowing) {
                dialog.dismiss()
            }
        }, 1500L)
    }

    private fun requestAutoCopyIfNeeded() {
        if (!autoCopyEnabled) return

        binding.root.post {
            autoCopyIfQueueChanged(viewModel.queueItems.value.orEmpty())
        }
    }

    private fun autoCopyIfQueueChanged(list: List<QueueItem>) {
        val text = buildQueueText(list)

        if (text.isBlank()) return
        if (text == lastAutoCopiedText) return

        copyQueueText(text)
        lastAutoCopiedText = text
    }

    private fun copyQueueOnce(list: List<QueueItem>) {
        val text = buildQueueText(list)
        copyQueueText(text)
        lastAutoCopiedText = text
    }

    private fun copyQueueText(text: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("CarList_PRO Queue", text))
    }

    private fun buildQueueText(list: List<QueueItem>): String {
        if (list.isEmpty()) return ""

        val sb = StringBuilder()

        list.forEachIndexed { index, item ->
            val lineIndex = index + 1
            sb.append(lineIndex).append(". ").append(item.number)

            val info = viewModel.getTransportInfo(item.number)
            val categoryLetters = buildCategoryLetters(info.transportType, info.isMyCar)
            if (categoryLetters.isNotEmpty()) {
                sb.append(" (").append(categoryLetters).append(")")
            }

            when (item.status) {
                Status.SERVICE -> sb.append(" SERVICE")
                Status.OFFICE -> sb.append(" OFFICE")
                Status.JURNIEKS -> sb.append(" JURNIEKS")
                Status.NONE -> {}
            }

            if (index != list.lastIndex) sb.append('\n')
        }

        return sb.toString()
    }

    private fun buildCategoryLetters(
        transportType: TransportType,
        isMyCar: Boolean
    ): String {
        val sb = StringBuilder()

        when (transportType) {
            TransportType.BUS -> sb.append("B")
            TransportType.VAN -> sb.append("V")
            TransportType.NONE -> {}
        }

        return sb.toString()
    }

    private fun updateMyCarCounter(queue: List<QueueItem>) {
        val total = queue.size
        if (total == 0) {
            binding.counterText.text = "-/-"
            return
        }
        val pos = viewModel.findMyCarPosition(queue)
        binding.counterText.text = if (pos == null) "- /$total" else "$pos /$total"
    }

    private fun ensureMyCarVisible(queue: List<QueueItem>) {
        val myCarPosition = viewModel.findMyCarPosition(queue) ?: return
        val myCarIndex = myCarPosition - 1
        if (myCarIndex !in queue.indices) return

        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        val lastVisible = layoutManager.findLastVisibleItemPosition()

        if (firstVisible == androidx.recyclerview.widget.RecyclerView.NO_POSITION ||
            lastVisible == androidx.recyclerview.widget.RecyclerView.NO_POSITION
        ) {
            layoutManager.scrollToPositionWithOffset(myCarIndex, 0)
            return
        }

        if (myCarIndex < firstVisible || myCarIndex > lastVisible) {
            layoutManager.scrollToPositionWithOffset(myCarIndex, 0)
        }
    }

    private fun setupImeHandling() {

        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->

            imeVisibleNow = insets.isVisible(WindowInsetsCompat.Type.ime())

            if (!imeVisibleNow) {
                lockManualInput()
            }

            applyInputVisualState(imeVisibleNow)

            if (!isTechMenuOpen) {
                binding.queueRecycler.post {
                    if (imeVisibleNow) {
                        val count = adapter.itemCount
                        if (count > 0) layoutManager.scrollToPositionWithOffset(count - 1, 0)
                    } else {
                        layoutManager.scrollToPositionWithOffset(0, 0)
                    }
                }
            }

            insets
        }
    }

    private fun isInsideInputPanel(rawX: Float, rawY: Float): Boolean {
        val location = IntArray(2)
        binding.inputPanel.getLocationOnScreen(location)

        val left = location[0]
        val top = location[1]
        val right = left + binding.inputPanel.width
        val bottom = top + binding.inputPanel.height

        return rawX >= left && rawX <= right && rawY >= top && rawY <= bottom
    }

    private fun isInsideCounterPanel(rawX: Float, rawY: Float): Boolean {
        val location = IntArray(2)
        binding.counterPanel.getLocationOnScreen(location)

        val left = location[0]
        val top = location[1]
        val right = left + binding.counterPanel.width
        val bottom = top + binding.counterPanel.height

        return rawX >= left && rawX <= right && rawY >= top && rawY <= bottom
    }

    private fun playDeleteSwipeSound() {
        val audioManager = getSystemService(AudioManager::class.java) ?: return
        try {
            audioManager.playSoundEffect(AudioManager.FX_FOCUS_NAVIGATION_RIGHT, 1.0f)
        } catch (_: Throwable) {
            try {
                audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1.0f)
            } catch (_: Throwable) {
                // Без звука
            }
        }
    }

    override fun onDestroy() {
        gestureHandler.removeCallbacks(techTapTimeoutRunnable)
        gestureHandler.removeCallbacks(syncOfferCountdownRunnable)
        gestureHandler.removeCallbacks(networkVibrationRunnable)
        gestureHandler.removeCallbacks(micSilenceTimeoutRunnable)
        gestureHandler.removeCallbacks(micRestoreSayNumberRunnable)
        stopVibration()
        syncOfferDialog?.dismiss()
        syncOfferDialog = null
        networkAlertDialog?.dismiss()
        networkAlertDialog = null
        voiceInputManager.release()
        super.onDestroy()
        feedback.release()
    }
}