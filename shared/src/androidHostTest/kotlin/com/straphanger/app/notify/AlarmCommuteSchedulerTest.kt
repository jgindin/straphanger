package com.straphanger.app.notify

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(AndroidJUnit4::class)
class AlarmCommuteSchedulerTest {
  class TestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
  }

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val alarmManager = context.getSystemService(AlarmManager::class.java)
  private val scheduler = AlarmCommuteScheduler(context, TestReceiver::class.java)

  private fun alarms() = shadowOf(alarmManager).scheduledAlarms

  private fun commuteOf(alarm: ShadowAlarmManager.ScheduledAlarm): String? =
    shadowOf(alarm.operation)
      .savedIntent
      .getStringExtra(AlarmCommuteScheduler.EXTRA_COMMUTE_ID)

  // Robolectric's default is no permission; the platform's below Android 12 is always allowed.
  @Before
  fun allowExactAlarms() {
    ShadowAlarmManager.setCanScheduleExactAlarms(true)
  }

  @Test
  fun twoCommutesHaveDistinctAlarms() {
    scheduler.schedule("c1", 1_000L)
    scheduler.schedule("c2", 2_000L)
    scheduler.schedule("c1", 3_000L) // replaces c1's own alarm only

    val byCommute = alarms().associateBy(::commuteOf)
    assertEquals(setOf("c1", "c2"), byCommute.keys)
    assertEquals(3_000L, byCommute.getValue("c1").triggerAtMs)
    assertEquals(2_000L, byCommute.getValue("c2").triggerAtMs)
    assertNotEquals(byCommute.getValue("c1").operation, byCommute.getValue("c2").operation)
    byCommute.values.forEach {
      assertEquals(AlarmManager.RTC_WAKEUP, it.type)
      assertTrue(it.isAllowWhileIdle)
      assertEquals(ShadowAlarmManager.WINDOW_EXACT, it.windowLengthMs)
      assertEquals(TestReceiver::class.java.name, shadowOf(it.operation).savedIntent.component?.className)
    }
    assertTrue(scheduler.canScheduleExact())
  }

  @Test
  fun cancelRemovesOnlyThatCommute() {
    scheduler.schedule("c1", 1_000L)
    scheduler.schedule("c2", 2_000L)

    scheduler.cancel("c1")

    assertEquals(listOf("c2"), alarms().map(::commuteOf))
  }

  @Test
  fun requestCodesStayTheSameForANewScheduler() {
    scheduler.schedule("c1", 1_000L)
    scheduler.schedule("c2", 2_000L)

    // A new process: c2 keeps its code, so cancelling it from there removes the same alarm.
    AlarmCommuteScheduler(context, TestReceiver::class.java).cancel("c2")

    assertEquals(listOf("c1"), alarms().map(::commuteOf))
  }

  @Test
  fun fallsBackToInexactWithoutPermission() {
    ShadowAlarmManager.setCanScheduleExactAlarms(false)

    scheduler.schedule("c1", 1_000L)

    assertFalse(scheduler.canScheduleExact())
    val alarm = alarms().single()
    assertTrue(alarm.isAllowWhileIdle)
    assertEquals(ShadowAlarmManager.WINDOW_HEURISTIC, alarm.windowLengthMs)
    assertEquals(1_000L, alarm.triggerAtMs)
  }
}
