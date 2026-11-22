package com.straphanger.app.data.commute

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

private const val SEARCH_DAYS = 8
private val WEEKDAYS =
  setOf(
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY,
  )

private fun Commute.instantOn(date: LocalDate, zone: TimeZone): Long? =
  if (date.dayOfWeek in days) {
    resolveLocal(LocalDateTime(date.year, date.month, date.day, hour, minute), zone)
  } else {
    null
  }

/**
 * [local] in [zone]. A time skipped by spring-forward resolves to the first valid instant (the
 * moment the clocks jump) rather than being shifted by the whole gap.
 */
private fun resolveLocal(local: LocalDateTime, zone: TimeZone): Long {
  val resolved = local.toInstant(zone)
  val shift = resolved.toLocalDateTime(zone).toInstant(TimeZone.UTC) - local.toInstant(TimeZone.UTC)
  var instant = resolved - shift
  val before = zone.offsetAt(instant)
  if (shift != Duration.ZERO) {
    while (zone.offsetAt(instant) == before) instant += 1.minutes
  } else {
    instant = resolved
  }
  return instant.toEpochMilliseconds()
}

private fun localDateOf(epochMs: Long, zone: TimeZone): LocalDate =
  Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).date

/** The first occurrence strictly after [afterEpochMs], or null when no day is selected. */
fun Commute.nextOccurrenceMs(afterEpochMs: Long, zone: TimeZone): Long? {
  val start = localDateOf(afterEpochMs, zone)
  return (0..SEARCH_DAYS).firstNotNullOfOrNull {
    instantOn(start.plus(DatePeriod(days = it)), zone)?.takeIf { at -> at > afterEpochMs }
  }
}

/** The latest occurrence at or before [epochMs], or null when no day is selected. */
fun Commute.occurrenceAtOrBeforeMs(epochMs: Long, zone: TimeZone): Long? {
  val start = localDateOf(epochMs, zone)
  return (0..SEARCH_DAYS).firstNotNullOfOrNull {
    instantOn(start.plus(DatePeriod(days = -it)), zone)?.takeIf { at -> at <= epochMs }
  }
}

/** "Weekdays", "Every day", or the days Monday-first as "Mon, Wed". */
fun daysText(days: Set<DayOfWeek>): String =
  when {
    days.size == DayOfWeek.entries.size -> "Every day"
    days == WEEKDAYS -> "Weekdays"
    else ->
      DayOfWeek.entries
        .filter { it in days }
        .joinToString(", ") { it.name.take(3).lowercase().replaceFirstChar(Char::uppercase) }
  }
