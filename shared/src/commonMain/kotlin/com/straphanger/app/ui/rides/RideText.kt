package com.straphanger.app.ui.rides

import com.straphanger.app.data.gtfs.StopRole
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.data.local.RideLegEntity
import com.straphanger.app.ui.countOf
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** The station's name, or its ID when the network does not have it. */
internal fun SubwayGraphEngine.stationName(stationId: String): String =
  getStation(stationId)?.name ?: stationId

/** "Source: Manual • 5 stops (1 leg, 0 transfers)", flagged when the ride needs review. */
internal fun rideDetail(rec: CompleteRideRecord): String {
  val transferCount = rec.stationStops.count { it.stopRole == StopRole.TRANSFERRED }
  return "Source: ${rec.ride.source.displayLabel} • ${rec.stationStops.size} stops " +
    "(${countOf(rec.legs.size, "leg")}, ${countOf(transferCount, "transfer")})" +
    if (rec.ride.needsReview) " • NEEDS REVIEW" else ""
}

private const val NOON_HOUR = 12

/**
 * When the ride happened, like "Mon, Nov 17 • 6:31 PM – 7:11 PM", in [zone] (the device's by
 * default). The end shows its date too when the ride crossed midnight.
 */
internal fun rideWhen(
  startEpochMs: Long,
  endEpochMs: Long,
  zone: TimeZone = TimeZone.currentSystemDefault(),
): String {
  val start = Instant.fromEpochMilliseconds(startEpochMs).toLocalDateTime(zone)
  val end = Instant.fromEpochMilliseconds(endEpochMs).toLocalDateTime(zone)
  val endText = if (end.date == start.date) clockTime(end) else "${dayText(end)} ${clockTime(end)}"
  return "${dayText(start)} • ${clockTime(start)} – $endText"
}

private fun dayText(at: LocalDateTime): String {
  val weekday = at.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
  return "$weekday, ${monthAbbreviation(at.month)} ${at.day}"
}

private fun monthAbbreviation(month: Month): String =
  month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }

private fun clockTime(at: LocalDateTime): String {
  val hour = if (at.hour % NOON_HOUR == 0) NOON_HOUR else at.hour % NOON_HOUR
  val suffix = if (at.hour < NOON_HOUR) "AM" else "PM"
  return "$hour:${at.minute.toString().padStart(2, '0')} $suffix"
}

/** "Local • 14 St → 168 St • 6.8 mi" for one leg of a ride. */
internal fun legDetail(leg: RideLegEntity, graphEngine: SubwayGraphEngine): String =
  "${if (leg.isExpress) "Express" else "Local"} • " +
    "${graphEngine.stationName(leg.fromStationId)} → ${graphEngine.stationName(leg.toStationId)}" +
    " • ${leg.distanceMiles} mi"
