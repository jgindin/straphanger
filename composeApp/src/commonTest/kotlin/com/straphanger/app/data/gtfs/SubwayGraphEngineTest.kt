package com.straphanger.app.data.gtfs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubwayGraphEngineTest {

    private val engine = SubwayGraphEngine()

    // 2026-11-09 14:00:00 EST (19:00 UTC) -> Weekday Daytime
    private val daytimeEpochMs = 1794250800000L

    // 2026-11-09 01:30:00 EST (06:30 UTC) -> Late Night
    private val lateNightEpochMs = 1794205800000L

    @Test
    fun expressSkipLocalStopsWhilePreservingPhysicalCorridorMiles() {
        val expressTrip = engine.resolveTrip(
            legs = listOf(
                TripLegRequest(
                    fromStationId = "LEX_14_USQ",
                    toStationId = "LEX_86",
                    preferredRouteId = "4"
                )
            ),
            timestampEpochMs = daytimeEpochMs
        )

        val localTrip = engine.resolveTrip(
            legs = listOf(
                TripLegRequest(
                    fromStationId = "LEX_14_USQ",
                    toStationId = "LEX_86",
                    preferredRouteId = "6"
                )
            ),
            timestampEpochMs = daytimeEpochMs
        )

        val expLeg = expressTrip.legs.single()
        val locLeg = localTrip.legs.single()

        assertTrue(expLeg.isExpress, "4 train during daytime should run Express")
        assertFalse(locLeg.isExpress, "6 train should always run Local")

        // Physical corridor segments and mileage must match identically
        assertEquals(locLeg.segmentIds, expLeg.segmentIds)
        assertEquals(locLeg.distanceMiles, expLeg.distanceMiles)

        // 4 Express stops only at 14 St, 42 St-GCT, 59 St, 86 St
        assertEquals(
            listOf("LEX_14_USQ", "LEX_42_GCT", "LEX_59", "LEX_86"),
            expLeg.stoppedStationIds
        )
        assertEquals(
            listOf("LEX_23", "LEX_28", "LEX_33", "LEX_51", "LEX_68", "LEX_77"),
            expLeg.skippedLocalStationIds
        )

        // 6 Local stops at all 10 stations
        assertEquals(10, locLeg.stoppedStationIds.size)
        assertTrue(locLeg.skippedLocalStationIds.isEmpty())
    }

    @Test
    fun lateNightATrainAutomaticallySwitchesFromExpressToLocal() {
        val daytimeATrip = engine.resolveTrip(
            legs = listOf(
                TripLegRequest(
                    fromStationId = "IND8_59_COL",
                    toStationId = "IND8_125",
                    preferredRouteId = "A"
                )
            ),
            timestampEpochMs = daytimeEpochMs
        )

        val lateNightATrip = engine.resolveTrip(
            legs = listOf(
                TripLegRequest(
                    fromStationId = "IND8_59_COL",
                    toStationId = "IND8_125",
                    preferredRouteId = "A"
                )
            ),
            timestampEpochMs = lateNightEpochMs
        )

        val dayLeg = daytimeATrip.legs.single()
        val nightLeg = lateNightATrip.legs.single()

        assertTrue(dayLeg.isExpress, "Daytime A train from 59 St to 125 St runs Express")
        assertEquals(listOf("IND8_59_COL", "IND8_125"), dayLeg.stoppedStationIds)
        assertEquals(6, dayLeg.skippedLocalStationIds.size)

        assertEquals(ServicePeriod.LATE_NIGHT, nightLeg.servicePeriod)
        assertFalse(nightLeg.isExpress, "Late night A train (1:30 AM) runs Local in Manhattan")
        assertEquals(8, nightLeg.stoppedStationIds.size)
        assertTrue(nightLeg.skippedLocalStationIds.isEmpty())
    }

    @Test
    fun autoTransferSplitsCrossTrunkTripIntoMultipleLegs() {
        // Bedford Ave (L) in Brooklyn -> 86 St (4/5/6) in Manhattan via 14 St-Union Sq complex
        val transferTrip = engine.resolveOriginToDestinationAuto(
            originStationId = "L_BEDFORD",
            destinationStationId = "LEX_86",
            timestampEpochMs = daytimeEpochMs
        )

        assertEquals(2, transferTrip.legs.size, "Cross-trunk trip should split into 2 legs")
        assertEquals("L", transferTrip.legs[0].routeId)
        assertEquals("L_BEDFORD", transferTrip.legs[0].fromStationId)
        assertEquals("L_14_USQ", transferTrip.legs[0].toStationId)

        assertEquals("4", transferTrip.legs[1].routeId)
        assertEquals("LEX_14_USQ", transferTrip.legs[1].fromStationId)
        assertEquals("LEX_86", transferTrip.legs[1].toStationId)

        // Verify BOARDED, TRANSFERRED, EXITED stop roles
        assertEquals(StopRole.BOARDED, transferTrip.stationStops.first().role)
        assertEquals(StopRole.EXITED, transferTrip.stationStops.last().role)
        assertTrue(transferTrip.stationStops.any { it.complexId == "CPLX_UNION_SQ" && it.role == StopRole.TRANSFERRED })
    }

    @Test
    fun passiveAmbiguousRideDetectsExpressVsLocalAndUsesDuration() {
        val fastPassiveTrip = engine.resolveOriginToDestinationAuto(
            originStationId = "LEX_14_USQ",
            destinationStationId = "LEX_86",
            preferredRouteId = null,
            timestampEpochMs = daytimeEpochMs,
            actualDurationMinutes = 9.5
        )

        assertTrue(fastPassiveTrip.isAmbiguous)
        assertEquals(2, fastPassiveTrip.ambiguousCandidates.size)
        assertTrue(fastPassiveTrip.legs.single().isExpress, "Fast 9.5 min duration should match 4 Express")

        val slowPassiveTrip = engine.resolveOriginToDestinationAuto(
            originStationId = "LEX_14_USQ",
            destinationStationId = "LEX_86",
            preferredRouteId = null,
            timestampEpochMs = daytimeEpochMs,
            actualDurationMinutes = 19.0
        )

        assertTrue(slowPassiveTrip.isAmbiguous)
        assertFalse(slowPassiveTrip.legs.single().isExpress, "Slow 19 min duration should match 6 Local")
        assertEquals("6", slowPassiveTrip.legs.single().routeId)
    }
}
