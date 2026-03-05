package com.carlist.pro.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
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

    private var isDragging = false
    private var imeVisibleNow: Boolean = false

    // AUTOCOPY rules:
    // default OFF, label shows "AUTOCOPY ON"
    // short tap -> copy once
    // long press -> toggle auto mode
    private var autoCopyEnabled: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // IME stability baseline (the fix that worked for you)
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

        adapter = QueueAdapter()
        layoutManager = LinearLayoutManager(this)

        binding.queueRecycler.layoutManager = layoutManager
        binding.queueRecycler.adapter = adapter

        setupImeHandlingOnDecorView()
        setupButtons()

        viewModel.queueItems.observe(this) { list ->
            // CENTER reserved for sync/network (later). For now:
            binding.infoText.text = "SYNC OFF"

            // RIGHT: MYCAR_POSITION/TOTAL (MyCar not implemented yet => "-/TOTAL")
            binding.counterText.text = if (list.isEmpty()) "-/-" else "-/${list.size}"

            if (!isDragging) {
                adapter.submitItems(list)

                // Keep last visible while IME open (your normal workflow)
                if (imeVisibleNow && list.isNotEmpty()) {
                    val last = list.size - 1
                    binding.queueRecycler.post {
                        layoutManager.scrollToPositionWithOffset(last, 0)
                    }
                }
            }

            // Auto-copy after ANY queue change (if enabled)
            if (autoCopyEnabled) {
                copyQueueOnce(list)
            }
        }

        binding.numberInput.setOnEditorActionListener { _, actionId, event ->
            val isEnter =
                actionId == EditorInfo.IME_ACTION_DONE ||
                        (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)

            if (!isEnter) return@setOnEditorActionListener false

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
                    if (imeVisibleNow) {
                        binding.queueRecycler.post {
                            val count = adapter.itemCount
                            if (count > 0) layoutManager.scrollToPositionWithOffset(count - 1, 0)
                        }
                    }
                }
            )
        )
        touchHelper.attachToRecyclerView(binding.queueRecycler)

        // Force initial insets dispatch
        binding.root.post {
            ViewCompat.requestApplyInsets(window.decorView)
        }
    }

    private fun setupButtons() {
        updateAutocopyButtonText()

        // Short tap: copy once (regardless of auto mode)
        binding.btnAutocopy.setOnClickListener {
            val list = viewModel.queueItems.value.orEmpty()
            copyQueueOnce(list)
            feedback.ok()
        }

        // Long press: toggle auto mode
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
        // Per spec: default OFF -> show "AUTOCOPY ON"
        // Auto ON -> show "AUTOCOPY OFF"
        binding.btnAutocopy.text = if (autoCopyEnabled) "AUTOCOPY OFF" else "AUTOCOPY ON"
    }

    private fun handleManualSubmit() {
        val text = binding.numberInput.text?.toString()?.trim().orEmpty()
        val number = text.toIntOrNull()

        if (number == null) {
            feedback.error()
            binding.numberInput.setText("")
            return
        }

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

            // Categories come later (B/V/MY_CAR). For now: only status text.
            when (item.status) {
                Status.SERVICE -> sb.append(" ").append("SERVICE")
                Status.JURNIEKS -> sb.append(" ").append("JURNIEKS")
                Status.NONE -> { /* omit */ }
            }

            if (index != list.lastIndex) sb.append('\n')
        }
        return sb.toString()
    }

    private fun setupImeHandlingOnDecorView() {
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            imeVisibleNow = insets.isVisible(WindowInsetsCompat.Type.ime())

            binding.queueRecycler.post {
                if (imeVisibleNow) {
                    val count = adapter.itemCount
                    if (count > 0) layoutManager.scrollToPositionWithOffset(count - 1, 0)
                } else {
                    layoutManager.scrollToPositionWithOffset(0, 0)
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