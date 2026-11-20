package com.straphanger.app.data.capture

import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.data.local.SubwayDao
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Turns Google Maps navigation sessions that the notification listener recorded as ended into
 * rides. [onSaved] is called for each ride that was saved, and not for one skipped as a duplicate
 * or one that could not be saved.
 */
class NavigationSessionRecorder(
  private val dao: SubwayDao,
  private val services: () -> NetworkServices?,
  private val store: NotificationSessionStore,
  private val onSaved: (CompleteRideRecord) -> Unit,
) {
  // Two drains at once would both read the same ended sessions.
  private val mutex = Mutex()

  /**
   * Records one ride per ended navigation session: from the first post to the session's end, parsed
   * from its latest text (or its first, if the latest does not name a route). A ride that
   * duplicates one already stored is skipped. Sessions are removed from the store once handled, and
   * kept if the ride could not be saved. Does nothing until the network has loaded.
   */
  suspend fun drain() {
    val services = services() ?: return
    mutex.withLock {
      val ended = store.read().completed
      val handled = mutableListOf<NavigationNotificationSession>()
      for (navigation in ended) {
        if (!record(services, navigation)) break
        handled += navigation
      }
      if (handled.isEmpty()) return
      // Re-read: the store may have changed while parsing suspended.
      val current = store.read()
      store.write(current.copy(completed = current.completed - handled.toSet()))
    }
  }

  /** False when the session's ride could not be saved; true when it was saved or skipped. */
  private suspend fun record(
    services: NetworkServices,
    navigation: NavigationNotificationSession,
  ): Boolean {
    val parser = services.shareParser
    val end = navigation.endMs ?: navigation.lastPostMs
    val record =
      parser.parseAndResolveSharedRoute(navigation.latestText, navigation.firstPostMs, end)
        ?: parser.parseAndResolveSharedRoute(navigation.firstText, navigation.firstPostMs, end)
    if (record == null || isDuplicateRide(dao.getAllRides(), record)) return true
    dao.insertOrReplaceRide(record)
    if (dao.lastSaveSucceeded) onSaved(record)
    return dao.lastSaveSucceeded
  }
}
