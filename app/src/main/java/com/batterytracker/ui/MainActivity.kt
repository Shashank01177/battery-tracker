package com.batterytracker.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.batterytracker.data.db.AppDatabase
import com.batterytracker.data.repository.BatteryRepository
import com.batterytracker.databinding.ActivityMainBinding
import com.batterytracker.service.BatteryMonitorService

/**
 * Main (and only) screen.
 *
 * Shows:
 *  • A header with current battery status pulled live from the system
 *  • A RecyclerView of all past charging / discharging sessions
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var adapter: SessionAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Start the background monitor service
        val serviceIntent = Intent(this, BatteryMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Wire up ViewModel
        val repository = BatteryRepository(AppDatabase.getInstance(this).batterySessionDao())
        viewModel = ViewModelProvider(this, MainViewModelFactory(repository))
            .get(MainViewModel::class.java)

        // Set up RecyclerView
        adapter = SessionAdapter()
        binding.recyclerSessions.layoutManager = LinearLayoutManager(this)
        binding.recyclerSessions.adapter = adapter

        // Observe and display sessions
        viewModel.sessions.observe(this) { sessions ->
            adapter.submitList(sessions)
            binding.tvEmpty.visibility =
                if (sessions.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }
    }
}
