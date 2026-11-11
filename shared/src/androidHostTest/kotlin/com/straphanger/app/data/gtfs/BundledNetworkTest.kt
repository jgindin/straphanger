package com.straphanger.app.data.gtfs

import com.straphanger.app.testutil.BundledNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Checks the network generated from MTA GTFS by tools/gtfs-import. */
class BundledNetworkTest {

    private val network = BundledNetwork.data

    @Test
    fun hasASourceDate() {
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}""").matches(network.sourceDate), network.sourceDate)
    }

    @Test
    fun stationAndComplexCountsComeFromTheData() {
        assertTrue(network.stationCount in 470..500, "stationCount=${network.stationCount}")
        assertTrue(network.complexCount in 420..450, "complexCount=${network.complexCount}")
        assertEquals(network.stations.size, network.stations.map { it.id }.toSet().size, "station IDs are unique")
    }

    @Test
    fun everySegmentEndpointIsAKnownStation() {
        val ids = network.stations.map { it.id }.toSet()
        for (seg in network.segments) {
            assertTrue(seg.fromStationId in ids, seg.segmentId)
            assertTrue(seg.toStationId in ids, seg.segmentId)
            assertTrue(seg.distanceMiles > 0.0, seg.segmentId)
            assertTrue(seg.polyline.size >= 2, seg.segmentId)
        }
    }

    @Test
    fun everyStationIsReachableFromTimesSqExceptStatenIsland() {
        // Segments plus walks between stations of the same complex.
        val adjacency = mutableMapOf<String, MutableSet<String>>()
        for (seg in network.segments) {
            adjacency.getOrPut(seg.fromStationId) { mutableSetOf() }.add(seg.toStationId)
            adjacency.getOrPut(seg.toStationId) { mutableSetOf() }.add(seg.fromStationId)
        }
        for (group in network.stations.groupBy { it.complexId }.values) {
            for (a in group) for (b in group) if (a.id != b.id) adjacency.getOrPut(a.id) { mutableSetOf() }.add(b.id)
        }
        val timesSq = network.stations.first { it.name == "Times Sq-42 St" }.id
        val seen = mutableSetOf(timesSq)
        val queue = ArrayDeque(listOf(timesSq))
        while (queue.isNotEmpty()) {
            for (next in adjacency[queue.removeFirst()].orEmpty()) if (seen.add(next)) queue.addLast(next)
        }
        val routesAt = mutableMapOf<String, MutableSet<String>>()
        for (seg in network.segments) for (id in listOf(seg.fromStationId, seg.toStationId)) {
            routesAt.getOrPut(id) { mutableSetOf() }.addAll(seg.routes)
        }
        val unreached = network.stations.filter { it.id !in seen }
        assertTrue(unreached.isNotEmpty(), "Staten Island Railway is not connected to the subway")
        for (st in unreached) {
            assertEquals<Set<String>?>(setOf("SI"), routesAt[st.id], "${st.id} ${st.name} is unreachable")
        }
    }

    @Test
    fun allSubwayRoutesArePresent() {
        val ids = network.routes.map { it.routeId }.toSet()
        val expected = listOf("1", "2", "3", "4", "5", "6", "7") +
            "A C E B D F M G J Z L N Q R W".split(" ")
        assertTrue(ids.containsAll(expected), "missing ${expected - ids}")
    }

    @Test
    fun fourTrainSkips116StByDayButStopsThereLateAtNight() {
        val four = network.routes.single { it.routeId == "4" }
        val lex116 = network.stations.single { it.name == "116 St" && "6" in it.daytimeRoutes }.id
        assertFalse(lex116 in four.stopsAt.getValue(ServicePeriod.DAYTIME))
        assertTrue(lex116 in four.stopsAt.getValue(ServicePeriod.LATE_NIGHT))
    }

    @Test
    fun systemMilesIsTheSumOfSegments() {
        assertEquals(network.segments.sumOf { it.distanceMiles }, network.systemMiles, 1e-9)
        assertTrue(network.systemMiles in 200.0..300.0, "systemMiles=${network.systemMiles}")
    }
}
