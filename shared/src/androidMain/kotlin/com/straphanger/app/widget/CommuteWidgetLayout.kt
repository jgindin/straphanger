package com.straphanger.app.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.straphanger.app.data.commute.CommuteTap
import com.straphanger.app.data.widget.CommuteWidgetContent
import com.straphanger.app.data.widget.WidgetRow
import com.straphanger.app.ui.theme.MtaColors
import com.straphanger.shared.R

/** The widget's smallest size: room for one commute. */
val COMMUTE_WIDGET_SMALL = DpSize(110.dp, 40.dp)

/** From this height the widget shows two commutes, one under the other. */
val COMMUTE_WIDGET_TALL = DpSize(110.dp, 140.dp)

/** The sizes for the widget's `SizeMode.Responsive`. */
val COMMUTE_WIDGET_SIZES = setOf(COMMUTE_WIDGET_SMALL, COMMUTE_WIDGET_TALL)

private val titleStyle =
  TextStyle(
    color = ColorProvider(MtaColors.TextPrimary),
    fontSize = 14.sp,
    fontWeight = FontWeight.Bold,
  )
private val timesStyle =
  TextStyle(color = ColorProvider(MtaColors.AccentGold), fontSize = 14.sp, fontWeight = FontWeight.Bold)
private val noteStyle = TextStyle(color = ColorProvider(MtaColors.TextSecondary), fontSize = 12.sp)
private val actionStyle = TextStyle(color = ColorProvider(MtaColors.AccentGold), fontSize = 13.sp)

/**
 * The widget: [content]'s commutes, two when the widget is at least [COMMUTE_WIDGET_TALL] high and
 * otherwise one. ⟳, "Tap to refresh" and "Couldn't get train times" run [refresh]; the rest of a
 * row runs [open] for its commute; a widget without commutes runs [choose].
 */
@Composable
fun CommuteWidgetLayout(
  content: CommuteWidgetContent,
  refresh: Action,
  open: (CommuteTap) -> Action,
  choose: Action,
) {
  val tall = LocalSize.current.height >= COMMUTE_WIDGET_TALL.height
  val rows = content.rows.take(if (tall) 2 else 1)
  Column(
    modifier =
      GlanceModifier.fillMaxSize()
        .background(MtaColors.CardBg)
        .cornerRadius(16.dp)
        .padding(horizontal = 12.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (rows.isEmpty()) {
      Text(
        "Tap to choose commutes",
        style = actionStyle,
        modifier = GlanceModifier.fillMaxWidth().clickable(choose),
      )
    }
    rows.forEachIndexed { index, row ->
      if (index > 0) Spacer(GlanceModifier.height(6.dp))
      CommuteRow(row, refresh, open(row.tap))
    }
  }
}

@Composable
private fun CommuteRow(row: WidgetRow, refresh: Action, open: Action) {
  Column(modifier = GlanceModifier.fillMaxWidth().clickable(open)) {
    // A row with times is three lines, "Updated" and ⟳ beside the title, so one row fits the
    // default 3×2 cell with room to spare and two fit from COMMUTE_WIDGET_TALL.
    if (row is WidgetRow.Times) {
      Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(row.title, style = titleStyle, maxLines = 1, modifier = GlanceModifier.defaultWeight())
        Spacer(GlanceModifier.width(6.dp))
        Text(row.updatedText, style = noteStyle, maxLines = 1)
        Spacer(GlanceModifier.width(4.dp))
        RefreshIcon(refresh)
      }
    } else {
      Text(row.title, style = titleStyle, maxLines = 1)
    }
    when (row) {
      is WidgetRow.Times -> {
        Text(row.text, style = timesStyle, maxLines = 1)
        row.alertLine?.let { Text(it, style = noteStyle, maxLines = 1) }
      }
      is WidgetRow.TapToRefresh ->
        Text("Tap to refresh", style = actionStyle, modifier = GlanceModifier.clickable(refresh))
      is WidgetRow.Failed ->
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            "Couldn't get train times",
            style = actionStyle,
            modifier = GlanceModifier.clickable(refresh),
          )
          Spacer(GlanceModifier.width(6.dp))
          RefreshIcon(refresh)
        }
      is WidgetRow.StationGone -> Text("Station no longer listed", style = noteStyle)
    }
  }
}

/** ⟳, which fetches the times again at once. */
@Composable
private fun RefreshIcon(refresh: Action) {
  Image(
    provider = ImageProvider(R.drawable.ic_widget_refresh),
    contentDescription = "Refresh",
    modifier = GlanceModifier.size(18.dp).clickable(refresh),
  )
}
