package com.carlist.pro.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.carlist.pro.databinding.ActivityMainBinding
import com.carlist.pro.domain.QueueItem
import com.carlist.pro.domain.QueueManager
import com.carlist.pro.domain.Status
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var adapter: QueueAdapter
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var feedback: SystemFeedback

    private var imeVisibleNow = false
    private var autoCopyEnabled = false
    private var isDragging = false

    private var techTapCount = 0
    private var techFirstTapAtMs = 0L
    private var techCountdownActive = false

    private var isTechMenuOpen = false
    private var isManualInputMode = false

    // Жесты левого табло
    private val gestureHandler = Handler(Looper.getMainLooper())
    private val longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()
    private var inputPanelTouchActive = false
    private var longPressTriggered = false
    private var longPressRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {

        WindowCompat.setDecorFitsSystemWindows(window, true)

        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        feedback = SystemFeedback(this)

        adapter = QueueAdapter(
            transportInfoProvider = { number -> viewModel.getTransportInfo(number) }
        )

        layoutManager = LinearLayoutManager(this)
        binding.queueRecycler.layoutManager = layoutManager
        binding.queueRecycler.adapter = adapter

        setupInputPanel()
        setupButtons()
        setupImeHandling()

        viewModel.queueItems.observe(this) { list ->

            binding.infoText.text = "SYNC OFF"
            updateMyCarCounter(list)

            if (!isDragging) {

                adapter.submitItems(list)

                if (!isTechMenuOpen && imeVisibleNow && list.isNotEmpty()) {
                    val last = list.size - 1
                    binding.queueRecycler.post {
                        layoutManager.scrollToPositionWithOffset(last, 0)
                    }
                }
            }

            if (autoCopyEnabled) copyQueueOnce(list)
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
                    viewModel.removeAt(position)
                },
                onDragStateChanged = { dragging ->
                    isDragging = dragging
                },
                onDragEnded = { _, _ ->
                    viewModel.commitDrag()
                }
            )
        )

        touchHelper.attachToRecyclerView(binding.queueRecycler)

        binding.root.post {
            ViewCompat.requestApplyInsets(window.decorView)
        }

        lockManualInput()
        applyInputVisualState(false)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {

        if (!isTechMenuOpen) {
            when (ev.actionMasked) {

                MotionEvent.ACTION_DOWN -> {
                    if (isInsideInputPanel(ev.rawX, ev.rawY)) {
                        inputPanelTouchActive = true
                        longPressTriggered = false

                        longPressRunnable = Runnable {
                            if (!inputPanelTouchActive || isTechMenuOpen) return@Runnable

                            longPressTriggered = true
                            resetTechTapSequence()
                            startManualInputMode()
                        }

                        gestureHandler.postDelayed(longPressRunnable!!, longPressTimeoutMs)
                    } else {
                        inputPanelTouchActive = false
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (inputPanelTouchActive) {
                        longPressRunnable?.let { gestureHandler.removeCallbacks(it) }

                        val releasedInside = isInsideInputPanel(ev.rawX, ev.rawY)

                        if (releasedInside && !longPressTriggered) {
                            handleTechTaps()
                        }

                        inputPanelTouchActive = false
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { gestureHandler.removeCallbacks(it) }
                    inputPanelTouchActive = false
                    longPressTriggered = false
                }
            }
        }

        return super.dispatchTouchEvent(ev)
    }

    fun getMainViewModel(): MainViewModel = viewModel

    fun onTechnicalMenuClosed() {
        isTechMenuOpen = false
        techTapCount = 0
        techCountdownActive = false
        applyInputVisualState(imeVisibleNow)
    }

    private fun setupInputPanel() {

        binding.numberInput.isLongClickable = false
        binding.numberInput.setTextIsSelectable(false)
        binding.numberInput.isClickable = false
        binding.numberInput.isFocusable = false
        binding.numberInput.isFocusableInTouchMode = false
        binding.numberInput.isCursorVisible = false

        // Обычный longClickListener больше не используем — long press обрабатывается вручную
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
        } else if (now - techFirstTapAtMs > 5000) {
            techTapCount = 0
            techFirstTapAtMs = now
        }

        techTapCount++

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
                binding.inputHint.text = "OPEN"
                techTapCount = 0
                techCountdownActive = false
                openTechnicalMenu()
            }
        }
    }

    private fun resetTechTapSequence() {
        techTapCount = 0
        techFirstTapAtMs = 0L
        techCountdownActive = false
    }

    private fun openTechnicalMenu() {

        isTechMenuOpen = true

        lockManualInput()

        val imm = getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(binding.numberInput.windowToken, 0)

        DriverRegistryDialogFragment()
            .show(supportFragmentManager, "DriverRegistryDialog")
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
            binding.inputHint.text = "Nr / 🛠 / 🔊"
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
            MaterialAlertDialogBuilder(this)
                .setTitle("CLEAR LIST")
                .setMessage("Are you sure?")
                .setPositiveButton("YES") { _, _ ->
                    viewModel.clear()
                    feedback.ok()
                }
                .setNegativeButton("NO", null)
                .show()
        }
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
            }
            QueueManager.AddResult.InvalidNumber,
            QueueManager.AddResult.DuplicateInQueue,
            QueueManager.AddResult.NotInRegistry -> {
                feedback.error()
                binding.numberInput.setText("")
            }
        }
    }

    private fun copyQueueOnce(list: List<QueueItem>) {
        val text = buildQueueText(list)
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("CarList_PRO Queue", text))
    }

    private fun buildQueueText(list: List<QueueItem>): String {
        if (list.isEmpty()) return ""
        val sb = StringBuilder()
        list.forEachIndexed { index, item ->
            val lineIndex = index + 1
            sb.append(lineIndex).append(". ").append(item.number)
            when (item.status) {
                Status.SERVICE -> sb.append(" SERVICE")
                Status.JURNIEKS -> sb.append(" JURNIEKS")
                Status.NONE -> {}
            }
            if (index != list.lastIndex) sb.append('\n')
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
        binding.counterText.text = if (pos == null) "-/$total" else "$pos/$total"
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

    override fun onDestroy() {
        longPressRunnable?.let { gestureHandler.removeCallbacks(it) }
        super.onDestroy()
        feedback.release()
    }
}