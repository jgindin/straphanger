package com.straphanger.app

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.straphanger.app.data.commute.Commute
import com.straphanger.app.data.commute.stationComplexes
import com.straphanger.app.data.realtime.Direction
import com.straphanger.app.testutil.BundledNetwork
import com.straphanger.app.ui.status.CommuteAccess
import kotlin.test.assertEquals
import kotlinx.datetime.DayOfWeek
import org.junit.Test

class CommuteNotesScreenTest : CommuteScreenTestBase() {
  @Test
  fun blockedAndLateNotesOpenSettings() {
    commuteStore.write(listOf(commute()))
    commuteAccess = CommuteAccess(notificationsAllowed = false, exactAlarmsAllowed = false)
    openStatus()

    composeRule.onNodeWithTag("commute_notifications_blocked").performClick()
    composeRule.onNodeWithTag("commute_exact_alarms_note").performClick()

    assertEquals(1, notificationSettingsOpens)
    assertEquals(1, exactAlarmSettingsOpens)
  }

  @Test
  fun notesAreHiddenWithoutCommutesOrWhenAllowed() {
    commuteAccess = CommuteAccess(notificationsAllowed = false, exactAlarmsAllowed = false)
    openStatus()
    assertEquals(0, count("commute_notifications_blocked") + count("commute_exact_alarms_note"))
  }

  @Test
  fun missingStationShowsNoLongerListed() {
    commuteStore.write(listOf(commute(complexId = "gone")))
    openStatus()

    composeRule
      .onNodeWithTag("commute_row_c1")
      .assertTextEquals("N Southbound · Station no longer listed · Weekdays 7:45")
  }
}
