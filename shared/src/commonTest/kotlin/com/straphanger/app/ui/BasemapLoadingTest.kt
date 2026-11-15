package com.straphanger.app.ui

import com.straphanger.app.data.basemap.BasemapArea
import com.straphanger.app.data.basemap.BasemapShape
import com.straphanger.app.data.basemap.NycBasemap
import com.straphanger.app.data.local.InMemoryPersistenceBackend
import com.straphanger.app.diagnostics.DiagnosticsLog
import com.straphanger.app.diagnostics.DiagnosticsSink
import com.straphanger.app.testutil.FixedClock
import com.straphanger.app.testutil.TestNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow

class BasemapLoadingTest {

  private val basemap =
    NycBasemap(
      sourceDate = "2026-11-15",
      land =
        listOf(
          BasemapArea("Manhattan", listOf(BasemapShape(doubleArrayOf(-74.0, 40.7, -73.9, 40.8))))
        ),
      parks = emptyList(),
      highways = emptyList(),
      arterials = emptyList(),
    )

  private fun viewModel(basemapLoader: suspend () -> NycBasemap) =
    StraphangerViewModel(
      backend = InMemoryPersistenceBackend(),
      clock = FixedClock(Instant.parse("2026-11-11T14:00:00Z")),
      dispatcher = Dispatchers.Unconfined,
      computeDispatcher = Dispatchers.Unconfined,
      networkLoader = { TestNetwork.data },
      basemapLoader = basemapLoader,
      geofenceEvents = emptyFlow(),
      notificationSessionsEnded = emptyFlow(),
    )

  @Test
  fun theBasemapIsProjectedIntoTheStateAndShownByDefault() {
    val vm = viewModel { basemap }
    val loaded = assertNotNull(vm.uiState.value.basemap)
    assertEquals("2026-11-15", loaded.sourceDate)
    assertTrue(vm.uiState.value.showBasemap)
    vm.setBasemapVisible(false)
    assertFalse(vm.uiState.value.showBasemap)
    vm.setBasemapVisible(true)
    assertTrue(vm.uiState.value.showBasemap)
  }

  @Test
  fun aBasemapThatFailsToLoadIsLoggedAndTheAppWorksWithoutIt() {
    val previous = DiagnosticsLog.sink
    val warnings = mutableListOf<Pair<String, Throwable?>>()
    val vm =
      try {
        DiagnosticsLog.sink = DiagnosticsSink { tag, _, t -> warnings += tag to t }
        viewModel { throw IllegalArgumentException("bad basemap") }
      } finally {
        DiagnosticsLog.sink = previous
      }
    assertNull(vm.uiState.value.basemap)
    assertTrue(vm.uiState.value.isNetworkLoaded)
    assertNull(vm.uiState.value.networkLoadError)
    val (tag, throwable) = warnings.single()
    assertEquals("StraphangerBasemap", tag)
    assertEquals("bad basemap", throwable?.message)
  }
}
