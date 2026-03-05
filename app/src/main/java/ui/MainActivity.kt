package com.carlist.pro.ui

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
import com.carlist.pro.domain.QueueManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel

    private lateinit var adapter: QueueAdapter
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var feedback: SystemFeedback

    private var isDragging = false
    private var imeVisibleNow: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Hard safety: keep classic window fitting (prevents edge-to-edge from breaking adjustResize)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        // Hard safety: enforce the SAME mode as manifest (stateHidden|adjustResize)
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

        viewModel.queueItems.observe(this) { list ->
            // CENTER: reserved for sync/network info
            binding.infoText.text = "SYNC OFF"

            // RIGHT: MYCAR_POSITION/TOTAL (MyCar not implemented yet => "-/TOTAL")
            binding.counterText.text = if (list.isEmpty()) "-/-" else "-/${list.size}"

            if (!isDragging) {
                adapter.submitItems(list)

                // Your real workflow: IME is usually visible from the very first entry.
                if (imeVisibleNow && list.isNotEmpty()) {
                    val last = list.size - 1
                    binding.queueRecycler.post {
                        layoutManager.scrollToPositionWithOffset(last, 0)
                    }
                }
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

    private fun setupImeHandlingOnDecorView() {
        // IMPORTANT: attach listener to decorView (top-level window view)
        // Some devices/ROMs don't reliably dispatch IME insets to content root.
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