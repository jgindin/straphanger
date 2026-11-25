package com.straphanger.app.ui.rides

import com.straphanger.app.data.gtfs.AmbiguousRouteCandidate
import com.straphanger.app.data.local.RideEntity
import kotlin.math.roundToInt

/** What the needs-review banner offers for one ride. */
sealed interface ReviewAction {
  /**
   * Re-resolve leg [legIndex] of the ride on [routeId], express or local; [index] is the
   * candidate's position among that leg's candidates.
   */
  data class ConfirmCandidate(
    val index: Int,
    val routeId: String,
    val isExpress: Boolean,
    val label: String,
    val legIndex: Int = 0,
  ) : ReviewAction

  /** "Looks right": clear the review flag and keep the ride as recorded. */
  data object KeepAsIs : ReviewAction
}

/**
 * The question for a ride and its buttons. On a ride with a change, [legHeadings] names each leg
 * that asks express or local by where it starts ("From 14 St:"), and its candidates are shown under
 * that heading; on a ride with one leg it is empty.
 */
data class RideReview(
  val prompt: String,
  val actions: List<ReviewAction>,
  val legHeadings: Map<Int, String> = emptyMap(),
)

/**
 * The question and choices for a ride that needs review. A ride with route candidates (express or
 * local on the same stretch) offers one button per candidate; on a ride of more than one leg
 * ([legCount]), the candidates are grouped by leg under the name of the station it starts from
 * ([stationName]). A ride without candidates was flagged because a station name matched more than
 * one station; the app cannot offer the alternatives, so it offers only "Looks right".
 */
fun reviewFor(
  ride: RideEntity,
  legCount: Int = 1,
  stationName: (String) -> String = { it },
): RideReview {
  val candidates = ride.ambiguousCandidates
  val overlaps = ride.overlapsRideIds.size
  return when {
    candidates.isNotEmpty() && (legCount > 1 || candidates.any { it.legIndex > 0 }) ->
      reviewByLeg(candidates, stationName)
    candidates.isNotEmpty() ->
      RideReview(
        prompt =
          "Express or local? " +
            candidates.joinToString(" or ") { it.label } +
            " both serve this trip.",
        actions =
          candidates.mapIndexed { index, c ->
            ReviewAction.ConfirmCandidate(index, c.routeId, c.isExpress, "Confirm ${c.label}")
          } + ReviewAction.KeepAsIs,
      )
    overlaps > 0 ->
      RideReview(
        prompt =
          "This ride overlaps $overlaps other ${if (overlaps == 1) "ride" else "rides"} in time, " +
            "and nobody rides two trains at once. Delete or edit the one that is wrong.",
        actions = listOf(ReviewAction.KeepAsIs),
      )
    else ->
      RideReview(
        prompt =
          "A station name in this ride matches more than one station. " +
            "Check the stations; delete the ride if they are wrong.",
        actions = listOf(ReviewAction.KeepAsIs),
      )
  }
}

/** The candidates of a ride with a change, grouped by the leg they are for. */
private fun reviewByLeg(
  candidates: List<AmbiguousRouteCandidate>,
  stationName: (String) -> String,
): RideReview {
  val byLeg = candidates.groupBy { it.legIndex }.toList().sortedBy { it.first }
  // Where each leg starts, when the candidates say (rides stored before 0.37 do not).
  val fromNames = byLeg.associate { (legIndex, legCandidates) ->
    legIndex to legCandidates.firstNotNullOfOrNull { it.fromStationId }?.let(stationName)
  }
  val headings = fromNames.mapValues { (legIndex, from) ->
    if (from != null) "From $from:" else "Leg ${legIndex + 1}:"
  }
  val prompt =
    byLeg.singleOrNull()?.let { (legIndex, legCandidates) ->
      val from = fromNames[legIndex]
      val leg = if (from != null) "the leg from $from" else "leg ${legIndex + 1}"
      "Express or local? ${legCandidates.joinToString(" or ") { it.label }} both serve $leg."
    } ?: "Express or local? Choose the train for each leg."
  val actions = byLeg.flatMap { (legIndex, legCandidates) ->
    legCandidates.mapIndexed { index, c ->
      ReviewAction.ConfirmCandidate(
        index = index,
        routeId = c.routeId,
        isExpress = c.isExpress,
        label = "${c.label} · ${c.estimatedMinutes.roundToInt()} min",
        legIndex = legIndex,
      )
    }
  }
  return RideReview(prompt, actions + ReviewAction.KeepAsIs, headings)
}
