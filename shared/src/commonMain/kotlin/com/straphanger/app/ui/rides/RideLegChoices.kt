package com.straphanger.app.ui.rides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.straphanger.app.data.local.RouteChoice
import com.straphanger.app.ui.theme.confirmButtonColors
import com.straphanger.app.ui.theme.quietButtonColors

/**
 * The express-or-local choices of a ride with a change: one group per leg that asks, headed by
 * where the leg starts ("From 14 St:"). Tapping a candidate chooses it for its leg (it is then
 * shown selected, here and wherever else the ride's choices show, through [selections]); once every
 * leg has a choice, [onConfirm] gets them all by leg index. With one such leg, one tap confirms.
 */
@Composable
internal fun LegChoiceGroups(
  rideId: String,
  review: RideReview,
  selections: LegChoiceSelections,
  onConfirm: (Map<Int, RouteChoice>) -> Unit,
) {
  val candidates = review.actions.filterIsInstance<ReviewAction.ConfirmCandidate>()
  // Only choices still offered count; the ride's legs may have changed since they were made.
  val chosen =
    selections.of(rideId).filter { (leg, choice) ->
      candidates.any { it.legIndex == leg && RouteChoice(it.routeId, it.isExpress) == choice }
    }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    review.legHeadings
      .toList()
      .sortedBy { it.first }
      .forEach { (legIndex, heading) ->
        Text(
          heading,
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.testTag("review_leg_${rideId}_leg$legIndex"),
        )
        FlowRow(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          candidates
            .filter { it.legIndex == legIndex }
            .forEach { c ->
              val choice = RouteChoice(c.routeId, c.isExpress)
              val isChosen = chosen[legIndex] == choice
              Button(
                onClick = {
                  val all = chosen + (legIndex to choice)
                  if (all.keys.containsAll(review.legHeadings.keys)) {
                    selections.clear(rideId)
                    onConfirm(all)
                  } else {
                    selections.set(rideId, all)
                  }
                },
                colors = if (isChosen) confirmButtonColors() else quietButtonColors(),
                modifier =
                  Modifier.semantics { selected = isChosen }
                    .testTag("confirm_candidate_${rideId}_leg${legIndex}_${c.index}"),
              ) {
                Text(c.label)
              }
            }
        }
      }
  }
}
