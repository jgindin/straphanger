package com.straphanger.app.data.commute

import com.straphanger.app.data.realtime.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

class CommuteWindowTest {
  private val zone = TimeZone.of("America/New_York")
  private val weekdays =
    setOf(
      DayOfWeek.MONDAY,
      DayOfWeek.TUESDAY,
      DayOfWeek.WEDNESDAY,
      DayOfWeek.THURSDAY,
      DayOfWeek.FRIDAY,
    )

  private fun commute(
    days: Set<DayOfWeek>,
    hour: Int,
    minute: Int,
    endHour: Int? = null,
    endMinute: Int? = null,
  ) = Commute("c", "6", "635", Direction.SOUTH, days, hour, minute, true, endHour, endMinute)

  private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
    LocalDateTime(y, mo, d, h, mi).toInstant(zone).toEpochMilliseconds()

  // 2027-03-08 is a Monday; 2027-03-12 a Friday; 2027-03-14 the spring-forward Sunday.
  @Test
  fun windowIsActiveBetweenStartAndEnd() {
    val c = commute(weekdays, 7, 45, 8, 30)
    assertEquals(
      ms(2027, 3, 8, 7, 45) until ms(2027, 3, 8, 8, 30),
      c.windowAt(ms(2027, 3, 8, 8, 0), zone),
    )
  }

  @Test
  fun windowEndsExclusive() {
    assertNull(commute(weekdays, 7, 45, 8, 30).windowAt(ms(2027, 3, 8, 8, 30), zone))
  }

  @Test
  fun noWindowWithoutAnEndTime() {
    val c = commute(weekdays, 7, 45)
    assertNull(c.windowAt(ms(2027, 3, 8, 8, 0), zone))
    assertNull(c.nextWindowStartMs(ms(2027, 3, 8, 8, 0), zone))
  }

  @Test
  fun overnightWindowIsActiveAfterMidnightOnTheNextDay() {
    val c = commute(setOf(DayOfWeek.FRIDAY), 23, 30, 0, 30)
    assertEquals(
      ms(2027, 3, 12, 23, 30) until ms(2027, 3, 13, 0, 30),
      c.windowAt(ms(2027, 3, 13, 0, 10), zone),
    )
  }

  @Test
  fun endEqualToStartIsTwentyFourHours() {
    val c = commute(weekdays, 7, 45, 7, 45)
    assertEquals(
      ms(2027, 3, 8, 7, 45) until ms(2027, 3, 9, 7, 45),
      c.windowAt(ms(2027, 3, 8, 20, 0), zone),
    )
  }

  @Test
  fun nextWindowStartSkipsTheActiveOne() {
    val c = commute(weekdays, 7, 45, 8, 30)
    assertEquals(ms(2027, 3, 9, 7, 45), c.nextWindowStartMs(ms(2027, 3, 8, 8, 0), zone))
  }

  @Test
  fun windowOnSpringForwardDay() {
    val c = commute(DayOfWeek.entries.toSet(), 2, 30, 3, 30)
    val during = ms(2027, 3, 14, 3, 15)
    val window = c.windowAt(during, zone)
    assertEquals(ms(2027, 3, 14, 3, 0) until ms(2027, 3, 14, 3, 30), window)
  }
}
