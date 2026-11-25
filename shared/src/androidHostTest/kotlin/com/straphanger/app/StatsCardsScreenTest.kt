package com.straphanger.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import org.junit.Test

/** The Stats cards' titles. */
class StatsCardsScreenTest : AppScreenTest() {
  @Test
  fun statsCardsUseTheShortTitlesAndHaveNoScreenTitle() {
    launchApp()
    openTab("STATS")
    composeRule.onAllNodesWithText("NYC Subway System Analytics").assertCountEquals(0)
    for (title in listOf("Counting stations", "Lines", "Boroughs", "Achievements")) {
      composeRule.onNodeWithText(title).performScrollTo().assertIsDisplayed()
    }
  }
}
