package com.carlist.pro.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
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

    private var autoCopyEnabled: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {

        // IME stability baseline (confirmed working)
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
        setupInputPanelTap()

        viewModel.queueItems.observe(this) { list ->

            binding.infoText.text = "SYNC OFF"
            binding.counterText.text = if (list.isEmpty()) "-/-" else "-/${list.size}"

            if (!isDragging) {
                adapter.submitItems(list)

                if (imeVisibleNow && list.isNotEmpty()) {
                    val last = list.size - 1
                    binding.queueRecycler.post {
                        layoutManager.scrollToPositionWithOffset(last, 0)
                    }
                }
            }

            if (autoCopyEnabled) {
                copyQueueOnce(list)
            }
        }

        binding.numberInput.setOnEditorActionListener { _, actionId, event ->

            val isEnter =
                actionId == EditorInfo.IME_ACTION_DONE ||
                        (event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                                event.action == KeyEvent.ACTION_DOWN)

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
                            if (count > 0) {
                                layoutManager.scrollToPositionWithOffset(count - 1, 0)
                            }
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

        // Initial visual state: hint visible, cursor hidden
        applyInputVisualState(imeVisible = false)
    }

    private fun setupInputPanelTap() {
        // Tap on the whole left panel should always bring up keyboard,
        // and ONLY then we show cursor + hide hint.
        binding.inputPanel.setOnClickListener {
            binding.numberInput.requestFocus()
        }
        binding.inputHint.setOnClickListener {
            binding.numberInput.requestFocus()
        }
    }

    private fun applyInputVisualState(imeVisible: Boolean) {
        if (imeVisible) {
            // IME on screen -> empty black field + blinking cursor
            binding.inputHint.visibility = View.INVISIBLE
            binding.numberInput.isCursorVisible = true
        } else {
            // IME hidden -> show icons, NO cursor blinking
            binding.inputHint.visibility = View.VISIBLE
            binding.numberInput.isCursorVisible = false
            binding.numberInput.clearFocus()
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

            when (item.status) {
                Status.SERVICE -> sb.append(" SERVICE")
                Status.JURNIEKS -> sb.append(" JURNIEKS")
                Status.NONE -> {}
            }

            if (index != list.lastIndex) sb.append('\n')
        }
        return sb.toString()
    }

    private fun setupImeHandlingOnDecorView() {
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->

            imeVisibleNow = insets.isVisible(WindowInsetsCompat.Type.ime())
            applyInputVisualState(imeVisibleNow)

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