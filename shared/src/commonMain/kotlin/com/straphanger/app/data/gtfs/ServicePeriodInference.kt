package com.straphanger.app.data.gtfs

import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private val NEW_YORK = TimeZone.of("America/New_York")

/** Late night runs 23:45 to 05:30. */
private const val LATE_NIGHT_START_HOUR = 23
private const val LATE_NIGHT_START_MINUTE = 45
private const val LATE_NIGHT_END_HOUR = 5
private const val LATE_NIGHT_END_MINUTE = 30

private val EARLY_MORNING_HOURS = 0..4
private val MORNING_RUSH_HOURS = 7..9
private val EVENING_RUSH_HOURS = 16..19

/** The service pattern that runs at [timestampEpochMs], judged by New York local time. */
internal fun servicePeriodAt(timestampEpochMs: Long): ServicePeriod {
  val dt = Instant.fromEpochMilliseconds(timestampEpochMs).toLocalDateTime(NEW_YORK)
  return when {
    isLateNight(dt) -> ServicePeriod.LATE_NIGHT
    dt.dayOfWeek == DayOfWeek.SATURDAY || dt.dayOfWeek == DayOfWeek.SUNDAY -> ServicePeriod.WEEKEND
    dt.hour in MORNING_RUSH_HOURS || dt.hour in EVENING_RUSH_HOURS -> ServicePeriod.RUSH_PEAK
    else -> ServicePeriod.DAYTIME
  }
}

private fun isLateNight(dt: LocalDateTime): Boolean {
  val lateEvening = dt.hour == LATE_NIGHT_START_HOUR && dt.minute >= LATE_NIGHT_START_MINUTE
  val earlyMorning = dt.hour == LATE_NIGHT_END_HOUR && dt.minute < LATE_NIGHT_END_MINUTE
  return dt.hour in EARLY_MORNING_HOURS || lateEvening || earlyMorning
}
