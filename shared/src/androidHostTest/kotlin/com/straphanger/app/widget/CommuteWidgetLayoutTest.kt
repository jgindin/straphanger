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
  private val title = "A Southbound · 168 St"
  private val small = DpSize(250.dp, 139.dp)
  private val tall = DpSize(250.dp, 140.dp)

  private fun times(id: String = "c1", title: String = this.title) =
    WidgetRow.Times(
      id,
      title,
      tap,
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
  fun timesRowShowsTitleTimesAlertAndUpdated() =
    render(listOf(times())) {
      onNode(hasTextEqualTo(title)).assertExists()
      onNode(hasTextEqualTo("3 min · 9 min · 14 min")).assertExists()
      onNode(hasTextEqualTo("Delays: signal problems at 125 St")).assertExists()
      onNode(hasTextEqualTo("Updated 7:52")).assertExists()
      onNode(opensTap and hasAnyDescendant(hasTextEqualTo(title))).assertExists()
    }

  @Test
  fun refreshIconHasRefreshDescriptionAndAction() =
    render(listOf(times())) {
      onNode(hasContentDescription("Refresh"))
        .assert(runs("refresh"))
    }

  @Test
  fun tapToRefreshRowRunsRefresh() =
    render(listOf(WidgetRow.TapToRefresh("c1", title, tap))) {
      onNode(hasTextEqualTo(title)).assertExists()
      onNode(hasTextEqualTo("Tap to refresh"))
        .assert(runs("refresh"))
      onNode(opensTap and hasAnyDescendant(hasTextEqualTo(title))).assertExists()
    }

  @Test
  fun failedRowShowsCouldNotGetTrainTimes() =
    render(listOf(WidgetRow.Failed("c1", title, tap))) {
      onNode(hasTextEqualTo("Couldn't get train times"))
        .assert(runs("refresh"))
      onNode(hasContentDescription("Refresh"))
        .assert(runs("refresh"))
    }

  @Test
  fun stationGoneRowShowsStationNoLongerListed() =
    render(listOf(WidgetRow.StationGone("c1", "A Southbound", tap))) {
      onNode(hasTextEqualTo("Station no longer listed")).assertExists()
      onNode(opensTap and hasAnyDescendant(hasTextEqualTo("A Southbound"))).assertExists()
    }

  @Test
  fun noRowsShowsTapToChooseCommutes() =
    render(emptyList()) {
      onNode(hasTextEqualTo("Tap to choose commutes"))
        .assert(runs("choose"))
    }

  @Test
  fun twoRowsOnlyWhenTall() {
    val rows = listOf(times("c1", "A Southbound · 168 St"), times("c2", "C Northbound · 14 St"))
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
