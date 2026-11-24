package com.straphanger.app.ui.widget

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.straphanger.app.data.commute.Commute
import com.straphanger.app.data.realtime.Direction
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.DayOfWeek
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The widget's configuration screen on its own. */
@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h891dp-xxhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class WidgetCommutePickerTest {
  @get:Rule val rule = createComposeRule()

  private val weekdays =
    setOf(
      DayOfWeek.MONDAY,
      DayOfWeek.TUESDAY,
      DayOfWeek.WEDNESDAY,
      DayOfWeek.THURSDAY,
      DayOfWeek.FRIDAY,
    )

  private fun commute(id: String, endHour: Int? = 8, endMinute: Int? = 30) =
    Commute(id, "A", "611", Direction.SOUTH, weekdays, 7, 45, endHour = endHour, endMinute = endMinute)

  private var done: List<String>? = null
  private var openedApp = false

  private fun show(commutes: List<Commute>, selected: Set<String> = emptySet()) =
    rule.setContent {
      WidgetCommutePicker(
        commutes = commutes,
        complexName = { "168 St" },
        initiallySelected = selected,
        onDone = { done = it },
        onOpenApp = { openedApp = true },
      )
    }

  @Test
  fun listsCommutesWithEndTimesAsCheckable() {
    show(listOf(commute("c1")))

    rule.onNodeWithText("Choose commutes").assertIsDisplayed()
    rule.onNodeWithText("A Southbound · 168 St · Weekdays 7:45–8:30").assertIsDisplayed()
    rule.onNodeWithTag("widget_commute_c1").assertIsEnabled().assertIsOff().performClick()
    rule.onNodeWithTag("widget_commute_c1").assertIsOn()
  }

  @Test
  fun commutesWithoutEndTimeAreDisabledWithHint() {
    show(listOf(commute("c1", endHour = null, endMinute = null)))

    rule.onNodeWithTag("widget_commute_c1").assertIsNotEnabled()
    rule.onNodeWithText("Set an end time to show it on a widget").assertIsDisplayed()
  }

  @Test
  fun doneReturnsTickedIdsInListOrder() {
    show(listOf(commute("c1"), commute("c2"), commute("c3")))

    rule.onNodeWithTag("widget_commute_c3").performClick()
    rule.onNodeWithTag("widget_commute_c1").performClick()
    rule.onNodeWithText("Done").performClick()

    assertEquals(listOf("c1", "c3"), done)
  }

  @Test
  fun noCommutesShowsOpenStraphanger() {
    show(emptyList())

    rule.onNodeWithText("No commutes yet. Open Straphanger to add one.").assertIsDisplayed()
    rule.onNodeWithText("Open Straphanger").performClick()

    assertTrue(openedApp)
  }

  @Test
  fun startsWithTheCurrentSelection() {
    show(listOf(commute("c1"), commute("c2")), selected = setOf("c2"))

    rule.onNodeWithTag("widget_commute_c1").assertIsOff()
    rule.onNodeWithTag("widget_commute_c2").assertIsOn()
    rule.onNodeWithText("Done").performClick()

    assertEquals(listOf("c2"), done)
  }
}
