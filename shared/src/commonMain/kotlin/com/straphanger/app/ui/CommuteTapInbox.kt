package com.straphanger.app.ui

import com.straphanger.app.data.commute.CommuteTap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The commute a tapped "See trains" action asks the app to show on the Status tab. */
object CommuteTapInbox {
  private val _pendingTap = MutableStateFlow<CommuteTap?>(null)
  val pendingTap: StateFlow<CommuteTap?> = _pendingTap.asStateFlow()

  fun open(tap: CommuteTap) {
    _pendingTap.value = tap
  }

  /** Returns the pending tap, if any, and clears it. */
  fun consume(): CommuteTap? {
    val current = _pendingTap.value
    _pendingTap.value = null
    return current
  }
}
