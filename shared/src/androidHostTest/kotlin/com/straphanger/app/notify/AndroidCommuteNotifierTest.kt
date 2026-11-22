package com.straphanger.app.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.straphanger.app.data.commute.CommuteAlertText
import com.straphanger.app.data.commute.CommuteTap
import com.straphanger.app.data.realtime.Direction
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class AndroidCommuteNotifierTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val notificationManager = context.getSystemService(NotificationManager::class.java)
  private val tapIntents = mutableMapOf<String, PendingIntent>()
  private val taps = mutableListOf<CommuteTap>()

  private val notifier =
    AndroidCommuteNotifier(
      context,
      smallIcon = android.R.drawable.ic_dialog_info,
      tapIntent = { tap, commuteId ->
        taps += tap
        tapIntents.getOrPut(commuteId) {
          PendingIntent.getActivity(
            context,
            commuteId.hashCode(),
            Intent(Intent.ACTION_VIEW).putExtra("commute", commuteId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
          )
        }
      },
    )

  private val tap = CommuteTap("611", "6", Direction.SOUTH)
  private val text = CommuteAlertText("6 Southbound · 77 St", "3, 9, 14 min", null)

  private fun posted(commuteId: String): Notification =
    assertNotNull(shadowOf(notificationManager).getNotification(commuteId, 1))

  @Test
  fun postsOnTheCommuteChannelWithSeeTrains() {
    notifier.post("c1", text, tap)

    val channel = assertNotNull(notificationManager.getNotificationChannel("commute_alerts"))
    assertEquals("Commute alerts", channel.name.toString())
    assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
    val posted = posted("c1")
    assertEquals("commute_alerts", posted.channelId)
    assertEquals("6 Southbound · 77 St", posted.extras.getCharSequence(NotificationCompat.EXTRA_TITLE))
    assertEquals("3, 9, 14 min", posted.extras.getCharSequence(NotificationCompat.EXTRA_TEXT))
    assertEquals(tapIntents.getValue("c1"), posted.contentIntent)
    val action = posted.actions.single()
    assertEquals("See trains", action.title.toString())
    assertEquals(tapIntents.getValue("c1"), action.actionIntent)
    assertEquals(listOf(tap), taps)
    assertNull(posted.extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT))
  }

  @Test
  fun bigTextIncludesTheAlertLine() {
    notifier.post("c1", text.copy(alertLine = "Delays on the 6"), tap)

    assertEquals(
      "3, 9, 14 min\nDelays on the 6",
      posted("c1").extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT).toString(),
    )
  }

  @Test
  fun postsNothingWhenBlocked() {
    shadowOf(notificationManager).setNotificationsEnabled(false)

    notifier.post("c1", text, tap)

    assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
  }

  @Test
  fun twoCommutesPostSeparately() {
    notifier.post("c1", text, tap)
    notifier.post("c2", text.copy(title = "L Eastbound"), tap)
    notifier.post("c1", text.copy(text = "now"), tap)

    assertEquals(2, shadowOf(notificationManager).allNotifications.size)
    assertEquals("now", posted("c1").extras.getCharSequence(NotificationCompat.EXTRA_TEXT))
    assertEquals("L Eastbound", posted("c2").extras.getCharSequence(NotificationCompat.EXTRA_TITLE))
  }

  @Test
  fun aFailureToPostIsLoggedNotThrown() {
    val failing =
      AndroidCommuteNotifier(
        context,
        smallIcon = android.R.drawable.ic_dialog_info,
        tapIntent = { _, _ -> throw SecurityException("refused") },
      )

    failing.post("c1", text, tap)

    assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
  }
}
