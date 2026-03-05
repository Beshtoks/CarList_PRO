package com.carlist.pro.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.carlist.pro.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.titleText.text = "CarList_PRO"

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        viewModel.queueItems.observe(this) { items ->
            binding.subtitleText.text = "Queue size: ${items.size}"
        }
    }
}