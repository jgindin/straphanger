package com.straphanger.app.data.capture

/**
 * Tells the user a ride was recorded in the background. Called once per ride that passive capture
 * or a Google Maps navigation session saved; never for a failed save, a skipped duplicate, or a
 * ride the user entered.
 */
fun interface RideNotifier {
  fun rideRecorded(rideId: String, text: RideNotificationText)

  companion object {
    /** Posts nothing; used where the platform has no notifications, and in tests. */
    val None = RideNotifier { _, _ -> }
  }
}
