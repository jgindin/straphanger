package com.straphanger.app.data.local

import com.straphanger.app.testutil.FixedClock
import com.straphanger.app.testutil.TestNetwork
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString

/** The start-up paths of [RideSnapshotLoader], seen through [SubwayDao]. */
class RideSnapshotLoaderTest {

  private val engine = TestNetwork.engine()
  private val sampleTime = 1794250800000L
  private val clock = FixedClock(Instant.fromEpochMilliseconds(sampleTime))

  private fun ride(id: String): CompleteRideRecord =
    SubwayDao.buildCompleteRideRecord(
      rideId = id,
      startTimeEpochMs = sampleTime,
      endTimeEpochMs = sampleTime + 11 * 60_000L,
      source = RideSource.MANUAL,
      resolved =
        engine.resolveOriginToDestinationAuto(
          originStationId = "635",
          destinationStationId = "626",
          preferredRouteId = "4",
          timestampEpochMs = sampleTime,
        ),
      graphEngine = engine,
    )

  /** The JSON a save of [rides] writes. */
  private fun snapshotOf(vararg rides: CompleteRideRecord): String {
    val backend = InMemoryPersistenceBackend()
    SubwayDao(backend, clock).insertOrReplaceRides(rides.toList())
    return backend.loadSnapshotJson()!!
  }

  private class ScriptedBackend(
    private val snapshot: () -> String?,
    private val backup: () -> String? = { null },
  ) : RidePersistenceBackend {
    val saved = mutableListOf<String>()
    val quarantined = mutableListOf<String>()

    override fun loadSnapshotJson(): String? = snapshot()

    override fun loadBackupSnapshotJson(): String? = backup()

    override fun saveSnapshotJson(json: String) {
      saved += json
    }

    override fun quarantineCorruptSnapshot(suffix: String) {
      quarantined += suffix
    }
  }

  @Test
  fun snapshotThatCannotBeReadBlocksSaves() {
    val backend = ScriptedBackend(snapshot = { throw IOException("permission denied") })
    val dao = SubwayDao(backend, clock)

    assertTrue(dao.getAllRides().isEmpty())
    val error = dao.loadError.value!!
    assertTrue(error.startsWith("Could not read saved rides: permission denied."), error)
    assertTrue(error.contains("will not be saved"), error)

    dao.insertOrReplaceRide(ride("r1"))
    assertFalse(dao.lastSaveSucceeded)
    assertTrue(backend.saved.isEmpty(), "a blocked save must not reach the backend")
    assertTrue(dao.getAllRides().isEmpty())
  }

  @Test
  fun backendStateErrorIsReportedWithoutAMessage() {
    val backend = ScriptedBackend(snapshot = { throw IllegalStateException() })
    val dao = SubwayDao(backend, clock)
    assertTrue(dao.loadError.value!!.contains("unknown error"), dao.loadError.value)
  }

  @Test
  fun missingSnapshotWithAGoodCopyRestoresTheCopy() {
    val copy = snapshotOf(ride("r1"), ride("r2"))
    val backend = ScriptedBackend(snapshot = { null }, backup = { copy })
    val dao = SubwayDao(backend, clock)

    assertEquals(setOf("r1", "r2"), dao.getAllRides().map { it.ride.rideId }.toSet())
    assertEquals(
      "Saved rides were missing, so the previous copy was restored.",
      dao.loadError.value,
    )
    assertTrue(backend.quarantined.isEmpty(), "nothing to set aside when the file is missing")
  }

  @Test
  fun missingSnapshotAndUnreadableCopyStartsEmpty() {
    val backend = ScriptedBackend(snapshot = { null }, backup = { throw IOException("eio") })
    val dao = SubwayDao(backend, clock)
    assertTrue(dao.getAllRides().isEmpty())
    assertTrue(dao.loadError.value!!.contains("(eio)"), dao.loadError.value)

    // Saves still work: nothing on disk is at risk.
    dao.insertOrReplaceRide(ride("r1"))
    assertTrue(dao.lastSaveSucceeded)
    assertEquals(1, backend.saved.size)
  }

  @Test
  fun noSnapshotAndNoCopyIsAFirstRun() {
    val dao = SubwayDao(ScriptedBackend(snapshot = { null }), clock)
    assertTrue(dao.getAllRides().isEmpty())
    assertNull(dao.loadError.value)
  }

  @Test
  fun corruptSnapshotWithUnreadableCopyIsSetAsideAndBothProblemsAreReported() {
    val backend =
      ScriptedBackend(snapshot = { "{not json" }, backup = { throw IOException("bad sector") })
    val dao = SubwayDao(backend, clock)

    assertEquals(listOf("corrupt-$sampleTime"), backend.quarantined)
    val error = dao.loadError.value!!
    assertTrue(error.contains("rides.corrupt-$sampleTime.json"), error)
    assertTrue(error.contains("The previous copy could not be read either (bad sector)"), error)
    assertTrue(dao.getAllRides().isEmpty())
  }

  @Test
  fun blankSnapshotWithNoCopyIsAFirstRun() {
    val backend = ScriptedBackend(snapshot = { "   " })
    val dao = SubwayDao(backend, clock)
    assertTrue(backend.quarantined.isEmpty())
    assertNull(dao.loadError.value)
  }

  @Test
  fun blankSnapshotWithAGoodCopyIsSetAsideAndTheCopyRestored() {
    val copy = snapshotOf(ride("r1"))
    val backend = ScriptedBackend(snapshot = { "" }, backup = { copy })
    val dao = SubwayDao(backend, clock)
    assertEquals(1, backend.quarantined.size)
    assertEquals(listOf("r1"), dao.getAllRides().map { it.ride.rideId })
    assertTrue(dao.loadError.value!!.contains("the file is empty"), dao.loadError.value)
  }

  @Test
  fun goodSnapshotLoadsNewestFirstWithoutTouchingTheCopy() {
    val older = ride("old")
    val newer =
      ride("new").let { it.copy(ride = it.ride.copy(startTimeEpochMs = sampleTime + 60_000L)) }
    val backend =
      ScriptedBackend(
        snapshot = { snapshotOf(older, newer) },
        backup = { error("the copy must not be read when the snapshot is good") },
      )
    val dao = SubwayDao(backend, clock)
    assertEquals(listOf("new", "old"), dao.getAllRides().map { it.ride.rideId })
    assertNull(dao.loadError.value)
  }

  @Test
  fun defaultBackendHasNoCopyAndSetsNothingAside() {
    val plain =
      object : RidePersistenceBackend {
        override fun loadSnapshotJson(): String? = null

        override fun saveSnapshotJson(json: String) = Unit
      }
    assertNull(plain.loadBackupSnapshotJson())
    plain.quarantineCorruptSnapshot("x")
  }

  @Test
  fun fileWithoutAnExtensionIsSetAsideWithTheSuffixAppended() {
    val dir = Path(SystemTemporaryDirectory, "straphanger-test-${Random.nextLong().toULong()}")
    val file = Path(dir, "rides")
    val backend = FileRidePersistenceBackend(file)
    SystemFileSystem.sink(file).buffered().use { it.writeString("{not json") }

    SubwayDao(backend, clock)

    assertFalse(SystemFileSystem.exists(file))
    assertTrue(SystemFileSystem.exists(Path(dir, "rides.corrupt-$sampleTime")))
  }
}
