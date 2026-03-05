package com.carlist.pro.ui

import android.os.Bundle
import android.view.KeyEvent
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
    private lateinit var touchHelper: ItemTouchHelper
    private lateinit var layoutManager: LinearLayoutManager

    private lateinit var feedback: SystemFeedback

    private var baseRootPadLeft = 0
    private var baseRootPadTop = 0
    private var baseRootPadRight = 0
    private var baseRootPadBottom = 0

    private var baseRvPadLeft = 0
    private var baseRvPadTop = 0
    private var baseRvPadRight = 0
    private var baseRvPadBottom = 0

    private var lastImeVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // КЛЮЧЕВОЕ: чтобы IME/systemBars insets гарантированно приходили в listener.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        feedback = SystemFeedback(this)

        // Recycler
        adapter = QueueAdapter(dragStart = { vh -> touchHelper.startDrag(vh) })
        layoutManager = LinearLayoutManager(this)

        binding.queueRecycler.layoutManager = layoutManager
        binding.queueRecycler.adapter = adapter

        touchHelper = ItemTouchHelper(
            QueueTouchHelperCallback(
                onMove = { from, to -> viewModel.move(from, to) },
                onSwipedRight = { index -> viewModel.removeAt(index) }
            )
        )
        touchHelper.attachToRecyclerView(binding.queueRecycler)

        // Запоминаем базовые паддинги из XML (их надо сохранять)
        baseRootPadLeft = binding.root.paddingLeft
        baseRootPadTop = binding.root.paddingTop
        baseRootPadRight = binding.root.paddingRight
        baseRootPadBottom = binding.root.paddingBottom

        baseRvPadLeft = binding.queueRecycler.paddingLeft
        baseRvPadTop = binding.queueRecycler.paddingTop
        baseRvPadRight = binding.queueRecycler.paddingRight
        baseRvPadBottom = binding.queueRecycler.paddingBottom

        // Данные -> список
        viewModel.queueItems.observe(this) { items ->
            binding.infoText.text = "Queue: ${items.size}"

            // ВАЖНО: скроллим после того, как DiffUtil применит список
            adapter.submitList(items) {
                if (lastImeVisible) {
                    scrollToBottom()
                }
            }
        }

        // Ввод по Enter/Done
        binding.numberInput.setOnEditorActionListener { _, actionId, event ->
            val isEnterKey =
                event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            val isImeDone = actionId == EditorInfo.IME_ACTION_DONE

            if (isEnterKey || isImeDone) {
                handleManualSubmit()
                true
            } else false
        }

        // ЕДИНСТВЕННЫЙ правильный источник истины: WindowInsets (systemBars + IME)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = ime.bottom > 0

            // 1) Не залезаем под статусбар/навигацию
            binding.root.setPadding(
                baseRootPadLeft + bars.left,
                baseRootPadTop + bars.top,
                baseRootPadRight + bars.right,
                baseRootPadBottom + bars.bottom
            )

            // 2) Главное: чтобы список НИКОГДА не был под клавиатурой
            val rvBottom = if (imeVisible) baseRvPadBottom + ime.bottom else baseRvPadBottom
            binding.queueRecycler.setPadding(
                baseRvPadLeft,
                baseRvPadTop,
                baseRvPadRight,
                rvBottom
            )

            // 3) Детерминированный автоскролл на смене видимости IME
            if (imeVisible != lastImeVisible) {
                lastImeVisible = imeVisible
                binding.queueRecycler.post {
                    if (lastImeVisible) {
                        scrollToBottom()
                    } else {
                        scrollToTop()
                    }
                }
            }

            insets
        }

        // Принудительно запрашиваем insets
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun onDestroy() {
        feedback.release()
        super.onDestroy()
    }

    private fun handleManualSubmit() {
        val raw = binding.numberInput.text?.toString()?.trim().orEmpty()
        val number = raw.toIntOrNull()
        binding.numberInput.setText("")

        if (number == null || number !in 1..99) {
            feedback.error()
            flashInputError()
            return
        }

        val result = try {
            viewModel.addNumber(number)
        } catch (_: Throwable) {
            null
        }

        when (result) {
            is QueueManager.AddResult.Added -> {
                feedback.ok()
                // Если IME открыта — показываем последнюю карточку
                if (lastImeVisible) {
                    binding.queueRecycler.post { scrollToBottom() }
                }
            }
            QueueManager.AddResult.InvalidNumber,
            QueueManager.AddResult.DuplicateInQueue,
            QueueManager.AddResult.NotInRegistry,
            null -> {
                feedback.error()
                flashInputError()
            }
        }
    }

    private fun scrollToBottom() {
        val last = adapter.itemCount - 1
        if (last >= 0) {
            layoutManager.scrollToPositionWithOffset(last, 0)
        }
    }

    private fun scrollToTop() {
        if (adapter.itemCount > 0) {
            layoutManager.scrollToPositionWithOffset(0, 0)
        }
    }

    private fun flashInputError() {
        binding.inputPanel.strokeColor = 0xFFFF4444.toInt()
        binding.inputPanel.postDelayed({
            binding.inputPanel.strokeColor = 0xFFB0B0B0.toInt()
        }, 400)
    }
}