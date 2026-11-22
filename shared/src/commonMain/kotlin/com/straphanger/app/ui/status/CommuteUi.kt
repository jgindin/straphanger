package com.straphanger.app.ui.status

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.straphanger.app.data.commute.Commute
import com.straphanger.app.data.commute.StationComplex
import com.straphanger.app.data.commute.newCommuteId
import com.straphanger.app.data.realtime.Direction

/** What the editor opens with: a new commute's starting choices, or [existing] to change. */
internal class CommuteDraft(
  val complexId: String,
  val routes: List<String>,
  val existing: Commute?,
  val routeId: String,
  val direction: Direction,
)

/** The rider's commutes on the Status tab and the editor over them. */
internal class CommuteUi(
  initial: List<Commute>,
  private val complexes: List<StationComplex>,
  var controls: CommuteControls,
) {
  var commutes by mutableStateOf(initial)
    private set

  var editor by mutableStateOf<CommuteDraft?>(null)
    private set

  fun complexName(commute: Commute): String? =
    complexes.firstOrNull { it.complexId == commute.complexId }?.name

  fun add(complex: StationComplex, filter: StatusFilter?) {
    editor =
      CommuteDraft(
        complex.complexId,
        complex.routes,
        null,
        filter?.line ?: complex.routes.firstOrNull().orEmpty(),
        filter?.direction ?: Direction.NORTH,
      )
  }

  fun edit(commute: Commute) {
    val routes = complexes.firstOrNull { it.complexId == commute.complexId }?.routes
    editor =
      CommuteDraft(
        commute.complexId,
        routes ?: listOf(commute.routeId),
        commute,
        commute.routeId,
        commute.direction,
      )
  }

  fun closeEditor() {
    editor = null
  }

  fun save(commute: Commute) {
    val first = commutes.isEmpty()
    val saved = editor?.existing != null
    apply(
      if (saved) commutes.map { if (it.id == commute.id) commute else it } else commutes + commute,
    )
    if (first) controls.onFirstCommuteCreated()
    editor = null
  }

  fun toggle(commute: Commute) {
    apply(commutes.map { if (it.id == commute.id) it.copy(enabled = !it.enabled) else it })
  }

  fun delete(commute: Commute) {
    apply(commutes.filter { it.id != commute.id })
    editor = null
  }

  fun newId(): String = newCommuteId()

  private fun apply(updated: List<Commute>) {
    commutes = updated
    controls.store.write(updated)
    controls.onCommutesChanged(updated)
  }
}

@Composable
internal fun rememberCommuteUi(
  controls: CommuteControls,
  complexes: List<StationComplex>,
): CommuteUi {
  val ui = remember(complexes) { CommuteUi(controls.store.read(), complexes, controls) }
  ui.controls = controls
  return ui
}
