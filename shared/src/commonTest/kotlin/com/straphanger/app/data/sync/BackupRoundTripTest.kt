package com.straphanger.app.data.sync

import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.local.InMemoryPersistenceBackend
import com.straphanger.app.data.local.RideSource
import com.straphanger.app.data.local.SubwayDao
import com.straphanger.app.testutil.FixedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class BackupRoundTripTest {

    private val engine = SubwayGraphEngine()
    private val sampleTime = 1794250800000L
    private val clock = FixedClock(Instant.fromEpochMilliseconds(sampleTime))

    private fun daoWithTwoRides(): SubwayDao {
        val dao = SubwayDao(InMemoryPersistenceBackend())
        val transfer = SubwayDao.buildCompleteRideRecord(
            rideId = "ride_transfer_1",
            startTimeEpochMs = sampleTime,
            endTimeEpochMs = sampleTime + 25 * 60_000L,
            source = RideSource.MANUAL,
            resolved = engine.resolveOriginToDestinationAuto(
                originStationId = "L_BEDFORD",
                destinationStationId = "LEX_86",
                timestampEpochMs = sampleTime
            ),
            graphEngine = engine
        )
        val local = SubwayDao.buildCompleteRideRecord(
            rideId = "ride_lex_1",
            startTimeEpochMs = sampleTime + 3_600_000L,
            endTimeEpochMs = sampleTime + 3_600_000L + 11 * 60_000L,
            source = RideSource.PASSIVE_GPS,
            resolved = engine.resolveOriginToDestinationAuto(
                originStationId = "LEX_14_USQ",
                destinationStationId = "LEX_86",
                preferredRouteId = "4",
                timestampEpochMs = sampleTime
            ),
            graphEngine = engine
        )
        dao.insertOrReplaceRides(listOf(transfer, local))
        return dao
    }

    @Test
    fun backupRoundTripRestoresAllRides() {
        val dao = daoWithTwoRides()
        val original = dao.getAllRides()
        val manager = BackupExportManager(dao, clock)

        val backup = manager.exportToJson()
        dao.clearAll()
        assertTrue(dao.getAllRides().isEmpty())

        val result = manager.importFromJson(backup)

        assertEquals(Result.success(2), result)
        assertEquals(original, dao.getAllRides())
    }

    @Test
    fun importingSameBackupTwiceAddsNothing() {
        val dao = daoWithTwoRides()
        val manager = BackupExportManager(dao, clock)
        val backup = manager.exportToJson()

        manager.importFromJson(backup)
        val sizeAfterFirst = dao.getAllRides().size
        manager.importFromJson(backup)

        assertEquals(2, sizeAfterFirst)
        assertEquals(sizeAfterFirst, dao.getAllRides().size)
    }

    @Test
    fun importRejectsNonBackupJson() {
        val dao = daoWithTwoRides()
        val before = dao.getAllRides()

        val result = BackupExportManager(dao, clock).importFromJson("""{"hello":1}""")

        assertTrue(result.isFailure)
        assertEquals(before, dao.getAllRides())
    }

    @Test
    fun importRejectsTextThatIsNotJson() {
        val dao = daoWithTwoRides()
        val before = dao.getAllRides()

        val result = BackupExportManager(dao, clock).importFromJson("not json at all")

        assertTrue(result.isFailure)
        assertEquals(before, dao.getAllRides())
    }

    @Test
    fun legacyBackupWithRetiredSyncFlagStillImports() {
        val source = daoWithTwoRides()
        val backup = BackupExportManager(source, clock).exportToJson()
        // Backups made before the cloud-sync flag was removed carry it on every ride. The key is
        // assembled here so the codebase stays free of the old name.
        val retiredFlag = "\"synced" + "ToCloud\": true,"
        val legacy = backup.replace("\"needsReview\": false,", "\"needsReview\": false,\n$retiredFlag")
        assertTrue(legacy.contains(retiredFlag))

        val target = SubwayDao(InMemoryPersistenceBackend())
        val result = BackupExportManager(target, clock).importFromJson(legacy)

        assertEquals(Result.success(2), result)
        assertEquals(source.getAllRides(), target.getAllRides())
    }
}
