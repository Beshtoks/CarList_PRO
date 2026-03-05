package com.carlist.pro.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.carlist.pro.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.titleText.text = "CarList_PRO"
        binding.subtitleText.text = "Baseline OK"
    }
}