package com.straphanger.app.ui.rides

import com.straphanger.app.data.gtfs.ServicePeriod
import com.straphanger.app.data.local.RideLegEntity
import com.straphanger.app.testutil.TestNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

class RideTextTest {
  private val newYork = TimeZone.of("America/New_York")

  private fun ms(iso: String) = Instant.parse(iso).toEpochMilliseconds()

  @Test
  fun showsTheDayAndTheStartAndEndTimes() {
    assertEquals(
      "Tue, Nov 17 • 6:31 PM – 7:11 PM",
      rideWhen(ms("2026-11-17T23:31:00Z"), ms("2026-11-18T00:11:00Z"), newYork),
    )
  }

  @Test
  fun midnightAndNoonReadAsTwelve() {
    assertEquals(
      "Wed, Nov 18 • 12:05 AM – 12:30 PM",
      rideWhen(ms("2026-11-18T05:05:00Z"), ms("2026-11-18T17:30:00Z"), newYork),
    )
  }

  @Test
  fun aRideAcrossMidnightNamesTheEndDay() {
    assertEquals(
      "Tue, Nov 17 • 11:50 PM – Wed, Nov 18 12:10 AM",
      rideWhen(ms("2026-11-18T04:50:00Z"), ms("2026-11-18T05:10:00Z"), newYork),
    )
  }

  @Test
  fun aLegNamesItsStationsAndWhetherItIsExpress() {
    val leg = RideLegEntity("l0", "r", 0, "A", true, ServicePeriod.RUSH_PEAK, "A31", "A15", 5.2)
    assertEquals(
      "Express • 14 St → 125 St • 5.2 mi",
      legDetail(leg, TestNetwork.engine()),
    )
  }
}
