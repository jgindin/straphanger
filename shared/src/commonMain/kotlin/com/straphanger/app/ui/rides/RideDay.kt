package com.straphanger.app.ui.rides

import com.straphanger.app.data.local.CompleteRideRecord
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/** The rides that started on one local [date], newest first, under their [heading]. */
internal data class RideDay(
  val date: LocalDate,
  val heading: String,
  val rides: List<CompleteRideRecord>,
)

/**
 * [rides] grouped by the local date each started on in [zone], newest day first and newest ride
 * first within a day, each day headed as [dayHeading] says relative to [today].
 */
internal fun ridesByDay(
  rides: List<CompleteRideRecord>,
  today: LocalDate,
  zone: TimeZone,
): List<RideDay> =
  rides
    .sortedByDescending { it.ride.startTimeEpochMs }
    .groupBy { Instant.fromEpochMilliseconds(it.ride.startTimeEpochMs).toLocalDateTime(zone).date }
    .map { (date, onDay) -> RideDay(date, dayHeading(date, today), onDay) }

/**
 * "Today", "Yesterday", "Mon, Nov 18" for another day of [today]'s year, or "Nov 18, 2025" for a
 * day in another year.
 */
internal fun dayHeading(date: LocalDate, today: LocalDate): String {
  val month = date.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
  return when {
    date == today -> "Today"
    date == today.minus(DatePeriod(days = 1)) -> "Yesterday"
    date.year == today.year -> {
      val weekday = date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
      "$weekday, $month ${date.day}"
    }
    else -> "$month ${date.day}, ${date.year}"
  }
}
