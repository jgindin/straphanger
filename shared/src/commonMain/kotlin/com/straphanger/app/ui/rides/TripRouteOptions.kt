package com.straphanger.app.ui.rides

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.straphanger.app.data.gtfs.AmbiguousRouteCandidate
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.local.RideEntity
import com.straphanger.app.data.local.RouteChoice
import com.straphanger.app.ui.theme.MtaRouteBullet

/**
 * The ways to ride a trip of [legCount] legs: for each leg where an express and a local both run,
 * by leg index, its candidates. A leg with one way to ride is missing.
 */
internal data class TripRouteOptions(
  val legCount: Int,
  val byLeg: Map<Int, List<AmbiguousRouteCandidate>>,
) {
  /** The choices in [routes] that are offered for their leg. */
  fun offered(routes: Map<Int, RouteChoice>): Map<Int, RouteChoice> =
    routes.filter { (legIndex, choice) ->
      byLeg[legIndex].orEmpty().any { routeOf(it) == choice }
    }
}

/** The ways to ride from [origin] to [destination] at [ride]'s time; nothing for one station. */
internal fun routeChoices(
  graphEngine: SubwayGraphEngine,
  ride: RideEntity,
  origin: String,
  destination: String,
): TripRouteOptions {
  if (origin == destination) return TripRouteOptions(0, emptyMap())
  val trip =
    graphEngine.resolveOriginToDestinationAuto(
      originStationId = origin,
      destinationStationId = destination,
      timestampEpochMs = ride.startTimeEpochMs,
      actualDurationMinutes = ride.durationMinutes,
    )
  return TripRouteOptions(trip.legs.size, trip.ambiguousCandidates.groupBy { it.legIndex })
}

internal fun routeOf(candidate: AmbiguousRouteCandidate) =
  RouteChoice(candidate.routeId, candidate.isExpress)

/**
 * "Which train?", then for each leg of [routes] that asks, "Let the app decide" and one chip per
 * line. On a trip with a change each leg's chips are headed by where it starts ("From 14 St") and
 * tagged with its index. [chosen] holds the choices by leg index; a leg missing is left to the app.
 */
@Composable
internal fun LegRouteChips(
  routes: TripRouteOptions,
  graphEngine: SubwayGraphEngine,
  chosen: Map<Int, RouteChoice>,
  onChoose: (legIndex: Int, choice: RouteChoice?) -> Unit,
) {
  val multiLeg = routes.legCount > 1
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
      "Which train?",
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelLarge,
    )
    routes.byLeg
      .toList()
      .sortedBy { it.first }
      .forEach { (legIndex, candidates) ->
        val tagPrefix =
          if (multiLeg) "edit_stations_route_leg${legIndex}_" else "edit_stations_route_"
        if (multiLeg) {
          val from = candidates.firstNotNullOfOrNull { it.fromStationId }
          Text(
            if (from != null) "From ${graphEngine.stationName(from)}" else "Leg ${legIndex + 1}",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.testTag("edit_stations_leg_$legIndex"),
          )
        }
        FlowRow(
          modifier = Modifier.selectableGroup(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          RouteChip("Let the app decide", null, chosen[legIndex] == null, "${tagPrefix}auto") {
            onChoose(legIndex, null)
          }
          candidates.forEachIndexed { index, candidate ->
            val choice = routeOf(candidate)
            RouteChip(
              candidate.label,
              candidate.routeId,
              chosen[legIndex] == choice,
              "$tagPrefix$index",
            ) {
              onChoose(legIndex, choice)
            }
          }
        }
      }
  }
}

@Composable
private fun RouteChip(
  label: String,
  routeId: String?,
  selected: Boolean,
  tag: String,
  onClick: () -> Unit,
) {
  Row(
    modifier =
      Modifier.clip(MaterialTheme.shapes.small)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .border(
          width = if (selected) 2.dp else 1.dp,
          color =
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
          shape = MaterialTheme.shapes.small,
        )
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 7.dp)
        .testTag(tag),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    routeId?.let { MtaRouteBullet(routeId = it, size = 20.dp, fontSize = 10.sp) }
    Text(
      label,
      color =
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
      style =
        MaterialTheme.typography.bodyMedium.copy(
          fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        ),
    )
  }
}
