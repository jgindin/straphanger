package com.straphanger.app.domain.stats

import com.straphanger.app.data.gtfs.TripLegRequest
import com.straphanger.app.data.local.RideSource
import com.straphanger.app.data.local.SubwayDao
import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.data.local.RideEntity
import com.straphanger.app.data.local.RideSegmentEntity
import com.straphanger.app.data.local.RideStationStopEntity
import com.straphanger.app.data.gtfs.StopRole
import com.straphanger.app.testutil.TestNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubwayStatsCalculatorTest {

    private val engine = TestNetwork.engine()
    private val calculator = SubwayStatsCalculator(engine)
    private val daytimeEpochMs = 1794250800000L

    @Test
    fun sharedTrunkRidesCountTotalMilesTwiceButUniqueSystemMilesOnceWhileCreditingBothLines() {
        val rideA = SubwayDao.buildCompleteRideRecord(
            rideId = "ride_a_8ave",
            startTimeEpochMs = daytimeEpochMs,
            endTimeEpochMs = daytimeEpochMs + 10 * 60_000L,
            source = RideSource.MANUAL,
            resolved = engine.resolveTrip(
                legs = listOf(TripLegRequest("A31", "A24", preferredRouteId = "A")),
                timestampEpochMs = daytimeEpochMs
            ),
            graphEngine = engine
        )

        val rideC = SubwayDao.buildCompleteRideRecord(
            rideId = "ride_c_8ave",
            startTimeEpochMs = daytimeEpochMs + 3600_000L,
            endTimeEpochMs = daytimeEpochMs + 3600_000L + 15 * 60_000L,
            source = RideSource.MANUAL,
            resolved = engine.resolveTrip(
                legs = listOf(TripLegRequest("A31", "A24", preferredRouteId = "C")),
                timestampEpochMs = daytimeEpochMs
            ),
            graphEngine = engine
        )

        val stats = calculator.computeStats(listOf(rideA, rideC))

        assertEquals(2, stats.totalRides)
        val singleStretchMiles = rideA.ride.totalDistanceMiles
        assertEquals(singleStretchMiles * 2.0, stats.totalMilesRidden, 0.02)
        assertEquals(
            singleStretchMiles,
            stats.uniqueCorridorMilesRidden,
            0.02,
            "Shared 8th Ave trunk must only count once toward Unique System Miles"
        )

        val aStat = stats.lineCompletionStats.first { it.routeInfo.routeId == "A" }
        val cStat = stats.lineCompletionStats.first { it.routeInfo.routeId == "C" }
        assertEquals(1, aStat.legRideCount)
        assertEquals(1, cStat.legRideCount)
        assertEquals(singleStretchMiles, aStat.uniqueCorridorMilesRidden, 0.02)
        assertEquals(singleStretchMiles, cStat.uniqueCorridorMilesRidden, 0.02)
    }

    @Test
    fun stationTogglesSeparateStationsVsComplexesAndStoppedAtVsBoardedExited() {
        // Bedford Ave (L) -> 14 St-Union Sq (transfer) -> 86 St (4 Express)
        val resolved = engine.resolveOriginToDestinationAuto(
            originStationId = "L08",
            destinationStationId = "626",
            timestampEpochMs = daytimeEpochMs
        )
        val transferRide = SubwayDao.buildCompleteRideRecord(
            rideId = "ride_transfer_stats",
            startTimeEpochMs = daytimeEpochMs,
            endTimeEpochMs = daytimeEpochMs + 22 * 60_000L,
            source = RideSource.MANUAL,
            resolved = resolved,
            graphEngine = engine
        )

        val stats = calculator.computeStats(listOf(transferRide))

        assertEquals(1, stats.totalRides, "2-leg transfer must count as 1 Total Ride")
        assertEquals(TestNetwork.data.stationCount, stats.getDisplayedStationTotal(StationGroupingMode.INDIVIDUAL_STATIONS))
        assertEquals(TestNetwork.data.complexCount, stats.getDisplayedStationTotal(StationGroupingMode.STATION_COMPLEXES))

        // Leg 0 (L): L08 Bedford Av, L06 1 Av, L05 3 Av, L03 14 St-Union Sq (4 stations)
        // Leg 1 (4 Express): 635 14 St-Union Sq, 631 Grand Central, 629 59 St, 626 86 St (4 stations)
        // Total individual stopped stations = 8; complexes = 7 (L03 and 635 share complex 602)
        assertEquals(
            8,
            stats.getDisplayedStationCount(StationGroupingMode.INDIVIDUAL_STATIONS, StationVisitFilterMode.STOPPED_AT)
        )
        assertEquals(
            7,
            stats.getDisplayedStationCount(StationGroupingMode.STATION_COMPLEXES, StationVisitFilterMode.STOPPED_AT)
        )

        // Boarded/Transferred/Exited only: L08, L03, 635, 626 = 4 individual stations, 3 complexes
        assertEquals(
            4,
            stats.getDisplayedStationCount(
                StationGroupingMode.INDIVIDUAL_STATIONS,
                StationVisitFilterMode.BOARDED_OR_EXITED_ONLY
            )
        )
        assertEquals(
            3,
            stats.getDisplayedStationCount(
                StationGroupingMode.STATION_COMPLEXES,
                StationVisitFilterMode.BOARDED_OR_EXITED_ONLY
            )
        )
    }

    @Test
    fun riding42StShuttleUnlocksFirstSwipeShuttleMasterAndEndToEndLineFinisher() {
        val shuttleRide = SubwayDao.buildCompleteRideRecord(
            rideId = "ride_shuttle_42",
            startTimeEpochMs = daytimeEpochMs,
            endTimeEpochMs = daytimeEpochMs + 3 * 60_000L,
            source = RideSource.MANUAL,
            resolved = engine.resolveTrip(
                legs = listOf(TripLegRequest("902", "901", preferredRouteId = "GS")),
                timestampEpochMs = daytimeEpochMs
            ),
            graphEngine = engine
        )

        val stats = calculator.computeStats(listOf(shuttleRide))
        val unlockedIds = stats.achievements.filter { it.isUnlocked }.map { it.id }.toSet()

        assertTrue("FIRST_SWIPE" in unlockedIds)
        assertTrue("SHUTTLE_COLLECTOR" in unlockedIds)
        assertTrue("LINE_COMPLETIONIST" in unlockedIds)
    }

    @Test
    fun ridesSavedWithStationAndSegmentIdsMissingFromTheNetworkAreSkipped() {
        // A ride stored with the old hand-typed IDs, from before the GTFS network.
        val legacy = CompleteRideRecord(
            ride = RideEntity(
                rideId = "legacy",
                startTimeEpochMs = daytimeEpochMs,
                endTimeEpochMs = daytimeEpochMs + 10 * 60_000L,
                originStationId = "LEX_14_USQ",
                destinationStationId = "LEX_86",
                totalDistanceMiles = 3.4,
                durationMinutes = 10.0,
                source = RideSource.MANUAL
            ),
            legs = emptyList(),
            segments = listOf(RideSegmentEntity("legacy", 0, "LEX_14_USQ-LEX_23", "4", 0.4)),
            stationStops = listOf(
                RideStationStopEntity("legacy", "LEX_14_USQ", "CPLX_UNION_SQ", StopRole.BOARDED, 0),
                RideStationStopEntity("legacy", "LEX_86", "CPLX_86", StopRole.EXITED, 0)
            )
        )

        val stats = calculator.computeStats(listOf(legacy))

        assertEquals(1, stats.totalRides)
        assertEquals(0, stats.getDisplayedStationCount(StationGroupingMode.INDIVIDUAL_STATIONS, StationVisitFilterMode.STOPPED_AT))
        assertEquals(0, stats.getDisplayedStationCount(StationGroupingMode.STATION_COMPLEXES, StationVisitFilterMode.BOARDED_OR_EXITED_ONLY))
        assertEquals(0.0, stats.uniqueCorridorMilesRidden)
        assertTrue(stats.segmentRideCounts.isEmpty())
        assertTrue(stats.topStations.isEmpty())
        assertEquals("LEX_14_USQ", stats.topRoutePairs.single().originName)
    }
}
