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

class CommuteScreenTest : CommuteScreenTestBase() {
  @Test
  fun addCommuteFromStationCardPrefillsStationAndDefaults() {
    openEditorFromStationCard()

    composeRule.onNodeWithTag("commute_editor").assertExists()
    composeRule.onNodeWithTag("commute_line_${unionSq.routes.first()}").assertIsSelected()
    composeRule.onNodeWithTag("commute_direction_NORTH").assertIsSelected()
    weekdays.forEach { composeRule.onNodeWithTag("commute_day_${it.name}").assertIsSelected() }
    composeRule.onNodeWithTag("commute_hour").assertTextContains("8")
    composeRule.onNodeWithTag("commute_minute").assertTextContains("00")
    composeRule.onNodeWithTag("commute_save").performClick()

    val saved = commuteStore.read().single()
    assertEquals(unionSq.complexId, saved.complexId)
    assertEquals(weekdays, saved.days)
    assertEquals(8 to 0, saved.hour to saved.minute)
    assertEquals(true, saved.enabled)
  }

  @Test
  fun savingAddsARowAndReportsTheList() {
    openEditorFromStationCard()
    composeRule.onNodeWithTag("commute_direction_SOUTH").performClick()
    composeRule.onNodeWithTag("commute_hour").performTextReplacement("18")
    composeRule.onNodeWithTag("commute_minute").performTextReplacement("10")
    composeRule.onNodeWithTag("commute_save").performClick()

    val saved = commuteStore.read().single()
    assertEquals(listOf(listOf(saved)), commuteChanges)
    composeRule
      .onNodeWithTag("commute_row_${saved.id}")
      .assertTextEquals("${saved.routeId} Southbound · ${unionSq.name} · Weekdays 18:10")
    assertEquals(0, count("commute_editor"))
  }

  @Test
  fun firstCommuteAsksForPermissionsOnce() {
    openEditorFromStationCard()
    composeRule.onNodeWithTag("commute_save").performClick()
    composeRule.onNodeWithTag("status_add_commute").performClick()
    composeRule.onNodeWithTag("commute_save").performClick()

    assertEquals(2, commuteStore.read().size)
    assertEquals(1, firstCommuteCreations)
  }

  @Test
  fun toggleDisables() {
    commuteStore = commuteStore.also { it.write(listOf(commute())) }
    openStatus()

    composeRule.onNodeWithTag("commute_row_c1").assertTextEquals("N Southbound · ${unionSq.name} · Weekdays 7:45")
    composeRule.onNodeWithTag("commute_switch_c1").assertIsOn().performClick()

    composeRule.onNodeWithTag("commute_switch_c1").assertIsOff()
    assertEquals(false, commuteStore.read().single().enabled)
    assertEquals(false, commuteChanges.single().single().enabled)
  }

  @Test
  fun editingAndDeleting() {
    commuteStore.write(listOf(commute()))
    openStatus()

    composeRule.onNodeWithTag("commute_row_c1").performClick()
    composeRule.onNodeWithTag("commute_day_FRIDAY").performClick()
    composeRule.onNodeWithTag("commute_minute").performTextReplacement("05")
    composeRule.onNodeWithTag("commute_save").performClick()

    val edited = commuteStore.read().single()
    assertEquals("c1", edited.id)
    assertEquals(weekdays - DayOfWeek.FRIDAY, edited.days)
    assertEquals(5, edited.minute)
    assertEquals(0, firstCommuteCreations)

    composeRule.onNodeWithTag("commute_row_c1").performClick()
    composeRule.onNodeWithTag("commute_delete").performClick()

    assertEquals(emptyList(), commuteStore.read())
    assertEquals(emptyList(), commuteChanges.last())
    assertEquals(0, count("commute_row_c1"))
  }

  @Test
  fun saveIsDisabledWithNoDays() {
    openEditorFromStationCard()
    weekdays.forEach { composeRule.onNodeWithTag("commute_day_${it.name}").performClick() }

    composeRule.onNodeWithTag("commute_save").assertIsNotEnabled()
    composeRule.onNodeWithTag("commute_day_SATURDAY").performClick()
    composeRule.onNodeWithTag("commute_save").assertIsEnabled()
  }
}
