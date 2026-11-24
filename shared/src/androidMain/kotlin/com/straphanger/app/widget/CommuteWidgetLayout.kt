package com.straphanger.app.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
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
import com.straphanger.app.data.commute.displayName
import com.straphanger.app.data.widget.CommuteWidgetContent
import com.straphanger.app.data.widget.WidgetRow
import com.straphanger.app.ui.theme.MtaColors
import com.straphanger.shared.R

/** The widget's smallest size: room for one commute. */
val COMMUTE_WIDGET_SMALL = DpSize(110.dp, 40.dp)

/** From this height the widget shows two commutes, one under the other. */
val COMMUTE_WIDGET_TALL = DpSize(110.dp, 200.dp)

/** The sizes for the widget's `SizeMode.Responsive`. */
val COMMUTE_WIDGET_SIZES = setOf(COMMUTE_WIDGET_SMALL, COMMUTE_WIDGET_TALL)

private val BULLET_SIZE = 26.dp

private val stationStyle =
  TextStyle(
    color = ColorProvider(MtaColors.TextPrimary),
    fontSize = 17.sp,
    fontWeight = FontWeight.Bold,
  )
private val directionStyle =
  TextStyle(
    color = ColorProvider(MtaColors.TextSecondary),
    fontSize = 11.sp,
    fontWeight = FontWeight.Bold,
  )
private val updatedStyle = TextStyle(color = ColorProvider(MtaColors.TextSecondary), fontSize = 11.sp)
private val nextStyle =
  TextStyle(
    color = ColorProvider(MtaColors.TextPrimary),
    fontSize = 24.sp,
    fontWeight = FontWeight.Bold,
  )
private val laterStyle =
  TextStyle(
    color = ColorProvider(MtaColors.TextPrimary),
    fontSize = 18.sp,
    fontWeight = FontWeight.Medium,
  )
private val messageStyle = TextStyle(color = ColorProvider(MtaColors.TextSecondary), fontSize = 13.sp)
private val alertStyle = TextStyle(color = ColorProvider(MtaColors.AccentGold), fontSize = 12.sp)
private val actionStyle =
  TextStyle(
    color = ColorProvider(MtaColors.AccentGold),
    fontSize = 15.sp,
    fontWeight = FontWeight.Bold,
  )

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
        .cornerRadius(20.dp)
        .padding(horizontal = 14.dp, vertical = 12.dp),
    verticalAlignment = if (rows.isEmpty()) Alignment.CenterVertically else Alignment.Top,
  ) {
    if (rows.isEmpty()) {
      Text(
        "Tap to choose commutes",
        style = actionStyle,
        modifier = GlanceModifier.fillMaxWidth().clickable(choose),
      )
    }
    rows.forEachIndexed { index, row ->
      if (index > 0) Divider()
      CommuteRow(row, refresh, open(row.tap))
    }
  }
}

@Composable
private fun CommuteRow(row: WidgetRow, refresh: Action, open: Action) {
  Column(modifier = GlanceModifier.fillMaxWidth().clickable(open)) {
    // (A) 14 St / 8 Av                ⟳
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      LineBullet(row.tap.routeId)
      Spacer(GlanceModifier.width(10.dp))
      Text(
        row.station ?: row.tap.direction.displayName(),
        style = stationStyle,
        maxLines = 1,
        modifier = GlanceModifier.defaultWeight(),
      )
      if (row !is WidgetRow.StationGone) {
        Spacer(GlanceModifier.width(8.dp))
        RefreshIcon(refresh)
      }
    }
    // NORTHBOUND                 Updated 2:04 PM
    Spacer(GlanceModifier.height(4.dp))
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(
        row.tap.direction.displayName().uppercase(),
        style = directionStyle,
        maxLines = 1,
        modifier = GlanceModifier.defaultWeight(),
      )
      if (row is WidgetRow.Times) Text(row.updatedText, style = updatedStyle, maxLines = 1)
    }
    Spacer(GlanceModifier.height(6.dp))
    RowBody(row, refresh)
  }
}

/** What a row says under its heading: its times, or why it has none. */
@Composable
private fun RowBody(row: WidgetRow, refresh: Action) {
  when (row) {
    is WidgetRow.Times -> {
      if (row.departures.isEmpty()) {
        Text(row.text, style = messageStyle, maxLines = 2)
      } else {
        Departures(row.departures)
      }
      row.alertLine?.let {
        Spacer(GlanceModifier.height(4.dp))
        Text("⚠ $it", style = alertStyle, maxLines = 1)
      }
    }
    is WidgetRow.TapToRefresh ->
      Text("Tap to refresh", style = actionStyle, modifier = GlanceModifier.clickable(refresh))
    is WidgetRow.Failed ->
      Text(
        "Couldn't get train times",
        style = actionStyle,
        modifier = GlanceModifier.clickable(refresh),
      )
    is WidgetRow.StationGone -> Text("Station no longer listed", style = messageStyle)
  }
}

/** "3 min   9 min   14 min": the next train large, the ones after it smaller. */
@Composable
private fun Departures(departures: List<String>) {
  Row(verticalAlignment = Alignment.Bottom) {
    departures.forEachIndexed { index, departure ->
      if (index > 0) Spacer(GlanceModifier.width(16.dp))
      Text(departure, style = if (index == 0) nextStyle else laterStyle, maxLines = 1)
    }
  }
}

/** The line's bullet: its letter or number on a circle of its colour. */
@Composable
private fun LineBullet(routeId: String) {
  Box(modifier = GlanceModifier.size(BULLET_SIZE), contentAlignment = Alignment.Center) {
    Image(
      provider = ImageProvider(R.drawable.ic_widget_bullet),
      contentDescription = null,
      colorFilter = ColorFilter.tint(ColorProvider(MtaColors.routeColor(routeId))),
      modifier = GlanceModifier.size(BULLET_SIZE),
    )
    val label = MtaColors.routeLabel(routeId)
    Text(
      label,
      style =
        TextStyle(
          color = ColorProvider(MtaColors.routeTextColor(routeId)),
          fontSize = if (label.length > 1) 9.sp else 14.sp,
          fontWeight = FontWeight.Bold,
        ),
    )
  }
}

/** A thin line between two commutes. */
@Composable
private fun Divider() {
  Spacer(GlanceModifier.height(10.dp))
  Spacer(GlanceModifier.fillMaxWidth().height(1.dp).background(MtaColors.CardBorder))
  Spacer(GlanceModifier.height(10.dp))
}

/** ⟳, which fetches the times again at once. */
@Composable
private fun RefreshIcon(refresh: Action) {
  Image(
    provider = ImageProvider(R.drawable.ic_widget_refresh),
    contentDescription = "Refresh",
    colorFilter = ColorFilter.tint(ColorProvider(MtaColors.AccentGold)),
    modifier = GlanceModifier.size(24.dp).clickable(refresh),
  )
}
