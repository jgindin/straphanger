package com.straphanger.app.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.straphanger.app.data.capture.BackgroundWindowRefresh
import com.straphanger.app.data.capture.DynamicGeofenceWindowManager
import com.straphanger.app.data.capture.GeofenceEvent
import com.straphanger.app.data.capture.GeofenceEventBus
import com.straphanger.app.data.capture.PendingGeofenceEventStore

/**
 * Records station entries (not the window anchor) in the pending-event file, then forwards every
 * transition to [GeofenceEventBus]. The file keeps entries that arrive while no view model is
 * running; the coordinator replays it when it starts and on each entry it receives. When no
 * coordinator is running, leaving the window anchor registers a new window around the triggering
 * location here.
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val event = GeofencingEvent.fromIntent(intent) ?: return
    if (event.hasError()) {
      onError(context, event.errorCode)
    } else {
      val pendingEvents = PassiveCaptureStorage.pendingEvents(context)
      transitionsOf(event).forEach { onTransition(context, it, pendingEvents) }
    }
  }

  private fun onError(context: Context, errorCode: Int) {
    Log.w(TAG, "Geofence error: ${GeofenceStatusCodes.getStatusCodeString(errorCode)}")
    // Play services drops every geofence when device location is turned off.
    if (errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
      PassiveCaptureStorage.recordGeofencesDropped(context)
    }
  }

  /** One transition per triggering geofence; none without a triggering location. */
  private fun transitionsOf(event: GeofencingEvent): List<GeofenceEvent> {
    val location = event.triggeringLocation ?: return emptyList()
    val epochMs = location.time.takeIf { it > 0 } ?: System.currentTimeMillis()
    return event.triggeringGeofences.orEmpty().mapNotNull { geofence ->
      when (event.geofenceTransition) {
        Geofence.GEOFENCE_TRANSITION_ENTER ->
          GeofenceEvent.Entered(geofence.requestId, epochMs, location.latitude, location.longitude)
        Geofence.GEOFENCE_TRANSITION_EXIT ->
          GeofenceEvent.Exited(geofence.requestId, epochMs, location.latitude, location.longitude)
        else -> null
      }
    }
  }

  private fun onTransition(
    context: Context,
    transition: GeofenceEvent,
    pendingEvents: PendingGeofenceEventStore,
  ) {
    Log.i(TAG, "$transition")
    if (transition is GeofenceEvent.Entered && isStation(transition.geofenceId)) {
      pendingEvents.append(transition)
    }
    // With no coordinator running (the app process was gone), move the window here.
    if (
      BackgroundWindowRefresh.shouldRefresh(transition, GeofenceEventBus.hasCollectors) &&
        BackgroundGeofenceRefresher.captureAllowed(context)
    ) {
      BackgroundGeofenceRefresher.refresh(this, context, "Anchor exit") {
        transition.lat to transition.lng
      }
    }
    GeofenceEventBus.emit(transition)
  }

  private fun isStation(geofenceId: String): Boolean =
    DynamicGeofenceWindowManager.complexIdForGeofence(geofenceId) != null

  private companion object {
    const val TAG = AndroidGeofenceRegistrar.TAG
  }
}
