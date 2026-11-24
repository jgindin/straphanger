package com.straphanger.app.widget

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.straphanger.app.notify.AlarmCommuteScheduler
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(AndroidJUnit4::class)
class AlarmWidgetRefreshSchedulerTest {
  class TestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
  }

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val alarmManager = context.getSystemService(AlarmManager::class.java)
  private val scheduler = AlarmWidgetRefreshScheduler(context, TestReceiver::class.java)

  private fun alarms() = shadowOf(alarmManager).scheduledAlarms

  private fun kindOf(alarm: ShadowAlarmManager.ScheduledAlarm) =
    shadowOf(alarm.operation)
      .savedIntent
      .let {
        it.getStringExtra(AlarmWidgetRefreshScheduler.EXTRA_WIDGET_ALARM) to
          it.getIntExtra(AlarmWidgetRefreshScheduler.EXTRA_WIDGET_ID, -1)
      }

  @Before
  fun allowExactAlarms() {
    ShadowAlarmManager.setCanScheduleExactAlarms(true)
  }

  @Test
  fun refreshIsANonWakingExactAlarmWhenAllowed() {
    scheduler.scheduleRefresh(7, 5_000L)

    val alarm = alarms().single()
    assertEquals(AlarmManager.RTC, alarm.type)
    assertEquals(5_000L, alarm.triggerAtMs)
    assertEquals(ShadowAlarmManager.WINDOW_EXACT, alarm.windowLengthMs)
    assertFalse(alarm.isAllowWhileIdle)
    assertEquals("refresh" to 7, kindOf(alarm))
  }

  @Test
  fun refreshUsesAThirtySecondWindowWhenExactIsNotAllowed() {
    ShadowAlarmManager.setCanScheduleExactAlarms(false)

    scheduler.scheduleRefresh(7, 5_000L)

    val alarm = alarms().single()
    assertEquals(AlarmManager.RTC, alarm.type)
    assertEquals(30_000L, alarm.windowLengthMs)
    assertEquals(5_000L, alarm.triggerAtMs)
  }

  @Test
  fun staleRedrawIsAWakingInexactAllowWhileIdleAlarm() {
    scheduler.scheduleStaleRedraw(7, 9_000L)

    val alarm = alarms().single()
    assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
    assertTrue(alarm.isAllowWhileIdle)
    assertEquals(ShadowAlarmManager.WINDOW_HEURISTIC, alarm.windowLengthMs)
    assertEquals("stale" to 7, kindOf(alarm))
  }

  @Test
  fun aNewRefreshReplacesTheOld() {
    scheduler.scheduleRefresh(7, 5_000L)
    scheduler.scheduleRefresh(7, 6_000L)

    assertEquals(6_000L, alarms().single().triggerAtMs)
  }

  @Test
  fun twoWidgetsHaveDistinctAlarms() {
    scheduler.scheduleRefresh(1, 1_000L)
    scheduler.scheduleRefresh(2, 2_000L)
    scheduler.scheduleStaleRedraw(1, 3_000L)

    assertEquals(
      setOf("refresh" to 1, "refresh" to 2, "stale" to 1),
      alarms().map(::kindOf).toSet(),
    )
    assertEquals(3, alarms().size)
  }

  @Test
  fun cancelRemovesBoth() {
    scheduler.scheduleRefresh(1, 1_000L)
    scheduler.scheduleStaleRedraw(1, 3_000L)
    scheduler.scheduleRefresh(2, 2_000L)

    scheduler.cancel(1)

    assertEquals(listOf("refresh" to 2), alarms().map(::kindOf))
  }

  @Test
  fun widgetAlarmsNeverCollideWithCommuteAlarms() {
    // Both are the first alarm of their scheduler, so both use request code 0.
    AlarmCommuteScheduler(context, TestReceiver::class.java).schedule("c1", 1_000L)
    scheduler.scheduleRefresh(1, 2_000L)

    assertEquals(2, alarms().size)
  }
}
