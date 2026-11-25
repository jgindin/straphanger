package com.straphanger.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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
import com.straphanger.app.data.local.CompleteRideRecord
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
import com.straphanger.app.ui.about.LicensesScreen
import com.straphanger.app.ui.map.SubwayMapScreen
import com.straphanger.app.ui.permissions.BackgroundLocationDisclosure
import com.straphanger.app.ui.permissions.NotificationAccessDisclosure
import com.straphanger.app.ui.rides.LogRideFab
import com.straphanger.app.ui.rides.RidesHistoryAndLoggerScreen
import com.straphanger.app.ui.rides.rememberLegChoiceSelections
import com.straphanger.app.ui.settings.SettingsScreen
import com.straphanger.app.ui.stats.StatsDashboardScreen
import com.straphanger.app.ui.status.CommuteAccess
import com.straphanger.app.ui.status.CommuteControls
import com.straphanger.app.ui.status.StatusScreen
import com.straphanger.app.ui.theme.StraphangerTheme
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
 * @param use24HourTime whether commute times show as "17:30" rather than "5:30 PM", following the
 *   phone's setting.
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
  use24HourTime: Boolean = false,
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
        use24HourTime,
      ),
    ),
  )
}

/**
 * The Rides tab's hooks: delete with Undo in [snackbarHostState], [onListAtTopChange], and the
 * express-or-local choices in progress, kept here above the tabs so switching tabs keeps them.
 */
@Composable
private fun rememberRidesTabHooks(
  viewModel: StraphangerViewModel,
  snackbarHostState: SnackbarHostState,
  onListAtTopChange: (Boolean) -> Unit,
): RidesTabHooks {
  val scope = rememberCoroutineScope()
  val legSelections = rememberLegChoiceSelections()
  return remember(viewModel, snackbarHostState, scope, legSelections) {
    RidesTabHooks(
      onRideDeleted = { rec ->
        viewModel.deleteRide(rec.ride.rideId).also { deleted ->
          if (deleted) scope.launch { offerUndo(viewModel, rec, snackbarHostState) }
        }
      },
      onListAtTopChange = onListAtTopChange,
      legSelections = legSelections,
    )
  }
}

/** The app around a given [viewModel]; tests use it to supply one with a fake clock. */
@Composable
internal fun App(
  viewModel: StraphangerViewModel,
  captureAccess: CaptureAccessState?,
  platform: PlatformHooks,
) {
  LaunchedEffect(captureAccess) { captureAccess?.let { viewModel.updateCaptureAccess(it) } }
  val backStack = rememberAppBackStack()
  var commuteTap by remember { mutableStateOf<CommuteTap?>(null) }
  CollectNotificationTaps(viewModel, backStack) { commuteTap = it }
  val uiState by viewModel.uiState.collectAsState()
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  var ridesListAtTop by remember { mutableStateOf(true) }
  val ridesTab = rememberRidesTabHooks(viewModel, snackbarHostState) { ridesListAtTop = it }
  BackStackHandler(backStack)

  StraphangerTheme {
    AppScaffold(
      activeTab = uiState.activeTab,
      pushed = backStack.top,
      pendingReviewCount = uiState.stats.pendingReviewCount,
      storageError = uiState.loadError,
      settingsAvailable = uiState.isNetworkLoaded && viewModel.graphEngine != null,
      snackbarHostState = snackbarHostState,
      onOpenSettings = { backStack.push(AppDestination.SETTINGS) },
      onBack = backStack::pop,
      onSelectTab = { tab ->
        backStack.clear()
        viewModel.selectTab(tab)
      },
      floatingActionButton = {
        val ridesShowing =
          uiState.activeTab == AppTab.RIDES && backStack.top == null && uiState.isNetworkLoaded
        if (ridesShowing && viewModel.graphEngine != null) {
          LogRideFab(expanded = ridesListAtTop) { viewModel.openLogRideSheet() }
        }
      },
    ) {
      AppContent(
        viewModel,
        uiState,
        backStack,
        platform,
        ridesTab,
        commuteTap,
        { commuteTap = null },
      ) { message ->
        scope.launch {
          snackbarHostState.currentSnackbarData?.dismiss()
          snackbarHostState.showSnackbar(message)
        }
      }
    }
  }
}

/** Opens the tab a tapped ride or commute notification asks for, over any pushed screen. */
@Composable
private fun CollectNotificationTaps(
  viewModel: StraphangerViewModel,
  backStack: AppBackStack,
  onCommuteTap: (CommuteTap) -> Unit,
) {
  LaunchedEffect(Unit) {
    RideTapInbox.pendingRideId.collect {
      RideTapInbox.consume()?.let { rideId ->
        // Like a tab tap: the Rides tab shows at once, not behind Settings or About.
        backStack.clear()
        viewModel.showRide(rideId)
      }
    }
  }
  LaunchedEffect(Unit) {
    CommuteTapInbox.pendingTap.collect {
      CommuteTapInbox.consume()?.let { tap ->
        onCommuteTap(tap)
        // Like a tab tap: the Status tab shows at once, not behind Settings or About.
        backStack.clear()
        viewModel.selectTab(AppTab.STATUS)
      }
    }
  }
}

/**
 * Says [rec], just deleted, is gone, in a snackbar with Undo, which puts the ride back exactly as
 * it was. A message already showing is dismissed first, so only the newest deletion can be undone.
 */
private suspend fun offerUndo(
  viewModel: StraphangerViewModel,
  rec: CompleteRideRecord,
  snackbarHostState: SnackbarHostState,
) {
  snackbarHostState.currentSnackbarData?.dismiss()
  val result =
    snackbarHostState.showSnackbar(
      message = "Ride deleted",
      actionLabel = "Undo",
      duration = SnackbarDuration.Long,
    )
  if (result == SnackbarResult.ActionPerformed) viewModel.restoreRide(rec)
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

/** The loading message, the pushed screen on top, or the active tab. */
@Composable
private fun AppContent(
  viewModel: StraphangerViewModel,
  uiState: StraphangerUiState,
  backStack: AppBackStack,
  platform: PlatformHooks,
  ridesTab: RidesTabHooks,
  commuteTap: CommuteTap?,
  onCommuteTapConsumed: () -> Unit,
  onBackupMessage: (String) -> Unit,
) {
  val graphEngine = viewModel.graphEngine
  val pushed = backStack.top
  // Each tab's saved state (map zoom and pan, scroll positions) is kept while a screen is pushed
  // over it, and dropped when another tab is chosen, as it was before.
  val tabStates = rememberSaveableStateHolder()
  val activeTab = uiState.activeTab
  DisposableEffect(activeTab) { onDispose { tabStates.removeState(activeTab.name) } }
  when {
    !uiState.isNetworkLoaded || graphEngine == null -> NetworkLoading(uiState.networkLoadError)
    pushed != null ->
      PushedScreen(pushed, viewModel, uiState, graphEngine, backStack, platform, onBackupMessage)
    else ->
      tabStates.SaveableStateProvider(activeTab.name) {
        TabContent(
          viewModel,
          uiState,
          graphEngine,
          platform,
          ridesTab,
          commuteTap,
          onCommuteTapConsumed,
        )
      }
  }
}

/** Settings, About, the license list or a disclosure; each closes by popping [backStack]. */
@Composable
private fun PushedScreen(
  destination: AppDestination,
  viewModel: StraphangerViewModel,
  uiState: StraphangerUiState,
  graphEngine: SubwayGraphEngine,
  backStack: AppBackStack,
  platform: PlatformHooks,
  onBackupMessage: (String) -> Unit,
) {
  when (destination) {
    AppDestination.SETTINGS ->
      SettingsContent(viewModel, uiState, backStack, platform, onBackupMessage)
    AppDestination.ABOUT ->
      AboutScreen(
        appVersion = platform.appVersion,
        sourceDate = graphEngine.network.sourceDate,
        onOpenLicenses = {
          if (platform.licensesContent != null) backStack.push(AppDestination.LICENSES)
        },
        basemapSourceDate = uiState.basemap?.sourceDate,
        licensesAvailable = platform.licensesContent != null,
      )
    AppDestination.LICENSES -> {
      val licenses = platform.licensesContent
      // Only pushed when there is a list; a restored stack on a platform without one goes back.
      if (licenses != null) LicensesScreen(licenses) else LaunchedEffect(Unit) { backStack.pop() }
    }
    AppDestination.BACKGROUND_LOCATION_DISCLOSURE ->
      BackgroundLocationDisclosure(
        onContinue = {
          backStack.pop()
          platform.onRequestPassiveCapture()
        },
        onNotNow = backStack::pop,
      )
    AppDestination.NOTIFICATION_DISCLOSURE ->
      NotificationAccessDisclosure(
        onContinue = {
          backStack.pop()
          platform.onRequestNotificationAccess()
        },
        onNotNow = backStack::pop,
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
      color =
        if (loadError != null) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodyMedium,
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
  platform: PlatformHooks,
  ridesTab: RidesTabHooks,
  commuteTap: CommuteTap?,
  onCommuteTapConsumed: () -> Unit,
) {
  when (uiState.activeTab) {
    AppTab.MAP ->
      SubwayMapScreen(
        uiState = uiState,
        network = graphEngine.network,
        onColorModeChange = viewModel::setMapColorMode,
        onRouteFilterSelect = viewModel::setRouteFilter,
        onStationSelect = viewModel::selectStationOnMap,
        // The Rides tab opens with the log sheet, that station filled in.
        onStartRideFromStation = { stId ->
          viewModel.selectStationOnMap(null)
          viewModel.selectTab(AppTab.RIDES)
          viewModel.openLogRideSheet(originId = stId)
        },
        onEndRideAtStation = { stId ->
          viewModel.selectStationOnMap(null)
          viewModel.selectTab(AppTab.RIDES)
          viewModel.openLogRideSheet(destinationId = stId)
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
    AppTab.RIDES -> RidesTab(viewModel, uiState, graphEngine, ridesTab)
  }
}

@Composable
private fun RidesTab(
  viewModel: StraphangerViewModel,
  uiState: StraphangerUiState,
  graphEngine: SubwayGraphEngine,
  hooks: RidesTabHooks,
) {
  RidesHistoryAndLoggerScreen(
    uiState = uiState,
    graphEngine = graphEngine,
    onUpdateLogForm = { orig, trans, dest, route, exp ->
      viewModel.updateLogForm(orig, trans, dest, route, exp)
    },
    onSubmitQuickRide = { viewModel.submitQuickRide() },
    onConfirmAmbiguousRide = viewModel::confirmAmbiguousRide,
    onKeepRideAsIs = viewModel::keepRideAsIs,
    onDeleteRide = hooks.onRideDeleted,
    onChangeRideStations = viewModel::changeRideStations,
    onCombineRides = viewModel::combineRides,
    onClearRouteFilter = { viewModel.showRidesOnRoute(null) },
    onCloseLogRideSheet = viewModel::closeLogRideSheet,
    onListAtTopChange = hooks.onListAtTopChange,
    legSelections = hooks.legSelections,
  )
}

/** Settings: ride capture, commute alerts, your data and About. */
@Composable
private fun SettingsContent(
  viewModel: StraphangerViewModel,
  uiState: StraphangerUiState,
  backStack: AppBackStack,
  platform: PlatformHooks,
  onBackupMessage: (String) -> Unit,
) {
  SettingsScreen(
    uiState = uiState,
    commute = platform.commutes,
    appVersion = platform.appVersion,
    onImportTimelineFile = { readBytes -> viewModel.importTimelineFileInBackground(readBytes) },
    suggestedBackupName = viewModel.suggestedBackupName(),
    onExportBackup = { viewModel.exportBackup() },
    onBackupExported = viewModel::reportBackupExported,
    onBackupExportFailed = viewModel::reportBackupExportFailed,
    onBackupRestoreFailed = viewModel::reportBackupRestoreFailed,
    onImportBackup = { viewModel.importBackup(it) },
    onBackupRestored = viewModel::reportBackupRestored,
    onBackupMessage = onBackupMessage,
    onRequestPassiveCapture = { backStack.push(AppDestination.BACKGROUND_LOCATION_DISCLOSURE) },
    onDisablePassiveCapture = platform.onDisablePassiveCapture,
    onRequestNotificationAccess = {
      // Turning access off opens the same settings screen; the disclosure is for turning it on.
      if (uiState.googleMapsNotificationEnabled) platform.onRequestNotificationAccess()
      else backStack.push(AppDestination.NOTIFICATION_DISCLOSURE)
    },
    onRequestRideNotifications = platform.onRequestRideNotifications,
    onDisableRideNotifications = platform.onDisableRideNotifications,
    onOpenNotificationSettings = platform.onOpenNotificationSettings,
    onOpenAbout = { backStack.push(AppDestination.ABOUT) },
  )
}
