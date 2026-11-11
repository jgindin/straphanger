package com.straphanger.app.data.gtfs

import com.straphanger.app.testutil.BundledNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Origin-to-destination routing on the generated network: one-seat rides win over transfers. */
class BundledNetworkRoutingTest {

    private val engine = BundledNetwork.engine()

    // 2026-11-09 14:00:00 EST (Monday) -> weekday daytime
    private val daytimeEpochMs = 1794250800000L

    private fun route(from: String, to: String): ResolvedTripResult =
        engine.resolveOriginToDestinationAuto(
            originStationId = from,
            destinationStationId = to,
            timestampEpochMs = daytimeEpochMs
        )

    private fun describe(trip: ResolvedTripResult): String =
        trip.legs.joinToString(" | ") { "${it.routeId}: ${it.fromStationId}->${it.toStationId}" }

    @Test
    fun ninetySixthStreetToFlatbushIsOneLegOnTheTwoOrThree() {
        val trip = route("120", "235")
        assertEquals(1, trip.legs.size, describe(trip))
        assertTrue(trip.legs.single().routeId in setOf("2", "3"), describe(trip))
    }

    @Test
    fun grandConcourseToBrooklynIsOneLegOnTheD() {
        val trip = route("D11", "R31")
        assertEquals(1, trip.legs.size, describe(trip))
        assertEquals("D", trip.legs.single().routeId, describe(trip))
    }

    @Test
    fun bedfordAvToEightySixthStreetIsTheLThenTheLexington() {
        val trip = route("L08", "626")
        assertEquals(2, trip.legs.size, describe(trip))
        assertEquals("L", trip.legs[0].routeId, describe(trip))
        assertTrue(trip.legs[1].routeId in setOf("4", "5", "6"), describe(trip))
    }

    @Test
    fun unionSquareToEightySixthStreetByDayIsOneLegOnTheFourOrFive() {
        val trip = route("635", "626")
        assertEquals(1, trip.legs.size, describe(trip))
        assertTrue(trip.legs.single().routeId in setOf("4", "5"), describe(trip))
    }
}
