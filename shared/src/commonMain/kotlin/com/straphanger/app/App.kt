package com.straphanger.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.straphanger.app.data.capture.GeofenceRegistrar
import com.straphanger.app.data.capture.InMemoryNotificationSessionStore
import com.straphanger.app.data.capture.InMemoryPendingGeofenceEventStore
import com.straphanger.app.data.capture.NotificationSessionStore
import com.straphanger.app.data.capture.PendingGeofenceEventStore
import com.straphanger.app.data.capture.RideCaptureRuntime
import com.straphanger.app.data.commute.Commute
import com.straphanger.app.data.commute.CommuteStore
import com.straphanger.app.data.commute.CommuteTap
import com.straphanger.app.data.commute.InMemoryCommuteStore
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.local.FavoriteStationsStore
import com.straphanger.app.data.local.InMemoryFavoriteStationsStore
import com.straphanger.app.data.local.InMemoryPersistenceBackend
import com.straphanger.app.data.local.RidePersistenceBackend
import com.straphanger.app.data.realtime.RealtimeFeedClient
import com.straphanger.app.data.realtime.RealtimeSnapshot
import com.straphanger.app.ui.AppTab
import com.straphanger.app.ui.CaptureAccessState
import com.straphanger.app.ui.CommuteTapInbox
import com.straphanger.app.ui.RideTapInbox
import com.straphanger.app.ui.StraphangerUiState
import com.straphanger.app.ui.StraphangerViewModel
import com.straphanger.app.ui.about.AboutScreen
import com.straphanger.app.ui.map.SubwayMapScreen
import com.straphanger.app.ui.permissions.BackgroundLocationDisclosure
import com.straphanger.app.ui.permissions.NotificationAccessDisclosure
import com.straphanger.app.ui.rides.DataSourcesAndSyncScreen
import com.straphanger.app.ui.rides.RidesHistoryAndLoggerScreen
import com.straphanger.app.ui.stats.StatsDashboardScreen
import com.straphanger.app.ui.status.CommuteAccess
import com.straphanger.app.ui.status.CommuteControls
import com.straphanger.app.ui.status.StatusScreen
import com.straphanger.app.ui.theme.MtaColors
import kotlin.time.Clock
import kotlinx.coroutines.launch

/**
 * @param backend the rides file; null when [captureRuntime] is given, since the runtime owns it.
 * @param geofenceRegistrar registers station geofences; null where passive capture is not
 *   available.
 * @param captureAccess the platform's current permission state; the platform refreshes it on
 *   resume. Null until the platform has read it, so a view model built while capture is already
 *   running (the app reopened in a live process) does not stop it on a placeholder state.
 * @param pendingGeofenceEvents station entries recorded by the platform receiver.
 * @param notificationSessions Google Maps navigation sessions recorded by the notification
 *   listener.
 * @param onRequestPassiveCapture saves the user's choice to turn passive capture on and starts the
 *   platform's location permission flow.
 * @param onDisablePassiveCapture saves the user's choice to turn passive capture off.
 * @param onRequestNotificationAccess opens the platform's notification access settings.
 * @param onRequestRideNotifications saves the user's choice to be notified of recorded rides and,
 *   where the platform needs it, asks for permission to post notifications.
 * @param onDisableRideNotifications saves the user's choice not to be notified.
 * @param onOpenNotificationSettings opens the system settings where the user can allow this app's
 *   notifications, for when they are blocked.
 * @param appVersion the app's version name, shown on the About screen.
 * @param licensesContent the open-source license list for the About screen; null where the platform
 *   has none.
 * @param favoriteStations where the stations starred on the Status tab are kept.
 * @param commutes where the rider's commutes are kept.
 * @param onCommutesChanged called with the full list after every save, toggle or delete.
 * @param commuteAccess whether notifications and exact alarms are allowed right now.
 * @param onFirstCommuteCreated called when the first commute is saved, to ask for permissions.
 * @param onOpenExactAlarmSettings opens the system screen for allowing exact alarms.
 * @param captureRuntime the process-wide ride capture runtime the background receivers share; when
 *   given, [backend], [geofenceRegistrar], [pendingGeofenceEvents] and [notificationSessions] are
 *   ignored, and when null the view model builds its own runtime from them.
 *
 * Both request callbacks run only after the user taps "Continue" on the matching disclosure.
 */
@Composable
fun App(
  backend: RidePersistenceBackend?,
  geofenceRegistrar: GeofenceRegistrar? = null,
  pendingGeofenceEvents: PendingGeofenceEventStore = InMemoryPendingGeofenceEventStore(),
  notificationSessions: NotificationSessionStore = InMemoryNotificationSessionStore(),
  captureAccess: CaptureAccessState? = CaptureAccessState(),
  onRequestPassiveCapture: () -> Unit = {},
  onDisablePassiveCapture: () -> Unit = {},
  onRequestNotificationAccess: () -> Unit = {},
  onRequestRideNotifications: () -> Unit = {},
  onDisableRideNotifications: () -> Unit = {},
  onOpenNotificationSettings: () -> Unit = {},
  appVersion: String = "",
  licensesContent: (@Composable () -> Unit)? = null,
  favoriteStations: FavoriteStationsStore = InMemoryFavoriteStationsStore(),
  captureRuntime: RideCaptureRuntime? = null,
  commutes: CommuteStore = InMemoryCommuteStore(),
  onCommutesChanged: (List<Commute>) -> Unit = {},
  commuteAccess: CommuteAccess = CommuteAccess(),
  onFirstCommuteCreated: () -> Unit = {},
  onOpenExactAlarmSettings: () -> Unit = {},
) {
  val realtime = remember { RealtimeFeedClient.mta() }
  require(backend != null || captureRuntime != null) { "App needs a backend or a captureRuntime" }
  val viewModel: StraphangerViewModel = viewModel {
    StraphangerViewModel(
      backend ?: InMemoryPersistenceBackend(),
      geofenceRegistrar = geofenceRegistrar,
      pendingGeofenceEvents = pendingGeofenceEvents,
      notificationSessions = notificationSessions,
      runtime = captureRuntime,
    )
  }
  App(
    viewModel,
    captureAccess,
    PlatformHooks(
      onRequestPassiveCapture,
      onDisablePassiveCapture,
      onRequestNotificationAccess,
      onRequestRideNotifications,
      onDisableRideNotifications,
      onOpenNotificationSettings,
      appVersion,
      licensesContent,
      realtime::fetch,
      { Clock.System.now().epochSeconds },
      favoriteStations,
      CommuteControls(
        commutes,
        onCommutesChanged,
        commuteAccess,
        onFirstCommuteCreated,
        onOpenExactAlarmSettings,
        onOpenNotificationSettings,
      ),
    ),
  )
}

/** The app around a given [viewModel]; tests use it to supply one with a fake clock. */
@Composable
internal fun App(
  viewModel: StraphangerViewModel,
  captureAccess: CaptureAccessState?,
  platform: PlatformHooks,
) {
  LaunchedEffect(captureAccess) { captureAccess?.let { viewModel.updateCaptureAccess(it) } }
  LaunchedEffect(Unit) {
    RideTapInbox.pendingRideId.collect {
      RideTapInbox.consume()?.let { rideId -> viewModel.showRide(rideId) }
    }
  }
  var commuteTap by remember { mutableStateOf<CommuteTap?>(null) }
  LaunchedEffect(Unit) {
    CommuteTapInbox.pendingTap.collect {
      CommuteTapInbox.consume()?.let { tap ->
        commuteTap = tap
        viewModel.selectTab(AppTab.STATUS)
      }
    }
  }
  val uiState by viewModel.uiState.collectAsState()
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val overlays = rememberAppOverlays()
  OverlayBackHandlers(overlays)

  StraphangerTheme {
    Column(
      modifier = Modifier.fillMaxSize().background(MtaColors.DarkBg).testTag("straphanger_root"),
    ) {
      AppHeader(uiState.stats)
      uiState.loadError?.let { StorageErrorBanner(it) }
      Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        AppContent(viewModel, uiState, overlays, platform, commuteTap, { commuteTap = null }) {
          message ->
          scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
          }
        }
      }
      SnackbarHost(snackbarHostState, modifier = Modifier.testTag("backup_snackbar"))
      BottomNavBar(uiState) { tab ->
        overlays.closeAll()
        viewModel.selectTab(tab)
      }
    }
  }
}

/** What the platform supplies besides the view model's inputs; see [App]. */
internal class PlatformHooks(
  val onRequestPassiveCapture: () -> Unit,
  val onDisablePassiveCapture: () -> Unit,
  val onRequestNotificationAccess: () -> Unit,
  val onRequestRideNotifications: () -> Unit,
  val onDisableRideNotifications: () -> Unit,
  val onOpenNotificationSettings: () -> Unit,
  val appVersion: String,
  val licensesContent: (@Composable () -> Unit)?,
  /** Reads the MTA's live feeds for the given lines (see [RealtimeFeedClient]). */
  val fetchRealtime: suspend (Collection<String>) -> RealtimeSnapshot,
  val nowEpochSeconds: () -> Long,
  /** Where the Status tab keeps the stations the rider starred. */
  val favoriteStations: FavoriteStationsStore = InMemoryFavoriteStationsStore(),
  val commutes: CommuteControls = CommuteControls(),
)

/** The disclosures and the About screen, shown over the tabs; each survives recreation. */
private class AppOverlays(
  backgroundLocationDisclosure: MutableState<Boolean>,
  notificationDisclosure: MutableState<Boolean>,
  about: MutableState<Boolean>,
) {
  var showBackgroundLocationDisclosure by backgroundLocationDisclosure
  var showNotificationDisclosure by notificationDisclosure
  var showAbout by about

  fun closeAll() {
    showAbout = false
    showBackgroundLocationDisclosure = false
    showNotificationDisclosure = false
  }
}

@Composable
private fun rememberAppOverlays(): AppOverlays {
  val backgroundLocation = rememberSaveable { mutableStateOf(false) }
  val notification = rememberSaveable { mutableStateOf(false) }
  val about = rememberSaveable { mutableStateOf(false) }
  return remember(backgroundLocation, notification, about) {
    AppOverlays(backgroundLocation, notification, about)
  }
}

/** System Back: a disclosure acts as "Not now", then About closes. */
@OptIn(
  ExperimentalComposeUiApi::class,
) // ui-backhandler's BackHandler is still marked experimental in CMP 1.12
@Composable
private fun OverlayBackHandlers(overlays: AppOverlays) {
  BackHandler(enabled = overlays.showBackgroundLocationDisclosure) {
    overlays.showBackgroundLocationDisclosure = false
  }
  BackHandler(enabled = overlays.showNotificationDisclosure) {
    overlays.showNotificationDisclosure = false
  }
  BackHandler(
    enabled =
      overlays.showAbout &&
        !overlays.showBackgroundLocationDisclosure &&
        !overlays.showNotificationDisclosure,
  ) {
    overlays.showAbout = false
  }
}

/** The top NYC Subway header strip. */
/** The loading message, a disclosure, the About screen, or the active tab. */
@Composable
private fun AppContent(
  viewModel: StraphangerViewModel,
  uiState: StraphangerUiState,
  overlays: AppOverlays,
  platform: PlatformHooks,
  commuteTap: CommuteTap?,
  onCommuteTapConsumed: () -> Unit,
  onBackupMessage: (String) -> Unit,
) {
  val graphEngine = viewModel.graphEngine
  when {
    !uiState.isNetworkLoaded || graphEngine == null -> NetworkLoading(uiState.networkLoadError)
    overlays.showBackgroundLocationDisclosure ->
      BackgroundLocationDisclosure(
        onContinue = {
          overlays.showBackgroundLocationDisclosure = false
          platform.onRequestPassiveCapture()
        },
        onNotNow = { overlays.showBackgroundLocationDisclosure = false },
      )
    overlays.showNotificationDisclosure ->
      NotificationAccessDisclosure(
        onContinue = {
          overlays.showNotificationDisclosure = false
          platform.onRequestNotificationAccess()
        },
        onNotNow = { overlays.showNotificationDisclosure = false },
      )
    overlays.showAbout ->
      AboutScreen(
        appVersion = platform.appVersion,
        sourceDate = graphEngine.network.sourceDate,
        basemapSourceDate = uiState.basemap?.sourceDate,
        onBack = { overlays.showAbout = false },
        licensesContent = platform.licensesContent,
      )
    else ->
      TabContent(
        viewModel,
        uiState,
        graphEngine,
        overlays,
        platform,
        commuteTap,
        onCommuteTapConsumed,
        onBackupMessage,
      )
  }
}

@Composable
private fun NetworkLoading(loadError: String?) {
  Box(
    modifier = Modifier.fillMaxSize().testTag("network_loading"),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = loadError ?: "Loading subway network",
      color = if (loadError != null) Color.White else MtaColors.TextSecondary,
      fontSize = 13.sp,
      modifier =
        Modifier.padding(16.dp)
          .testTag(if (loadError != null) "network_load_error" else "network_loading_text"),
    )
  }
}

@Composable
private fun TabContent(
  viewModel: StraphangerViewModel,
  uiState: StraphangerUiState,
  graphEngine: SubwayGraphEngine,
  overlays: AppOverlays,
  platform: PlatformHooks,
  commuteTap: CommuteTap?,
  onCommuteTapConsumed: () -> Unit,
  onBackupMessage: (String) -> Unit,
) {
  when (uiState.activeTab) {
    AppTab.MAP ->
      SubwayMapScreen(
        uiState = uiState,
        network = graphEngine.network,
        onColorModeChange = viewModel::setMapColorMode,
        onRouteFilterSelect = viewModel::setRouteFilter,
        onStationSelect = viewModel::selectStationOnMap,
        onStartRideFromStation = { stId ->
          viewModel.updateLogForm(originId = stId)
          viewModel.selectTab(AppTab.RIDES)
        },
        onEndRideAtStation = { stId ->
          viewModel.updateLogForm(destinationId = stId)
          viewModel.selectTab(AppTab.RIDES)
        },
      )
    AppTab.STATS ->
      StatsDashboardScreen(
        uiState = uiState,
        onGroupingModeChange = viewModel::setStationGroupingMode,
        onVisitFilterModeChange = viewModel::setStationVisitFilterMode,
        onLineSelect = viewModel::showRidesOnRoute,
      )
    AppTab.STATUS ->
      StatusScreen(
        graphEngine.network,
        platform.fetchRealtime,
        platform.nowEpochSeconds,
        platform.favoriteStations,
        commuteTap,
        onCommuteTapConsumed,
        platform.commutes,
      )
    AppTab.RIDES -> RidesTab(viewModel, uiState, graphEngine)
    AppTab.SOURCES -> SourcesTab(viewModel, uiState, overlays, platform, onBackupMessage)
  }
}

@Composable
private fun RidesTab(
  viewModel: StraphangerViewModel,
  uiState: StraphangerUiState,
  graphEngine: SubwayGraphEngine,
) {
  RidesHistoryAndLoggerScreen(
    uiState = uiState,
    graphEngine = graphEngine,
    onUpdateLogForm = { orig, trans, dest, route, exp ->
      viewModel.updateLogForm(orig, trans, dest, route, exp)
    },
    onSubmitQuickRide = { viewModel.submitQuickRide() },
    onConfirmAmbiguousRide = { id, r, exp -> viewModel.confirmAmbiguousRide(id, r, exp) },
    onKeepRideAsIs = viewModel::keepRideAsIs,
    onDeleteRide = viewModel::deleteRide,
    onChangeRideStations = viewModel::changeRideStations,
    onCombineRides = viewModel::combineRides,
    onClearRouteFilter = { viewModel.showRidesOnRoute(null) },
  )
}

@Composable
private fun SourcesTab(
  viewModel: StraphangerViewModel,
  uiState: StraphangerUiState,
  overlays: AppOverlays,
  platform: PlatformHooks,
  onBackupMessage: (String) -> Unit,
) {
  DataSourcesAndSyncScreen(
    uiState = uiState,
    onImportTimelineFile = { readBytes -> viewModel.importTimelineFileInBackground(readBytes) },
    suggestedBackupName = viewModel.suggestedBackupName(),
    onExportBackup = { viewModel.exportBackup() },
    onBackupExported = viewModel::reportBackupExported,
    onBackupExportFailed = viewModel::reportBackupExportFailed,
    onBackupRestoreFailed = viewModel::reportBackupRestoreFailed,
    onImportBackup = { viewModel.importBackup(it) },
    onBackupRestored = viewModel::reportBackupRestored,
    onBackupMessage = onBackupMessage,
    onRequestPassiveCapture = { overlays.showBackgroundLocationDisclosure = true },
    onDisablePassiveCapture = platform.onDisablePassiveCapture,
    onRequestNotificationAccess = {
      // Turning access off opens the same settings screen; the disclosure is for turning it on.
      if (uiState.googleMapsNotificationEnabled) platform.onRequestNotificationAccess()
      else overlays.showNotificationDisclosure = true
    },
    onRequestRideNotifications = platform.onRequestRideNotifications,
    onDisableRideNotifications = platform.onDisableRideNotifications,
    onOpenNotificationSettings = platform.onOpenNotificationSettings,
    onOpenAbout = { overlays.showAbout = true },
  )
}

/** The bottom tab bar; the Rides tab shows how many rides need review. */
