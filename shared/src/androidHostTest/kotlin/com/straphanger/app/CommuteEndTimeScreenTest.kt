package com.straphanger.app

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class CommuteEndTimeScreenTest : CommuteScreenTestBase() {
  @Test
  fun endTimeIsEmptyByDefaultAndSavesAsNone() {
    openEditorFromStationCard()
    composeRule.onNodeWithTag("commute_end_time").assertTextEquals("Not set")
    composeRule.onNodeWithTag("commute_save").performClick()

    val saved = commuteStore.read().single()
    assertNull(saved.endHour)
    assertNull(saved.endMinute)
  }

  @Test
  fun endTimeSavesBothFields() {
    openEditorFromStationCard()
    pickTime("commute_end_time", "8 hours", "30 minutes")
    composeRule.onNodeWithTag("commute_end_time").assertTextEquals("8:30")
    composeRule.onNodeWithTag("commute_save").performClick()

    val saved = commuteStore.read().single()
    assertEquals(8 to 30, saved.endHour to saved.endMinute)
  }

  @Test
  fun clearRemovesTheEndTime() {
    commuteStore.write(listOf(commute().copy(endHour = 8, endMinute = 30)))
    openStatus()

    composeRule.onNodeWithTag("commute_row_c1").performClick()
    composeRule.onNodeWithTag("commute_end_time").performClick()
    composeRule.onNodeWithTag("commute_time_clear").performClick()
    composeRule.onNodeWithTag("commute_end_time").assertTextEquals("Not set")
    composeRule.onNodeWithTag("commute_save").performClick()

    val saved = commuteStore.read().single()
    assertNull(saved.endHour)
    assertNull(saved.endMinute)
  }

  @Test
  fun editingKeepsTheEndTime() {
    commuteStore.write(listOf(commute().copy(endHour = 8, endMinute = 5)))
    openStatus()

    composeRule.onNodeWithTag("commute_row_c1").performClick()
    composeRule.onNodeWithTag("commute_end_time").assertTextEquals("8:05")
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

  @Test
  fun commutesListShowsTheWindowInTwelveHourTime() {
    use24Hour = false
    commuteStore.write(listOf(commute().copy(endHour = 8, endMinute = 30)))
    openStatus()

    composeRule
      .onNodeWithTag("commute_row_c1")
      .assertTextEquals("N Southbound · ${unionSq.name} · Weekdays 7:45 AM–8:30 AM")
  }
}
