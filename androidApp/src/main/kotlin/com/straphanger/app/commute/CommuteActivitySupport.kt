package com.straphanger.app.commute

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.straphanger.app.MainActivity
import com.straphanger.app.data.commute.CommuteTap
import com.straphanger.app.data.realtime.Direction
import com.straphanger.app.notify.AndroidCommuteNotifier
import com.straphanger.app.ui.status.CommuteAccess

/** What the platform lets the commute alerts do right now. */
fun readCommuteAccess(context: Context): CommuteAccess =
  CommuteAccess(
    notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled(),
    exactAlarmsAllowed = CommuteAlertsHolder.scheduler(context).canScheduleExact(),
  )

/**
 * The station, line and direction a tapped commute notification carries, or null when the intent is
 * not one or an extra is missing or unknown.
 */
fun commuteTapFrom(intent: Intent?): CommuteTap? {
  val complexId = intent?.getStringExtra(MainActivity.EXTRA_COMMUTE_COMPLEX)
  val routeId = intent?.getStringExtra(MainActivity.EXTRA_COMMUTE_ROUTE)
  val directionName = intent?.getStringExtra(MainActivity.EXTRA_COMMUTE_DIRECTION)
  val direction = Direction.entries.firstOrNull { it.name == directionName }
  return if (complexId != null && routeId != null && direction != null) {
    CommuteTap(complexId, routeId, direction)
  } else {
    null
  }
}

/**
 * Opens the Status tab on [tap]'s station, line and direction. A notification adds
 * [MainActivity.EXTRA_COMMUTE_NOTIFICATION] so the tap also dismisses it; a widget row does not.
 */
fun commuteTapActivityIntent(context: Context, tap: CommuteTap): Intent =
  Intent(context, MainActivity::class.java)
    .putExtra(MainActivity.EXTRA_COMMUTE_COMPLEX, tap.complexId)
    .putExtra(MainActivity.EXTRA_COMMUTE_ROUTE, tap.routeId)
    .putExtra(MainActivity.EXTRA_COMMUTE_DIRECTION, tap.direction.name)
    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

/** Takes down the commute notification [intent] came from, if it names one. */
fun dismissCommuteNotification(context: Context, intent: Intent?) {
  val commuteId = intent?.getStringExtra(MainActivity.EXTRA_COMMUTE_NOTIFICATION) ?: return
  NotificationManagerCompat.from(context).cancel(commuteId, AndroidCommuteNotifier.NOTIFICATION_ID)
}
