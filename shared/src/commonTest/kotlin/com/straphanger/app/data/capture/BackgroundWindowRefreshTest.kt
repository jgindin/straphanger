package com.straphanger.app.data.capture

import com.straphanger.app.data.capture.DynamicGeofenceWindowManager.Companion.MACRO_ANCHOR_ID
import com.straphanger.app.data.gtfs.NycSubwayNetworkCatalog
import com.straphanger.app.testutil.TestNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackgroundWindowRefreshTest {

  // Next to Grand Central-42 St.
  private val lat = 40.7520
  private val lng = -73.9770

  @Test
  fun windowIsTheNearestComplexesPlusAnAnchorAtTheLocation() {
    val specs = BackgroundWindowRefresh.windowAt(TestNetwork.data, lat, lng)

    assertTrue(specs.size <= 19, "${specs.size} regions")
    val anchor = specs.single { it.isMacroAnchor }
    assertEquals(MACRO_ANCHOR_ID, anchor.geofenceId)
    assertEquals(lat, anchor.lat)
    assertEquals(lng, anchor.lng)
    val nearest =
      TestNetwork.data.stations.minBy {
        NycSubwayNetworkCatalog.haversineMiles(lat, lng, it.lat, it.lng)
      }
    assertEquals(nearest.complexId, specs.first { !it.isMacroAnchor }.complexId)
  }

  @Test
  fun onlyAnAnchorExitWithNoRunningAppIsRefreshedInTheBackground() {
    val anchorExit = GeofenceEvent.Exited(MACRO_ANCHOR_ID, 0L, lat, lng)
    assertTrue(BackgroundWindowRefresh.shouldRefresh(anchorExit, appIsHandlingEvents = false))
    // A running view model's coordinator refreshes the window itself.
    assertFalse(BackgroundWindowRefresh.shouldRefresh(anchorExit, appIsHandlingEvents = true))
    val stationExit =
      GeofenceEvent.Exited(DynamicGeofenceWindowManager.stationGeofenceId("602"), 0L, lat, lng)
    assertFalse(BackgroundWindowRefresh.shouldRefresh(stationExit, appIsHandlingEvents = false))
    assertFalse(
      BackgroundWindowRefresh.shouldRefresh(
        GeofenceEvent.Entered(MACRO_ANCHOR_ID, 0L, lat, lng),
        false,
      )
    )
  }

  @Test
  fun theBusReportsWhetherAnythingCollects() {
    assertFalse(GeofenceEventBus.hasCollectors)
  }
}
