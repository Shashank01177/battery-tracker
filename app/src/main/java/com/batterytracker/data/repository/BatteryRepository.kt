package com.batterytracker.data.repository

import androidx.lifecycle.LiveData
import com.batterytracker.data.db.BatterySession
import com.batterytracker.data.db.BatterySessionDao

/**
 * Single source of truth for battery session data.
 * The service writes sessions; the ViewModel reads them via LiveData.
 */
class BatteryRepository(private val dao: BatterySessionDao) {

    /** Live list of all completed sessions (newest first). */
    val allCompletedSessions: LiveData<List<BatterySession>> = dao.getAllCompleted()

    /** Synchronous snapshot of all completed sessions for file export. */
    suspend fun getAllCompletedList(): List<BatterySession> = dao.getAllCompletedList()

    /** Start a new session and return its ID so the service can close it later. */
    suspend fun startSession(session: BatterySession): Long = dao.insert(session)

    /** Close an open session with final battery level and timestamps. */
    suspend fun closeSession(session: BatterySession) = dao.update(session)

    /** Retrieve the currently open session of a type (used on service restart). */
    suspend fun getOpenSession(type: String): BatterySession? = dao.getOpenSession(type)

    /** Purge sessions older than 30 days to keep storage tidy. */
    suspend fun pruneOld() {
        val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
        dao.deleteOlderThan(thirtyDaysAgo)
    }
}
