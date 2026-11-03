package com.batterytracker.data.repository

import androidx.lifecycle.LiveData
import com.batterytracker.data.db.BatterySession
import com.batterytracker.data.db.BatterySessionDao

/**
 * Single source of truth for battery session data.
 * The service writes sessions; the ViewModel reads them via LiveData.
 */
class BatteryRepository(private val dao: BatterySessionDao) {

    /** Live list of all sessions (both ongoing and completed, newest first). */
    val allSessions: LiveData<List<BatterySession>> = dao.getAllSessions()

    /** Synchronous snapshot of all sessions for file export. */
    suspend fun getAllSessionsList(): List<BatterySession> = dao.getAllSessionsList()

    /** Start a new session and return its ID so the service can close it later. */
    suspend fun startSession(session: BatterySession): Long = dao.insert(session)

    /** Update an open or completed session. */
    suspend fun updateSession(session: BatterySession) = dao.update(session)

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
