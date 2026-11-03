package com.batterytracker.data.db

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface BatterySessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: BatterySession): Long

    @Update
    suspend fun update(session: BatterySession)

    /** Observe ALL sessions (both ongoing and completed), newest first. */
    @Query("SELECT * FROM battery_sessions ORDER BY startTimeMs DESC")
    fun getAll(): LiveData<List<BatterySession>>

    /** Get direct snapshot of all sessions for export. */
    @Query("SELECT * FROM battery_sessions ORDER BY startTimeMs DESC")
    suspend fun getAllList(): List<BatterySession>

    /** Get the most recent open/ongoing session of a given type. */
    @Query("SELECT * FROM battery_sessions WHERE sessionType = :type AND endTimeMs IS NULL ORDER BY startTimeMs DESC LIMIT 1")
    suspend fun getOpenSession(type: String): BatterySession?

    /** Delete sessions older than a given epoch timestamp to keep DB lean. */
    @Query("DELETE FROM battery_sessions WHERE startTimeMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)
}
