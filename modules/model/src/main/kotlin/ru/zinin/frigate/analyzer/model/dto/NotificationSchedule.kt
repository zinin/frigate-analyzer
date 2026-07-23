package ru.zinin.frigate.analyzer.model.dto

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/**
 * Daily notification window over the half-open interval [start, end).
 * start > end means the window crosses midnight (e.g. 23:00-07:00).
 * start == end is not a valid window: [parse] returns null, direct construction throws.
 */
data class ScheduleWindow(
    val start: LocalTime,
    val end: LocalTime,
) {
    init {
        require(start != end) { "Window start must differ from end" }
    }

    /** Membership in [start, end) with midnight wrap when start > end. */
    fun contains(time: LocalTime): Boolean =
        if (start < end) {
            time >= start && time < end
        } else {
            time >= start || time < end
        }

    /** Storage form, e.g. "00:00-07:00". */
    fun storageFormat(): String = "${FORMATTER.format(start)}-${FORMATTER.format(end)}"

    /** Human form for dialogs, e.g. "00:00–07:00" (en dash). */
    fun displayFormat(): String = "${FORMATTER.format(start)}–${FORMATTER.format(end)}"

    companion object {
        private val FORMATTER = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)

        /** Parses the storage form; null on malformed input or start == end. */
        fun parse(raw: String): ScheduleWindow? {
            val parts = raw.split("-")
            if (parts.size != 2) return null
            val start = runCatching { LocalTime.parse(parts[0], FORMATTER) }.getOrNull() ?: return null
            val end = runCatching { LocalTime.parse(parts[1], FORMATTER) }.getOrNull() ?: return null
            if (start == end) return null
            return ScheduleWindow(start, end)
        }

        fun ofHours(
            startHour: Int,
            endHour: Int,
        ): ScheduleWindow = ScheduleWindow(LocalTime.of(startHour, 0), LocalTime.of(endHour, 0))
    }
}

/** Effective global schedule for recording-detection notifications. */
data class NotificationSchedule(
    val window: ScheduleWindow,
    val zone: ZoneId,
) {
    /** True when [instant], viewed in [zone], falls inside the window. */
    fun contains(instant: Instant): Boolean = window.contains(instant.atZone(zone).toLocalTime())
}
