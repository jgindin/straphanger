package com.straphanger.app.ui.status

import com.straphanger.app.data.commute.StationComplex
import com.straphanger.app.data.realtime.RealtimeSnapshot
import kotlin.coroutines.cancellation.CancellationException

/** What the Status screen last learned from the MTA, and whether the last try worked. */
internal data class StatusState(
  val snapshot: RealtimeSnapshot? = null,
  /** When the latest refresh failed; the [snapshot] is then older than it looks. */
  val failedAtEpochSeconds: Long? = null,
)

/** Fetches the feeds for [routes]; on failure keeps the previous data and records the failure. */
internal suspend fun refreshStatus(
  previous: StatusState,
  routes: Collection<String>,
  fetch: suspend (Collection<String>) -> RealtimeSnapshot,
  nowEpochSeconds: Long,
): StatusState =
  try {
    StatusState(snapshot = fetch(routes))
  } catch (e: CancellationException) {
    throw e
  } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
    // The failure is shown to the user as stale data, not lost.
    previous.copy(failedAtEpochSeconds = nowEpochSeconds)
  }

private const val MAX_RESULTS = 25

/**
 * Complexes whose name contains [query], those that start with it first; empty for a blank query.
 */
internal fun searchComplexes(all: List<StationComplex>, query: String): List<StationComplex> {
  val q = query.trim()
  return if (q.isEmpty()) emptyList()
  else
    all
      .filter { it.name.contains(q, ignoreCase = true) }
      .sortedBy { if (it.name.startsWith(q, ignoreCase = true)) 0 else 1 }
      .take(MAX_RESULTS)
}

/** [favorites] with [complexId] added at the end, or removed when it is already there. */
internal fun toggleFavorite(favorites: List<String>, complexId: String): List<String> =
  if (complexId in favorites) favorites - complexId else favorites + complexId

private const val SECONDS_PER_MINUTE = 60
private const val SHOW_SECONDS_BELOW = 90

internal fun ageText(seconds: Long): String =
  if (seconds < SHOW_SECONDS_BELOW) "${seconds.coerceAtLeast(0)} s"
  else "${seconds / SECONDS_PER_MINUTE} min"
