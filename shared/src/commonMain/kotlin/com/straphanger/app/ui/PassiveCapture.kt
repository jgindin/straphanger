package com.straphanger.app.ui

import com.straphanger.app.data.capture.NetworkServices
import com.straphanger.app.data.capture.PassiveCaptureCoordinator
import com.straphanger.app.data.capture.PassiveCaptureStatus
import com.straphanger.app.data.local.RideEntity
import kotlinx.coroutines.launch

/** Receives the platform's permission and access state. */
interface CaptureAccessControl {
  /**
   * Called by the platform with the current permission state, e.g. on resume and after a permission
   * request.
   */
  fun updateCaptureAccess(access: CaptureAccessState)
}

// 14 St-Union Sq, used when the platform has no last known location. Android also reports leaving
// the macro anchor at registration, which moves the window to the real location.
private const val DEFAULT_WINDOW_LAT = 40.7347
private const val DEFAULT_WINDOW_LNG = -73.9899

/**
 * Runs passive capture (station geofences) while the user's choice and permissions allow it. The
 * coordinator belongs to [com.straphanger.app.data.capture.RideCaptureRuntime], so it records rides
 * on the runtime's DAO and outlives the view model; this controller only starts and stops it.
 */
internal class PassiveCaptureController(private val session: StraphangerSession) :
  CaptureAccessControl {
  private var coordinator: PassiveCaptureCoordinator? = null
  private var captureAccess = CaptureAccessState()
  private var handledGeofencesDroppedCount = 0

  /** Starts showing [created]'s state; null where the platform has no passive capture. */
  fun start(created: PassiveCaptureCoordinator?) {
    created ?: return
    coordinator = created
    session.scope.launch { created.status.collect { status -> showStatus(status) } }
    applyCaptureAccess()
  }

  private fun showStatus(status: PassiveCaptureStatus) {
    session.update {
      it.copy(
        passiveGeofenceEnabled = status.running && status.registeredCount > 0,
        activeGeofenceCount = status.registeredCount,
        passiveCaptureError = status.error ?: captureBlockedReason(),
      )
    }
  }

  override fun updateCaptureAccess(access: CaptureAccessState) {
    captureAccess = access
    session.update {
      it.copy(
        googleMapsNotificationEnabled = access.notificationAccessEnabled,
        rideNotificationsTurnedOn = access.rideNotificationsTurnedOn,
        postNotificationsGranted = access.postNotificationsGranted,
      )
    }
    applyCaptureAccess()
  }

  /** Why passive capture cannot run with the current access state, or null. */
  private fun captureBlockedReason(): String? =
    when {
      !captureAccess.passiveCaptureTurnedOn -> null
      !captureAccess.locationGranted -> "Location permission is needed"
      !captureAccess.locationServicesEnabled -> "Device location is off"
      else -> null
    }

  private fun applyCaptureAccess() {
    val coordinator = coordinator ?: return
    session.update {
      it.copy(passiveCaptureError = coordinator.status.value.error ?: captureBlockedReason())
    }
    val canRun =
      captureAccess.passiveCaptureTurnedOn &&
        captureAccess.locationGranted &&
        captureAccess.locationServicesEnabled
    if (canRun) {
      run(coordinator)
    } else if (coordinator.status.value.running) {
      // With device location off, Play services has already dropped the geofences.
      coordinator.stop()
    }
  }

  /** Starts capture, and registers again when the platform reported dropped geofences. */
  private fun run(coordinator: PassiveCaptureCoordinator) {
    val wasRunning = coordinator.status.value.running
    coordinator.start(
      captureAccess.lastKnownLat ?: DEFAULT_WINDOW_LAT,
      captureAccess.lastKnownLng ?: DEFAULT_WINDOW_LNG,
    )
    if (captureAccess.geofencesDroppedCount != handledGeofencesDroppedCount) {
      handledGeofencesDroppedCount = captureAccess.geofencesDroppedCount
      if (wasRunning) coordinator.reregister()
    }
  }
}

/** The status shown when passive capture records a ride. */
internal fun recordedPassiveRideMessage(services: NetworkServices, ride: RideEntity): String {
  val origin = services.graphEngine.getStation(ride.originStationId)?.name
  val destination = services.graphEngine.getStation(ride.destinationStationId)?.name
  return "Recorded passive ride: $origin → $destination"
}
