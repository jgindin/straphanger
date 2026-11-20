package com.straphanger.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.straphanger.app.data.local.FileFavoriteStationsStore
import com.straphanger.app.permissions.CapturePermissions
import com.straphanger.app.permissions.rememberPassiveCapturePermissionRequest
import com.straphanger.app.permissions.rememberPostNotificationsPermissionRequest
import com.straphanger.app.ui.CaptureAccessState
import com.straphanger.app.ui.RideTapInbox
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.init
import kotlinx.io.files.Path

class MainActivity : ComponentActivity() {
  // Null until onResume reads it: a placeholder state would stop capture that is already running
  // when the app is reopened in a live process.
  private val captureAccess = mutableStateOf<CaptureAccessState?>(null)

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
    }
    val captureRuntime = CaptureRuntimeHolder.get(applicationContext)
    val versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    val favoriteStations = FileFavoriteStationsStore(Path(filesDir.path, "favorite-stations.json"))
    setContent {
      val requestPassiveCapture =
        rememberPassiveCapturePermissionRequest(onResult = ::refreshCaptureAccess)
      val requestPostNotifications = rememberPostNotificationsPermissionRequest {
        // A denial keeps the choice on; the switch then shows the blocked text.
        refreshCaptureAccess()
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
        licensesContent = {
          val libraries by produceLibraries(R.raw.aboutlibraries)
          LibrariesContainer(libraries)
        },
      )
    }
  }

  override fun onResume() {
    super.onResume()
    refreshCaptureAccess()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIncomingShareIntent(intent)
    handleRideTapIntent(intent)
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

  companion object {
    /** The ride a "ride recorded" notification opens. */
    const val EXTRA_RIDE_ID = "com.straphanger.app.RIDE_ID"
  }
}
