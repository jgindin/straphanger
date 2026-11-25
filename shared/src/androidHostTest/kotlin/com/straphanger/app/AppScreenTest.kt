package com.straphanger.app

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onChildAt
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.straphanger.app.data.commute.Commute
import com.straphanger.app.data.commute.InMemoryCommuteStore
import com.straphanger.app.data.local.InMemoryFavoriteStationsStore
import com.straphanger.app.data.local.InMemoryPersistenceBackend
import com.straphanger.app.data.local.RidePersistenceBackend
import com.straphanger.app.testutil.BundledBasemap
import com.straphanger.app.testutil.BundledNetwork
import com.straphanger.app.testutil.FixedClock
import com.straphanger.app.ui.AppTab
import com.straphanger.app.ui.CaptureAccessState
import com.straphanger.app.data.realtime.RealtimeSnapshot
import com.straphanger.app.ui.StraphangerViewModel
import com.straphanger.app.ui.status.CommuteAccess
import com.straphanger.app.ui.status.CommuteControls
import kotlin.time.Instant
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

internal fun hasTestTagPrefix(prefix: String): SemanticsMatcher =
  SemanticsMatcher("test tag starts with $prefix") { node ->
    node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
  }

internal fun hasTestTagSuffix(suffix: String): SemanticsMatcher =
  SemanticsMatcher("test tag ends with $suffix") { node ->
    node.config.getOrNull(SemanticsProperties.TestTag)?.endsWith(suffix) == true
  }

/**
 * The real screens rendered on the JVM under Robolectric, with a view model on an in-memory
 * backend, a fixed clock and the bundled network. These port the logic-level checks of the emulator
 * tests in androidApp/src/androidTest so they run in seconds.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
// Native graphics so the map canvas is really drawn.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class AppScreenTest {

  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  protected var passiveCaptureRequests = 0
  protected var notificationAccessRequests = 0
  protected var passiveCaptureDisables = 0
  protected var rideNotificationRequests = 0
  protected var rideNotificationDisables = 0
  protected var notificationSettingsOpens = 0
  /**
   * What the platform reports; a test can change it while the app is shown. Null is the platform
   * not having read it yet.
   */
  protected var captureAccess by mutableStateOf<CaptureAccessState?>(CaptureAccessState())
  protected val viewModels = mutableListOf<StraphangerViewModel>()

  @After
  fun cancelViewModels() {
    viewModels.forEach { it.viewModelScope.cancel() }
  }

  protected fun newViewModel(
    backend: RidePersistenceBackend = InMemoryPersistenceBackend()
  ): StraphangerViewModel =
    StraphangerViewModel(
        backend = backend,
        clock = FixedClock(Instant.parse("2026-11-11T14:00:00Z")),
        networkLoader = { BundledNetwork.data },
        basemapLoader = { BundledBasemap.data },
        geofenceEvents = emptyFlow(),
        notificationSessionsEnded = emptyFlow(),
      )
      .also { viewModels += it }

  /** What the Status tab's feed fetch does; tests replace it. Offline by default. */
  protected var realtime: suspend (Collection<String>) -> RealtimeSnapshot = {
    throw java.io.IOException("offline")
  }
  protected var realtimeNow: Long = 1_800_000_000L

  /** Where the Status tab keeps starred stations; a test can seed it or read it back. */
  protected var favorites = InMemoryFavoriteStationsStore()

  /** The commutes the Status tab keeps, and what it reports to the platform. */
  protected var commuteStore = InMemoryCommuteStore()
  protected val commuteChanges = mutableListOf<List<Commute>>()
  protected var firstCommuteCreations = 0
  protected var exactAlarmSettingsOpens = 0
  protected var commuteAccess = CommuteAccess()
  /** The phone's 12/24-hour setting as the commutes see it; 24-hour unless a test says not. */
  protected var use24Hour = true

  protected fun launchApp(
    viewModel: StraphangerViewModel = newViewModel(),
    withLicenses: Boolean = true,
    awaitNetwork: Boolean = true,
    restoration: StateRestorationTester? = null,
  ): StraphangerViewModel {
    val content: @Composable () -> Unit = {
      App(
        viewModel = viewModel,
        captureAccess = captureAccess,
        platform =
          PlatformHooks(
            onRequestPassiveCapture = { passiveCaptureRequests++ },
            onDisablePassiveCapture = { passiveCaptureDisables++ },
            onRequestNotificationAccess = { notificationAccessRequests++ },
            onRequestRideNotifications = { rideNotificationRequests++ },
            onDisableRideNotifications = { rideNotificationDisables++ },
            onOpenNotificationSettings = { notificationSettingsOpens++ },
            appVersion = "9.8.7",
            licensesContent = if (withLicenses) ({ Text("Apache License 2.0") }) else null,
            fetchRealtime = { realtime(it) },
            nowEpochSeconds = { realtimeNow },
            favoriteStations = favorites,
            commutes =
              CommuteControls(
                commuteStore,
                { commuteChanges += it },
                commuteAccess,
                { firstCommuteCreations++ },
                { exactAlarmSettingsOpens++ },
                { notificationSettingsOpens++ },
                use24Hour = use24Hour,
              ),
          ),
      )
    }
    if (restoration != null) restoration.setContent(content) else composeRule.setContent(content)
    if (awaitNetwork) {
      composeRule.waitUntil(timeoutMillis = 5_000) {
        composeRule.onAllNodesWithTag("network_loading").fetchSemanticsNodes().isEmpty()
      }
    }
    return viewModel
  }

  protected fun openTab(name: String) {
    composeRule.onNodeWithTag("nav_tab_$name").performClick()
  }

  /** Taps the top bar's gear, which pushes Settings over the current tab. */
  protected fun openSettings() {
    composeRule.onNodeWithTag("top_bar_settings").performClick()
  }

  protected fun pressBack() {
    composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
    composeRule.waitForIdle()
  }

  /** Opens the Stats tab, reads its Total Rides card, then goes back to the tab it was on. */
  protected fun statsRideCount(): Int {
    val startTab =
      AppTab.entries.first { tab ->
        composeRule
          .onNodeWithTag("nav_tab_${tab.name}")
          .fetchSemanticsNode()
          .config
          .getOrNull(SemanticsProperties.Selected) == true
      }
    openTab("STATS")
    val count =
      composeRule
        .onNodeWithTag("stat_card_total_rides")
        .onChildAt(1)
        .fetchSemanticsNode()
        .config
        .getOrNull(SemanticsProperties.Text)!!
        .first()
        .text
        .toInt()
    openTab(startTab.name)
    return count
  }
}
