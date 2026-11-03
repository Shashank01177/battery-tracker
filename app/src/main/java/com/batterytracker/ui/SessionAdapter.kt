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
import com.batterytracker.databinding.ItemDayHeaderBinding
import com.batterytracker.databinding.ItemMonthHeaderBinding
import com.batterytracker.databinding.ItemSessionBinding
import com.batterytracker.service.BatteryMonitorService
import java.text.SimpleDateFormat
import java.util.*

/**
 * Hierarchical folder items: Month Folder -> Day Folder -> Session Card
 */
sealed class BatteryListItem {
    data class MonthHeader(
        val monthKey: String,
        val monthTitle: String,
        val totalCharges: Int,
        val totalUsageMs: Long,
        var isExpanded: Boolean
    ) : BatteryListItem()

    data class DayHeader(
        val monthKey: String,
        val dayKey: String,
        val dayTitle: String,
        val chargeCount: Int,
        val totalUsageMs: Long,
        var isExpanded: Boolean
    ) : BatteryListItem()

    data class SessionCard(
        val monthKey: String,
        val dayKey: String,
        val session: BatterySession
    ) : BatteryListItem()
}

/**
 * Heterogeneous adapter organizing battery logs into an expandable Month -> Day -> Session folder structure.
 */
class SessionAdapter :
    ListAdapter<BatteryListItem, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private const val VIEW_TYPE_MONTH = 0
        private const val VIEW_TYPE_DAY = 1
        private const val VIEW_TYPE_SESSION = 2

        private val TIME_FMT = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())
        private val MONTH_KEY_FMT = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        private val MONTH_TITLE_FMT = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        private val DAY_KEY_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<BatteryListItem>() {
            override fun areItemsTheSame(a: BatteryListItem, b: BatteryListItem): Boolean = when {
                a is BatteryListItem.MonthHeader && b is BatteryListItem.MonthHeader -> a.monthKey == b.monthKey
                a is BatteryListItem.DayHeader && b is BatteryListItem.DayHeader -> a.dayKey == b.dayKey
                a is BatteryListItem.SessionCard && b is BatteryListItem.SessionCard -> a.session.id == b.session.id
                else -> false
            }

            override fun areContentsTheSame(a: BatteryListItem, b: BatteryListItem): Boolean = a == b
        }
    }

    private var rawSessions: List<BatterySession> = emptyList()
    private val collapsedMonths = mutableSetOf<String>()
    private val collapsedDays = mutableSetOf<String>()

    /** Call this to update sessions and rebuild the folder tree. */
    fun setSessions(sessions: List<BatterySession>) {
        rawSessions = sessions
        rebuildAndSubmitList()
    }

    private fun rebuildAndSubmitList() {
        val items = mutableListOf<BatteryListItem>()

        // Group sessions by Month (yyyy-MM) preserving newest-first order
        val monthGroups = LinkedHashMap<String, MutableList<BatterySession>>()
        for (session in rawSessions) {
            val mKey = MONTH_KEY_FMT.format(Date(session.startTimeMs))
            monthGroups.getOrPut(mKey) { mutableListOf() }.add(session)
        }

        for ((mKey, monthSessions) in monthGroups) {
            val firstDate = Date(monthSessions.first().startTimeMs)
            val monthTitle = MONTH_TITLE_FMT.format(firstDate)

            val totalMonthCharges = monthSessions.count {
                it.sessionType == BatteryMonitorService.SESSION_CHARGING
            }
            val totalMonthUsageMs = monthSessions.filter {
                it.sessionType == BatteryMonitorService.SESSION_USAGE ||
                        it.sessionType == BatteryMonitorService.SESSION_USAGE_CHARGING
            }.sumOf { it.durationMs ?: (System.currentTimeMillis() - it.startTimeMs) }

            val isMonthExpanded = !collapsedMonths.contains(mKey)
            items.add(
                BatteryListItem.MonthHeader(
                    monthKey = mKey,
                    monthTitle = monthTitle,
                    totalCharges = totalMonthCharges,
                    totalUsageMs = totalMonthUsageMs,
                    isExpanded = isMonthExpanded
                )
            )

            if (isMonthExpanded) {
                // Group by Day (yyyy-MM-dd)
                val dayGroups = LinkedHashMap<String, MutableList<BatterySession>>()
                for (session in monthSessions) {
                    val dKey = DAY_KEY_FMT.format(Date(session.startTimeMs))
                    dayGroups.getOrPut(dKey) { mutableListOf() }.add(session)
                }

                for ((dKey, daySessions) in dayGroups) {
                    val dDate = Date(daySessions.first().startTimeMs)
                    val dayTitle = formatDayTitle(dDate)

                    val dayCharges = daySessions.count {
                        it.sessionType == BatteryMonitorService.SESSION_CHARGING
                    }
                    val dayUsageMs = daySessions.filter {
                        it.sessionType == BatteryMonitorService.SESSION_USAGE ||
                                it.sessionType == BatteryMonitorService.SESSION_USAGE_CHARGING
                    }.sumOf { it.durationMs ?: (System.currentTimeMillis() - it.startTimeMs) }

                    val isDayExpanded = !collapsedDays.contains(dKey)
                    items.add(
                        BatteryListItem.DayHeader(
                            monthKey = mKey,
                            dayKey = dKey,
                            dayTitle = dayTitle,
                            chargeCount = dayCharges,
                            totalUsageMs = dayUsageMs,
                            isExpanded = isDayExpanded
                        )
                    )

                    if (isDayExpanded) {
                        for (session in daySessions) {
                            items.add(
                                BatteryListItem.SessionCard(
                                    monthKey = mKey,
                                    dayKey = dKey,
                                    session = session
                                )
                            )
                        }
                    }
                }
            }
        }

        submitList(items)
    }

    private fun formatDayTitle(date: Date): String {
        val todayCal = Calendar.getInstance()
        val sessionCal = Calendar.getInstance().apply { time = date }

        val isToday = todayCal.get(Calendar.YEAR) == sessionCal.get(Calendar.YEAR) &&
                todayCal.get(Calendar.DAY_OF_YEAR) == sessionCal.get(Calendar.DAY_OF_YEAR)

        val yestCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val isYesterday = yestCal.get(Calendar.YEAR) == sessionCal.get(Calendar.YEAR) &&
                yestCal.get(Calendar.DAY_OF_YEAR) == sessionCal.get(Calendar.DAY_OF_YEAR)

        val df = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        return when {
            isToday -> "Today • ${df.format(date)}"
            isYesterday -> "Yesterday • ${df.format(date)}"
            else -> SimpleDateFormat("dd MMM yyyy (EEE)", Locale.getDefault()).format(date)
        }
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is BatteryListItem.MonthHeader -> VIEW_TYPE_MONTH
        is BatteryListItem.DayHeader -> VIEW_TYPE_DAY
        is BatteryListItem.SessionCard -> VIEW_TYPE_SESSION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_MONTH -> {
                val b = ItemMonthHeaderBinding.inflate(inflater, parent, false)
                MonthViewHolder(b)
            }
            VIEW_TYPE_DAY -> {
                val b = ItemDayHeaderBinding.inflate(inflater, parent, false)
                DayViewHolder(b)
            }
            else -> {
                val b = ItemSessionBinding.inflate(inflater, parent, false)
                SessionViewHolder(b)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is BatteryListItem.MonthHeader -> (holder as MonthViewHolder).bind(item)
            is BatteryListItem.DayHeader -> (holder as DayViewHolder).bind(item)
            is BatteryListItem.SessionCard -> (holder as SessionViewHolder).bind(item)
        }
    }

    inner class MonthViewHolder(private val b: ItemMonthHeaderBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(item: BatteryListItem.MonthHeader) {
            b.tvMonthTitle.text = item.monthTitle
            b.tvFolderIcon.text = if (item.isExpanded) "📂" else "📁"
            b.tvChevron.text = if (item.isExpanded) "▲" else "▼"

            val chargeTimes = if (item.totalCharges == 1) "1 time" else "${item.totalCharges} times"
            b.tvMonthStats.text = "⚡ Charged: $chargeTimes   •   📱 Used: ${formatDuration(item.totalUsageMs)}"

            b.root.setOnClickListener {
                if (collapsedMonths.contains(item.monthKey)) {
                    collapsedMonths.remove(item.monthKey)
                } else {
                    collapsedMonths.add(item.monthKey)
                }
                rebuildAndSubmitList()
            }
        }
    }

    inner class DayViewHolder(private val b: ItemDayHeaderBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(item: BatteryListItem.DayHeader) {
            b.tvDayTitle.text = item.dayTitle
            b.tvDayIcon.text = if (item.isExpanded) "📂" else "📁"
            b.tvChevron.text = if (item.isExpanded) "▲" else "▼"

            val chargeTimes = if (item.chargeCount == 1) "1 time" else "${item.chargeCount} times"
            b.tvDayStats.text = "⚡ Charged: $chargeTimes   •   📱 Used: ${formatDuration(item.totalUsageMs)}"

            b.root.setOnClickListener {
                if (collapsedDays.contains(item.dayKey)) {
                    collapsedDays.remove(item.dayKey)
                } else {
                    collapsedDays.add(item.dayKey)
                }
                rebuildAndSubmitList()
            }
        }
    }

    inner class SessionViewHolder(private val b: ItemSessionBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(item: BatteryListItem.SessionCard) {
            val session = item.session
            val ctx = b.root.context
            val isOngoing = session.endTimeMs == null

            // ── Type label, colour, and nesting indentation ──
            val (baseLabel, tintColor, isSubUsage) = when (session.sessionType) {
                BatteryMonitorService.SESSION_CHARGING ->
                    Triple("⚡ Charging", ContextCompat.getColor(ctx, R.color.charging_color), false)
                BatteryMonitorService.SESSION_DISCHARGING ->
                    Triple("🔋 Discharging", ContextCompat.getColor(ctx, R.color.discharging_color), false)
                BatteryMonitorService.SESSION_USAGE_CHARGING ->
                    Triple("📱⚡ Active Usage (Charging)", ContextCompat.getColor(ctx, R.color.usage_charging_color), true)
                else ->  // SESSION_USAGE
                    Triple("📱 Active Usage", ContextCompat.getColor(ctx, R.color.usage_color), true)
            }

            b.tvSessionType.text = if (isOngoing) "$baseLabel (Live)" else baseLabel
            b.tvSessionType.setTextColor(tintColor)
            b.cardView.strokeColor = tintColor

            // Indentation: Session sits indented under Day folder (16dp). Sub-logs sit indented further (32dp).
            val density = ctx.resources.displayMetrics.density
            val indentDp = if (isSubUsage) 32 else 16
            val marginPx = (indentDp * density).toInt()
            (b.root.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart = marginPx

            // ── Time range (with seconds) ──
            val startStr = TIME_FMT.format(Date(session.startTimeMs))
            val endStr = if (isOngoing) "Now (Live)" else TIME_FMT.format(Date(session.endTimeMs!!))
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
