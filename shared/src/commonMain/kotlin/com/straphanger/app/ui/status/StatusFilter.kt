package com.straphanger.app.ui.status

import androidx.compose.runtime.saveable.listSaver
import com.straphanger.app.data.realtime.Arrival
import com.straphanger.app.data.realtime.Direction

/** The one line and direction the Status tab is narrowed to; an express counts as its line. */
internal data class StatusFilter(val line: String, val direction: Direction) {
  fun matches(arrival: Arrival): Boolean =
    arrival.direction == direction && (arrival.routeId == line || arrival.routeId == line + "X")

  val label: String
    get() = "Showing $line ${if (direction == Direction.NORTH) "Northbound" else "Southbound"}"

  companion object {
    val Saver =
      listSaver<StatusFilter?, String>(
        save = { filter -> filter?.let { listOf(it.line, it.direction.name) }.orEmpty() },
        restore = { saved ->
          if (saved.size == 2) StatusFilter(saved[0], Direction.valueOf(saved[1])) else null
        },
      )
  }
}
