package com.straphanger.app.data.commute

import com.straphanger.app.data.realtime.RealtimeSnapshot
import com.straphanger.app.diagnostics.DiagnosticsLog
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone

/**
 * How long a fired alarm waits, from when it starts its work, for the network and the MTA before
 * saying it couldn't get train times; it must stay under the receiver's own limit.
 */
const val COMMUTE_FETCH_TIMEOUT_MS = 8_000L

/** An alarm that fires later than this after its time posts nothing. */
const val LATE_SKIP_MS = 15 * 60_000L

private const val MS_PER_SECOND = 1_000L

private const val MS_PER_MINUTE = 60_000L

/**
 * Keeps each commute's alarm set and, when one fires, posts its live departures. [complexes] loads
 * the station complexes, waiting for the network if it has to; null means it could not be loaded.
 */
class CommuteAlerts(
  private val store: CommuteStore,
  private val complexes: suspend () -> List<StationComplex>?,
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
    if (lateMs <= LATE_SKIP_MS) {
      post(commute)
    } else {
      DiagnosticsLog.warn(
        COMMUTE_LOG_TAG,
        "A commute alarm fired ${lateMs / MS_PER_MINUTE} min late; skipped",
      )
    }
  }

  /**
   * Posts [commute]'s departures. Posts nothing when the network could not be loaded, and cancels
   * the alarm of a commute whose station is no longer listed. The fetch runs while the network
   * loads, and both share one [COMMUTE_FETCH_TIMEOUT_MS] limit from the start.
   */
  private suspend fun post(commute: Commute) = coroutineScope {
    val snapshot = async { withTimeoutOrNull(COMMUTE_FETCH_TIMEOUT_MS) { fetchOrNull(commute) } }
    val all = withTimeoutOrNull(COMMUTE_FETCH_TIMEOUT_MS) { complexes() }
    val complex = all?.firstOrNull { it.complexId == commute.complexId }
    if (complex == null) snapshot.cancel()
    when {
      all == null ->
        DiagnosticsLog.warn(COMMUTE_LOG_TAG, "A commute fired before the network loaded")
      complex == null -> scheduler.cancel(commute.id)
      else -> postFor(commute, complex, snapshot)
    }
  }

  private suspend fun postFor(
    commute: Commute,
    complex: StationComplex,
    snapshot: Deferred<RealtimeSnapshot?>,
  ) {
    val text = commuteAlertText(commute, complex, snapshot.await(), nowSeconds())
    notifier.post(
      commute.id,
      text,
      CommuteTap(commute.complexId, commute.routeId, commute.direction),
    )
  }

  private fun nowSeconds(): Long = clock.now().toEpochMilliseconds() / MS_PER_SECOND

  /**
   * Sets the alarm of every commute that can fire, and cancels the rest. Suspends while [complexes]
   * loads; if it gives null, every station is assumed to still exist.
   */
  suspend fun rescheduleAll() {
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

  /**
   * Saves [commutes], dropping the alarms of any that were removed; suspends like [rescheduleAll].
   */
  suspend fun save(commutes: List<Commute>) {
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
      DiagnosticsLog.warn(COMMUTE_LOG_TAG, "Train times could not be fetched for a commute", e)
      null
    }
}
