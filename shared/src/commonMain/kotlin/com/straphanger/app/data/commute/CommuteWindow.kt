package com.straphanger.app.data.commute

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus

/** True when the commute has an end time, so it shows on a widget between its start and end. */
val Commute.hasWindow: Boolean
  get() = endHour != null && endMinute != null

/**
 * The window containing [epochMs] as `startMs until endMs`, or null when it is outside one or the
 * commute has no end time. It starts at the latest occurrence and ends at the end time on the same
 * date, or the next date when the end is at or before the start (equal is 24 hours).
 */
fun Commute.windowAt(epochMs: Long, zone: TimeZone): LongRange? {
  val endH = endHour
  val endM = endMinute
  val start = occurrenceAtOrBeforeMs(epochMs, zone)
  if (endH == null || endM == null || start == null) return null
  val startDate = localDateOf(start, zone)
  val endsNextDay = endH * MINUTES_PER_HOUR + endM <= hour * MINUTES_PER_HOUR + minute
  val endDate = if (endsNextDay) startDate.plus(DatePeriod(days = 1)) else startDate
  val end = resolveLocal(LocalDateTime(endDate.year, endDate.month, endDate.day, endH, endM), zone)
  return if (epochMs < end) start until end else null
}

/** The first window start strictly after [afterEpochMs], or null without an end time. */
fun Commute.nextWindowStartMs(afterEpochMs: Long, zone: TimeZone): Long? =
  if (hasWindow) nextOccurrenceMs(afterEpochMs, zone) else null

/**
 * The time as "7:45" or "17:30" when [use24Hour], else as "7:45 AM" or "5:30 PM", with noon "12:00
 * PM" and midnight "12:00 AM".
 */
fun commuteTimeText(hour: Int, minute: Int, use24Hour: Boolean): String {
  val minutes = minute.toString().padStart(2, '0')
  if (use24Hour) return "$hour:$minutes"
  val twelveHour = (hour + HOURS_PER_HALF_DAY - 1) % HOURS_PER_HALF_DAY + 1
  return "$twelveHour:$minutes ${if (hour < HOURS_PER_HALF_DAY) "AM" else "PM"}"
}

private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_HALF_DAY = 12
