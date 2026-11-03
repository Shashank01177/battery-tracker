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
 *  • CHARGING    – charger connected until unplugged
 *  • DISCHARGING – charger unplugged until plugged back in
 *  • USAGE       – screen-on period that falls inside a DISCHARGING session
 */
class BatteryMonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "battery_monitor_channel"
        const val NOTIFICATION_ID = 1
        const val SESSION_CHARGING = "CHARGING"
        const val SESSION_DISCHARGING = "DISCHARGING"
        const val SESSION_USAGE = "USAGE"
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
            }
            isCharging = nowCharging
            updateNotification(percent, nowCharging)
        }
    }

    // ──────────────────────────────────────────────────
    // Receiver 2: screen on/off (only relevant during discharge)
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

    /** Charger plugged in: close any open discharging/usage sessions, open a charging session. */
    private fun onChargingStarted(percent: Int) {
        openUsageId?.let { closeSession(it, SESSION_USAGE, percent) }
        openUsageId = null
        openDischargingId?.let { closeSession(it, SESSION_DISCHARGING, percent) }
        openDischargingId = null

        scope.launch {
            openChargingId = repository.startSession(
                BatterySession(
                    sessionType = SESSION_CHARGING,
                    startPercent = percent,
                    startTimeMs = System.currentTimeMillis()
                )
            )
        }
    }

    /** Charger unplugged: close charging session and open a discharging session. */
    private fun onChargingStopped(percent: Int) {
        openChargingId?.let { closeSession(it, SESSION_CHARGING, percent) }
        openChargingId = null

        scope.launch {
            openDischargingId = repository.startSession(
                BatterySession(
                    sessionType = SESSION_DISCHARGING,
                    startPercent = percent,
                    startTimeMs = System.currentTimeMillis()
                )
            )
        }
    }

    // ──────────────────────────────────────────────────
    // Usage segment management (screen on/off during discharge)
    // ──────────────────────────────────────────────────

    /**
     * Screen turned ON while discharging → start a new USAGE segment.
     * If we are charging, screen events are ignored (no usage segments during charge).
     */
    private fun onScreenOn() {
        if (isCharging || openDischargingId == null) return  // only track during discharge

        scope.launch {
            openUsageId = repository.startSession(
                BatterySession(
                    sessionType = SESSION_USAGE,
                    startPercent = currentPercent,
                    startTimeMs = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Screen turned OFF → close the current USAGE segment (if any).
     */
    private fun onScreenOff() {
        val id = openUsageId ?: return
        closeSession(id, SESSION_USAGE, currentPercent)
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
            repository.closeSession(open)
        }
    }

    /** Re-link open sessions surviving a service restart. */
    private suspend fun recoverOpenSessions() {
        repository.getOpenSession(SESSION_CHARGING)?.let { openChargingId = it.id }
        repository.getOpenSession(SESSION_DISCHARGING)?.let { openDischargingId = it.id }
        repository.getOpenSession(SESSION_USAGE)?.let { openUsageId = it.id }
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
