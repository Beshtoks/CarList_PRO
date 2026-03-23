package com.carlist.pro.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.carlist.pro.R
import com.carlist.pro.databinding.ActivityMainBinding
import com.carlist.pro.databinding.PopupStatusMenuBinding
import com.carlist.pro.domain.QueueItem
import com.carlist.pro.domain.QueueManager
import com.carlist.pro.domain.Status
import com.carlist.pro.domain.sync.SyncState
import com.carlist.pro.sync.SyncForegroundService
import com.carlist.pro.ui.adapter.QueueAdapter
import com.carlist.pro.ui.adapter.QueueTouchHelperCallback
import com.carlist.pro.ui.controller.InputTechController
import com.carlist.pro.ui.controller.InputUiController
import com.carlist.pro.ui.controller.QueueClipboardHelper
import com.carlist.pro.ui.controller.SyncUiController
import com.carlist.pro.ui.controller.VoiceSessionController
import com.carlist.pro.ui.dialog.DriverRegistryDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var adapter: QueueAdapter
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var feedback: SystemFeedback
    private lateinit var uiSoundManager: UiSoundManager
    private lateinit var voiceSessionController: VoiceSessionController
    private lateinit var inputTechController: InputTechController
    private lateinit var inputUiController: InputUiController
    private lateinit var queueClipboardHelper: QueueClipboardHelper
    private lateinit var syncUiController: SyncUiController

    private var imeVisibleNow = false
    private var autoCopyEnabled = false
    private var isDragging = false
    private var isTechMenuOpen = false
    private var lastQueueSize = 0
    private var pendingScrollToBottom = false
    private var lastAutoCopiedText = ""
    private var suppressNextQueueAutoScroll = false
    private var backgroundServiceStarted = false
    private var pendingServerToggleSound = false
    private var currentSyncState: SyncState = SyncState.Off
    private var blockedFeedbackAtMs = 0L
    private var blockedBlinkToken = 0
    private var replacingNumber: Int? = null
    private var preserveScrollOnNextImeOpen = false
    private var preservedFirstVisiblePosition = 0
    private var preservedFirstVisibleTop = 0
    private var suppressReplaceCancelOnNextKeyboardHide = false

    private val recordAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            voiceSessionController.onRecordAudioPermissionResult(granted)
        }

    private val postNotificationsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                ensureBackgroundMonitorServiceStarted()
            } else {
                backgroundServiceStarted = false
            }
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
        uiSoundManager = UiSoundManager(this)

        syncUiController = SyncUiController(
            context = this,
            onAcceptSyncOffer = {
                viewModel.acceptSyncOffer()
            },
            onDeclineSyncOffer = {
                viewModel.declineSyncOffer()
            },
            onNetworkAlertAcknowledged = {
                viewModel.onNetworkAlertAcknowledged()
            },
            onConfirmUpload = {
                viewModel.onServerPanelDoubleTapUpload()
            },
            onCancelUpload = {
                // nothing
            }
        )

        val clipboardManager = getSystemService(ClipboardManager::class.java)
        queueClipboardHelper = QueueClipboardHelper(
            clipboardManager = clipboardManager,
            transportInfoProvider = { number -> viewModel.getTransportInfo(number) }
        )

        inputUiController = InputUiController()

        inputTechController = InputTechController(
            onStartManualInputMode = {
                val imm = getSystemService(InputMethodManager::class.java)
                inputUiController.startManualInputMode(binding.numberInput, imm)
                applyInputVisualState(true)
            },
            onLockManualInput = {
                inputUiController.lockManualInput(binding.numberInput)
            },
            onApplyInputVisualState = { imeVisible ->
                applyInputVisualState(imeVisible)
            },
            onShowCountdownValue = { text ->
                binding.inputHint.text = text
            },
            onOpenTechnicalMenu = {
                openTechnicalMenuInternal()
            }
        )

        voiceSessionController = VoiceSessionController(
            context = this,
            hasRecordAudioPermission = {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            },
            requestRecordAudioPermission = {
                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onSessionStarted = {
                uiSoundManager.setSoundEnabled(false)
            },
            onSessionStopped = {
                uiSoundManager.setSoundEnabled(true)
            },
            onNumberRecognized = { recognizedNumber ->
                handleVoiceRecognizedNumber(recognizedNumber)
            },
            applyMicOffState = {
                setMicButtonOffState()
            },
            applyMicListeningState = {
                setMicButtonListeningState()
            },
            applyMicNotFoundState = {
                showMicNotFoundVisualState()
            }
        )

        adapter = QueueAdapter(
            transportInfoProvider = { number -> viewModel.getTransportInfo(number) },
            onCardShortTap = { item, anchor ->
                if (isLockedByOther()) {
                    blockedActionFeedback()
                    return@QueueAdapter
                }

                if (replacingNumber != null && replacingNumber != item.number) {
                    cancelReplacingMode()
                }

                showStatusMenu(anchor, item)
            },
            onCardDoubleTap = { item, _ ->
                if (isLockedByOther()) {
                    blockedActionFeedback()
                    return@QueueAdapter
                }

                saveCurrentQueueScrollPosition()
                preserveScrollOnNextImeOpen = true
                replacingNumber = item.number
                suppressReplaceCancelOnNextKeyboardHide = false

                binding.numberInput.setText(item.number.toString())
                binding.numberInput.requestFocus()
                binding.numberInput.setSelection(binding.numberInput.text?.length ?: 0)

                val imm = getSystemService(InputMethodManager::class.java)
                inputUiController.startManualInputMode(binding.numberInput, imm)
                applyInputVisualState(true)
            }
        )

        layoutManager = LinearLayoutManager(this)
        binding.queueRecycler.layoutManager = layoutManager
        binding.queueRecycler.adapter = adapter

        inputUiController.setupInputPanel(binding.numberInput, binding.inputPanel)
        setupReadOnlyInputGuards()
        setupQueueReadOnlyTouchGuard()
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
                        if (!isTechMenuOpen && pendingScrollToBottom && list.isNotEmpty()) {
                            val last = list.lastIndex
                            layoutManager.scrollToPositionWithOffset(last, 0)
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
            currentSyncState = state
            updateSyncInfoTextColor(state)

            if (pendingServerToggleSound) {
                when (state) {
                    SyncState.Connecting,
                    SyncState.Off -> {
                        uiSoundManager.playSync()
                        feedback.ok()
                        pendingServerToggleSound = false
                    }

                    SyncState.NoNetwork,
                    SyncState.NeedMyCar -> {
                        pendingServerToggleSound = false
                    }

                    else -> Unit
                }
            }
        }

        viewModel.syncMessage.observe(this) { message ->
            message ?: return@observe
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            viewModel.onSyncMessageShown()
        }

        viewModel.syncOffer.observe(this) { offer ->
            if (offer == null) {
                syncUiController.dismissSyncOfferDialogOnly()
            } else {
                syncUiController.showSyncOfferDialog(offer)
            }
        }

        viewModel.networkAlertVisible.observe(this) { visible ->
            if (visible) {
                syncUiController.showNetworkAlertDialog()
            } else {
                syncUiController.dismissNetworkAlertDialogOnly()
            }
        }

        binding.numberInput.setOnEditorActionListener { _, actionId, event ->
            val isEnter =
                actionId == EditorInfo.IME_ACTION_DONE ||
                        (event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                                event.action == KeyEvent.ACTION_DOWN)

            if (!isEnter) return@setOnEditorActionListener false
            if (isTechMenuOpen) return@setOnEditorActionListener true
            if (!inputUiController.isManualInputMode()) return@setOnEditorActionListener true

            handleManualSubmit()
            true
        }

        val touchHelper = ItemTouchHelper(
            QueueTouchHelperCallback(
                onMoveForDrag = { from, to ->
                    if (isLockedByOther()) {
                        blockedActionFeedback()
                        return@QueueTouchHelperCallback
                    }
                    viewModel.moveForDrag(from, to)
                    adapter.moveForDrag(from, to)
                },
                onSwipedRight = { position ->
                    if (isLockedByOther()) {
                        adapter.notifyItemChanged(position)
                        blockedActionFeedback()
                        return@QueueTouchHelperCallback
                    }

                    uiSoundManager.playDelete()
                    feedback.ok()
                    viewModel.removeAt(position)
                    requestAutoCopyIfNeeded()
                },
                onDragStateChanged = { dragging ->
                    isDragging = dragging
                },
                onDragEnded = { _, _ ->
                    if (isLockedByOther()) return@QueueTouchHelperCallback
                    viewModel.commitDrag()
                    requestAutoCopyIfNeeded()
                },
                isQueueReadOnly = {
                    isLockedByOther()
                }
            )
        )

        touchHelper.attachToRecyclerView(binding.queueRecycler)

        binding.root.post {
            ViewCompat.requestApplyInsets(window.decorView)
        }

        inputUiController.lockManualInput(binding.numberInput)
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
                    if (isLockedByOther()) {
                        blockedActionFeedback()
                        return true
                    }
                    inputTechController.onInputPanelReleased()
                }

                releasedInsideCounterPanel -> {
                    if (replacingNumber != null) {
                        cancelReplacingMode()
                    }
                    inputTechController.onCounterPanelReleased()
                }

                else -> {
                    if (replacingNumber != null) {
                        cancelReplacingMode()
                    }
                    inputTechController.onOutsideReleased(imeVisibleNow)
                }
            }
        }

        return super.dispatchTouchEvent(ev)
    }

    fun getMainViewModel(): MainViewModel = viewModel

    fun onTechnicalMenuClosed() {
        isTechMenuOpen = false
        inputTechController.onTechnicalMenuClosed(imeVisibleNow)

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                backgroundServiceStarted = false
                return
            }
        }

        val intent = Intent(this, SyncForegroundService::class.java).apply {
            action = SyncForegroundService.ACTION_START_MONITORING
        }

        ContextCompat.startForegroundService(this, intent)
        backgroundServiceStarted = true
    }

    private fun setupServerPanel() {
        val gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (isLockedByOther()) {
                        blockedActionFeedback()
                        return true
                    }

                    viewModel.onServerPanelClick()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (isLockedByOther()) {
                        blockedActionFeedback()
                        return true
                    }

                    if (!viewModel.canUploadCurrentQueue()) {
                        return true
                    }

                    syncUiController.showUploadConfirmDialog(
                        currentListSize = viewModel.getCurrentQueueSize(),
                        secondsRemaining = 15
                    )
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    pendingServerToggleSound = true
                    viewModel.onServerPanelLongPress()
                }
            }
        )

        binding.infoPanel.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
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
        binding.infoText.alpha = 1f
    }

    private fun openTechnicalMenuInternal() {
        isTechMenuOpen = true

        inputUiController.lockManualInput(binding.numberInput)
        inputTechController.reset()

        val imm = getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(binding.numberInput.windowToken, 0)

        DriverRegistryDialogFragment()
            .show(supportFragmentManager, "DriverRegistryDialog")
    }

    private fun showStatusMenu(anchor: View, item: QueueItem) {
        if (isLockedByOther()) {
            blockedActionFeedback()
            return
        }

        val popupBinding = PopupStatusMenuBinding.inflate(layoutInflater)

        popupBinding.actionStandard.text = "STANDARD"
        popupBinding.actionService.text = "SERVICE"
        popupBinding.actionOffice.text = "OFFICE"
        popupBinding.actionJurnieks.text = "JURNIEKS"

        val numberColor = popupBinding.actionStandard.currentTextColor

        when (item.status) {
            Status.NONE -> popupBinding.actionStandard.text =
                buildStatusMenuLine("STANDARD", item.number, numberColor)

            Status.SERVICE -> popupBinding.actionService.text =
                buildStatusMenuLine("SERVICE", item.number, numberColor)

            Status.OFFICE -> popupBinding.actionOffice.text =
                buildStatusMenuLine("OFFICE", item.number, numberColor)

            Status.JURNIEKS -> popupBinding.actionJurnieks.text =
                buildStatusMenuLine("JURNIEKS", item.number, numberColor)
        }

        val popup = PopupWindow(
            popupBinding.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )

        popup.isOutsideTouchable = true
        popup.isFocusable = true
        popup.elevation = 16f
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isClippingEnabled = true

        popupBinding.actionStandard.setOnClickListener {
            if (isLockedByOther()) {
                blockedActionFeedback()
                return@setOnClickListener
            }
            suppressNextQueueAutoScroll = true
            viewModel.setStatus(item.number, Status.NONE)
            requestAutoCopyIfNeeded()
            popup.dismiss()
        }

        popupBinding.actionService.setOnClickListener {
            if (isLockedByOther()) {
                blockedActionFeedback()
                return@setOnClickListener
            }
            suppressNextQueueAutoScroll = true
            viewModel.setStatus(item.number, Status.SERVICE)
            requestAutoCopyIfNeeded()
            popup.dismiss()
        }

        popupBinding.actionOffice.setOnClickListener {
            if (isLockedByOther()) {
                blockedActionFeedback()
                return@setOnClickListener
            }
            suppressNextQueueAutoScroll = true
            viewModel.setStatus(item.number, Status.OFFICE)
            requestAutoCopyIfNeeded()
            popup.dismiss()
        }

        popupBinding.actionJurnieks.setOnClickListener {
            if (isLockedByOther()) {
                blockedActionFeedback()
                return@setOnClickListener
            }
            suppressNextQueueAutoScroll = true
            viewModel.setStatus(item.number, Status.JURNIEKS)
            requestAutoCopyIfNeeded()
            popup.dismiss()
        }

        popup.contentView.measure(
            View.MeasureSpec.makeMeasureSpec(
                resources.displayMetrics.widthPixels,
                View.MeasureSpec.AT_MOST
            ),
            View.MeasureSpec.makeMeasureSpec(
                resources.displayMetrics.heightPixels,
                View.MeasureSpec.AT_MOST
            )
        )

        val popupWidth = popup.contentView.measuredWidth
        val popupHeight = popup.contentView.measuredHeight

        val anchorLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)

        val anchorX = anchorLocation[0]
        val anchorY = anchorLocation[1]
        val anchorWidth = anchor.width
        val anchorHeight = anchor.height

        val visibleFrame = Rect()
        window.decorView.getWindowVisibleDisplayFrame(visibleFrame)

        val spaceBelow = visibleFrame.bottom - (anchorY + anchorHeight)
        val spaceAbove = anchorY - visibleFrame.top

        val margin = dpToPx(8)
        var x = anchorX + anchorWidth - popupWidth
        var y = if (spaceBelow >= popupHeight + margin || spaceBelow >= spaceAbove) {
            anchorY + anchorHeight + margin
        } else {
            anchorY - popupHeight - margin
        }

        if (x < visibleFrame.left + margin) {
            x = visibleFrame.left + margin
        }
        if (x + popupWidth > visibleFrame.right - margin) {
            x = visibleFrame.right - popupWidth - margin
        }

        if (y < visibleFrame.top + margin) {
            y = visibleFrame.top + margin
        }
        if (y + popupHeight > visibleFrame.bottom - margin) {
            y = visibleFrame.bottom - popupHeight - margin
        }

        popup.showAtLocation(window.decorView, Gravity.TOP or Gravity.START, x, y)
    }

    private fun buildStatusMenuLine(label: String, number: Int, numberColor: Int): SpannableString {
        val fullText = "$label $number"
        val spannable = SpannableString(fullText)
        val start = fullText.lastIndexOf(number.toString())
        if (start >= 0) {
            spannable.setSpan(
                ForegroundColorSpan(numberColor),
                start,
                fullText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }

    private fun applyInputVisualState(imeVisible: Boolean) {
        inputUiController.applyInputVisualState(
            isTechMenuOpen = isTechMenuOpen,
            isCountdownActive = inputTechController.isCountdownActive(),
            imeVisible = imeVisible,
            inputHint = binding.inputHint,
            numberInput = binding.numberInput
        )
    }

    private fun setupButtons() {
        updateAutocopyButtonText()

        binding.btnAutocopy.setOnClickListener {
            val list = viewModel.queueItems.value.orEmpty()
            lastAutoCopiedText = queueClipboardHelper.copyQueueOnce(list)
            uiSoundManager.playOk()
            feedback.ok()
        }

        binding.btnAutocopy.setOnLongClickListener {
            autoCopyEnabled = !autoCopyEnabled
            updateAutocopyButtonText()
            uiSoundManager.playOk()
            feedback.ok()
            true
        }

        binding.btnClearList.setOnClickListener {
            if (isLockedByOther()) {
                blockedActionFeedback()
                return@setOnClickListener
            }
            voiceSessionController.toggle()
        }

        binding.btnClearList.setOnLongClickListener {
            if (isLockedByOther()) {
                blockedActionFeedback()
                return@setOnLongClickListener true
            }

            val listIsEmpty = viewModel.queueItems.value.orEmpty().isEmpty()

            if (listIsEmpty) {
                viewModel.clear()
                lastAutoCopiedText = ""
                uiSoundManager.playClear()
                feedback.warning()
                return@setOnLongClickListener true
            }

            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle("CLEAR LIST")
                .setMessage("Are you sure?")
                .setPositiveButton("YES") { _, _ ->
                    viewModel.clear()
                    lastAutoCopiedText = ""
                    uiSoundManager.playClear()
                    feedback.warning()
                }
                .setNegativeButton("NO", null)
                .show()

            dialog.window?.setBackgroundDrawable(ColorDrawable(0xFF2A0033.toInt()))
            dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)
                ?.setTextColor(Color.WHITE)
            dialog.findViewById<TextView>(android.R.id.message)
                ?.setTextColor(Color.WHITE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(0xFFFF8A8A.toInt())
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(0xFFFFFFFF.toInt())

            true
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

    private fun showMicNotFoundVisualState() {
        binding.btnClearList.text = "not found"
        binding.btnClearList.backgroundTintList = null
        binding.btnClearList.setBackgroundResource(R.drawable.bg_button_clear_red_3d)
    }

    private fun updateAutocopyButtonText() {
        binding.btnAutocopy.text = if (autoCopyEnabled) "AUTOCOPY ON" else "AUTOCOPY OFF"
    }

    private fun handleManualSubmit() {
        if (isLockedByOther()) {
            blockedActionFeedback()
            return
        }

        val text = binding.numberInput.text?.toString()?.trim().orEmpty()
        val number = text.toIntOrNull()

        if (replacingNumber != null) {
            val oldNumber = replacingNumber!!
            val result = viewModel.replaceNumber(oldNumber, number ?: -1)

            when (result) {
                QueueManager.OperationResult.Success -> {
                    replacingNumber = null
                    preserveScrollOnNextImeOpen = false
                    suppressReplaceCancelOnNextKeyboardHide = true
                    binding.numberInput.setText("")
                    uiSoundManager.playOk()
                    feedback.ok()
                    requestAutoCopyIfNeeded()
                }

                QueueManager.OperationResult.DuplicateInQueue -> {
                    uiSoundManager.playError()
                    feedback.error()
                    binding.numberInput.setText("")
                    showInputProblemDialog("DUPLICATE NUMBER")
                }

                QueueManager.OperationResult.NotInRegistry -> {
                    uiSoundManager.playError()
                    feedback.error()
                    binding.numberInput.setText("")
                    showInputProblemDialog("NOT IN REGISTRY")
                }

                QueueManager.OperationResult.InvalidNumber -> {
                    uiSoundManager.playError()
                    feedback.error()
                    binding.numberInput.setText("")
                    showInputProblemDialog("INVALID NUMBER")
                }

                else -> {
                    uiSoundManager.playError()
                    feedback.error()
                    binding.numberInput.setText("")
                }
            }

            return
        }

        val result = viewModel.addNumber(number)

        when (result) {
            is QueueManager.AddResult.Added -> {
                uiSoundManager.playOk()
                feedback.ok()
                binding.numberInput.setText("")
                requestAutoCopyIfNeeded()
            }

            QueueManager.AddResult.DuplicateInQueue -> {
                uiSoundManager.playError()
                feedback.error()
                binding.numberInput.setText("")
                showInputProblemDialog("DUPLICATE NUMBER")
            }

            QueueManager.AddResult.NotInRegistry,
            QueueManager.AddResult.InvalidNumber -> {
                uiSoundManager.playError()
                feedback.error()
                binding.numberInput.setText("")
                showInputProblemDialog("NOT IN REGISTRY")
            }
        }
    }

    private fun handleVoiceRecognizedNumber(number: Int) {
        if (isLockedByOther()) {
            blockedActionFeedback()
            voiceSessionController.onNumberRejected()
            return
        }

        val result = viewModel.addNumber(number)

        when (result) {
            is QueueManager.AddResult.Added -> {
                uiSoundManager.playOk()
                feedback.ok()
                requestAutoCopyIfNeeded()
                voiceSessionController.onNumberAccepted()
            }

            QueueManager.AddResult.DuplicateInQueue -> {
                uiSoundManager.playError()
                feedback.error()
                showInputProblemDialog("DUPLICATE NUMBER")
                voiceSessionController.onNumberRejected()
            }

            QueueManager.AddResult.NotInRegistry,
            QueueManager.AddResult.InvalidNumber -> {
                uiSoundManager.playError()
                feedback.error()
                showInputProblemDialog("NOT IN REGISTRY")
                voiceSessionController.onNumberRejected()
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

        binding.root.postDelayed({
            if (dialog.isShowing) {
                dialog.dismiss()
            }
        }, 1500L)
    }

    private fun requestAutoCopyIfNeeded() {
        if (!autoCopyEnabled) return

        binding.root.post {
            lastAutoCopiedText = queueClipboardHelper.autoCopyIfQueueChanged(
                list = viewModel.queueItems.value.orEmpty(),
                lastAutoCopiedText = lastAutoCopiedText
            )
        }
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

    private fun setupImeHandling() {
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            val wasImeVisible = imeVisibleNow
            imeVisibleNow = insets.isVisible(WindowInsetsCompat.Type.ime())

            if (!imeVisibleNow) {
                inputTechController.onKeyboardHidden()

                if (replacingNumber != null) {
                    if (suppressReplaceCancelOnNextKeyboardHide) {
                        suppressReplaceCancelOnNextKeyboardHide = false
                    } else if (!isTechMenuOpen) {
                        cancelReplacingMode()
                    }
                }
            }

            applyInputVisualState(imeVisibleNow)

            if (!isTechMenuOpen) {
                binding.queueRecycler.post {
                    if (imeVisibleNow) {
                        if (preserveScrollOnNextImeOpen) {
                            layoutManager.scrollToPositionWithOffset(
                                preservedFirstVisiblePosition,
                                preservedFirstVisibleTop
                            )
                        } else {
                            val count = adapter.itemCount
                            if (count > 0) layoutManager.scrollToPositionWithOffset(count - 1, 0)
                        }
                    } else {
                        if (preserveScrollOnNextImeOpen) {
                            layoutManager.scrollToPositionWithOffset(
                                preservedFirstVisiblePosition,
                                preservedFirstVisibleTop
                            )
                        } else if (wasImeVisible) {
                            layoutManager.scrollToPositionWithOffset(0, 0)
                        }
                    }
                }
            }

            insets
        }
    }

    private fun setupReadOnlyInputGuards() {
        val lockBlocker = View.OnTouchListener { _, event ->
            if (!isLockedByOther()) return@OnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> blockedActionFeedback()
            }
            true
        }

        binding.inputPanel.setOnTouchListener(lockBlocker)
        binding.numberInput.setOnTouchListener(lockBlocker)
    }

    private fun setupQueueReadOnlyTouchGuard() {
        val gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    if (!isLockedByOther()) return false
                    val child = binding.queueRecycler.findChildViewUnder(e.x, e.y) ?: return false
                    blockedActionFeedback()
                    return child != null
                }

                override fun onLongPress(e: MotionEvent) {
                    if (!isLockedByOther()) return
                    val child = binding.queueRecycler.findChildViewUnder(e.x, e.y) ?: return
                    blockedActionFeedback()
                }
            }
        )

        binding.queueRecycler.addOnItemTouchListener(
            object : RecyclerView.SimpleOnItemTouchListener() {
                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    if (!isLockedByOther()) return false
                    val child = rv.findChildViewUnder(e.x, e.y) ?: return false
                    val handled = gestureDetector.onTouchEvent(e)
                    return handled && child != null
                }

                override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                    if (isLockedByOther()) {
                        gestureDetector.onTouchEvent(e)
                    }
                }

                override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit
            }
        )
    }

    private fun saveCurrentQueueScrollPosition() {
        val firstPos = layoutManager.findFirstVisibleItemPosition()
        val firstView = layoutManager.findViewByPosition(firstPos)
        preservedFirstVisiblePosition = if (firstPos == RecyclerView.NO_POSITION) 0 else firstPos
        preservedFirstVisibleTop = firstView?.top ?: 0
    }

    private fun cancelReplacingMode() {
        replacingNumber = null
        preserveScrollOnNextImeOpen = false
        suppressReplaceCancelOnNextKeyboardHide = false
        binding.numberInput.setText("")

        val imm = getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(binding.numberInput.windowToken, 0)

        inputUiController.lockManualInput(binding.numberInput)
        applyInputVisualState(false)
    }

    private fun isLockedByOther(): Boolean {
        return currentSyncState is SyncState.LockedByOther
    }

    private fun blockedActionFeedback() {
        val now = System.currentTimeMillis()
        if (now - blockedFeedbackAtMs < 180L) return
        blockedFeedbackAtMs = now
        feedback.ok()
        blinkInfoTextThreeTimes()
    }

    private fun blinkInfoTextThreeTimes() {
        val token = ++blockedBlinkToken
        val delays = longArrayOf(0L, 110L, 220L, 330L, 440L, 550L)
        val alphas = floatArrayOf(0.25f, 1f, 0.25f, 1f, 0.25f, 1f)

        for (i in delays.indices) {
            binding.infoText.postDelayed({
                if (token != blockedBlinkToken) return@postDelayed
                if (!isLockedByOther()) {
                    binding.infoText.alpha = 1f
                    return@postDelayed
                }
                binding.infoText.alpha = alphas[i]
            }, delays[i])
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

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        syncUiController.release()
        inputTechController.release()
        voiceSessionController.release()
        uiSoundManager.release()
        super.onDestroy()
        feedback.release()
    }
}