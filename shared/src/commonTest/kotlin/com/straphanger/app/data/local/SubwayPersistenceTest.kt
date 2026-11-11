package com.straphanger.app.data.local

import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.sync.BackupExportManager
import com.straphanger.app.testutil.FixedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class SubwayPersistenceTest {

    private val engine = SubwayGraphEngine()
    private val sampleTime = 1794250800000L

    @Test
    fun multiLegTransferRidePersistsAsOneRideWithTwoLegsAndRestoresFromBackup() {
        val backend = InMemoryPersistenceBackend()
        val dao = SubwayDao(backend)
        val resolved = engine.resolveOriginToDestinationAuto(
            originStationId = "L_BEDFORD",
            destinationStationId = "LEX_86",
            timestampEpochMs = sampleTime
        )

        val record = SubwayDao.buildCompleteRideRecord(
            rideId = "ride_transfer_1",
            startTimeEpochMs = sampleTime,
            endTimeEpochMs = sampleTime + 25 * 60_000L,
            source = RideSource.MANUAL,
            resolved = resolved,
            graphEngine = engine
        )

        dao.insertOrReplaceRide(record)

        assertEquals(1, dao.getAllRides().size, "Multi-leg transfer must count as 1 ride")
        val saved = dao.getAllRides().single()
        assertEquals(2, saved.legs.size, "Must store both L and 4 legs")
        assertEquals("L", saved.legs[0].routeId)
        assertEquals("4", saved.legs[1].routeId)

        // Verify BackupExportManager lossless JSON round-trip into a fresh DAO
        val exporter = BackupExportManager(dao, FixedClock(Instant.fromEpochMilliseconds(sampleTime)))
        val backupJson = exporter.exportToJson()

        val freshDao = SubwayDao(InMemoryPersistenceBackend())
        val importedCount = BackupExportManager(freshDao).importFromJson(backupJson)
        assertEquals(Result.success(1), importedCount)
        assertEquals(saved.ride.totalDistanceMiles, freshDao.getAllRides().single().ride.totalDistanceMiles)
    }

    @Test
    fun exportUsesClockTime() {
        val clock = FixedClock(Instant.parse("2026-11-11T14:00:00Z"))
        val json = BackupExportManager(SubwayDao(InMemoryPersistenceBackend()), clock).exportToJson()
        assertTrue(json.contains("\"exportedAtEpochMs\": ${clock.instant.toEpochMilliseconds()}"))
    }

    @Test
    fun confirmAmbiguousRideUpdatesExpressOrLocalStopsAndClearsReviewFlag() {
        val dao = SubwayDao(InMemoryPersistenceBackend())
        val ambiguousResolved = engine.resolveOriginToDestinationAuto(
            originStationId = "LEX_14_USQ",
            destinationStationId = "LEX_86",
            preferredRouteId = null,
            timestampEpochMs = sampleTime,
            actualDurationMinutes = 10.0
        )
        val record = SubwayDao.buildCompleteRideRecord(
            rideId = "ride_ambig_1",
            startTimeEpochMs = sampleTime,
            endTimeEpochMs = sampleTime + 10 * 60_000L,
            source = RideSource.PASSIVE_GPS,
            resolved = ambiguousResolved,
            graphEngine = engine
        )
        dao.insertOrReplaceRide(record)
        assertTrue(dao.getRideById("ride_ambig_1")!!.ride.needsReview)

        // User confirms they actually rode the 6 Local
        val confirmed = dao.confirmAmbiguousRide(
            rideId = "ride_ambig_1",
            confirmedRouteId = "6",
            confirmedIsExpress = false,
            graphEngine = engine
        )!!

        assertFalse(confirmed.ride.needsReview)
        assertEquals("6", confirmed.legs.single().routeId)
        assertFalse(confirmed.legs.single().isExpress)
        assertEquals(10, confirmed.stationStops.size, "6 Local should record all 10 stops from 14 St to 86 St")
    }
}
