package com.straphanger.app.ui.rides

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import com.straphanger.app.data.local.RouteChoice

/**
 * The trains chosen so far for the legs of rides with a change that are not yet confirmed, by ride
 * id and then leg index. One holder serves the whole Rides tab, so the review banner and a ride's
 * row show the same choices; the app keeps it above the tabs, so they survive scrolling and
 * switching tabs.
 */
class LegChoiceSelections(initial: Map<String, Map<Int, RouteChoice>> = emptyMap()) {
  private val byRide = mutableStateMapOf<String, Map<Int, RouteChoice>>().apply { putAll(initial) }

  /** The choices made so far for [rideId]. */
  fun of(rideId: String): Map<Int, RouteChoice> = byRide[rideId].orEmpty()

  /** Makes [choices] the ones made so far for [rideId]. */
  fun set(rideId: String, choices: Map<Int, RouteChoice>) {
    byRide[rideId] = choices
  }

  /** Forgets [rideId]'s choices, once they are stored. */
  fun clear(rideId: String) {
    byRide.remove(rideId)
  }

  companion object {
    private const val SEPARATOR = "|"

    /** Saves each choice as "rideId|legIndex|routeId|isExpress". */
    val saver: Saver<LegChoiceSelections, List<String>> =
      Saver(
        save = { holder ->
          holder.byRide.flatMap { (rideId, choices) ->
            choices.map { (leg, c) ->
              listOf(rideId, leg, c.routeId, c.isExpress).joinToString(SEPARATOR)
            }
          }
        },
        restore = { saved ->
          val rows = saved.mapNotNull { row -> row.split(SEPARATOR).takeIf { it.size == 4 } }
          LegChoiceSelections(
            rows
              .groupBy { it[0] }
              .mapValues { (_, parts) ->
                parts.associate { it[1].toInt() to RouteChoice(it[2], it[3].toBoolean()) }
              },
          )
        },
      )
  }
}

/** A [LegChoiceSelections] kept across recomposition and recreation of where it is remembered. */
@Composable
fun rememberLegChoiceSelections(): LegChoiceSelections =
  rememberSaveable(saver = LegChoiceSelections.saver) { LegChoiceSelections() }
