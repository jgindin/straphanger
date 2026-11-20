package com.straphanger.app.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The ride a tapped "ride recorded" notification asks the app to open. */
object RideTapInbox {
  private val _pendingRideId = MutableStateFlow<String?>(null)
  val pendingRideId: StateFlow<String?> = _pendingRideId.asStateFlow()

  fun open(rideId: String) {
    _pendingRideId.value = rideId
  }

  /** Returns the pending ride id, if any, and clears it. */
  fun consume(): String? {
    val current = _pendingRideId.value
    _pendingRideId.value = null
    return current
  }
}
