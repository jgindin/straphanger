package com.straphanger.app.data.commute

import com.straphanger.app.data.realtime.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

class CommuteScheduleTest {
  private val zone = TimeZone.of("America/New_York")
  private val weekdays =
    setOf(
      DayOfWeek.MONDAY,
      DayOfWeek.TUESDAY,
      DayOfWeek.WEDNESDAY,
      DayOfWeek.THURSDAY,
      DayOfWeek.FRIDAY,
    )

  private fun commute(days: Set<DayOfWeek>, hour: Int, minute: Int) =
    Commute("c", "6", "635", Direction.SOUTH, days, hour, minute)

  private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
    LocalDateTime(y, mo, d, h, mi).toInstant(zone).toEpochMilliseconds()

  // 2027-03-08 is a Monday; 2027-03-12 a Friday.
  @Test
  fun nextOccurrenceIsLaterTodayWhenTimeNotPassed() {
    assertEquals(
      ms(2027, 3, 8, 7, 45),
      commute(weekdays, 7, 45).nextOccurrenceMs(ms(2027, 3, 8, 7, 0), zone),
    )
  }

  @Test
  fun nextOccurrenceSkipsToMondayFromFridayEvening() {
    assertEquals(
      ms(2027, 3, 15, 7, 45),
      commute(weekdays, 7, 45).nextOccurrenceMs(ms(2027, 3, 12, 18, 0), zone),
    )
  }

  @Test
  fun nextOccurrenceIsStrictlyAfter() {
    val at = ms(2027, 3, 8, 7, 45)
    assertEquals(ms(2027, 3, 9, 7, 45), commute(weekdays, 7, 45).nextOccurrenceMs(at, zone))
  }

  @Test
  fun springForwardFiresAtFirstValidMinute() {
    val everyDay = DayOfWeek.entries.toSet()
    assertEquals(
      ms(2027, 3, 14, 3, 0),
      commute(everyDay, 2, 30).nextOccurrenceMs(ms(2027, 3, 14, 0, 0), zone),
    )
  }

  @Test
  fun fallBackFiresOnce() {
    val c = commute(DayOfWeek.entries.toSet(), 1, 30)
    val first = c.nextOccurrenceMs(ms(2027, 11, 7, 0, 0), zone)!!
    assertEquals("2027-11-07T05:30:00Z", kotlin.time.Instant.fromEpochMilliseconds(first).toString())
    assertEquals(ms(2027, 11, 8, 1, 30), c.nextOccurrenceMs(first, zone))
  }

  @Test
  fun emptyDaysHasNoOccurrence() {
    assertNull(commute(emptySet(), 7, 45).nextOccurrenceMs(0, zone))
    assertNull(commute(emptySet(), 7, 45).occurrenceAtOrBeforeMs(0, zone))
  }

  @Test
  fun occurrenceAtOrBeforeFindsTodaysWhenJustAfter() {
    assertEquals(
      ms(2027, 3, 8, 7, 45),
      commute(weekdays, 7, 45).occurrenceAtOrBeforeMs(ms(2027, 3, 8, 7, 50), zone),
    )
  }

  @Test
  fun daysTextNamesWeekdaysEveryDayAndLists() {
    assertEquals("Weekdays", daysText(weekdays))
    assertEquals("Every day", daysText(DayOfWeek.entries.toSet()))
    assertEquals("Mon, Wed", daysText(setOf(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY)))
  }
}
