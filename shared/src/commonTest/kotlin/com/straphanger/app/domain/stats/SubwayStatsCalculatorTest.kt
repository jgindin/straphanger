package com.straphanger.app.domain.stats

import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.gtfs.TripLegRequest
import com.straphanger.app.data.local.RideSource
import com.straphanger.app.data.local.SubwayDao
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubwayStatsCalculatorTest {

    private val engine = SubwayGraphEngine()
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
                legs = listOf(TripLegRequest("IND8_14", "IND8_59_COL", preferredRouteId = "A")),
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
                legs = listOf(TripLegRequest("IND8_14", "IND8_59_COL", preferredRouteId = "C")),
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
    fun stationTogglesAccuratelySeparate472StationsVs424ComplexesAndStoppedAtVsBoardedExited() {
        // Bedford Ave (L) -> 14 St-Union Sq (transfer) -> 86 St (4 Express)
        val resolved = engine.resolveOriginToDestinationAuto(
            originStationId = "L_BEDFORD",
            destinationStationId = "LEX_86",
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

        // Leg 0 (L): L_BEDFORD, L_1AVE, L_3AVE, L_14_USQ (4 stations)
        // Leg 1 (4 Express): LEX_14_USQ, LEX_42_GCT, LEX_59, LEX_86 (4 stations)
        // Total individual stopped stations = 8; complexes = 7 (since L_14_USQ and LEX_14_USQ share CPLX_UNION_SQ)
        assertEquals(
            8,
            stats.getDisplayedStationCount(StationGroupingMode.INDIVIDUAL_472, StationVisitFilterMode.STOPPED_AT)
        )
        assertEquals(
            7,
            stats.getDisplayedStationCount(StationGroupingMode.COMPLEXES_424, StationVisitFilterMode.STOPPED_AT)
        )

        // Boarded/Transferred/Exited only: L_BEDFORD, L_14_USQ, LEX_14_USQ, LEX_86 = 4 individual stations, 3 complexes
        assertEquals(
            4,
            stats.getDisplayedStationCount(
                StationGroupingMode.INDIVIDUAL_472,
                StationVisitFilterMode.BOARDED_OR_EXITED_ONLY
            )
        )
        assertEquals(
            3,
            stats.getDisplayedStationCount(
                StationGroupingMode.COMPLEXES_424,
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
                legs = listOf(TripLegRequest("IRT7_42_TSQ", "LEX_42_GCT", preferredRouteId = "S")),
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
}
