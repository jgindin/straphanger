package com.straphanger.app.data.commute

import com.straphanger.app.data.realtime.RealtimeSnapshot
import com.straphanger.app.diagnostics.DiagnosticsLog
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone

/** How long a fired alarm waits for the MTA before saying it couldn't get train times. */
const val COMMUTE_FETCH_TIMEOUT_MS = 8_000L

/** An alarm that fires later than this after its time posts nothing. */
const val LATE_SKIP_MS = 15 * 60_000L

private const val MS_PER_SECOND = 1_000L

/**
 * Keeps each commute's alarm set and, when one fires, posts its live departures. [complexes] is
 * null while the network has not loaded yet.
 */
class CommuteAlerts(
  private val store: CommuteStore,
  private val complexes: () -> List<StationComplex>?,
  private val fetch: suspend (Collection<String>) -> RealtimeSnapshot,
  private val notifier: CommuteNotifier,
  private val scheduler: CommuteAlarmScheduler,
  private val clock: Clock = Clock.System,
  private val zone: () -> TimeZone = TimeZone::currentSystemDefault,
) {
  /**
   * Handles the alarm for [commuteId]: sets the next one, then posts this one unless it is late.
   */
  suspend fun fire(commuteId: String) {
    val commute = store.read().firstOrNull { it.id == commuteId }
    if (commute == null || !commute.enabled) {
      scheduler.cancel(commuteId)
      return
    }
    val now = clock.now().toEpochMilliseconds()
    val tz = zone()
    // Scheduled first so a crash during the fetch never leaves the commute without an alarm.
    scheduleNext(commute, now, tz)
    val lateMs = commute.occurrenceAtOrBeforeMs(now, tz)?.let { now - it } ?: 0L
    val complex = complexes()?.firstOrNull { it.complexId == commute.complexId }
    if (lateMs > LATE_SKIP_MS || complex == null) return
    val snapshot = withTimeoutOrNull(COMMUTE_FETCH_TIMEOUT_MS) { fetchOrNull(commute) }
    val nowSeconds = clock.now().toEpochMilliseconds() / MS_PER_SECOND
    notifier.post(
      commute.id,
      commuteAlertText(commute, complex, snapshot, nowSeconds),
      CommuteTap(commute.complexId, commute.routeId, commute.direction),
    )
  }

  /** Sets the alarm of every commute that can fire, and cancels the rest. */
  fun rescheduleAll() {
    val known = complexes()?.mapTo(HashSet()) { it.complexId }
    val now = clock.now().toEpochMilliseconds()
    val tz = zone()
    store.read().forEach { commute ->
      val stationExists = known == null || commute.complexId in known
      if (commute.enabled && commute.days.isNotEmpty() && stationExists) {
        scheduleNext(commute, now, tz)
      } else {
        scheduler.cancel(commute.id)
      }
    }
  }

  /** Saves [commutes], dropping the alarms of any that were removed. */
  fun save(commutes: List<Commute>) {
    val kept = commutes.mapTo(HashSet()) { it.id }
    val removed = store.read().map { it.id }.filter { it !in kept }
    store.write(commutes)
    removed.forEach(scheduler::cancel)
    rescheduleAll()
  }

  private fun scheduleNext(commute: Commute, now: Long, tz: TimeZone) {
    val next = commute.nextOccurrenceMs(now, tz)
    if (next == null) scheduler.cancel(commute.id) else scheduler.schedule(commute.id, next)
  }

  private suspend fun fetchOrNull(commute: Commute): RealtimeSnapshot? =
    try {
      fetch(listOf(commute.routeId, commute.routeId + "X"))
    } catch (e: CancellationException) {
      throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
      // Told to the rider as "Couldn't get train times"; logged for the diagnostics.
      DiagnosticsLog.warn(LOG_TAG, "Train times could not be fetched for a commute", e)
      null
    }

  private companion object {
    const val LOG_TAG = "StraphangerCommutes"
  }
}
