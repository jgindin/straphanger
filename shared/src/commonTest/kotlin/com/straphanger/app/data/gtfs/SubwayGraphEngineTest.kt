package com.straphanger.app.data.gtfs

import com.straphanger.app.testutil.TestNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubwayGraphEngineTest {

  private val engine = TestNetwork.engine()

  // 2026-11-09 14:00:00 EST (19:00 UTC) -> Weekday Daytime
  private val daytimeEpochMs = 1794250800000L

  // 2026-11-09 01:30:00 EST (06:30 UTC) -> Late Night
  private val lateNightEpochMs = 1794205800000L

  @Test
  fun expressSkipLocalStopsWhilePreservingPhysicalCorridorMiles() {
    val expressTrip =
      engine.resolveTrip(
        legs =
          listOf(
            TripLegRequest(
              fromStationId = "635",
              toStationId = "626",
              preferredRouteId = "4",
            ),
          ),
        timestampEpochMs = daytimeEpochMs,
      )

    val localTrip =
      engine.resolveTrip(
        legs =
          listOf(
            TripLegRequest(
              fromStationId = "635",
              toStationId = "626",
              preferredRouteId = "6",
            ),
          ),
        timestampEpochMs = daytimeEpochMs,
      )

    val expLeg = expressTrip.legs.single()
    val locLeg = localTrip.legs.single()

    assertTrue(expLeg.isExpress, "4 train during daytime should run Express")
    assertFalse(locLeg.isExpress, "6 train should always run Local")

    // Physical corridor segments and mileage must match identically
    assertEquals(locLeg.segmentIds, expLeg.segmentIds)
    assertEquals(locLeg.distanceMiles, expLeg.distanceMiles)

    // 4 Express stops only at 14 St, Grand Central-42 St, 59 St, 86 St (its GTFS DAYTIME pattern)
    assertEquals(
      listOf("635", "631", "629", "626"),
      expLeg.stoppedStationIds,
    )
    assertEquals(
      listOf("634", "633", "632", "630", "628", "627"),
      expLeg.skippedLocalStationIds,
    )

    // 6 Local stops at all 10 stations
    assertEquals(10, locLeg.stoppedStationIds.size)
    assertTrue(locLeg.skippedLocalStationIds.isEmpty())
  }

  @Test
  fun lateNightATrainAutomaticallySwitchesFromExpressToLocal() {
    val daytimeATrip =
      engine.resolveTrip(
        legs =
          listOf(
            TripLegRequest(
              fromStationId = "A31",
              toStationId = "A15",
              preferredRouteId = "A",
            ),
          ),
        timestampEpochMs = daytimeEpochMs,
      )

    val lateNightATrip =
      engine.resolveTrip(
        legs =
          listOf(
            TripLegRequest(
              fromStationId = "A31",
              toStationId = "A15",
              preferredRouteId = "A",
            ),
          ),
        timestampEpochMs = lateNightEpochMs,
      )

    val dayLeg = daytimeATrip.legs.single()
    val nightLeg = lateNightATrip.legs.single()

    assertTrue(dayLeg.isExpress, "Daytime A train from 14 St to 125 St runs Express")
    assertEquals(listOf("A31", "A28", "A27", "A24", "A15"), dayLeg.stoppedStationIds)
    assertEquals(9, dayLeg.skippedLocalStationIds.size)

    assertEquals(ServicePeriod.LATE_NIGHT, nightLeg.servicePeriod)
    assertFalse(nightLeg.isExpress, "Late night A train (1:30 AM) runs Local in Manhattan")
    assertEquals(14, nightLeg.stoppedStationIds.size)
    assertTrue(nightLeg.skippedLocalStationIds.isEmpty())
  }

  @Test
  fun autoTransferSplitsCrossTrunkTripIntoMultipleLegs() {
    // Bedford Av (L) in Brooklyn -> 86 St (4/6) in Manhattan via the 14 St-Union Sq complex (602)
    val transferTrip =
      engine.resolveOriginToDestinationAuto(
        originStationId = "L08",
        destinationStationId = "626",
        timestampEpochMs = daytimeEpochMs,
      )

    assertEquals(2, transferTrip.legs.size, "Cross-trunk trip should split into 2 legs")
    assertEquals("L", transferTrip.legs[0].routeId)
    assertEquals("L08", transferTrip.legs[0].fromStationId)
    assertEquals("L03", transferTrip.legs[0].toStationId)

    assertEquals("4", transferTrip.legs[1].routeId)
    assertEquals("635", transferTrip.legs[1].fromStationId)
    assertEquals("626", transferTrip.legs[1].toStationId)

    // Verify BOARDED, TRANSFERRED, EXITED stop roles
    assertEquals(StopRole.BOARDED, transferTrip.stationStops.first().role)
    assertEquals(StopRole.EXITED, transferTrip.stationStops.last().role)
    assertTrue(
      transferTrip.stationStops.any { it.complexId == "602" && it.role == StopRole.TRANSFERRED },
    )
  }

  @Test
  fun passiveAmbiguousRideDetectsExpressVsLocalAndUsesDuration() {
    val fastPassiveTrip =
      engine.resolveOriginToDestinationAuto(
        originStationId = "635",
        destinationStationId = "626",
        preferredRouteId = null,
        timestampEpochMs = daytimeEpochMs,
        actualDurationMinutes = 9.5,
      )

    assertTrue(fastPassiveTrip.isAmbiguous)
    assertEquals(2, fastPassiveTrip.ambiguousCandidates.size)
    assertTrue(
      fastPassiveTrip.legs.single().isExpress,
      "Fast 9.5 min duration should match 4 Express",
    )

    val slowPassiveTrip =
      engine.resolveOriginToDestinationAuto(
        originStationId = "635",
        destinationStationId = "626",
        preferredRouteId = null,
        timestampEpochMs = daytimeEpochMs,
        actualDurationMinutes = 19.0,
      )

    assertTrue(slowPassiveTrip.isAmbiguous)
    assertFalse(
      slowPassiveTrip.legs.single().isExpress,
      "Slow 19 min duration should match 6 Local",
    )
    assertEquals("6", slowPassiveTrip.legs.single().routeId)
  }

  @Test
  fun routeWithoutPatternForPeriodFallsBackToDaytime() {
    // 2026-11-09 08:30 EST is RUSH_PEAK; the 4 has no RUSH_PEAK pattern, so its DAYTIME one
    // applies.
    val rushEpochMs = 1794231000000L
    assertEquals(ServicePeriod.RUSH_PEAK, engine.inferServicePeriod(rushEpochMs))
    val leg =
      engine
        .resolveTrip(
          legs = listOf(TripLegRequest("635", "626", preferredRouteId = "4")),
          timestampEpochMs = rushEpochMs,
        )
        .legs
        .single()
    assertTrue(leg.isExpress)
    assertEquals(listOf("635", "631", "629", "626"), leg.stoppedStationIds)
  }

  @Test
  fun unknownStationIdsResolveToNoLegs() {
    val result =
      engine.resolveOriginToDestinationAuto(
        originStationId = "LEX_14_USQ",
        destinationStationId = "LEX_86",
        timestampEpochMs = daytimeEpochMs,
      )
    assertTrue(result.legs.isEmpty())
  }
}
