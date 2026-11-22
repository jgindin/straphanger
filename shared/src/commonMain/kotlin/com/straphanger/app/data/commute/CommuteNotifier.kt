package com.straphanger.app.data.commute

import com.straphanger.app.data.realtime.Direction

/** What tapping a commute's notification opens: the Status tab at that station, line and way. */
data class CommuteTap(val complexId: String, val routeId: String, val direction: Direction)

/** Posts a commute's notification; a new one for the same [commuteId] replaces the last. */
fun interface CommuteNotifier {
  fun post(commuteId: String, text: CommuteAlertText, tap: CommuteTap)

  companion object {
    val None = CommuteNotifier { _, _, _ -> }
  }
}
