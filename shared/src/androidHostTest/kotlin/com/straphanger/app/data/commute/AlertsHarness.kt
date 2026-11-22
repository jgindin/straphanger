package com.straphanger.app.data.commute

import com.straphanger.app.data.realtime.Arrival
import com.straphanger.app.data.realtime.ArrivalBoard
import com.straphanger.app.data.realtime.Direction
import com.straphanger.app.data.realtime.RealtimeSnapshot
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

private val ZONE = TimeZone.of("America/New_York")

internal fun nyMs(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
  LocalDateTime(y, mo, d, h, mi).toInstant(ZONE).toEpochMilliseconds()

internal class FakeClock(var nowMs: Long) : Clock {
  override fun now(): Instant = Instant.fromEpochMilliseconds(nowMs)
}

internal class RecordingScheduler : CommuteAlarmScheduler {
  val scheduled = mutableListOf<Pair<String, Long>>()
  val cancelled = mutableListOf<String>()

  override fun schedule(commuteId: String, atEpochMs: Long) {
    scheduled += commuteId to atEpochMs
  }

  override fun cancel(commuteId: String) {
    cancelled += commuteId
  }
}

/** A [CommuteAlerts] with fakes around it; 2027-03-08 is a Monday. */
internal class AlertsHarness(
  commutes: List<Commute>,
  nowMs: Long = nyMs(2027, 3, 8, 7, 45),
  complexes: List<StationComplex>? = listOf(COMPLEX),
  loadComplexes: suspend () -> List<StationComplex>? = { complexes },
  fetch: (suspend (Collection<String>) -> RealtimeSnapshot)? = null,
) {
  val fetched = mutableListOf<String>()
  private val fetchOrDefault: suspend (Collection<String>) -> RealtimeSnapshot =
    fetch
      ?: { routes ->
        fetched += routes
        // One A train southbound at 168 St, three minutes out.
        val at = nowMs / 1000 + 180
        RealtimeSnapshot(
          ArrivalBoard(listOf(Arrival("A", "A09", Direction.SOUTH, at))),
          emptyList(),
          nowMs / 1000,
          partial = false,
        )
      }

  val store = InMemoryCommuteStore(commutes)
  val scheduler = RecordingScheduler()
  val posted = mutableListOf<Pair<String, CommuteAlertText>>()
  val taps = mutableListOf<CommuteTap>()
  val clock = FakeClock(nowMs)
  val alerts =
    CommuteAlerts(
      store = store,
      complexes = loadComplexes,
      fetch = { fetchOrDefault(it) },
      notifier = { id, text, tap ->
        posted += id to text
        taps += tap
      },
      scheduler = scheduler,
      clock = clock,
      zone = { ZONE },
    )

  companion object {
    val COMPLEX = StationComplex("614", "168 St", listOf("A09"), listOf("A", "C"))
    val WEEKDAYS =
      setOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
      )

    fun commute(id: String = "c1", complexId: String = "614", enabled: Boolean = true) =
      Commute(id, "A", complexId, Direction.SOUTH, WEEKDAYS, 7, 45, enabled)
  }
}
