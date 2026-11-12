package com.straphanger.app.ui

import com.straphanger.app.SharedRouteInbox
import com.straphanger.app.data.capture.NavigationNotificationSession
import com.straphanger.app.data.capture.NotificationSessionStore
import com.straphanger.app.data.capture.isDuplicateRide
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Records rides that Google Maps handed to the app: routes shared to it, and navigation sessions
 * the notification listener recorded.
 */
internal class CapturedRideRecorder(
  private val session: StraphangerSession,
  private val notificationSessions: NotificationSessionStore,
  private val notificationSessionsEnded: Flow<Int>,
) {
  fun start(services: NetworkServices) {
    session.scope.launch {
      SharedRouteInbox.pendingSharedTexts.collect { pending ->
        if (pending.isNotEmpty()) {
          SharedRouteInbox.consumeAll().forEach { text -> recordSharedRoute(services, text) }
        }
      }
    }
    session.scope.launch {
      // Collecting replays the current count, so sessions that ended while no view model ran are
      // drained at start.
      notificationSessionsEnded.collect { drainNotificationSessions(services) }
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

  /**
   * Records one ride per ended navigation session: from the first post to the session's end, parsed
   * from its latest text (or its first, if the latest does not name a route). A ride that
   * duplicates one already stored is skipped. Sessions are removed from the store once handled, and
   * kept if the ride could not be saved.
   */
  private suspend fun drainNotificationSessions(services: NetworkServices) {
    val ended = notificationSessions.read().completed
    val handled = mutableListOf<NavigationNotificationSession>()
    for (navigation in ended) {
      if (!recordNavigationSession(services, navigation)) break
      handled += navigation
    }
    if (handled.isEmpty()) return
    // Re-read: the store may have changed while parsing suspended.
    val current = notificationSessions.read()
    notificationSessions.write(current.copy(completed = current.completed - handled.toSet()))
  }

  /** False when the session's ride could not be saved; true when it was saved or skipped. */
  private suspend fun recordNavigationSession(
    services: NetworkServices,
    navigation: NavigationNotificationSession,
  ): Boolean {
    val parser = services.shareParser
    val end = navigation.endMs ?: navigation.lastPostMs
    val record =
      parser.parseAndResolveSharedRoute(navigation.latestText, navigation.firstPostMs, end)
        ?: parser.parseAndResolveSharedRoute(navigation.firstText, navigation.firstPostMs, end)
    if (record == null || isDuplicateRide(session.dao.getAllRides(), record)) return true
    session.dao.insertOrReplaceRide(record)
    session.setStatusIfSaved {
      "Recorded ride from Google Maps navigation: " +
        "${services.stationName(record.ride.originStationId)} → " +
        services.stationName(record.ride.destinationStationId)
    }
    return session.dao.lastSaveSucceeded
  }
}
