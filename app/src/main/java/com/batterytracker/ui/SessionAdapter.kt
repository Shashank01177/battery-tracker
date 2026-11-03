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
 * RecyclerView adapter that displays each battery session as a card.
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

            // ── Type label, colour, and indentation ──
            val (label, tintColor, indentDp) = when (session.sessionType) {
                BatteryMonitorService.SESSION_CHARGING    ->
                    Triple("⚡  Charging",      ContextCompat.getColor(ctx, R.color.charging_color),    0)
                BatteryMonitorService.SESSION_DISCHARGING ->
                    Triple("🔋  Discharging",   ContextCompat.getColor(ctx, R.color.discharging_color), 0)
                else ->  // USAGE
                    Triple("📱  Active Usage",  ContextCompat.getColor(ctx, R.color.usage_color),       24)
            }

            b.tvSessionType.text = label
            b.tvSessionType.setTextColor(tintColor)
            b.cardView.strokeColor = tintColor

            // Indent USAGE cards to visually nest them under the discharge entry
            val density = ctx.resources.displayMetrics.density
            val marginPx = (indentDp * density).toInt()
            (b.root.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart = marginPx

            // ── Time range ──
            val startDate = Date(session.startTimeMs)
            val endDate   = session.endTimeMs?.let { Date(it) }

            val todayCal   = Calendar.getInstance()
            val sessionCal = Calendar.getInstance().apply { time = startDate }
            val sameDay    = todayCal.get(Calendar.DAY_OF_YEAR) == sessionCal.get(Calendar.DAY_OF_YEAR) &&
                             todayCal.get(Calendar.YEAR)        == sessionCal.get(Calendar.YEAR)

            val startStr = if (sameDay) TIME_FMT.format(startDate) else DATE_FMT.format(startDate)
            val endStr   = endDate?.let { if (sameDay) TIME_FMT.format(it) else DATE_FMT.format(it) } ?: "—"
            b.tvTimeRange.text = "$startStr  →  $endStr"

            // ── Duration ──
            b.tvDuration.text = formatDuration(session.durationMs ?: 0L)

            // ── % change ──
            val startPct = session.startPercent
            val endPct   = session.endPercent ?: startPct
            val diff     = session.percentChange ?: 0
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

        /** Formats milliseconds into a human-readable string, e.g. "1h 23m" or "45 mins". */
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
