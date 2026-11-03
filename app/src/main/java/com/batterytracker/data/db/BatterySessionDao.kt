package com.batterytracker.data.db

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface BatterySessionDao {

    /** Insert a new session and return its generated ID. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: BatterySession): Long

    /** Update an existing session (used to update progress or close a session). */
    @Update
    suspend fun update(session: BatterySession)

    /** Observe all sessions (ongoing and completed) ordered by most recent first. */
    @Query("SELECT * FROM battery_sessions ORDER BY startTimeMs DESC")
    fun getAllSessions(): LiveData<List<BatterySession>>

    /** Get direct snapshot of all sessions for export. */
    @Query("SELECT * FROM battery_sessions ORDER BY startTimeMs DESC")
    suspend fun getAllSessionsList(): List<BatterySession>

    /** Get the most recent open/ongoing session of a given type. */
    @Query("SELECT * FROM battery_sessions WHERE sessionType = :type AND endTimeMs IS NULL ORDER BY startTimeMs DESC LIMIT 1")
    suspend fun getOpenSession(type: String): BatterySession?

    /** Delete sessions older than a given epoch timestamp to keep DB lean. */
    @Query("DELETE FROM battery_sessions WHERE startTimeMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)
}
