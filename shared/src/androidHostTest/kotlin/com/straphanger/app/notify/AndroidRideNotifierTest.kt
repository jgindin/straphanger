package com.straphanger.app.notify

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.straphanger.app.data.capture.RideNotificationText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class AndroidRideNotifierTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val notificationManager = context.getSystemService(NotificationManager::class.java)
  private val tapIntents = mutableMapOf<String, PendingIntent>()
  private var turnedOn = true

  private val notifier =
    AndroidRideNotifier(
      context,
      smallIcon = android.R.drawable.ic_dialog_info,
      turnedOn = { turnedOn },
      tapIntent = { rideId ->
        tapIntents.getOrPut(rideId) {
          PendingIntent.getActivity(
            context,
            rideId.hashCode(),
            Intent(Intent.ACTION_VIEW).putExtra("ride", rideId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
          )
        }
      },
    )

  private val text = RideNotificationText("Ride recorded", "Times Sq to Union Sq")

  @Test
  fun postsOnTheRidesRecordedChannelWithTitleTextAndTapIntent() {
    notifier.rideRecorded("ride-1", text)

    val channel = assertNotNull(notificationManager.getNotificationChannel("rides_recorded"))
    assertEquals("Rides recorded", channel.name.toString())
    assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
    val posted = shadowOf(notificationManager).allNotifications.single()
    assertEquals("rides_recorded", posted.channelId)
    assertEquals("Ride recorded", posted.extras.getCharSequence(NotificationCompat.EXTRA_TITLE))
    assertEquals(
      "Times Sq to Union Sq",
      posted.extras.getCharSequence(NotificationCompat.EXTRA_TEXT),
    )
    assertEquals(tapIntents.getValue("ride-1"), posted.contentIntent)
    assertTrue(posted.flags and android.app.Notification.FLAG_AUTO_CANCEL != 0)
  }

  @Test
  fun postsNothingWhenTurnedOff() {
    turnedOn = false

    notifier.rideRecorded("ride-1", text)

    assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
  }

  @Test
  fun postsNothingWhenNotificationsAreDisabled() {
    shadowOf(notificationManager).setNotificationsEnabled(false)

    notifier.rideRecorded("ride-1", text)

    assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
  }

  @Test
  fun twoRidesMakeTwoNotifications() {
    notifier.rideRecorded("ride-1", text)
    notifier.rideRecorded("ride-2", text)

    assertEquals(2, shadowOf(notificationManager).allNotifications.size)
    assertNotNull(shadowOf(notificationManager).getNotification("ride-1", "ride-1".hashCode()))
    assertNotNull(shadowOf(notificationManager).getNotification("ride-2", "ride-2".hashCode()))
  }

  @Test
  fun aFailureToPostIsLoggedNotThrown() {
    val failing =
      AndroidRideNotifier(
        context,
        smallIcon = android.R.drawable.ic_dialog_info,
        turnedOn = { true },
        tapIntent = { throw SecurityException("refused") },
      )

    failing.rideRecorded("ride-1", text)

    assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
  }
}
