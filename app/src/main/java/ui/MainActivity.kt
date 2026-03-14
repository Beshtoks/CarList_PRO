package com.carlist.pro.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
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

    private val recordAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            voiceSessionController.onRecordAudioPermissionResult(granted)
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
                feedback.setSoundEnabled(false)
            },
            onSessionStopped = {
                feedback.setSoundEnabled(true)
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
                showStatusMenu(anchor, item)
            }
        )

        layoutManager = LinearLayoutManager(this)
        binding.queueRecycler.layoutManager = layoutManager
        binding.queueRecycler.adapter = adapter

        inputUiController.setupInputPanel(binding.numberInput, binding.inputPanel)
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
                    inputTechController.onInputPanelReleased()
                }

                releasedInsideCounterPanel -> {
                    inputTechController.onCounterPanelReleased()
                }

                else -> {
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
        val popupBinding = PopupStatusMenuBinding.inflate(layoutInflater)

        popupBinding.actionStandard.text = "NO STATUS"
        popupBinding.actionService.text = "SERVICE"
        popupBinding.actionOffice.text = "OFFICE"
        popupBinding.actionJurnieks.text = "JURNIEKS"

        when (item.status) {
            Status.NONE -> {
                popupBinding.actionStandard.text = buildStatusLabel(
                    base = "NO STATUS",
                    number = item.number,
                    baseColor = 0xFFFFD0D0.toInt()
                )
            }

            Status.SERVICE -> {
                popupBinding.actionService.text = buildStatusLabel(
                    base = "SERVICE",
                    number = item.number,
                    baseColor = 0xFFFF67D8.toInt()
                )
            }

            Status.OFFICE -> {
                popupBinding.actionOffice.text = buildStatusLabel(
                    base = "OFFICE",
                    number = item.number,
                    baseColor = 0xFFFF67D8.toInt()
                )
            }

            Status.JURNIEKS -> {
                popupBinding.actionJurnieks.text = buildStatusLabel(
                    base = "JURNIEKS",
                    number = item.number,
                    baseColor = 0xFF4DB7FF.toInt()
                )
            }
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
            suppressNextQueueAutoScroll = true
            viewModel.setStatus(item.number, Status.NONE)
            requestAutoCopyIfNeeded()
            popup.dismiss()
        }

        popupBinding.actionService.setOnClickListener {
            suppressNextQueueAutoScroll = true
            viewModel.setStatus(item.number, Status.SERVICE)
            requestAutoCopyIfNeeded()
            popup.dismiss()
        }

        popupBinding.actionOffice.setOnClickListener {
            suppressNextQueueAutoScroll = true
            viewModel.setStatus(item.number, Status.OFFICE)
            requestAutoCopyIfNeeded()
            popup.dismiss()
        }

        popupBinding.actionJurnieks.setOnClickListener {
            suppressNextQueueAutoScroll = true
            viewModel.setStatus(item.number, Status.JURNIEKS)
            requestAutoCopyIfNeeded()
            popup.dismiss()
        }

        popup.contentView.measure(
            View.MeasureSpec.makeMeasureSpec(resources.displayMetrics.widthPixels, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(resources.displayMetrics.heightPixels, View.MeasureSpec.AT_MOST)
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

    private fun buildStatusLabel(base: String, number: Int, baseColor: Int): CharSequence {
        val text = "$base $number"
        val start = base.length + 1
        val end = text.length
        val numberColor = lightenColor(baseColor, 0.20f)

        return SpannableStringBuilder(text).apply {
            setSpan(
                ForegroundColorSpan(numberColor),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun lightenColor(color: Int, amount: Float): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)

        val newR = (r + ((255 - r) * amount)).toInt().coerceIn(0, 255)
        val newG = (g + ((255 - g) * amount)).toInt().coerceIn(0, 255)
        val newB = (b + ((255 - b) * amount)).toInt().coerceIn(0, 255)

        return Color.argb(Color.alpha(color), newR, newG, newB)
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
            feedback.ok()
        }

        binding.btnAutocopy.setOnLongClickListener {
            autoCopyEnabled = !autoCopyEnabled
            updateAutocopyButtonText()
            feedback.ok()
            true
        }

        binding.btnClearList.setOnClickListener {
            voiceSessionController.toggle()
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

    private fun handleVoiceRecognizedNumber(number: Int) {
        val result = viewModel.addNumber(number)

        when (result) {
            is QueueManager.AddResult.Added -> {
                feedback.ok()
                requestAutoCopyIfNeeded()
                voiceSessionController.onNumberAccepted()
            }

            QueueManager.AddResult.DuplicateInQueue -> {
                feedback.error()
                showInputProblemDialog("DUPLICATE NUMBER")
                voiceSessionController.onNumberRejected()
            }

            QueueManager.AddResult.NotInRegistry,
            QueueManager.AddResult.InvalidNumber -> {
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
                inputTechController.onKeyboardHidden()
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

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        syncUiController.release()
        inputTechController.release()
        voiceSessionController.release()
        super.onDestroy()
        feedback.release()
    }
}