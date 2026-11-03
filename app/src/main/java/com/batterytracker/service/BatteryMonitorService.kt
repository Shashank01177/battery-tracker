package com.batterytracker.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import com.batterytracker.data.db.AppDatabase
import com.batterytracker.data.db.BatterySession
import com.batterytracker.data.repository.BatteryRepository
import com.batterytracker.ui.MainActivity
import kotlinx.coroutines.*

/**
 * Foreground service that actively monitors battery and screen events.
 *
 * Session types:
 *  • CHARGING    – charger connected until unplugged (tracks extra time kept at 100%)
 *  • DISCHARGING – charger unplugged until plugged back in
 *  • USAGE       – screen-on period that falls inside a discharge period
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

    // Charger connection state
    private var isCharging = false

    // Flag for initial broadcast setup
    private var isFirstBatteryCheck = true

    // Latest known battery %
    private var currentPercent: Int = 0

    // ──────────────────────────────────────────────────
    // Receiver 1: battery level & plug status changes
    // ──────────────────────────────────────────────────
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0 || scale <= 0) return

            val percent = (level * 100 / scale)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

            val nowCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            currentPercent = percent

            if (isFirstBatteryCheck) {
                isFirstBatteryCheck = false
                isCharging = nowCharging
                ensureInitialSession(nowCharging, percent)
            } else {
                when {
                    nowCharging && !isCharging -> onChargingStarted(percent)
                    !nowCharging && isCharging -> onChargingStopped(percent)
                }
                isCharging = nowCharging
            }

            // Keep the active session in Room updated with latest percent and duration
            updateOngoingSessions(percent)

            if (nowCharging && isCharging && percent >= 100) {
                checkReached100()
            }

            updateNotification(percent, nowCharging)
        }
    }

    // ──────────────────────────────────────────────────
    // Receiver 2: screen on/off for active phone usage
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

        // Start foreground service safely across Android 8 through 14+
        val notification = buildNotification("Monitoring battery…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Screen state receiver
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, screenFilter)

        // Battery state receiver
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        // Re-link open sessions from previous runs
        scope.launch { recoverOpenSessions() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ──────────────────────────────────────────────────
    // Session Initialization & Management
    // ──────────────────────────────────────────────────

    /** Ensures that an active session is running as soon as the app starts. */
    private fun ensureInitialSession(charging: Boolean, percent: Int) {
        scope.launch {
            val targetType = if (charging) SESSION_CHARGING else SESSION_DISCHARGING
            val existing = repository.getOpenSession(targetType)

            if (existing != null) {
                if (charging) openChargingId = existing.id else openDischargingId = existing.id
            } else {
                val now = System.currentTimeMillis()
                val session = BatterySession(
                    sessionType = targetType,
                    startPercent = percent,
                    endPercent = percent,
                    startTimeMs = now,
                    percentChange = 0,
                    reached100TimeMs = if (charging && percent >= 100) now else null
                )
                val id = repository.startSession(session)
                if (charging) openChargingId = id else openDischargingId = id
            }
        }
    }

    /** Charger plugged in: close discharging and active usage, open charging session. */
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
                    percentChange = 0,
                    reached100TimeMs = reached100
                )
            )
        }
    }

    /** Charger unplugged: close charging session and open discharging session. */
    private fun onChargingStopped(percent: Int) {
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
                    percentChange = 0
                )
            )
        }
    }

    /** Screen turned ON: start active usage log if on battery. */
    private fun onScreenOn() {
        if (isCharging || openUsageId != null) return

        val now = System.currentTimeMillis()
        scope.launch {
            openUsageId = repository.startSession(
                BatterySession(
                    sessionType = SESSION_USAGE,
                    startPercent = currentPercent,
                    endPercent = currentPercent,
                    startTimeMs = now,
                    percentChange = 0
                )
            )
        }
    }

    /** Screen turned OFF: complete active usage log. */
    private fun onScreenOff() {
        val id = openUsageId ?: return
        closeSession(id, SESSION_USAGE, currentPercent)
        openUsageId = null
    }

    /** Keep ongoing sessions up to date in DB so user sees real-time battery changes in UI. */
    private fun updateOngoingSessions(percent: Int) {
        val now = System.currentTimeMillis()
        scope.launch {
            val activeType = if (isCharging) SESSION_CHARGING else SESSION_DISCHARGING
            repository.getOpenSession(activeType)?.let { session ->
                session.endPercent = percent
                session.percentChange = kotlin.math.abs(percent - session.startPercent)
                session.durationMs = now - session.startTimeMs
                repository.updateSession(session)
            }

            openUsageId?.let { usageId ->
                repository.getOpenSession(SESSION_USAGE)?.let { usageSession ->
                    if (usageSession.id == usageId) {
                        usageSession.endPercent = percent
                        usageSession.percentChange = kotlin.math.abs(percent - usageSession.startPercent)
                        usageSession.durationMs = now - usageSession.startTimeMs
                        repository.updateSession(usageSession)
                    }
                }
            }
        }
    }

    /** Check and record 100% full charge timestamp. */
    private fun checkReached100() {
        scope.launch {
            val open = repository.getOpenSession(SESSION_CHARGING) ?: return@launch
            if (open.reached100TimeMs == null) {
                open.reached100TimeMs = System.currentTimeMillis()
                repository.updateSession(open)
            }
        }
    }

    /** Completes a session: writes final endTime, duration, percentChange, and overcharge. */
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

            repository.updateSession(open)
        }
    }

    /** Re-link open sessions on service restart. */
    private suspend fun recoverOpenSessions() {
        repository.getOpenSession(SESSION_CHARGING)?.let { openChargingId = it.id }
        repository.getOpenSession(SESSION_DISCHARGING)?.let { openDischargingId = it.id }
        repository.getOpenSession(SESSION_USAGE)?.let { openUsageId = it.id }
        repository.pruneOld()
    }

    // ──────────────────────────────────────────────────
    // Notification
    // ──────────────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Battery Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Tracks charging, discharging & screen usage" }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
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
            .setSmallIcon(com.batterytracker.R.drawable.ic_battery_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(percent: Int, charging: Boolean) {
        val status = if (charging) "⚡ Charging" else "🔋 On battery"
        val notification = buildNotification("$status • $percent%")
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }
}
