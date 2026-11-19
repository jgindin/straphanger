package com.straphanger.app.ui

import com.straphanger.app.testutil.BundledNetwork
import com.straphanger.app.ui.rides.bulletRoutes
import com.straphanger.app.ui.rides.distanceText
import com.straphanger.app.ui.rides.stationChoices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StationChoicesTest {
  private val engine = BundledNetwork.engine()

  @Test
  fun everyStationWithTheSameNameIsOffered() {
    val ids = stationChoices(engine, "14 St", nearStationId = null).map { it.station.id }
    // 8 Av (A/C/E), 7 Av (1/2/3) and 6 Av (F/M) all have a station named "14 St".
    assertTrue(ids.containsAll(listOf("A31", "132", "D19")), ids.toString())
  }

  @Test
  fun theNearestMatchComesFirst() {
    // Correcting a ride logged from 23 St on 8 Av: the 8 Av 14 St is the one a block away.
    val choices = stationChoices(engine, "14", nearStationId = "A30")
    assertEquals(StationIds.FOURTEENTH_ST_8AV, choices.first().station.id)
    val miles = choices.map { it.milesAway!! }
    assertEquals(miles.sorted(), miles)
  }

  @Test
  fun aBlankSearchListsTheStationsAroundTheCurrentOne() {
    val choices = stationChoices(engine, "", nearStationId = "A30")
    assertEquals("A30", choices.first().station.id)
    assertEquals("here", distanceText(choices.first().milesAway!!))
    assertTrue(choices.take(5).all { it.milesAway!! < 1.0 }, choices.take(5).toString())
  }

  @Test
  fun anUnknownStationFallsBackToNameOrder() {
    val choices = stationChoices(engine, "14 St", nearStationId = "no-such-station")
    assertNull(choices.first().milesAway)
    val names = choices.map { it.station.name }
    assertEquals(names.sorted(), names)
  }

  @Test
  fun expressVariantsAreNotDrawnTwice() {
    assertEquals(listOf("F", "M"), bulletRoutes(listOf("F", "FX", "M")))
    assertEquals(listOf("FX"), bulletRoutes(listOf("FX")))
  }

  @Test
  fun distancesAreShownInTenthsOfAMile() {
    assertEquals("here", distanceText(0.02))
    assertEquals("0.4 mi", distanceText(0.43))
    assertEquals("12.0 mi", distanceText(11.98))
  }
}
