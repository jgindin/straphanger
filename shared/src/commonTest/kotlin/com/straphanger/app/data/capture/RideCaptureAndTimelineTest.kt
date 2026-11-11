package com.straphanger.app.data.capture

import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.local.InMemoryPersistenceBackend
import com.straphanger.app.data.local.RideSource
import com.straphanger.app.data.local.SubwayDao
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RideCaptureAndTimelineTest {

    private val engine = SubwayGraphEngine()
    private val importer = TimelineJsonImporter(engine)

    @Test
    fun parsesAllThreeGoogleMapsTimelineJsonFormatsAndMergesTransfers() {
        val dao = SubwayDao(InMemoryPersistenceBackend())

        // 1. Format 1: Android On-Device Timeline.json (degree strings + 2-leg transfer within 4 min)
        val androidTimelineJson = """
            {
              "semanticSegments": [
                {
                  "startTime": "2026-11-08T14:00:00.000-05:00",
                  "endTime": "2026-11-08T14:08:00.000-05:00",
                  "activity": {
                    "start": { "latLng": "40.7173°, -73.9569°" },
                    "end": { "latLng": "40.7341°, -73.9907°" },
                    "topCandidate": { "type": "IN_SUBWAY", "transitLine": "L Train" }
                  }
                },
                {
                  "startTime": "2026-11-08T14:12:00.000-05:00",
                  "endTime": "2026-11-08T14:21:00.000-05:00",
                  "activity": {
                    "start": { "latLng": "40.7347° N, 73.9899° W" },
                    "end": { "latLng": "40.7795° N, 73.9556° W" },
                    "topCandidate": { "type": "IN_SUBWAY", "transitLine": "4 Express" }
                  }
                }
              ]
            }
        """.trimIndent()

        val summary1 = importer.importTimelineJson(androidTimelineJson, dao)
        assertEquals(2, summary1.rawSegmentsFound)
        assertEquals(1, summary1.ridesImported, "Consecutive L -> 4 segments at Union Sq within 4 min should merge into 1 ride")
        assertEquals(1, summary1.multiLegRidesMerged)
        assertEquals(2, summary1.importedRecords.single().legs.size)

        // Re-importing the exact same file must be deduplicated
        val duplicateSummary = importer.importTimelineJson(androidTimelineJson, dao)
        assertEquals(0, duplicateSummary.ridesImported)
        assertEquals(1, duplicateSummary.duplicatesSkipped)

        // 2. Format 2: iOS On-Device Timeline.json (root array with "geo:lat,lng" URIs)
        val iosTimelineJson = """
            [
              {
                "startTime": "2026-11-09T09:15:00.000-05:00",
                "endTime": "2026-11-09T09:32:00.000-05:00",
                "activity": {
                  "start": "geo:40.7682,-73.9819",
                  "end": "geo:40.7102,-74.0077",
                  "topCandidate": { "type": "IN_SUBWAY", "transitLine": "A" }
                }
              }
            ]
        """.trimIndent()

        val summary2 = importer.importTimelineJson(iosTimelineJson, dao)
        assertEquals(1, summary2.ridesImported)
        assertEquals("A", summary2.importedRecords.single().legs.single().routeId)

        // 3. Format 3: Google Takeout Semantic Location History (E7 integers + transitPath)
        val takeoutJson = """
            {
              "timelineObjects": [
                {
                  "activitySegment": {
                    "startLocation": { "latitudeE7": 407555000, "longitudeE7": -739876000 },
                    "endLocation": { "latitudeE7": 407596000, "longitudeE7": -738300000 },
                    "duration": {
                      "startTimestamp": "2026-11-09T18:00:00Z",
                      "endTimestamp": "2026-11-09T18:28:00Z"
                    },
                    "activityType": "IN_SUBWAY",
                    "transitPath": {
                      "name": "7 Train"
                    }
                  }
                }
              ]
            }
        """.trimIndent()

        val summary3 = importer.importTimelineJson(takeoutJson, dao)
        assertEquals(1, summary3.ridesImported)
        assertEquals("7", summary3.importedRecords.single().legs.single().routeId)
        assertEquals(3, dao.getAllRides().size)
    }

    @Test
    fun dynamicGeofenceWindowStrictlyRespectsIosTwentyRegionCapAcrossAllBoroughs() {
        val manager = DynamicGeofenceWindowManager(engine)
        val testPoints = listOf(
            40.7553 to -73.9875, // Manhattan: Times Sq
            40.6844 to -73.9776, // Brooklyn: Atlantic Ave-Barclays
            40.7468 to -73.8914, // Queens: Jackson Hts
            40.8279 to -73.9257, // Bronx: 161 St-Yankee Stadium
            40.6437 to -74.0736  // Staten Island: St George
        )

        for ((lat, lng) in testPoints) {
            val window = manager.computeRollingWindow(lat, lng)
            assertEquals(19, window.activeGeofences.size, "Must register 18 stations + 1 macro anchor = 19")
            assertTrue(
                window.activeGeofences.size < DynamicGeofenceWindowManager.IOS_MAX_REGION_LIMIT,
                "Active geofence count must remain strictly under iOS 20-region limit"
            )
            assertEquals(1, window.activeGeofences.count { it.isMacroAnchor })
        }

        // Small movement (100m) does not refresh window; large movement (> 1km) triggers refresh
        manager.computeRollingWindow(40.7553, -73.9875)
        assertTrue(!manager.shouldRefreshWindow(40.7556, -73.9875))
        assertTrue(manager.shouldRefreshWindow(40.7795, -73.9556))
    }

    @Test
    fun parsesGoogleMapsShareTextNotificationAndShortUrlRedirect() = runTest {
        val mockHttp = HttpClient(
            MockEngine { request ->
                if (request.url.host == "maps.app.goo.gl") {
                    respond(
                        content = "",
                        status = HttpStatusCode.Found,
                        headers = headersOf(
                            HttpHeaders.Location,
                            "https://www.google.com/maps/dir/Bedford+Ave/86+St/"
                        )
                    )
                } else {
                    respond("", HttpStatusCode.NotFound)
                }
            }
        ) {
            followRedirects = false
        }

        val parser = GoogleMapsShareParser(engine, mockHttp)

        // 1. Live Google Maps Transit Notification
        val notifRecord = parser.parseAndResolveSharedRoute(
            "GMAPS_NOTIFICATION: 4 Train | Board at 14 St-Union Sq • Exit at 86 St"
        )
        assertNotNull(notifRecord)
        assertEquals(RideSource.GMAPS_NOTIFICATION, notifRecord.ride.source)
        assertEquals("4", notifRecord.legs.single().routeId)

        // 2. Opaque maps.app.goo.gl short link resolved via 302 Location header
        val redirectRecord = parser.parseAndResolveSharedRoute(
            "Check out this route: https://maps.app.goo.gl/SubwayTestLink123"
        )
        assertNotNull(redirectRecord)
        assertEquals(RideSource.GMAPS_SHARE, redirectRecord.ride.source)
        assertEquals(2, redirectRecord.legs.size)
    }
}
