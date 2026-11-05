package com.batterytracker.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.batterytracker.R
import com.batterytracker.data.DataImporter
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

/**
 * Main screen:
 *  • Displays past and ongoing sessions in Month -> Day -> Session folder structure
 *  • Auto-seeds initial history from assets/initial_records.csv
 *  • Allows manual Import and Export of CSV records
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var adapter: SessionAdapter
    private lateinit var repository: BatteryRepository

    // File picker for importing battery records CSV
    private val importFilePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { importFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Wire up repository and ViewModel
        repository = BatteryRepository(AppDatabase.getInstance(this).batterySessionDao())
        viewModel = ViewModelProvider(this, MainViewModelFactory(repository))
            .get(MainViewModel::class.java)

        // Setup Toolbar Menu (Import & Export)
        binding.toolbar.inflateMenu(R.menu.menu_main)
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_export -> {
                    exportRecords()
                    true
                }
                R.id.action_import -> {
                    importFilePicker.launch("*/*")
                    true
                }
                else -> false
            }
        }

        // Start the background monitor service
        val serviceIntent = Intent(this, BatteryMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Set up RecyclerView
        adapter = SessionAdapter()
        binding.recyclerSessions.layoutManager = LinearLayoutManager(this)
        binding.recyclerSessions.adapter = adapter

        // Observe and display sessions in Month -> Day folders
        viewModel.sessions.observe(this) { sessions ->
            adapter.setSessions(sessions)
            binding.tvEmpty.visibility =
                if (sessions.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }

        // Automatically integrate user's previous history records if DB is fresh
        lifecycleScope.launch {
            DataImporter.autoSeedFromAssets(this@MainActivity, repository)
        }
    }

    /** Imports records from a selected CSV file. */
    private fun importFromUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val count = DataImporter.importFromInputStream(inputStream, repository)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            if (count > 0) "Imported $count records successfully!" else "All records in file already exist.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Failed to import: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
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
                    val endStr = s.endTimeMs?.let { dateFmt.format(Date(it)) } ?: "Ongoing (Live)"
                    val durMs = s.durationMs ?: (System.currentTimeMillis() - s.startTimeMs)
                    val durStr = formatDuration(durMs)
                    val overchargeStr = s.overchargeDurationMs?.let { formatDuration(it) } ?: "—"
                    val change = s.percentChange ?: kotlin.math.abs((s.endPercent ?: s.startPercent) - s.startPercent)
                    val isGaining = s.sessionType == BatteryMonitorService.SESSION_CHARGING ||
                            s.sessionType == BatteryMonitorService.SESSION_USAGE_CHARGING
                    val sign = if (isGaining) "+" else "-"

                    append("${s.id},${s.sessionType},\"$startStr\",\"$endStr\",\"$durStr\",")
                    append("${s.startPercent}%,${s.endPercent ?: s.startPercent}%,$sign$change%,\"$overchargeStr\"\n")
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
                        "Exported battery records log from Battery Tracker (with exact seconds)."
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Export Battery Records"))
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSecs = ms / 1000
        val hours = totalSecs / 3600
        val mins = (totalSecs % 3600) / 60
        val secs = totalSecs % 60
        return when {
            hours > 0 -> "${hours}h ${mins}m ${secs}s"
            mins > 0 -> "${mins}m ${secs}s"
            else -> "${secs}s"
        }
    }
}
