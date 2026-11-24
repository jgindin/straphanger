package com.straphanger.app.data.commute

import com.straphanger.app.data.realtime.AlertKind
import com.straphanger.app.data.realtime.Arrival
import com.straphanger.app.data.realtime.ArrivalBoard
import com.straphanger.app.data.realtime.Direction
import com.straphanger.app.data.realtime.RealtimeSnapshot
import com.straphanger.app.data.realtime.ServiceAlert
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.DayOfWeek

class CommuteAlertTextTest {
  private val now = 1_800_000_000L
  private val commute =
    Commute("c", "A", "614", Direction.SOUTH, setOf(DayOfWeek.MONDAY), 7, 45)
  private val complex = StationComplex("614", "168 St", listOf("A09", "112"), listOf("1", "A", "C"))

  private fun south(route: String, station: String, minutes: Int) =
    Arrival(route, station, Direction.SOUTH, now + minutes * 60L)

  private fun snapshot(arrivals: List<Arrival>, alerts: List<ServiceAlert> = emptyList()) =
    RealtimeSnapshot(ArrivalBoard(arrivals), alerts, now, partial = false)

  private fun text(snapshot: RealtimeSnapshot?, commute: Commute = this.commute) =
    commuteAlertText(commute, complex, snapshot, now)

  @Test
  fun listsUpToThreeDeparturesSoonestFirst() {
    val arrivals =
      listOf(south("A", "A09", 14), south("A", "A09", 3), south("A", "A09", 20), south("A", "A09", 9))
    assertEquals("3 min · 9 min · 14 min", text(snapshot(arrivals)).text)
    assertEquals("now · 5 min", text(snapshot(listOf(south("A", "A09", 0), south("A", "A09", 5)))).text)
  }

  @Test
  fun includesExpressVariant() {
    val six = commute.copy(routeId = "6", complexId = "x")
    val arrivals = listOf(south("6X", "A09", 4), south("6", "112", 2))
    assertEquals("2 min · 4 min", text(snapshot(arrivals), six).text)
  }

  @Test
  fun ignoresOtherDirectionAndLines() {
    val arrivals =
      listOf(
        Arrival("A", "A09", Direction.NORTH, now + 60),
        south("C", "A09", 2),
        south("AA", "A09", 3),
        south("A", "Z99", 4),
        south("A", "A09", 7),
      )
    assertEquals("7 min", text(snapshot(arrivals)).text)
  }

  @Test
  fun noTrainsText() {
    assertEquals("No A trains listed southbound right now", text(snapshot(emptyList())).text)
    val north = commute.copy(direction = Direction.NORTH)
    assertEquals("No A trains listed northbound right now", text(snapshot(emptyList()), north).text)
  }

  @Test
  fun partialSnapshotWithoutDeparturesIsAFailedFetch() {
    val alerts = listOf(ServiceAlert("a1", setOf("A"), AlertKind.DELAY, "Signal problems", ""))
    val failed =
      text(RealtimeSnapshot(ArrivalBoard(emptyList()), alerts, now, partial = true))
    assertEquals("Couldn't get train times", failed.text)
    assertEquals("Signal problems", failed.alertLine)
    // Partial but with the line's trains: they are shown.
    val shown =
      text(RealtimeSnapshot(ArrivalBoard(listOf(south("A", "A09", 3))), alerts, now, partial = true))
    assertEquals("3 min", shown.text)
  }

  @Test
  fun fetchFailedText() {
    val alert = text(null)
    assertEquals("Couldn't get train times", alert.text)
    assertNull(alert.alertLine)
  }

  @Test
  fun prefersDelayAlertHeader() {
    fun alert(kind: AlertKind, routes: Set<String>, header: String) =
      ServiceAlert("a-$header", routes, kind, header, "")
    val alerts =
      listOf(
        alert(AlertKind.PLANNED, setOf("A"), "Weekend work"),
        alert(AlertKind.DELAY, setOf("C"), "C delays"),
        alert(AlertKind.OTHER, setOf("A"), "Elevator out"),
        alert(AlertKind.DELAY, setOf("A", "C"), "Signal problems"),
      )
    assertEquals("Signal problems", text(snapshot(emptyList(), alerts)).alertLine)
    assertEquals("Elevator out", text(snapshot(emptyList(), alerts.take(3))).alertLine)
    assertEquals("Weekend work", text(snapshot(emptyList(), alerts.take(2))).alertLine)
    assertNull(text(snapshot(emptyList(), alerts.drop(1).take(1))).alertLine)
  }

  @Test
  fun titleNamesLineDirectionAndStation() {
    assertEquals("A Southbound · 168 St", text(null).title)
    assertEquals("A Northbound · 168 St", text(null, commute.copy(direction = Direction.NORTH)).title)
  }
}
