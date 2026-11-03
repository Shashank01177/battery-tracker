package com.batterytracker.data.repository

import androidx.lifecycle.LiveData
import com.batterytracker.data.db.BatterySession
import com.batterytracker.data.db.BatterySessionDao

/**
 * Single source of truth for battery session data.
 */
class BatteryRepository(private val dao: BatterySessionDao) {

    /** Live list of all sessions (both ongoing and completed), newest first. */
    val allSessions: LiveData<List<BatterySession>> = dao.getAll()

    /** Synchronous snapshot of all sessions for file export. */
    suspend fun getAllList(): List<BatterySession> = dao.getAllList()

    /** Start a new session and return its ID. */
    suspend fun startSession(session: BatterySession): Long = dao.insert(session)

    /** Update an ongoing or closed session. */
    suspend fun updateSession(session: BatterySession) = dao.update(session)

    /** Retrieve the currently open session of a type. */
    suspend fun getOpenSession(type: String): BatterySession? = dao.getOpenSession(type)

    /** Purge sessions older than 30 days. */
    suspend fun pruneOld() {
        val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
        dao.deleteOlderThan(thirtyDaysAgo)
    }
}
