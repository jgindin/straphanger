package com.straphanger.app.ui

import com.straphanger.app.SharedRouteInbox
import com.straphanger.app.data.capture.NetworkServices
import kotlinx.coroutines.launch

/**
 * Records routes the user shared to the app from Google Maps. Navigation sessions are recorded by
 * [com.straphanger.app.data.capture.RideCaptureRuntime]; shared routes are the user's own doing, so
 * they are not notified.
 */
internal class CapturedRideRecorder(private val session: StraphangerSession) {
  fun start(services: NetworkServices) {
    session.scope.launch {
      SharedRouteInbox.pendingSharedTexts.collect { pending ->
        if (pending.isNotEmpty()) {
          SharedRouteInbox.consumeAll().forEach { text -> recordSharedRoute(services, text) }
        }
      }
    }
  }

  private suspend fun recordSharedRoute(services: NetworkServices, text: String) {
    val parsed = services.shareParser.parseAndResolveSharedRoute(text) ?: return
    session.dao.insertOrReplaceRide(parsed)
    session.setStatusIfSaved {
      "Auto-captured ride from Google Maps: " +
        "${parsed.ride.originStationId} → ${parsed.ride.destinationStationId}"
    }
  }
}
