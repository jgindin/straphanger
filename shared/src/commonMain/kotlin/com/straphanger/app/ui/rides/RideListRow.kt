package com.straphanger.app.ui.rides

import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.ui.countOf
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** One row of the Rides list, keyed so the list keeps each row's state as rides come and go. */
internal sealed interface RideListRow {
  val key: String

  data object ReviewBanner : RideListRow {
    override val key = "row_review_banner"
  }

  data class RouteFilter(val routeId: String) : RideListRow {
    override val key = "row_route_filter"
  }

  /** "12 rides", or "3 rides on the A line" when filtered. */
  data class Count(val text: String) : RideListRow {
    override val key = "row_count"
  }

  data class DayHeader(val heading: String, val dateKey: String) : RideListRow {
    override val key = "row_day_$dateKey"
  }

  /** A ride, keyed by its ride id. */
  data class Ride(val rec: CompleteRideRecord) : RideListRow {
    override val key = rec.ride.rideId
  }

  data object Empty : RideListRow {
    override val key = "row_empty"
  }
}

/**
 * The Rides list from top to bottom: the review banner when [hasRidesToReview], the line filter
 * when [routeFilter] is set, then how many rides are shown and the rides under a heading per day.
 * With no rides at all ([allRides] empty) the list says how to add one instead.
 */
internal fun rideListRows(
  allRides: List<CompleteRideRecord>,
  hasRidesToReview: Boolean,
  routeFilter: String?,
  today: LocalDate,
  zone: TimeZone,
): List<RideListRow> = buildList {
  if (hasRidesToReview) add(RideListRow.ReviewBanner)
  if (routeFilter != null) add(RideListRow.RouteFilter(routeFilter))
  if (allRides.isEmpty()) {
    add(RideListRow.Empty)
    return@buildList
  }
  val shown =
    if (routeFilter == null) allRides
    else allRides.filter { rec -> rec.legs.any { it.routeId == routeFilter } }
  val count = countOf(shown.size, "ride")
  add(RideListRow.Count(if (routeFilter == null) count else "$count on the $routeFilter line"))
  ridesByDay(shown, today, zone).forEach { day ->
    add(RideListRow.DayHeader(day.heading, day.date.toString()))
    day.rides.forEach { add(RideListRow.Ride(it)) }
  }
}
