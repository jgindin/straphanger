package com.straphanger.app.ui.rides

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

class RideDayTest {
  private val newYork = TimeZone.of("America/New_York")
  private val today = LocalDate(2026, 11, 24)

  private fun ms(iso: String) = Instant.parse(iso).toEpochMilliseconds()

  @Test
  fun todayAndYesterday() {
    assertEquals("Today", dayHeading(today, today))
    assertEquals("Yesterday", dayHeading(LocalDate(2026, 11, 23), today))
    assertEquals("Yesterday", dayHeading(LocalDate(2025, 12, 31), LocalDate(2026, 1, 1)))
  }

  @Test
  fun otherDaysThisYearShowTheWeekdayAndOtherYearsTheYear() {
    assertEquals("Wed, Nov 18", dayHeading(LocalDate(2026, 11, 18), today))
    assertEquals("Thu, Jan 1", dayHeading(LocalDate(2026, 1, 1), today))
    assertEquals("Nov 18, 2025", dayHeading(LocalDate(2025, 11, 18), today))
  }

  @Test
  fun ridesAreGroupedByTheirLocalStartDateNewestFirst() {
    val rides =
      listOf(
        // 11:30 PM on the 23rd in New York, though the 24th in UTC.
        rideOver("late", ms("2026-11-24T04:30:00Z"), Triple("1", "A", "B")),
        rideOver("morning", ms("2026-11-24T13:00:00Z"), Triple("1", "A", "B")),
        rideOver("noon", ms("2026-11-24T17:00:00Z"), Triple("1", "A", "B")),
        rideOver("old", ms("2025-03-02T17:00:00Z"), Triple("1", "A", "B")),
      )

    val days = ridesByDay(rides, today, newYork)

    assertEquals(listOf("Today", "Yesterday", "Mar 2, 2025"), days.map { it.heading })
    assertEquals(listOf("noon", "morning"), days[0].rides.map { it.ride.rideId })
    assertEquals(listOf("late"), days[1].rides.map { it.ride.rideId })
  }

  @Test
  fun theListCountsTheRidesShownAndSaysWhenThereAreNone() {
    val rides =
      listOf(
        rideOver("a", ms("2026-11-24T13:00:00Z"), Triple("A", "X", "Y")),
        rideOver("l", ms("2026-11-24T14:00:00Z"), Triple("L", "X", "Y")),
      )

    val all = rideListRows(rides, hasRidesToReview = true, routeFilter = null, today, newYork)
    assertEquals(RideListRow.ReviewBanner, all.first())
    assertEquals(RideListRow.Count("2 rides"), all[1])
    assertEquals(listOf("l", "a"), all.filterIsInstance<RideListRow.Ride>().map { it.key })

    val onA = rideListRows(rides, hasRidesToReview = false, routeFilter = "A", today, newYork)
    assertEquals(RideListRow.RouteFilter("A"), onA.first())
    assertEquals(RideListRow.Count("1 ride on the A line"), onA[1])

    assertEquals(
      listOf<RideListRow>(RideListRow.Empty),
      rideListRows(emptyList(), hasRidesToReview = false, routeFilter = null, today, newYork),
    )
  }
}
