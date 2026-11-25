package com.straphanger.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationManagerCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.LocationServices
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.straphanger.app.capture.AndroidGeofenceRegistrar
import com.straphanger.app.capture.CaptureRuntimeHolder
import com.straphanger.app.capture.NotificationAccess
import com.straphanger.app.capture.PassiveCaptureStorage
import com.straphanger.app.commute.CommuteAlertsHolder
import com.straphanger.app.commute.commuteTapFrom
import com.straphanger.app.commute.dismissCommuteNotification
import com.straphanger.app.commute.readCommuteAccess
import com.straphanger.app.data.capture.RideCaptureRuntime
import com.straphanger.app.data.commute.Commute
import com.straphanger.app.data.commute.CommuteStore
import com.straphanger.app.data.local.FavoriteStationsStore
import com.straphanger.app.data.local.FileFavoriteStationsStore
import com.straphanger.app.permissions.CapturePermissions
import com.straphanger.app.permissions.rememberPassiveCapturePermissionRequest
import com.straphanger.app.permissions.rememberPostNotificationsPermissionRequest
import com.straphanger.app.ui.CaptureAccessState
import com.straphanger.app.ui.CommuteTapInbox
import com.straphanger.app.ui.RideTapInbox
import com.straphanger.app.ui.status.CommuteAccess
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.init
import kotlinx.io.files.Path

class MainActivity : ComponentActivity() {
  // Null until onResume reads it: a placeholder state would stop capture that is already running
  // when the app is reopened in a live process.
  private val captureAccess = mutableStateOf<CaptureAccessState?>(null)
  private val commuteAccess = mutableStateOf(CommuteAccess())

  // The phone's 12/24-hour setting, read again on every resume since changing it recreates nothing.
  private val use24HourTime = mutableStateOf(false)

  // The commutes last saved, so a change can cancel the alarms of the ones it removed.
  private var commuteIds: Set<String> = emptySet()

  override fun onCreate(savedInstanceState: Bundle?) {
    // The app is always dark, so the system bar icons are always light.
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )
    super.onCreate(savedInstanceState)
    FileKit.init(this)
    // A recreation (rotation) must not record the shared route again, nor reopen the ride and force
    // the Rides tab. Reopening from recents redelivers the launching intent, so skip it then too.
    if (savedInstanceState == null && !launchedFromHistory(intent)) {
      handleIncomingShareIntent(intent)
      handleRideTapIntent(intent)
      handleCommuteTapIntent(intent)
    }
    val captureRuntime = CaptureRuntimeHolder.get(applicationContext)
    val versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    val favoriteStations = FileFavoriteStationsStore(Path(filesDir.path, "favorite-stations.json"))
    val commutes = CommuteAlertsHolder.store(this)
    commuteIds = commutes.read().mapTo(HashSet()) { it.id }
    setContent { Content(captureRuntime, versionName, favoriteStations, commutes) }
  }

  @Composable
  private fun Content(
    captureRuntime: RideCaptureRuntime,
    versionName: String,
    favoriteStations: FavoriteStationsStore,
    commutes: CommuteStore,
  ) {
    val requestPassiveCapture =
      rememberPassiveCapturePermissionRequest(onResult = ::refreshCaptureAccess)
    val requestPostNotifications = rememberPostNotificationsPermissionRequest {
      // A denial keeps the choice on; the switch then shows the blocked text.
      refreshCaptureAccess()
    }
    // Asked once, when the first commute is saved: notifications, then exact alarms.
    val requestCommuteNotifications = rememberPostNotificationsPermissionRequest {
      commuteAccess.value = readCommuteAccess(this)
      if (!CommuteAlertsHolder.scheduler(this).canScheduleExact()) openExactAlarmSettings()
    }
    App(
      // The process-wide runtime owns rides.json, capture and navigation sessions.
      backend = null,
      captureRuntime = captureRuntime,
      captureAccess = captureAccess.value,
      onRequestPassiveCapture = {
        PassiveCaptureStorage.setTurnedOn(this, true)
        refreshCaptureAccess()
        requestPassiveCapture()
      },
      onDisablePassiveCapture = {
        PassiveCaptureStorage.setTurnedOn(this, false)
        refreshCaptureAccess()
      },
      onRequestNotificationAccess = { startActivity(NotificationAccess.settingsIntent()) },
      onRequestRideNotifications = {
        PassiveCaptureStorage.setRideNotificationsTurnedOn(this, true)
        refreshCaptureAccess()
        requestPostNotifications()
      },
      onOpenNotificationSettings = {
        startActivity(
          Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        )
      },
      onDisableRideNotifications = {
        PassiveCaptureStorage.setRideNotificationsTurnedOn(this, false)
        refreshCaptureAccess()
      },
      appVersion = versionName,
      favoriteStations = favoriteStations,
      commutes = commutes,
      onCommutesChanged = ::onCommutesChanged,
      commuteAccess = commuteAccess.value,
      onFirstCommuteCreated = requestCommuteNotifications,
      onOpenExactAlarmSettings = ::openExactAlarmSettings,
      use24HourTime = use24HourTime.value,
      licensesContent = {
        val libraries by produceLibraries(R.raw.aboutlibraries)
        LibrariesContainer(libraries)
      },
    )
  }

  override fun onResume() {
    super.onResume()
    refreshCaptureAccess()
    commuteAccess.value = readCommuteAccess(this)
    use24HourTime.value = DateFormat.is24HourFormat(this)
    // Android drops exact alarms without a broadcast when that permission is revoked, as do a
    // force-stop and a restore; setting them all again here is cheap and idempotent.
    CommuteAlertsHolder.commutesChanged(this, emptySet())
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIncomingShareIntent(intent)
    handleRideTapIntent(intent)
    handleCommuteTapIntent(intent)
  }

  /** The UI has already written [commutes]; this sets their alarms and drops removed ones'. */
  private fun onCommutesChanged(commutes: List<Commute>) {
    val kept = commutes.mapTo(HashSet()) { it.id }
    CommuteAlertsHolder.commutesChanged(this, commuteIds - kept)
    commuteIds = kept
  }

  /** The system screen for "Alarms & reminders"; Android 11 and older always allow exact alarms. */
  private fun openExactAlarmSettings() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val intent =
      Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
    try {
      startActivity(intent)
    } catch (e: ActivityNotFoundException) {
      Log.w(TAG, "No screen for allowing exact alarms", e)
    }
  }

  /**
   * Reads the permission and access state; with location granted it also reads the last known
   * location.
   */
  private fun refreshCaptureAccess() {
    val locationManager = getSystemService(android.location.LocationManager::class.java)
    val current =
      CaptureAccessState(
        locationGranted = CapturePermissions.fineLocationGranted(this),
        backgroundLocationGranted = CapturePermissions.backgroundLocationGranted(this),
        locationServicesEnabled =
          locationManager != null && LocationManagerCompat.isLocationEnabled(locationManager),
        notificationAccessEnabled = NotificationAccess.isEnabled(this),
        passiveCaptureTurnedOn = PassiveCaptureStorage.isTurnedOn(this),
        rideNotificationsTurnedOn = PassiveCaptureStorage.rideNotificationsTurnedOn(this),
        postNotificationsGranted = NotificationManagerCompat.from(this).areNotificationsEnabled(),
        geofencesDroppedCount = PassiveCaptureStorage.geofencesDroppedCount(this),
        lastKnownLat = captureAccess.value?.lastKnownLat,
        lastKnownLng = captureAccess.value?.lastKnownLng,
      )
    if (!current.locationGranted) {
      captureAccess.value = current
      return
    }
    try {
      LocationServices.getFusedLocationProviderClient(this).lastLocation.addOnCompleteListener {
        task ->
        val location = if (task.isSuccessful) task.result else null
        captureAccess.value =
          if (location != null) {
            current.copy(lastKnownLat = location.latitude, lastKnownLng = location.longitude)
          } else {
            current
          }
      }
    } catch (e: SecurityException) {
      Log.w(AndroidGeofenceRegistrar.TAG, "Last known location refused", e)
      captureAccess.value = current
    }
  }

  private fun handleIncomingShareIntent(intent: Intent?) {
    if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
      val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
      if (!sharedText.isNullOrBlank()) {
        SharedRouteInbox.enqueueSharedRouteText(sharedText)
      }
    }
  }

  private fun launchedFromHistory(intent: Intent?): Boolean =
    intent != null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0

  /** A tapped "ride recorded" notification carries the ride to open. */
  private fun handleRideTapIntent(intent: Intent?) {
    intent?.getStringExtra(EXTRA_RIDE_ID)?.let { RideTapInbox.open(it) }
  }

  /**
   * A tapped commute notification opens the Status tab on its station, line and direction, and is
   * dismissed: its "See trains" action does not dismiss it on its own.
   */
  private fun handleCommuteTapIntent(intent: Intent?) {
    val tap = commuteTapFrom(intent) ?: return
    CommuteTapInbox.open(tap)
    dismissCommuteNotification(this, intent)
  }

  companion object {
    private const val TAG = "StraphangerCommutes"

    /** The ride a "ride recorded" notification opens. */
    const val EXTRA_RIDE_ID = "com.straphanger.app.RIDE_ID"

    /** The station complex a commute notification opens on the Status tab. */
    const val EXTRA_COMMUTE_COMPLEX = "com.straphanger.app.COMMUTE_COMPLEX"

    /** The line a commute notification opens on the Status tab. */
    const val EXTRA_COMMUTE_ROUTE = "com.straphanger.app.COMMUTE_ROUTE"

    /** The direction a commute notification opens, as a `Direction` name. */
    const val EXTRA_COMMUTE_DIRECTION = "com.straphanger.app.COMMUTE_DIRECTION"

    /** The commute whose notification was tapped, so it can be dismissed. */
    const val EXTRA_COMMUTE_NOTIFICATION = "com.straphanger.app.COMMUTE_NOTIFICATION"
  }
}
