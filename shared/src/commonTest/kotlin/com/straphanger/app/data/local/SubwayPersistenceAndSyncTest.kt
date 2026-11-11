package com.straphanger.app.data.local

import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.sync.BackupExportManager
import com.straphanger.app.data.sync.FirebaseConfig
import com.straphanger.app.data.sync.FirestoreKmpSyncClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubwayPersistenceAndSyncTest {

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
        val exporter = BackupExportManager(dao)
        val backupJson = exporter.exportToJson(sampleTime)
        val csv = exporter.exportToCsv()
        assertTrue(csv.contains("L+4X"))

        val freshDao = SubwayDao(InMemoryPersistenceBackend())
        val importedCount = BackupExportManager(freshDao).importFromJson(backupJson)
        assertEquals(1, importedCount)
        assertEquals(saved.ride.totalDistanceMiles, freshDao.getAllRides().single().ride.totalDistanceMiles)
    }

    @Test
    fun offlineUndergroundQueueSurvivesFailureAndSyncsWhenBackOnline() = runTest {
        val dao = SubwayDao(InMemoryPersistenceBackend())
        val resolved = engine.resolveOriginToDestinationAuto(
            originStationId = "LEX_14_USQ",
            destinationStationId = "LEX_86",
            preferredRouteId = "4",
            timestampEpochMs = sampleTime
        )
        val record = SubwayDao.buildCompleteRideRecord(
            rideId = "ride_lex_1",
            startTimeEpochMs = sampleTime,
            endTimeEpochMs = sampleTime + 11 * 60_000L,
            source = RideSource.PASSIVE_GPS,
            resolved = resolved,
            graphEngine = engine
        )
        dao.insertOrReplaceRide(record)

        var networkOnline = false
        val mockEngine = MockEngine { request ->
            if (!networkOnline) {
                throw IOException("No cell signal in subway tunnel")
            }
            when {
                request.url.encodedPath.contains("accounts:signUp") -> {
                    respond(
                        content = """{"idToken":"mock_id_token","localId":"user_123"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                else -> {
                    respond(
                        content = """{"name":"projects/straphanger-test/databases/(default)/documents/users/user_123/rides/ride_lex_1"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
        }

        val syncClient = FirestoreKmpSyncClient(
            dao = dao,
            httpClient = HttpClient(mockEngine),
            config = FirebaseConfig(projectId = "straphanger-test", apiKey = "AIzaMockKey")
        )

        // 1. Attempt sync while underground (offline)
        val offlineResult = syncClient.syncPendingRides()
        assertFalse(offlineResult.succeeded)
        assertEquals(1, offlineResult.pendingOfflineCount)
        assertFalse(dao.getAllRides().single().ride.syncedToCloud)

        // 2. Rider exits station and regains signal
        networkOnline = true
        val onlineResult = syncClient.syncPendingRides()
        assertTrue(onlineResult.succeeded)
        assertEquals(1, onlineResult.uploadedCount)
        assertEquals(0, onlineResult.pendingOfflineCount)
        assertTrue(dao.getAllRides().single().ride.syncedToCloud)
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
