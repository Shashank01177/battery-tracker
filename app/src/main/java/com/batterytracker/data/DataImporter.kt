package com.batterytracker.data

import android.content.Context
import android.util.Log
import com.batterytracker.data.db.BatterySession
import com.batterytracker.data.repository.BatteryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*

object DataImporter {

    private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /**
     * Seeds initial records from assets/initial_records.csv if the database has no records,
     * so user's existing history is preserved seamlessly in the new version.
     */
    suspend fun autoSeedFromAssets(context: Context, repository: BatteryRepository) {
        withContext(Dispatchers.IO) {
            try {
                val existing = repository.getAllSessionsList()
                if (existing.isNotEmpty()) {
                    return@withContext
                }
                context.assets.open("initial_records.csv").use { inputStream ->
                    importFromInputStream(inputStream, repository)
                }
            } catch (e: Exception) {
                Log.e("DataImporter", "Error auto-seeding assets", e)
            }
        }
    }

    /**
     * Imports records from any CSV input stream (used by file picker or auto-seed).
     * Deduplicates based on startTimeMs so no duplicates are created.
     * Returns the count of newly imported sessions.
     */
    suspend fun importFromInputStream(inputStream: InputStream, repository: BatteryRepository): Int {
        return withContext(Dispatchers.IO) {
            var count = 0
            val existing = repository.getAllSessionsList()
            val existingTimestamps = existing.map { it.startTimeMs }.toSet()

            val reader = BufferedReader(InputStreamReader(inputStream))
            var line: String? = reader.readLine() // skip header line

            while (reader.readLine().also { line = it } != null) {
                val currentLine = line?.trim() ?: continue
                if (currentLine.isEmpty()) continue

                val row = parseCsvLine(currentLine)
                if (row.size < 7) continue

                try {
                    val sessionType = row[1].trim()
                    val startStr = row[2].trim().replace("\"", "")
                    val endStr = row[3].trim().replace("\"", "")

                    val startDate = DATE_FMT.parse(startStr) ?: continue
                    val startTimeMs = startDate.time

                    if (existingTimestamps.contains(startTimeMs)) {
                        continue
                    }

                    val endDate = if (endStr != "Ongoing (Live)" && endStr != "Ongoing" && endStr != "—") {
                        try { DATE_FMT.parse(endStr) } catch (e: Exception) { null }
                    } else null
                    val endTimeMs = endDate?.time

                    val startPct = row[5].trim().replace("%", "").toIntOrNull() ?: 0
                    val endPct = row[6].trim().replace("%", "").toIntOrNull() ?: startPct
                    val diff = kotlin.math.abs(endPct - startPct)
                    val durMs = if (endTimeMs != null) (endTimeMs - startTimeMs) else null

                    val session = BatterySession(
                        id = 0,
                        sessionType = sessionType,
                        startPercent = startPct,
                        endPercent = endPct,
                        startTimeMs = startTimeMs,
                        endTimeMs = endTimeMs,
                        durationMs = durMs,
                        percentChange = diff
                    )
                    repository.startSession(session)
                    count++
                } catch (e: Exception) {
                    Log.e("DataImporter", "Skipping row: $currentLine", e)
                }
            }
            count
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var inQuotes = false
        val sb = StringBuilder()
        for (ch in line) {
            when {
                ch == '\"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    result.add(sb.toString())
                    sb.clear()
                }
                else -> sb.append(ch)
            }
        }
        result.add(sb.toString())
        return result
    }
}
