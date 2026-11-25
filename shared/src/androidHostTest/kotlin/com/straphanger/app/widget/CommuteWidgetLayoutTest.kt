package com.straphanger.app.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest
import androidx.glance.appwidget.testing.unit.hasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasAnyDescendant
import androidx.glance.testing.unit.hasContentDescription
import androidx.glance.testing.unit.hasText
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.straphanger.app.data.commute.CommuteTap
import com.straphanger.app.data.realtime.Direction
import com.straphanger.app.data.widget.CommuteWidgetContent
import com.straphanger.app.data.widget.WidgetRow
import org.junit.Test
import org.junit.runner.RunWith

/** The widget's Glance layout, drawn from fixed content. */
@RunWith(AndroidJUnit4::class)
class CommuteWidgetLayoutTest {
  /** Which action a [TestCallback] stands in for. */
  private val kind = ActionParameters.Key<String>("kind")
  private val tap = CommuteTap("611", "A", Direction.SOUTH)
  private val small = DpSize(250.dp, 199.dp)
  private val tall = DpSize(250.dp, 200.dp)

  private fun times(id: String = "c1", station: String = "168 St", tap: CommuteTap = this.tap) =
    WidgetRow.Times(
      id,
      station,
      tap,
      listOf("3 min", "9 min", "14 min"),
      "3 min · 9 min · 14 min",
      "Delays: signal problems at 125 St",
      "Updated 7:52",
    )

  private fun render(
    rows: List<WidgetRow>,
    size: DpSize = small,
    check: GlanceAppWidgetUnitTest.() -> Unit,
  ) =
    runGlanceAppWidgetUnitTest {
      setAppWidgetSize(size)
      provideComposable {
        CommuteWidgetLayout(
          CommuteWidgetContent(rows),
          refresh = action("refresh"),
          open = { action("open ${it.complexId}") },
          choose = action("choose"),
        )
      }
      check()
    }

  private fun action(name: String) = actionRunCallback<TestCallback>(actionParametersOf(kind to name))

  private fun runs(name: String) =
    hasRunCallbackClickAction<TestCallback>(actionParametersOf(kind to name))

  private val opensTap = runs("open ${tap.complexId}")

  @Test
  fun timesRowShowsBulletStationDirectionDeparturesAlertAndUpdated() =
    render(listOf(times())) {
      onNode(hasTextEqualTo("A")).assertExists()
      onNode(hasTextEqualTo("168 St")).assertExists()
      onNode(hasTextEqualTo("SOUTHBOUND")).assertExists()
      onNode(hasTextEqualTo("3 min")).assertExists()
      onNode(hasTextEqualTo("9 min")).assertExists()
      onNode(hasTextEqualTo("14 min")).assertExists()
      onNode(hasTextEqualTo("⚠ Delays: signal problems at 125 St")).assertExists()
      onNode(hasTextEqualTo("Updated 7:52")).assertExists()
      onNode(opensTap and hasAnyDescendant(hasTextEqualTo("168 St"))).assertExists()
      onNode(hasContentDescription("Refresh")).assert(runs("refresh"))
    }

  @Test
  fun timesWithoutDeparturesShowTheMessage() =
    render(
      listOf(times().copy(departures = emptyList(), text = "No A trains listed southbound right now"))
    ) {
      onNode(hasTextEqualTo("No A trains listed southbound right now")).assertExists()
    }

  @Test
  fun tapToRefreshRowRunsRefresh() =
    render(listOf(WidgetRow.TapToRefresh("c1", "168 St", tap))) {
      onNode(hasTextEqualTo("168 St")).assertExists()
      onNode(hasTextEqualTo("Tap to refresh"))
        .assert(runs("refresh"))
      onNode(opensTap and hasAnyDescendant(hasTextEqualTo("168 St"))).assertExists()
    }

  @Test
  fun failedRowShowsCouldNotGetTrainTimes() =
    render(listOf(WidgetRow.Failed("c1", "168 St", tap))) {
      onNode(hasTextEqualTo("Couldn't get train times"))
        .assert(runs("refresh"))
      onNode(hasContentDescription("Refresh"))
        .assert(runs("refresh"))
    }

  @Test
  fun stationGoneRowShowsStationNoLongerListed() =
    render(listOf(WidgetRow.StationGone("c1", null, tap))) {
      onNode(hasTextEqualTo("Station no longer listed")).assertExists()
      onNode(opensTap and hasAnyDescendant(hasTextEqualTo("Station no longer listed")))
        .assertExists()
    }

  @Test
  fun noRowsShowsTapToChooseCommutes() =
    render(emptyList()) {
      onNode(hasTextEqualTo("Tap to choose commutes"))
        .assert(runs("choose"))
    }

  @Test
  fun twoRowsOnlyWhenTall() {
    val rows =
      listOf(times("c1", "168 St"), times("c2", "14 St", CommuteTap("618", "C", Direction.NORTH)))
    render(rows, small) {
      onNode(hasText("168 St")).assertExists()
      onNode(hasText("14 St")).assertDoesNotExist()
    }
    render(rows, tall) {
      onNode(hasText("168 St")).assertExists()
      onNode(hasText("14 St")).assertExists()
    }
  }
}
