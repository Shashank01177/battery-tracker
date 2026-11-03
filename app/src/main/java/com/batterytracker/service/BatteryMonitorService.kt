package com.batterytracker.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import androidx.core.app.NotificationCompat
import com.batterytracker.data.db.AppDatabase
import com.batterytracker.data.db.BatterySession
import com.batterytracker.data.repository.BatteryRepository
import com.batterytracker.ui.MainActivity
import kotlinx.coroutines.*

/**
 * Foreground service that listens to Android battery and screen events and
 * records each charging / discharging / active-usage session to Room.
 *
 * Session types:
 *  • CHARGING       – charger connected (updates live in DB while charging)
 *  • DISCHARGING    – on battery power (updates live while discharging)
 *  • USAGE          – screen-on active usage while discharging
 *  • USAGE_CHARGING – screen-on active usage while charging
 */
class BatteryMonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "battery_monitor_channel"
        const val NOTIFICATION_ID = 1
        const val SESSION_CHARGING = "CHARGING"
        const val SESSION_DISCHARGING = "DISCHARGING"
        const val SESSION_USAGE = "USAGE"
        const val SESSION_USAGE_CHARGING = "USAGE_CHARGING"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: BatteryRepository

    // IDs of currently open sessions (null = none open)
    private var openChargingId: Long? = null
    private var openDischargingId: Long? = null
    private var openUsageId: Long? = null

    // Whether the charger is currently connected
    private var isCharging = false

    // Latest known battery % (updated on every ACTION_BATTERY_CHANGED)
    private var currentPercent: Int = 0

    // ──────────────────────────────────────────────────
    // Receiver 1: battery status changes (charge level, plug state)
    // ──────────────────────────────────────────────────
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val percent = (level * 100 / scale)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

            val nowCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            currentPercent = percent

            when {
                nowCharging && !isCharging -> onChargingStarted(percent)
                !nowCharging && isCharging -> onChargingStopped(percent)
                else -> updateOpenSessionsProgress(percent)
            }

            if (nowCharging && percent >= 100) {
                checkReached100()
            }

            isCharging = nowCharging
            updateNotification(percent, nowCharging)
        }
    }

    // ──────────────────────────────────────────────────
    // Receiver 2: screen on/off (tracks active usage in both charging & discharging)
    // ──────────────────────────────────────────────────
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON  -> onScreenOn()
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
            }
        }
    }

    // ──────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        repository = BatteryRepository(AppDatabase.getInstance(this).batterySessionDao())
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Monitoring battery…"))

        // Battery state changes
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        // Screen on/off for usage segment tracking
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, screenFilter)

        // Re-link any sessions that were open before the service was killed
        scope.launch { recoverOpenSessions() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY  // Android restarts us if killed

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(batteryReceiver)
        unregisterReceiver(screenReceiver)
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ──────────────────────────────────────────────────
    // Charging session management
    // ──────────────────────────────────────────────────

    /** Charger plugged in: close discharging sessions and open a new charging session. */
    private fun onChargingStarted(percent: Int) {
        openUsageId?.let { closeSession(it, SESSION_USAGE, percent) }
        openUsageId = null
        openDischargingId?.let { closeSession(it, SESSION_DISCHARGING, percent) }
        openDischargingId = null

        val now = System.currentTimeMillis()
        val reached100 = if (percent >= 100) now else null

        scope.launch {
            openChargingId = repository.startSession(
                BatterySession(
                    sessionType = SESSION_CHARGING,
                    startPercent = percent,
                    endPercent = percent,
                    startTimeMs = now,
                    durationMs = 0L,
                    percentChange = 0,
                    reached100TimeMs = reached100
                )
            )
        }
    }

    /** Charger unplugged: close charging session and open a discharging session. */
    private fun onChargingStopped(percent: Int) {
        openUsageId?.let { closeSession(it, SESSION_USAGE_CHARGING, percent) }
        openUsageId = null
        openChargingId?.let { closeSession(it, SESSION_CHARGING, percent) }
        openChargingId = null

        val now = System.currentTimeMillis()
        scope.launch {
            openDischargingId = repository.startSession(
                BatterySession(
                    sessionType = SESSION_DISCHARGING,
                    startPercent = percent,
                    endPercent = percent,
                    startTimeMs = now,
                    durationMs = 0L,
                    percentChange = 0
                )
            )
        }
    }

    /** Update ongoing sessions live in the database as battery level changes. */
    private fun updateOpenSessionsProgress(percent: Int) {
        val now = System.currentTimeMillis()
        scope.launch {
            // Update open charging session
            openChargingId?.let { id ->
                val session = repository.getOpenSession(SESSION_CHARGING)
                if (session != null && session.id == id) {
                    session.endPercent = percent
                    session.durationMs = now - session.startTimeMs
                    session.percentChange = kotlin.math.abs(percent - session.startPercent)
                    if (percent >= 100 && session.reached100TimeMs != null) {
                        session.overchargeDurationMs = kotlin.math.max(0L, now - session.reached100TimeMs!!)
                    }
                    repository.updateSession(session)
                }
            }

            // Update open discharging session
            openDischargingId?.let { id ->
                val session = repository.getOpenSession(SESSION_DISCHARGING)
                if (session != null && session.id == id) {
                    session.endPercent = percent
                    session.durationMs = now - session.startTimeMs
                    session.percentChange = kotlin.math.abs(percent - session.startPercent)
                    repository.updateSession(session)
                }
            }

            // Update open usage session
            openUsageId?.let { id ->
                val type = if (isCharging) SESSION_USAGE_CHARGING else SESSION_USAGE
                val session = repository.getOpenSession(type)
                if (session != null && session.id == id) {
                    session.endPercent = percent
                    session.durationMs = now - session.startTimeMs
                    session.percentChange = kotlin.math.abs(percent - session.startPercent)
                    repository.updateSession(session)
                }
            }
        }
    }

    /** Mark when the phone hits 100% while on charge. */
    private fun checkReached100() {
        scope.launch {
            val open = repository.getOpenSession(SESSION_CHARGING) ?: return@launch
            if (open.reached100TimeMs == null) {
                open.reached100TimeMs = System.currentTimeMillis()
                repository.updateSession(open)
            }
        }
    }

    // ──────────────────────────────────────────────────
    // Usage segment management (screen on/off)
    // ──────────────────────────────────────────────────

    /**
     * Screen turned ON: start tracking active usage session (works in both charging and discharging).
     */
    private fun onScreenOn() {
        val usageType = if (isCharging) SESSION_USAGE_CHARGING else SESSION_USAGE
        val now = System.currentTimeMillis()

        scope.launch {
            openUsageId = repository.startSession(
                BatterySession(
                    sessionType = usageType,
                    startPercent = currentPercent,
                    endPercent = currentPercent,
                    startTimeMs = now,
                    durationMs = 0L,
                    percentChange = 0
                )
            )
        }
    }

    /**
     * Screen turned OFF: close the active usage session.
     */
    private fun onScreenOff() {
        val id = openUsageId ?: return
        val usageType = if (isCharging) SESSION_USAGE_CHARGING else SESSION_USAGE
        closeSession(id, usageType, currentPercent)
        openUsageId = null
    }

    // ──────────────────────────────────────────────────
    // Generic session closer
    // ──────────────────────────────────────────────────

    /** Fetches the open session by type, fills end values, and persists. */
    private fun closeSession(sessionId: Long, type: String, endPercent: Int) {
        scope.launch {
            val open = repository.getOpenSession(type) ?: return@launch
            if (open.id != sessionId) return@launch

            val now = System.currentTimeMillis()
            open.endPercent = endPercent
            open.endTimeMs = now
            open.durationMs = now - open.startTimeMs
            open.percentChange = kotlin.math.abs(endPercent - open.startPercent)

            if (type == SESSION_CHARGING) {
                if (endPercent >= 100 && open.reached100TimeMs == null) {
                    open.reached100TimeMs = now
                }
                open.reached100TimeMs?.let { hit100 ->
                    open.overchargeDurationMs = kotlin.math.max(0L, now - hit100)
                }
            }

            repository.closeSession(open)
        }
    }

    /** Re-link open sessions surviving a service restart. */
    private suspend fun recoverOpenSessions() {
        repository.getOpenSession(SESSION_CHARGING)?.let { openChargingId = it.id }
        repository.getOpenSession(SESSION_DISCHARGING)?.let { openDischargingId = it.id }
        repository.getOpenSession(SESSION_USAGE)?.let { openUsageId = it.id }
        repository.getOpenSession(SESSION_USAGE_CHARGING)?.let { openUsageId = it.id }
        repository.pruneOld()
    }

    // ──────────────────────────────────────────────────
    // Notification helpers
    // ──────────────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Battery Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Tracks charging & discharging sessions" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Battery Tracker")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(percent: Int, charging: Boolean) {
        val status = if (charging) "⚡ Charging" else "🔋 On battery"
        val notification = buildNotification("$status  •  $percent%")
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }
}
