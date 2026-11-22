package com.straphanger.app.data.realtime

import kotlinx.serialization.Serializable

/**
 * Which way a train is heading: the MTA's `N` (uptown / north) or `S` (downtown / south) suffix.
 */
@Serializable
enum class Direction(val suffix: Char) {
  NORTH('N'),
  SOUTH('S'),
}

/** One predicted train at a station. */
data class Arrival(
  val routeId: String,
  val stationId: String,
  val direction: Direction,
  val epochSeconds: Long,
) {
  /** Whole minutes from [nowEpochSeconds], never negative: 0 means arriving now. */
  fun minutesFrom(nowEpochSeconds: Long): Int =
    ((epochSeconds - nowEpochSeconds).coerceAtLeast(0) / SECONDS_PER_MINUTE).toInt()
}

private const val SECONDS_PER_MINUTE = 60L

/** A train that was predicted this many seconds ago or less is still shown as arriving now. */
private const val ARRIVED_GRACE_SECONDS = 30L

/** Predicted arrivals from one or more feeds, looked up by station. */
class ArrivalBoard internal constructor(private val arrivals: List<Arrival>) {
  private val byStation = arrivals.groupBy { it.stationId }

  val size: Int
    get() = arrivals.size

  /**
   * The next trains at [stationId], soonest first, at most [perLine] for each line and direction.
   * Trains already gone more than a short grace period ago are dropped.
   */
  fun arrivalsAt(stationId: String, nowEpochSeconds: Long, perLine: Int = 3): List<Arrival> =
    byStation[stationId]
      .orEmpty()
      .filter { it.epochSeconds >= nowEpochSeconds - ARRIVED_GRACE_SECONDS }
      .sortedBy { it.epochSeconds }
      .groupBy { it.routeId to it.direction }
      .values
      .flatMap { it.take(perLine) }
      .sortedBy { it.epochSeconds }

  companion object {
    val EMPTY = ArrivalBoard(emptyList())

    internal fun from(feeds: List<FeedMessage>): ArrivalBoard =
      ArrivalBoard(
        feeds.flatMap { feed -> feed.entity.mapNotNull { it.tripUpdate }.flatMap(::arrivalsOf) },
      )

    private fun arrivalsOf(update: TripUpdate): List<Arrival> =
      update.stopTimeUpdate.mapNotNull { stop ->
        val direction = Direction.entries.firstOrNull { stop.stopId.endsWith(it.suffix) }
        val time = (stop.arrival?.time ?: stop.departure?.time)?.takeIf { it > 0 }
        if (direction == null || time == null || update.trip.routeId.isEmpty()) null
        else Arrival(update.trip.routeId, stop.stopId.dropLast(1), direction, time)
      }
  }
}
