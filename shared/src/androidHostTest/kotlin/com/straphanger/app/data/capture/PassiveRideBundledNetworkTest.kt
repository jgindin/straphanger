package com.straphanger.app.data.capture

import com.straphanger.app.data.local.InMemoryPersistenceBackend
import com.straphanger.app.data.local.RideSource
import com.straphanger.app.data.local.SubwayDao
import com.straphanger.app.testutil.BundledNetwork
import kotlin.test.Test
import kotlin.test.assertEquals

class PassiveRideBundledNetworkTest {

    @Test
    fun unionSqToEightySixthStGeofenceEntriesRecordALexingtonAvRide() {
        val engine = BundledNetwork.engine()
        val dao = SubwayDao(InMemoryPersistenceBackend())
        val reconstructor = PassiveRideReconstructor(engine, dao)
        val unionSq = engine.getStation("635")!!.complexId
        val eightySixth = engine.getStation("626")!!.complexId
        val t0 = 1794405600000L

        // The window manager registers these complexes under the IDs the reconstructor is given.
        val window = DynamicGeofenceWindowManager(engine).computeRollingWindow(40.734673, -73.989951)
        assertEquals(unionSq, DynamicGeofenceWindowManager.complexIdForGeofence(window.activeGeofences.first().geofenceId))

        reconstructor.onStationGeofenceEntered(unionSq, t0)
        reconstructor.onStationGeofenceEntered(eightySixth, t0 + 14 * 60_000L)
        val record = reconstructor.closeIfIdle(t0 + 29 * 60_000L)!!

        assertEquals(RideSource.PASSIVE_GPS, record.ride.source)
        assertEquals("635", record.ride.originStationId)
        assertEquals("626", record.ride.destinationStationId)
        assertEquals(1, record.legs.size)
        assertEquals(1, dao.getAllRides().size)
    }
}
