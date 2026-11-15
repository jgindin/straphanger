package com.straphanger.app.data.capture

import com.straphanger.app.data.local.InMemoryPersistenceBackend
import com.straphanger.app.data.local.SubwayDao
import com.straphanger.app.testutil.TestNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [PassiveRideReconstructor] driven directly, without the coordinator's scheduling. */
class PassiveRideReconstructorTest {

  private val t0 = 1794250800000L
  private val minute = 60_000L
  private val dao = SubwayDao(InMemoryPersistenceBackend())
  private val reconstructor = PassiveRideReconstructor(TestNetwork.engine(), dao)

  // Complexes in TestNetwork: 602 is 14 St-Union Sq (Lex and L), 397 is 86 St (Lex), 618 is
  // 14 St (8 Av), 153 is 125 St (8 Av).

  @Test
  fun anEntryAfterTheIdleTimeClosesTheChainAndStartsANewOne() {
    assertNull(reconstructor.onStationGeofenceEntered("602", t0))
    assertNull(reconstructor.onStationGeofenceEntered("397", t0 + 12 * minute))

    val ride = reconstructor.onStationGeofenceEntered("618", t0 + 40 * minute)

    assertNotNull(ride)
    assertEquals("635", ride.ride.originStationId)
    assertEquals("626", ride.ride.destinationStationId)
    assertEquals("618", reconstructor.chainOriginComplexId)
    assertEquals(t0 + 40 * minute, reconstructor.chainOriginTimeMs)
    assertEquals(1, dao.getAllRides().size)
  }

  @Test
  fun reEnteringTheOnlyStationMovesTheBoardingTimeLater() {
    reconstructor.onStationGeofenceEntered("602", t0)
    reconstructor.onStationGeofenceEntered("602", t0 + 5 * minute)
    assertEquals(t0 + 5 * minute, reconstructor.chainOriginTimeMs)
    assertEquals("602", reconstructor.chainLastComplexId)
  }

  @Test
  fun reEnteringTheLastStationOfALongerChainKeepsTheOrigin() {
    reconstructor.onStationGeofenceEntered("602", t0)
    reconstructor.onStationGeofenceEntered("397", t0 + 12 * minute)
    reconstructor.onStationGeofenceEntered("397", t0 + 13 * minute)
    assertEquals(t0, reconstructor.chainOriginTimeMs)
    assertEquals(t0 + 12 * minute, reconstructor.chainLastTimeMs)
  }

  @Test
  fun outOfOrderEntryIsIgnored() {
    reconstructor.onStationGeofenceEntered("602", t0 + 10 * minute)
    assertNull(reconstructor.onStationGeofenceEntered("397", t0))
    assertEquals("602", reconstructor.chainLastComplexId)
  }

  @Test
  fun unknownComplexClosesTheChainWithoutARide() {
    reconstructor.onStationGeofenceEntered("602", t0)
    // No stations in the complex: no speed for the hop, so the chain closes and restarts there.
    assertNull(reconstructor.onStationGeofenceEntered("no-such-complex", t0 + 10 * minute))
    assertEquals("no-such-complex", reconstructor.chainOriginComplexId)
    assertNull(reconstructor.closeIfIdle(t0 + 60 * minute))
    assertTrue(dao.getAllRides().isEmpty())
  }

  @Test
  fun plausibleTripBetweenUnconnectedLinesRecordsNothing() {
    // 8 Av 14 St to Lexington 86 St is fast enough for a ride, but the test network has no route
    // between the two lines.
    reconstructor.onStationGeofenceEntered("618", t0)
    reconstructor.onStationGeofenceEntered("397", t0 + 15 * minute)
    assertNull(reconstructor.closeIfIdle(t0 + 31 * minute))
    assertTrue(dao.getAllRides().isEmpty())
    assertNull(reconstructor.chainOriginComplexId)
  }

  @Test
  fun closeIfIdleWaitsForTheIdleTime() {
    assertNull(reconstructor.closeIfIdle(t0), "no chain")
    reconstructor.onStationGeofenceEntered("618", t0)
    reconstructor.onStationGeofenceEntered("153", t0 + 12 * minute)
    assertNull(reconstructor.closeIfIdle(t0 + 20 * minute))
    assertEquals("618", reconstructor.chainOriginComplexId)

    val ride = reconstructor.closeIfIdle(t0 + 12 * minute + reconstructor.idleCloseMs)
    assertNotNull(ride)
    assertEquals("A31", ride.ride.originStationId)
    assertEquals("A15", ride.ride.destinationStationId)
    assertNull(reconstructor.unsavedChainOriginTimeMs)
  }
}
