package com.straphanger.app

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class CommuteEndTimeScreenTest : CommuteScreenTestBase() {
  private fun endTime(hour: String, minute: String) {
    composeRule.onNodeWithTag("commute_end_hour").performTextReplacement(hour)
    composeRule.onNodeWithTag("commute_end_minute").performTextReplacement(minute)
  }

  @Test
  fun endTimeIsEmptyByDefaultAndSavesAsNone() {
    openEditorFromStationCard()
    composeRule.onNodeWithTag("commute_save").performClick()

    val saved = commuteStore.read().single()
    assertNull(saved.endHour)
    assertNull(saved.endMinute)
  }

  @Test
  fun endTimeSavesBothFields() {
    openEditorFromStationCard()
    endTime("8", "30")
    composeRule.onNodeWithTag("commute_save").performClick()

    val saved = commuteStore.read().single()
    assertEquals(8 to 30, saved.endHour to saved.endMinute)
  }

  @Test
  fun aHalfFilledEndTimeDisablesSave() {
    openEditorFromStationCard()
    endTime("8", "")
    composeRule.onNodeWithTag("commute_save").assertIsNotEnabled()
    endTime("", "30")
    composeRule.onNodeWithTag("commute_save").assertIsNotEnabled()
    endTime("8", "30")
    composeRule.onNodeWithTag("commute_save").assertIsEnabled()
  }

  @Test
  fun anOutOfRangeEndTimeDisablesSave() {
    openEditorFromStationCard()
    endTime("24", "00")
    composeRule.onNodeWithTag("commute_save").assertIsNotEnabled()
    endTime("8", "60")
    composeRule.onNodeWithTag("commute_save").assertIsNotEnabled()
  }

  @Test
  fun editingKeepsTheEndTime() {
    commuteStore.write(listOf(commute().copy(endHour = 8, endMinute = 5)))
    openStatus()

    composeRule.onNodeWithTag("commute_row_c1").performClick()
    composeRule.onNodeWithTag("commute_end_hour").assertTextContains("8")
    composeRule.onNodeWithTag("commute_end_minute").assertTextContains("05")
    composeRule.onNodeWithTag("commute_save").performClick()

    val saved = commuteStore.read().single()
    assertEquals(8 to 5, saved.endHour to saved.endMinute)
  }

  @Test
  fun commutesListShowsTheWindow() {
    commuteStore.write(listOf(commute().copy(endHour = 8, endMinute = 30)))
    openStatus()

    composeRule
      .onNodeWithTag("commute_row_c1")
      .assertTextEquals("N Southbound · ${unionSq.name} · Weekdays 7:45–8:30")
  }
}
