package com.carlist.pro.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.*
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

    // КЛЮЧ: пока открыто техменю — Activity не вмешивается в фокус/инпут
    private var isTechMenuOpen = false

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

            // Пока техменю открыто — ввод в очередь запрещён (иначе IME улетает туда)
            if (isTechMenuOpen) return@setOnEditorActionListener true

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

        applyInputVisualState(false)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {

        // Пока меню открыто — Activity не считает тап-счётчик (чтобы не было побочных эффектов)
        if (!isTechMenuOpen && ev.action == MotionEvent.ACTION_DOWN) {

            val location = IntArray(2)
            binding.inputPanel.getLocationOnScreen(location)

            val x = ev.rawX
            val y = ev.rawY

            val left = location[0]
            val top = location[1]
            val right = left + binding.inputPanel.width
            val bottom = top + binding.inputPanel.height

            if (x >= left && x <= right && y >= top && y <= bottom) {
                handleTechTaps()
            }
        }

        return super.dispatchTouchEvent(ev)
    }

    fun getMainViewModel(): MainViewModel = viewModel

    // Вызывается из DriverRegistryDialogFragment при закрытии
    fun onTechnicalMenuClosed() {
        isTechMenuOpen = false
        techTapCount = 0
        techCountdownActive = false
        applyInputVisualState(imeVisibleNow)
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

            1 -> { /* tap1 -> no output */ }

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

    private fun openTechnicalMenu() {

        isTechMenuOpen = true

        // КЛЮЧ: полностью отвязываем IME от numberInput
        binding.numberInput.clearFocus()
        binding.numberInput.isCursorVisible = false

        // Важно: скрываем клавиатуру именно от токена numberInput,
        // чтобы следующий фокус (в диалоге) получил IME корректно.
        val imm = getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(binding.numberInput.windowToken, 0)

        DriverRegistryDialogFragment()
            .show(supportFragmentManager, "DriverRegistryDialog")
    }

    private fun applyInputVisualState(imeVisible: Boolean) {

        // Пока техменю открыто — Activity не трогает панель ввода (иначе снова фокус уедет)
        if (isTechMenuOpen) return

        if (techCountdownActive) {
            binding.inputHint.visibility = View.VISIBLE
            binding.numberInput.isCursorVisible = false
            return
        }

        if (imeVisible) {
            binding.inputHint.visibility = View.INVISIBLE
            binding.numberInput.isCursorVisible = true
        } else {
            binding.inputHint.visibility = View.VISIBLE
            binding.numberInput.isCursorVisible = false
            binding.numberInput.clearFocus()
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

            applyInputVisualState(imeVisibleNow)

            // Пока техменю открыто — не скроллим очередь из-за IME
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

    override fun onDestroy() {
        super.onDestroy()
        feedback.release()
    }
}