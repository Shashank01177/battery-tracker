package com.batterytracker.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a single battery session — charging, discharging, or active usage.
 *
 * @param id                   Auto-generated primary key
 * @param sessionType          "CHARGING", "DISCHARGING", or "USAGE"
 * @param startPercent         Battery level (%) at the start of the session
 * @param endPercent           Battery level (%) at the end of the session (null if session is ongoing)
 * @param startTimeMs          Epoch milliseconds when the session started
 * @param endTimeMs            Epoch milliseconds when the session ended (null if ongoing)
 * @param durationMs           Total duration in milliseconds (null if ongoing)
 * @param percentChange        Absolute difference in battery level (null if ongoing)
 * @param reached100TimeMs     Epoch milliseconds when battery first hit 100% (null if never reached 100%)
 * @param overchargeDurationMs Milliseconds phone remained plugged in after hitting 100%
 */
@Entity(tableName = "battery_sessions")
data class BatterySession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionType: String,
    val startPercent: Int,
    var endPercent: Int? = null,
    val startTimeMs: Long,
    var endTimeMs: Long? = null,
    var durationMs: Long? = null,
    var percentChange: Int? = null,
    var reached100TimeMs: Long? = null,
    var overchargeDurationMs: Long? = null
)
