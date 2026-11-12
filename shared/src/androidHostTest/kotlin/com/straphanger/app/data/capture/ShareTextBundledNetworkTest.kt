package com.straphanger.app.data.capture

import com.straphanger.app.testutil.BundledNetwork
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShareTextBundledNetworkTest {

    private val engine = BundledNetwork.engine()
    private val parser = GoogleMapsShareParser(engine, HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }))

    // 2026-11-09 14:00 EST, weekday daytime
    private val daytimeEpochMs = 1794250800000L

    @Test
    fun shareTextWithAmbiguousStationUsesNamedLine() = runTest {
        val record = parser.parseAndResolveSharedRoute("86 St to Grand Central via 4 train", daytimeEpochMs)
        assertNotNull(record)
        val origin = engine.getStation(record.ride.originStationId)
        assertNotNull(origin)
        assertEquals("86 St", origin.name)
        assertTrue(
            "4" in origin.daytimeRoutes && "6" in origin.daytimeRoutes,
            "origin ${origin.id} must be the Lexington Av 86 St",
        )
        assertEquals("Grand Central-42 St", engine.getStation(record.ride.destinationStationId)?.name)
        assertFalse(record.ride.needsReview)

        val unnamed = parser.parseAndResolveSharedRoute("86 St to Grand Central", daytimeEpochMs)
        assertNotNull(unnamed)
        assertTrue(unnamed.ride.needsReview, "no line named, and several stations are called 86 St")
    }
}
