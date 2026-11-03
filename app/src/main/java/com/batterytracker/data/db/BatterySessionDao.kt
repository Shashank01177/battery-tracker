package com.batterytracker.data.db

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface BatterySessionDao {

    /** Insert a new session and return its generated ID. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: BatterySession): Long

    /** Update an existing session (used to close/complete a session). */
    @Update
    suspend fun update(session: BatterySession)

    /** Observe all sessions ordered by most recent first — powers the RecyclerView. */
    @Query("SELECT * FROM battery_sessions WHERE endTimeMs IS NOT NULL ORDER BY startTimeMs DESC")
    fun getAllCompleted(): LiveData<List<BatterySession>>

    /** Get direct snapshot of all completed sessions for export. */
    @Query("SELECT * FROM battery_sessions WHERE endTimeMs IS NOT NULL ORDER BY startTimeMs DESC")
    suspend fun getAllCompletedList(): List<BatterySession>

    /** Get the most recent open/ongoing session of a given type. */
    @Query("SELECT * FROM battery_sessions WHERE sessionType = :type AND endTimeMs IS NULL ORDER BY startTimeMs DESC LIMIT 1")
    suspend fun getOpenSession(type: String): BatterySession?

    /** Delete sessions older than a given epoch timestamp to keep DB lean. */
    @Query("DELETE FROM battery_sessions WHERE startTimeMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)
}
