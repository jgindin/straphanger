package com.straphanger.app

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.straphanger.app.data.realtime.ArrivalBoard
import com.straphanger.app.data.realtime.RealtimeSnapshot
import kotlin.test.assertEquals
import org.junit.Test

class StatusPullRefreshScreenTest : AppScreenTest() {
  @Test
  fun pullingTheStatusListDownFetchesTheFeedsAgain() {
    var fetches = 0
    realtime = {
      fetches++
      RealtimeSnapshot(ArrivalBoard(emptyList()), emptyList(), realtimeNow, false)
    }
    launchApp()
    openTab("STATUS")
    composeRule.waitUntil(5_000) { fetches >= 1 }
    composeRule.waitForIdle()
    val before = fetches

    composeRule.onNodeWithTag("status_pull_refresh").performTouchInput {
      swipeDown(startY = top + 10f, endY = bottom - 10f)
    }

    composeRule.waitUntil(5_000) { fetches == before + 1 }
    assertEquals(before + 1, fetches)
  }
}
