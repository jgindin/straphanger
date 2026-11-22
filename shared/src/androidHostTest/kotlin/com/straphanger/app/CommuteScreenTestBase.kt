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

abstract class CommuteScreenTestBase : AppScreenTest() {
  protected val complexes = stationComplexes(BundledNetwork.data)
  protected val unionSq = complexes.first { it.name.contains("14 St-Union Sq") }
  protected val weekdays = DayOfWeek.entries.take(5).toSet()

  protected fun commute(complexId: String = unionSq.complexId, enabled: Boolean = true) =
    Commute("c1", "N", complexId, Direction.SOUTH, weekdays, 7, 45, enabled)

  protected fun openStatus() {
    launchApp()
    openTab("STATUS")
  }

  protected fun openEditorFromStationCard() {
    openStatus()
    composeRule.onNodeWithTag("status_search").performTextInput("Union Sq")
    composeRule.onNodeWithTag("status_station_${unionSq.complexId}").performClick()
    composeRule.onNodeWithTag("status_add_commute").performClick()
  }

  protected fun count(tag: String) = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().size
}
