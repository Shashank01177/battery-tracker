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
import java.util.concurrent.TimeUnit

/**
 * RecyclerView adapter that displays each battery session (both in-progress and completed).
 */
class SessionAdapter :
    ListAdapter<BatterySession, SessionAdapter.SessionViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val TIME_FMT = SimpleDateFormat("hh:mm a", Locale.getDefault())
        private val DATE_FMT = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())

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
            val (label, tintColor, indentDp) = when (session.sessionType) {
                BatteryMonitorService.SESSION_CHARGING    ->
                    Triple(
                        if (isOngoing) "⚡  Charging (Active)" else "⚡  Charging",
                        ContextCompat.getColor(ctx, R.color.charging_color),
                        0
                    )
                BatteryMonitorService.SESSION_DISCHARGING ->
                    Triple(
                        if (isOngoing) "🔋  On Battery (Active)" else "🔋  Discharging",
                        ContextCompat.getColor(ctx, R.color.discharging_color),
                        0
                    )
                else ->  // USAGE
                    Triple(
                        if (isOngoing) "📱  Screen On (Active)" else "📱  Screen On",
                        ContextCompat.getColor(ctx, R.color.usage_color),
                        24
                    )
            }

            b.tvSessionType.text = label
            b.tvSessionType.setTextColor(tintColor)
            b.cardView.strokeColor = tintColor

            // Indent screen usage cards
            val density = ctx.resources.displayMetrics.density
            val marginPx = (indentDp * density).toInt()
            (b.root.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart = marginPx

            // ── Time range ──
            val startDate = Date(session.startTimeMs)
            val todayCal   = Calendar.getInstance()
            val sessionCal = Calendar.getInstance().apply { time = startDate }
            val sameDay    = todayCal.get(Calendar.DAY_OF_YEAR) == sessionCal.get(Calendar.DAY_OF_YEAR) &&
                             todayCal.get(Calendar.YEAR)        == sessionCal.get(Calendar.YEAR)

            val startStr = if (sameDay) TIME_FMT.format(startDate) else DATE_FMT.format(startDate)
            val endStr = if (isOngoing) {
                "Now (Tracking)"
            } else {
                val endDate = Date(session.endTimeMs!!)
                if (sameDay) TIME_FMT.format(endDate) else DATE_FMT.format(endDate)
            }
            b.tvTimeRange.text = "$startStr  →  $endStr"

            // ── Duration ──
            val now = System.currentTimeMillis()
            val durationMs = if (isOngoing) (now - session.startTimeMs) else (session.durationMs ?: 0L)
            b.tvDuration.text = formatDuration(durationMs)

            // ── % change ──
            val startPct = session.startPercent
            val endPct   = session.endPercent ?: startPct
            val diff     = kotlin.math.abs(endPct - startPct)
            val sign     = if (session.sessionType == BatteryMonitorService.SESSION_CHARGING) "+" else "−"
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

        private fun formatDuration(ms: Long): String {
            val hours = TimeUnit.MILLISECONDS.toHours(ms)
            val mins  = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
            val secs  = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
            return when {
                hours > 0 -> "${hours}h ${mins}m"
                mins  > 0 -> "${mins} min${if (mins > 1) "s" else ""}"
                else      -> "${secs} sec${if (secs > 1) "s" else ""}"
            }
        }
    }
}
