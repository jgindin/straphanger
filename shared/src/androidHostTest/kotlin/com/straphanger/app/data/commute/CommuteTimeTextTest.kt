package com.straphanger.app.data.commute

import kotlin.test.Test
import kotlin.test.assertEquals

class CommuteTimeTextTest {
  @Test
  fun twentyFourHourIsUnpadded() {
    assertEquals("7:45", commuteTimeText(7, 45, use24Hour = true))
    assertEquals("17:30", commuteTimeText(17, 30, use24Hour = true))
    assertEquals("0:05", commuteTimeText(0, 5, use24Hour = true))
  }

  @Test
  fun twelveHourMorningAndEvening() {
    assertEquals("7:45 AM", commuteTimeText(7, 45, use24Hour = false))
    assertEquals("5:30 PM", commuteTimeText(17, 30, use24Hour = false))
  }

  @Test
  fun twelveHourNoonIsTwelvePm() {
    assertEquals("12:00 PM", commuteTimeText(12, 0, use24Hour = false))
  }

  @Test
  fun twelveHourJustAfterMidnightIsTwelveAm() {
    assertEquals("12:05 AM", commuteTimeText(0, 5, use24Hour = false))
  }
}
