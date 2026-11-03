package com.batterytracker.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.batterytracker.R
import com.batterytracker.data.db.AppDatabase
import com.batterytracker.data.repository.BatteryRepository
import com.batterytracker.databinding.ActivityMainBinding
import com.batterytracker.service.BatteryMonitorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Main screen:
 *  • Displays all charging, discharging, and screen-on sessions (live updating)
 *  • Requests required runtime notification permission
 *  • Provides one-click CSV export
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var adapter: SessionAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup Toolbar Menu (Export)
        binding.toolbar.inflateMenu(R.menu.menu_main)
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == R.id.action_export) {
                exportRecords()
                true
            } else {
                false
            }
        }

        // Request POST_NOTIFICATIONS permission on Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Start the background monitor service
        startMonitorService()

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

    override fun onResume() {
        super.onResume()
        startMonitorService()
    }

    private fun startMonitorService() {
        try {
            val serviceIntent = Intent(this, BatteryMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Exports all battery session records as a CSV file shared via Android Intent. */
    private fun exportRecords() {
        lifecycleScope.launch(Dispatchers.IO) {
            val list = viewModel.getAllSessionsForExport()
            if (list.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "No battery records to export yet.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }

            val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val csvContent = buildString {
                append("ID,Session Type,Start Time,End Time,Duration,Start %,End %,Change %,Kept at 100% Time\n")
                for (s in list) {
                    val startStr = dateFmt.format(Date(s.startTimeMs))
                    val endStr = s.endTimeMs?.let { dateFmt.format(Date(it)) } ?: "Ongoing"
                    val durMs = s.durationMs ?: (System.currentTimeMillis() - s.startTimeMs)
                    val durStr = formatDuration(durMs)
                    val overchargeStr = s.overchargeDurationMs?.let { formatDuration(it) } ?: "—"
                    val endPct = s.endPercent ?: s.startPercent
                    val change = kotlin.math.abs(endPct - s.startPercent)
                    val sign = if (s.sessionType == BatteryMonitorService.SESSION_CHARGING) "+" else "-"

                    append("${s.id},${s.sessionType},\"$startStr\",\"$endStr\",\"$durStr\",")
                    append("${s.startPercent}%,${endPct}%,$sign$change%,\"$overchargeStr\"\n")
                }
            }

            val exportFile = File(cacheDir, "battery_records_${System.currentTimeMillis()}.csv")
            exportFile.writeText(csvContent)

            val uri = FileProvider.getUriForFile(
                this@MainActivity,
                "${applicationContext.packageName}.fileprovider",
                exportFile
            )

            withContext(Dispatchers.Main) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Battery Tracker Records Export")
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "Exported battery records log from Battery Tracker."
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Export Battery Records"))
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(ms)
        val mins = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val secs = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return when {
            hours > 0 -> "${hours}h ${mins}m"
            mins > 0 -> "${mins} min${if (mins > 1) "s" else ""}"
            else -> "${secs} sec"
        }
    }
}
