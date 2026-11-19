package com.straphanger.app.ui

import com.straphanger.app.data.local.InMemoryPersistenceBackend
import com.straphanger.app.data.local.RidePersistenceBackend
import com.straphanger.app.testutil.FixedClock
import com.straphanger.app.testutil.TestBasemap
import com.straphanger.app.testutil.TestNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException

/**
 * The network loading, navigation and backup collaborators, through the view model's API.
 *
 * Shared routes (SharedRouteInbox) are not tested here: the inbox is a process-wide singleton, and
 * view models left running by other test classes would consume the text first.
 */
class ViewModelControllersTest {

  private val clock = FixedClock(Instant.parse("2026-11-11T14:00:00Z"))

  private fun viewModel(
    backend: RidePersistenceBackend = InMemoryPersistenceBackend(),
  ): StraphangerViewModel =
    StraphangerViewModel(
      backend = backend,
      clock = clock,
      dispatcher = Dispatchers.Unconfined,
      networkLoader = { TestNetwork.data },
      basemapLoader = { TestBasemap.empty },
      geofenceEvents = emptyFlow(),
      notificationSessionsEnded = emptyFlow(),
    )

  // Network loading

  @Test
  fun networkLoadFailuresAreReported() {
    val failures =
      listOf(
        IOException("no file"),
        IllegalArgumentException("bad json"),
        IllegalStateException("bad state"),
      )
    for (failure in failures) {
      val vm =
        StraphangerViewModel(
          backend = InMemoryPersistenceBackend(),
          clock = clock,
          dispatcher = Dispatchers.Unconfined,
          networkLoader = { throw failure },
          basemapLoader = { TestBasemap.empty },
          geofenceEvents = emptyFlow(),
          notificationSessionsEnded = emptyFlow(),
        )
      assertFalse(vm.uiState.value.isNetworkLoaded)
      assertEquals(null, vm.graphEngine)
      assertEquals(
        "The subway network could not be loaded: ${failure.message}",
        vm.uiState.value.networkLoadError,
      )
    }
  }

  // Navigation

  @Test
  fun logRideSheetOpensWithStationsAndCloses() {
    val vm = viewModel()
    val before = vm.uiState.value
    vm.openLogRideSheet(destinationId = StationIds.EIGHTY_SIXTH_LEX)
    with(vm.uiState.value) {
      assertTrue(isLogRideSheetOpen)
      assertEquals(before.logOriginStationId, logOriginStationId, "unchanged when not given")
      assertEquals(StationIds.EIGHTY_SIXTH_LEX, logDestinationStationId)
    }

    vm.openLogRideSheet(originId = StationIds.UNION_SQ_LEX, transferStationId = "X")
    assertEquals(StationIds.UNION_SQ_LEX, vm.uiState.value.logOriginStationId)
    assertEquals("X", vm.uiState.value.logTransferStationId)

    vm.closeLogRideSheet()
    assertFalse(vm.uiState.value.isLogRideSheetOpen)
  }

  @Test
  fun selectingTabsAndStations() {
    val vm = viewModel()
    vm.selectTab(AppTab.STATS)
    assertEquals(AppTab.STATS, vm.uiState.value.activeTab)
    vm.selectStationOnMap(StationIds.UNION_SQ_LEX)
    assertEquals(StationIds.UNION_SQ_LEX, vm.uiState.value.selectedStationId)
    vm.selectStationOnMap(null)
    assertEquals(null, vm.uiState.value.selectedStationId)
  }

  @Test
  fun showingRidesOnALineOpensTheRidesTabAndClearingKeepsIt() {
    val vm = viewModel()
    vm.selectTab(AppTab.STATS)

    vm.showRidesOnRoute("L")
    assertEquals("L", vm.uiState.value.ridesRouteFilter)
    assertEquals(AppTab.RIDES, vm.uiState.value.activeTab)

    vm.selectTab(AppTab.MAP)
    vm.showRidesOnRoute(null)
    assertEquals(null, vm.uiState.value.ridesRouteFilter)
    assertEquals(AppTab.MAP, vm.uiState.value.activeTab)
  }

  @Test
  fun updateLogFormKeepsFieldsNotGiven() {
    val vm = viewModel()
    vm.updateLogForm(
      originId = StationIds.UNION_SQ_LEX,
      preferredRouteId = "6",
      forceExpress = false,
    )
    vm.updateLogForm(destinationId = StationIds.EIGHTY_SIXTH_LEX)
    with(vm.uiState.value) {
      assertEquals(StationIds.UNION_SQ_LEX, logOriginStationId)
      assertEquals(StationIds.EIGHTY_SIXTH_LEX, logDestinationStationId)
      assertEquals("6", logPreferredRouteId)
      assertEquals(false, logForceExpress)
    }
  }

  // Backup

  @Test
  fun exportAndRestoreReportTheirOutcome() = runTest {
    val vm = viewModel()
    vm.submitQuickRide(timestampEpochMs = 1794405600000L)
    val json = vm.exportBackup()
    assertTrue(json.contains("rides"), json)

    assertEquals("Exported 1 ride to rides.json", vm.reportBackupExported("rides.json"))
    assertEquals("Exported 1 ride", vm.reportBackupExported(null))
    assertEquals("Exported 1 ride", vm.reportBackupExported(" "))
    assertEquals(vm.uiState.value.statusMessage, "Exported 1 ride")
    assertEquals("Backup export failed: disk full", vm.reportBackupExportFailed("disk full"))
    assertEquals("Backup export failed: unknown error", vm.reportBackupExportFailed(null))

    val fresh = viewModel()
    val restored = fresh.importBackup(json.encodeToByteArray())
    assertEquals(Result.success(1), restored)
    assertEquals("Restored 1 ride from backup", fresh.reportBackupRestored(restored))
    assertEquals(1, fresh.uiState.value.rides.size)
  }

  @Test
  fun restoreRejectsFilesThatAreNotBackups() = runTest {
    val vm = viewModel()
    val invalidUtf8 = byteArrayOf(0xC3.toByte(), 0x28)
    val notText = vm.importBackup(invalidUtf8)
    assertTrue(notText.isFailure)
    assertEquals(
      "Could not restore: the file is not a Straphanger backup",
      vm.reportBackupRestored(notText),
    )
    assertTrue(vm.importBackup("""{"hello": 1}""".encodeToByteArray()).isFailure)
    assertEquals(
      "Could not restore: the rides could not be saved (read-only)",
      vm.reportBackupRestoreFailed("read-only"),
    )
    assertEquals(
      "Could not restore: the rides could not be saved (unknown error)",
      vm.reportBackupRestoreFailed(null),
    )
  }

  @Test
  fun suggestedBackupNameUsesTheClockDate() {
    val name = viewModel().suggestedBackupName()
    // 14:00 UTC is 11 November in every time zone from UTC-14 to UTC+9.
    assertTrue(name.matches(Regex("straphanger-backup-2026-11-1[12]")), name)
  }
}
