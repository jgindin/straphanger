package com.straphanger.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.LocationServices
import com.straphanger.app.capture.AndroidGeofenceRegistrar
import com.straphanger.app.capture.NotificationAccess
import com.straphanger.app.data.local.FileRidePersistenceBackend
import com.straphanger.app.permissions.CapturePermissions
import com.straphanger.app.permissions.rememberPassiveCapturePermissionRequest
import com.straphanger.app.ui.CaptureAccessState
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.init
import kotlinx.io.files.Path

class MainActivity : ComponentActivity() {
    private val captureAccess = mutableStateOf(CaptureAccessState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileKit.init(this)
        handleIncomingShareIntent(intent)
        val geofenceRegistrar = AndroidGeofenceRegistrar(applicationContext)
        setContent {
            val requestPassiveCapture = rememberPassiveCapturePermissionRequest(onResult = ::refreshCaptureAccess)
            App(
                backend = FileRidePersistenceBackend(Path(filesDir.path, "rides.json")),
                geofenceRegistrar = geofenceRegistrar,
                captureAccess = captureAccess.value,
                onRequestPassiveCapture = requestPassiveCapture,
                onRequestNotificationAccess = { startActivity(NotificationAccess.settingsIntent()) }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshCaptureAccess()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingShareIntent(intent)
    }

    /** Reads the permission and access state; with location granted it also reads the last known location. */
    private fun refreshCaptureAccess() {
        val locationManager = getSystemService(android.location.LocationManager::class.java)
        val current = CaptureAccessState(
            locationGranted = CapturePermissions.fineLocationGranted(this),
            backgroundLocationGranted = CapturePermissions.backgroundLocationGranted(this),
            locationServicesEnabled = locationManager != null && LocationManagerCompat.isLocationEnabled(locationManager),
            notificationAccessEnabled = NotificationAccess.isEnabled(this),
            lastKnownLat = captureAccess.value.lastKnownLat,
            lastKnownLng = captureAccess.value.lastKnownLng
        )
        if (!current.locationGranted) {
            captureAccess.value = current
            return
        }
        try {
            LocationServices.getFusedLocationProviderClient(this).lastLocation.addOnCompleteListener { task ->
                val location = if (task.isSuccessful) task.result else null
                captureAccess.value = if (location != null) {
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
}
