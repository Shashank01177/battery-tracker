package com.batterytracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import com.batterytracker.service.BatteryMonitorService

/**
 * Static Manifest receiver that listens for hardware power events (charger connected/disconnected),
 * device boot, and app updates. Wakes up the app and starts the monitor service.
 */
class PowerConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Acquire a 5-second partial wakelock so CPU doesn't sleep before the service starts
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BatteryTracker:PowerReceiver")
        wakeLock?.acquire(5000L)

        val serviceIntent = Intent(context, BatteryMonitorService::class.java).apply {
            action = intent.action
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
