package com.straphanger.app.data.local

import com.straphanger.app.testutil.TestNetwork
import com.straphanger.app.testutil.FixedClock
import com.straphanger.app.ui.StraphangerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class FileRidePersistenceBackendTest {

    private val engine = TestNetwork.engine()
    private val sampleTime = 1794250800000L
    private val clock = FixedClock(Instant.fromEpochMilliseconds(sampleTime))

    private fun newDirectory(): Path {
        val dir = Path(SystemTemporaryDirectory, "straphanger-test-${Random.nextLong().toULong()}")
        SystemFileSystem.createDirectories(dir)
        return dir
    }

    private fun ride(id: String, offsetMs: Long = 0L): CompleteRideRecord =
        SubwayDao.buildCompleteRideRecord(
            rideId = id,
            startTimeEpochMs = sampleTime + offsetMs,
            endTimeEpochMs = sampleTime + offsetMs + 11 * 60_000L,
            source = RideSource.MANUAL,
            resolved = engine.resolveOriginToDestinationAuto(
                originStationId = "635",
                destinationStationId = "626",
                preferredRouteId = "4",
                timestampEpochMs = sampleTime
            ),
            graphEngine = engine
        )

    private fun Path.readText(): String = SystemFileSystem.source(this).buffered().use { it.readString() }

    private fun Path.writeText(text: String) {
        SystemFileSystem.sink(this).buffered().use { it.writeString(text) }
    }

    @Test
    fun ridesSurviveNewDaoInstance() {
        val file = Path(newDirectory(), "rides.json")
        val first = SubwayDao(FileRidePersistenceBackend(file), clock)
        first.insertOrReplaceRide(ride("a"))
        first.insertOrReplaceRide(ride("b", 3_600_000L))

        val second = SubwayDao(FileRidePersistenceBackend(file), clock)

        assertEquals(2, second.getAllRides().size)
        assertNull(second.loadError.value)
    }

    @Test
    fun corruptFileIsPreservedAndNotOverwritten() {
        val dir = newDirectory()
        val file = Path(dir, "rides.json")
        file.writeText("{not json")

        val dao = SubwayDao(FileRidePersistenceBackend(file), clock)

        assertNotNull(dao.loadError.value)
        assertTrue(dao.getAllRides().isEmpty())
        val corrupt = SystemFileSystem.list(dir).filter {
            it.name.startsWith("rides.corrupt-") && it.name.endsWith(".json")
        }
        assertEquals(1, corrupt.size)
        assertEquals("rides.corrupt-$sampleTime.json", corrupt.single().name)
        assertEquals("{not json", corrupt.single().readText())

        dao.insertOrReplaceRide(ride("a"))

        assertEquals("{not json", corrupt.single().readText())
        assertEquals(1, SubwayDao(FileRidePersistenceBackend(file), clock).getAllRides().size)
    }

    @Test
    fun saveKeepsThePreviousFileAsBak() {
        val file = Path(newDirectory(), "rides.json")
        val dao = SubwayDao(FileRidePersistenceBackend(file), clock)
        dao.insertOrReplaceRide(ride("a"))
        dao.insertOrReplaceRide(ride("b", 3_600_000L))

        val bak = Path(file.toString() + ".bak")
        assertTrue(SystemFileSystem.exists(bak))
        assertEquals(1, SubwayDao(InMemoryPersistenceBackend(bak.readText()), clock).getAllRides().size)
    }

    @Test
    fun blankFileWithAGoodBakLoadsTheBakAndKeepsTheBlankFile() {
        val dir = newDirectory()
        val file = Path(dir, "rides.json")
        val first = SubwayDao(FileRidePersistenceBackend(file), clock)
        first.insertOrReplaceRide(ride("a"))
        first.insertOrReplaceRide(ride("b", 3_600_000L))
        file.writeText("")

        val dao = SubwayDao(FileRidePersistenceBackend(file), clock)

        assertEquals(listOf("a"), dao.getAllRides().map { it.ride.rideId })
        assertTrue(dao.loadError.value!!.contains("previous copy"), dao.loadError.value)
        assertEquals(1, SystemFileSystem.list(dir).count { it.name.startsWith("rides.corrupt-") })
    }

    @Test
    fun corruptFileWithAGoodBakLoadsTheBak() {
        val file = Path(newDirectory(), "rides.json")
        val first = SubwayDao(FileRidePersistenceBackend(file), clock)
        first.insertOrReplaceRide(ride("a"))
        first.insertOrReplaceRide(ride("b", 3_600_000L))
        file.writeText("{not json")

        val dao = SubwayDao(FileRidePersistenceBackend(file), clock)

        assertEquals(listOf("a"), dao.getAllRides().map { it.ride.rideId })
        assertTrue(dao.loadError.value!!.contains("previous copy"), dao.loadError.value)
    }

    @Test
    fun failedWriteLeavesTheOldFileReadable() {
        val file = Path(newDirectory(), "rides.json")
        SubwayDao(FileRidePersistenceBackend(file), clock).insertOrReplaceRide(ride("a"))
        // kotlinx-io's FileSystem is sealed, so the write is made to fail for real: a directory
        // where the temporary file goes makes opening its sink throw.
        SystemFileSystem.createDirectories(Path(file.toString() + ".tmp"))
        val dao = SubwayDao(FileRidePersistenceBackend(file), clock)

        dao.insertOrReplaceRide(ride("b", 3_600_000L))

        assertFalse(dao.lastSaveSucceeded)
        assertEquals(listOf("a"), dao.getAllRides().map { it.ride.rideId })
        assertEquals(listOf("a"), SubwayDao(FileRidePersistenceBackend(file), clock).getAllRides().map { it.ride.rideId })
    }

    @Test
    fun noLeftoverTmpFileAfterSave() {
        val file = Path(newDirectory(), "rides.json")
        val dao = SubwayDao(FileRidePersistenceBackend(file), clock)

        dao.insertOrReplaceRide(ride("a"))

        assertTrue(SystemFileSystem.exists(file))
        assertFalse(SystemFileSystem.exists(Path(file.toString() + ".tmp")))
    }

    @Test
    fun unreadableSnapshotThatCannotBeSetAsideIsNotOverwritten() {
        val backend = object : RidePersistenceBackend {
            var saves = 0
            override fun loadSnapshotJson(): String = "{not json"
            override fun saveSnapshotJson(json: String) {
                saves++
            }
            override fun quarantineCorruptSnapshot(suffix: String) = throw IllegalStateException("read-only")
        }

        val dao = SubwayDao(backend, clock)
        dao.insertOrReplaceRide(ride("a"))

        assertNotNull(dao.loadError.value)
        assertEquals(0, backend.saves)
    }

    @Test
    fun blockedSavesAreNotAppliedToMemoryAndShowAnError() {
        val backend = object : RidePersistenceBackend {
            var saves = 0
            override fun loadSnapshotJson(): String = "{not json"
            override fun saveSnapshotJson(json: String) {
                saves++
            }
            override fun quarantineCorruptSnapshot(suffix: String) = throw IllegalStateException("read-only")
        }
        val vm = StraphangerViewModel(backend, clock, dispatcher = Dispatchers.Unconfined, networkLoader = { TestNetwork.data })

        val result = vm.submitQuickRide(timestampEpochMs = sampleTime)

        assertNull(result)
        assertTrue(vm.dao.getAllRides().isEmpty())
        assertTrue(vm.uiState.value.rides.isEmpty())
        assertFalse(vm.dao.lastSaveSucceeded)
        assertTrue(vm.uiState.value.loadError!!.contains("Saves are blocked"), vm.uiState.value.loadError)
        assertEquals(0, backend.saves)
    }

    @Test
    fun viewModelDoesNotSeedByDefault() {
        val vm = StraphangerViewModel(InMemoryPersistenceBackend(), dispatcher = Dispatchers.Unconfined, networkLoader = { TestNetwork.data })

        assertTrue(vm.uiState.value.rides.isEmpty())
    }

    @Test
    fun loadDemoRidesAddsSampleRides() {
        val vm = StraphangerViewModel(InMemoryPersistenceBackend(), clock, dispatcher = Dispatchers.Unconfined, networkLoader = { TestNetwork.data })

        vm.loadDemoRides()

        assertEquals(3, vm.uiState.value.rides.size)
    }

    @Test
    fun saveFailureDoesNotThrowLeavesRidesUnchangedAndSetsError() {
        val backend = object : RidePersistenceBackend {
            var failing = false
            var stored: String? = null
            override fun loadSnapshotJson(): String? = stored
            override fun saveSnapshotJson(json: String) {
                if (failing) throw IllegalStateException("disk full")
                stored = json
            }
        }
        val vm = StraphangerViewModel(backend, clock, dispatcher = Dispatchers.Unconfined, networkLoader = { TestNetwork.data })
        assertNotNull(vm.submitQuickRide(timestampEpochMs = sampleTime))
        val before = vm.dao.getAllRides()

        backend.failing = true
        val result = vm.submitQuickRide(timestampEpochMs = sampleTime + 1_000L)
        vm.loadDemoRides()

        assertNull(result)
        assertEquals(before, vm.dao.getAllRides())
        assertEquals(before, vm.uiState.value.rides)
        assertTrue(vm.uiState.value.loadError!!.contains("disk full"))

        backend.failing = false
        assertNotNull(vm.submitQuickRide(timestampEpochMs = sampleTime + 2_000L))
        assertNull(vm.uiState.value.loadError)
    }
}
