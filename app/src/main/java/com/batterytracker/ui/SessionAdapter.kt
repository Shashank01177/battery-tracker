package com.batterytracker.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.batterytracker.R
import com.batterytracker.data.db.BatterySession
import com.batterytracker.databinding.ItemSessionBinding
import com.batterytracker.service.BatteryMonitorService
import java.text.SimpleDateFormat
import java.util.*

/**
 * RecyclerView adapter that displays each battery session as a card with exact seconds.
 */
class SessionAdapter :
    ListAdapter<BatterySession, SessionAdapter.SessionViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val TIME_FMT = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())
        private val DATE_FMT = SimpleDateFormat("dd MMM, hh:mm:ss a", Locale.getDefault())

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<BatterySession>() {
            override fun areItemsTheSame(a: BatterySession, b: BatterySession) = a.id == b.id
            override fun areContentsTheSame(a: BatterySession, b: BatterySession) = a == b
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val binding = ItemSessionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return SessionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SessionViewHolder(private val b: ItemSessionBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(session: BatterySession) {
            val ctx = b.root.context
            val isOngoing = session.endTimeMs == null

            // ── Type label, colour, and indentation ──
            val (baseLabel, tintColor, indentDp) = when (session.sessionType) {
                BatteryMonitorService.SESSION_CHARGING ->
                    Triple("⚡ Charging", ContextCompat.getColor(ctx, R.color.charging_color), 0)
                BatteryMonitorService.SESSION_DISCHARGING ->
                    Triple("🔋 Discharging", ContextCompat.getColor(ctx, R.color.discharging_color), 0)
                BatteryMonitorService.SESSION_USAGE_CHARGING ->
                    Triple("📱⚡ Active Usage (Charging)", ContextCompat.getColor(ctx, R.color.usage_charging_color), 24)
                else ->  // SESSION_USAGE
                    Triple("📱 Active Usage", ContextCompat.getColor(ctx, R.color.usage_color), 24)
            }

            b.tvSessionType.text = if (isOngoing) "$baseLabel (Live)" else baseLabel
            b.tvSessionType.setTextColor(tintColor)
            b.cardView.strokeColor = tintColor

            // Indent sub-logs to visually nest under parent session
            val density = ctx.resources.displayMetrics.density
            val marginPx = (indentDp * density).toInt()
            (b.root.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart = marginPx

            // ── Time range (with seconds) ──
            val startDate = Date(session.startTimeMs)
            val todayCal = Calendar.getInstance()
            val sessionCal = Calendar.getInstance().apply { time = startDate }
            val sameDay = todayCal.get(Calendar.DAY_OF_YEAR) == sessionCal.get(Calendar.DAY_OF_YEAR) &&
                    todayCal.get(Calendar.YEAR) == sessionCal.get(Calendar.YEAR)

            val startStr = if (sameDay) TIME_FMT.format(startDate) else DATE_FMT.format(startDate)
            val endStr = if (isOngoing) {
                "Now (Live)"
            } else {
                val endDate = Date(session.endTimeMs!!)
                if (sameDay) TIME_FMT.format(endDate) else DATE_FMT.format(endDate)
            }
            b.tvTimeRange.text = "$startStr  →  $endStr"

            // ── Duration (with seconds) ──
            val currentDurationMs = if (isOngoing) {
                System.currentTimeMillis() - session.startTimeMs
            } else {
                session.durationMs ?: 0L
            }
            b.tvDuration.text = formatDuration(currentDurationMs)

            // ── % change ──
            val startPct = session.startPercent
            val endPct = session.endPercent ?: startPct
            val diff = session.percentChange ?: kotlin.math.abs(endPct - startPct)
            val isGaining = session.sessionType == BatteryMonitorService.SESSION_CHARGING ||
                    session.sessionType == BatteryMonitorService.SESSION_USAGE_CHARGING
            val sign = if (isGaining) "+" else "−"
            b.tvPercentChange.text = "$startPct%  →  $endPct%   ($sign$diff%)"

            // ── Kept plugged at 100% ──
            val overchargeMs = session.overchargeDurationMs
            if (session.sessionType == BatteryMonitorService.SESSION_CHARGING &&
                overchargeMs != null && overchargeMs > 0L) {
                b.tvOvercharge.visibility = View.VISIBLE
                b.tvOvercharge.text = "⚡ Kept plugged at 100%: ${formatDuration(overchargeMs)}"
            } else {
                b.tvOvercharge.visibility = View.GONE
            }
        }

        /** Formats milliseconds into a string with exact seconds, e.g. "1h 23m 45s", "10m 15s", or "42s". */
        private fun formatDuration(ms: Long): String {
            val totalSecs = ms / 1000
            val hours = totalSecs / 3600
            val mins = (totalSecs % 3600) / 60
            val secs = totalSecs % 60
            return when {
                hours > 0 -> "${hours}h ${mins}m ${secs}s"
                mins > 0 -> "${mins}m ${secs}s"
                else -> "${secs}s"
            }
        }
    }
}
