package com.carlist.pro.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.carlist.pro.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel

    private lateinit var adapter: QueueAdapter
    private lateinit var touchHelper: ItemTouchHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.titleText.text = "CarList_PRO"

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        adapter = QueueAdapter(
            dragStart = { vh -> touchHelper.startDrag(vh) }
        )

        binding.queueRecycler.layoutManager = LinearLayoutManager(this)
        binding.queueRecycler.adapter = adapter

        touchHelper = ItemTouchHelper(
            QueueTouchHelperCallback(
                onMove = { from, to ->
                    viewModel.move(from, to)
                },
                onSwipedRight = { index ->
                    viewModel.removeAt(index)
                }
            )
        )
        touchHelper.attachToRecyclerView(binding.queueRecycler)

        viewModel.queueItems.observe(this) { items ->
            binding.subtitleText.text = "Queue size: ${items.size}"
            adapter.submitList(items)
        }
    }
}