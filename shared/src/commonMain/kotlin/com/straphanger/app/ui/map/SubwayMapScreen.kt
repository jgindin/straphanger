package com.straphanger.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.straphanger.app.data.gtfs.SubwayNetworkData
import com.straphanger.app.data.gtfs.SubwayStation
import com.straphanger.app.ui.MapColorMode
import com.straphanger.app.ui.StraphangerUiState
import com.straphanger.app.ui.theme.MtaColors
import com.straphanger.app.ui.theme.MtaRouteBullet
import kotlin.math.hypot

private data class ProjectedStation(
    val station: SubwayStation,
    val normX: Float,
    val normY: Float
)

private data class ProjectedSegment(
    val segmentId: String,
    val trunkId: String,
    val routes: List<String>,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float
)

private data class PreProjectedMapGeometry(
    val stations: List<ProjectedStation>,
    val segments: List<ProjectedSegment>
)

@Composable
fun SubwayMapScreen(
    uiState: StraphangerUiState,
    network: SubwayNetworkData,
    onColorModeChange: (MapColorMode) -> Unit,
    onRouteFilterSelect: (String?) -> Unit,
    onStationSelect: (String?) -> Unit,
    onStartRideFromStation: (String) -> Unit,
    onEndRideAtStation: (String) -> Unit
) {
    // Pre-project geographic coordinates once (Adversarial Fix #7: zero GC allocation churn during pan/zoom)
    val projectedGeometry = remember(network) {
        val minLat = 40.505
        val maxLat = 40.900
        val minLng = -74.260
        val maxLng = -73.745

        fun normX(lng: Double): Float = ((lng - minLng) / (maxLng - minLng)).toFloat().coerceIn(0.02f, 0.98f)
        fun normY(lat: Double): Float = (1.0 - (lat - minLat) / (maxLat - minLat)).toFloat().coerceIn(0.02f, 0.98f)

        val stById = network.stations.associateBy { it.id }
        val projStations = network.stations.map { st ->
            ProjectedStation(
                station = st,
                normX = normX(st.lng),
                normY = normY(st.lat)
            )
        }
        val projSegments = network.segments.mapNotNull { seg ->
            val a = stById[seg.fromStationId] ?: return@mapNotNull null
            val b = stById[seg.toStationId] ?: return@mapNotNull null
            ProjectedSegment(
                segmentId = seg.segmentId,
                trunkId = seg.trunkId,
                routes = seg.routes,
                startX = normX(a.lng),
                startY = normY(a.lat),
                endX = normX(b.lng),
                endY = normY(b.lat)
            )
        }
        PreProjectedMapGeometry(projStations, projSegments)
    }

    var zoom by remember { mutableStateOf(1.65f) }
    var panOffset by remember { mutableStateOf(Offset(-120f, -60f)) }

    val stats = uiState.stats
    val selectedRoute = uiState.selectedRouteFilter
    val selectedStation = remember(uiState.selectedStationId, network) {
        network.stations.firstOrNull { it.id == uiState.selectedStationId }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MtaColors.DarkBg)
            .testTag("subway_map_screen")
    ) {
        // 60fps Interactive Subway Map Canvas
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag("subway_map_canvas")
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        zoom = (zoom * gestureZoom).coerceIn(0.85f, 5.0f)
                        panOffset += pan
                    }
                }
                .pointerInput(zoom, panOffset) {
                    detectTapGestures { tapPos ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val nearest = projectedGeometry.stations.minByOrNull { ps ->
                            val sx = (ps.normX * w * zoom) + panOffset.x
                            val sy = (ps.normY * h * zoom) + panOffset.y
                            hypot(tapPos.x - sx, tapPos.y - sy)
                        }
                        if (nearest != null) {
                            val sx = (nearest.normX * w * zoom) + panOffset.x
                            val sy = (nearest.normY * h * zoom) + panOffset.y
                            if (hypot(tapPos.x - sx, tapPos.y - sy) <= 48f) {
                                onStationSelect(nearest.station.id)
                            } else {
                                onStationSelect(null)
                            }
                        }
                    }
                }
        ) {
            val w = size.width
            val h = size.height

            // Subtle dark geographic grid lines
            val gridStep = 120f
            var gx = (panOffset.x % gridStep)
            while (gx < w) {
                drawLine(
                    color = Color(0xFF121722),
                    start = Offset(gx, 0f),
                    end = Offset(gx, h),
                    strokeWidth = 1f
                )
                gx += gridStep
            }
            var gy = (panOffset.y % gridStep)
            while (gy < h) {
                drawLine(
                    color = Color(0xFF121722),
                    start = Offset(0f, gy),
                    end = Offset(w, gy),
                    strokeWidth = 1f
                )
                gy += gridStep
            }

            // Pass 1: Draw all UNRIDDEN subway segments in muted slate gray
            for (seg in projectedGeometry.segments) {
                if (selectedRoute != null && selectedRoute !in seg.routes) continue
                val start = Offset(seg.startX * w * zoom + panOffset.x, seg.startY * h * zoom + panOffset.y)
                val end = Offset(seg.endX * w * zoom + panOffset.x, seg.endY * h * zoom + panOffset.y)

                // Frustum culling
                if ((start.x < -50f && end.x < -50f) || (start.x > w + 50f && end.x > w + 50f)) continue
                if ((start.y < -50f && end.y < -50f) || (start.y > h + 50f && end.y > h + 50f)) continue

                drawLine(
                    color = MtaColors.UnriddenTrack,
                    start = start,
                    end = end,
                    strokeWidth = 4.5f * zoom.coerceIn(0.9f, 1.8f),
                    cap = StrokeCap.Round
                )
            }

            // Pass 2: Draw all RIDDEN subway segments in Vibrant Official MTA Line Colors or Heatmap
            for (seg in projectedGeometry.segments) {
                if (selectedRoute != null && selectedRoute !in seg.routes) continue
                val rideCount = stats.segmentRideCounts[seg.segmentId] ?: 0
                val riddenOnSelectedRoute = if (selectedRoute != null) {
                    stats.segmentRoutesRidden[seg.segmentId]?.contains(selectedRoute) == true
                } else {
                    rideCount > 0
                }
                if (!riddenOnSelectedRoute) continue

                val start = Offset(seg.startX * w * zoom + panOffset.x, seg.startY * h * zoom + panOffset.y)
                val end = Offset(seg.endX * w * zoom + panOffset.x, seg.endY * h * zoom + panOffset.y)

                val activeColor = when (uiState.mapColorMode) {
                    MapColorMode.OFFICIAL_MTA_COLORS -> {
                        val routeToColor = selectedRoute
                            ?: stats.segmentDominantRoutes[seg.segmentId]
                            ?: seg.routes.firstOrNull().orEmpty()
                        MtaColors.routeColor(routeToColor)
                    }
                    MapColorMode.FREQUENCY_HEATMAP -> MtaColors.heatmapColorForCount(rideCount)
                }

                // Outer glow for ridden segments
                drawLine(
                    color = activeColor.copy(alpha = 0.28f),
                    start = start,
                    end = end,
                    strokeWidth = 13f * zoom.coerceIn(0.9f, 1.8f),
                    cap = StrokeCap.Round
                )

                // Crisp vibrant MTA core track
                drawLine(
                    color = activeColor,
                    start = start,
                    end = end,
                    strokeWidth = 6.5f * zoom.coerceIn(0.9f, 1.8f),
                    cap = StrokeCap.Round
                )
            }

            // Pass 3: Draw Station Dots (with Zoom LOD filtering)
            for (ps in projectedGeometry.stations) {
                val st = ps.station
                if (selectedRoute != null && selectedRoute !in st.daytimeRoutes) continue
                val center = Offset(ps.normX * w * zoom + panOffset.x, ps.normY * h * zoom + panOffset.y)
                if (center.x < -20f || center.x > w + 20f || center.y < -20f || center.y > h + 20f) continue

                val isVisited = st.id in stats.visitedStationIdsStoppedAt
                val isBoardedOrExited = st.id in stats.visitedStationIdsBoardedExited
                val isSelected = st.id == uiState.selectedStationId

                // LOD level 1: at low zoom, only draw visited stations or transfer hubs
                if (zoom < 1.25f && !isVisited && !st.isTransferHub) continue

                val radius = when {
                    isSelected -> 9.5f
                    isBoardedOrExited -> 6.5f
                    isVisited -> 5.2f
                    st.isTransferHub -> 4.2f
                    else -> 3.0f
                } * zoom.coerceIn(0.85f, 1.45f)

                val fillColor = when {
                    isSelected -> MtaColors.AccentGold
                    isBoardedOrExited -> Color.White
                    isVisited -> MtaColors.routeColor(st.daytimeRoutes.firstOrNull().orEmpty())
                    else -> MtaColors.UnriddenStation
                }

                drawCircle(
                    color = MtaColors.DarkBg,
                    radius = radius + 2.5f,
                    center = center
                )
                drawCircle(
                    color = fillColor,
                    radius = radius,
                    center = center
                )
            }
        }

        // Top Control Overlay: Color Mode Toggle + Route Bullet Filter Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MtaColors.CardBg.copy(alpha = 0.94f))
                    .border(1.dp, MtaColors.CardBorder, RoundedCornerShape(12.dp))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MapColorMode.entries.forEach { mode ->
                        val active = uiState.mapColorMode == mode
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) Color(0xFF0039A6) else Color(0xFF1D2534))
                                .clickable { onColorModeChange(mode) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .testTag("map_mode_${mode.name}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = mode.label,
                                color = if (active) Color.White else MtaColors.TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Text(
                    text = "${stats.uniqueCorridorMilesRidden} mi (${stats.systemCompletionPercent}%)",
                    color = MtaColors.AccentGold,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("map_header_unique_miles")
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Horizontal MTA Line Filter Bar
            val filterRoutes = listOf("1", "2", "3", "4", "5", "6", "7", "A", "C", "E", "B", "D", "F", "M", "G", "J", "L", "N", "Q", "R", "S")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MtaColors.CardBg.copy(alpha = 0.92f))
                    .border(1.dp, MtaColors.CardBorder, RoundedCornerShape(12.dp))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selectedRoute == null) Color.White else Color(0xFF1E2636))
                        .clickable { onRouteFilterSelect(null) }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .testTag("filter_route_ALL")
                ) {
                    Text(
                        text = "All Lines",
                        color = if (selectedRoute == null) Color.Black else MtaColors.TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                filterRoutes.forEach { rId ->
                    val isSelected = selectedRoute == rId
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .border(
                                width = if (isSelected) 2.dp else 0.dp,
                                color = if (isSelected) Color.White else Color.Transparent,
                                shape = RoundedCornerShape(50)
                            )
                            .clickable { onRouteFilterSelect(rId) }
                            .testTag("filter_route_$rId")
                    ) {
                        MtaRouteBullet(routeId = rId, size = 26.dp, fontSize = 12.sp)
                    }
                }
            }
        }

        // Zoom Controls (+/-)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                color = MtaColors.CardBg,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .clickable { zoom = (zoom * 1.25f).coerceAtMost(5.0f) }
                    .testTag("map_zoom_in")
            ) {
                Text(
                    text = "+",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            Surface(
                color = MtaColors.CardBg,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .clickable { zoom = (zoom / 1.25f).coerceAtLeast(0.85f) }
                    .testTag("map_zoom_out")
            ) {
                Text(
                    text = "−",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // Interactive Station Inspection Sheet at Bottom
        if (selectedStation != null) {
            val isStopped = selectedStation.id in stats.visitedStationIdsStoppedAt
            val isBoarded = selectedStation.id in stats.visitedStationIdsBoardedExited
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MtaColors.CardBg)
                    .border(1.dp, MtaColors.CardBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp)
                    .testTag("station_detail_sheet")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = selectedStation.name,
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${selectedStation.borough.displayName} • Complex: ${selectedStation.complexName}",
                            color = MtaColors.TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        selectedStation.daytimeRoutes.forEach { rId ->
                            MtaRouteBullet(routeId = rId, size = 24.dp, fontSize = 11.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = when {
                        isBoarded -> "Status: Visited (Boarded / Exited / Transferred here)"
                        isStopped -> "Status: Stopped Through on a train"
                        else -> "Status: Unvisited station"
                    },
                    color = if (isStopped) MtaColors.AccentGreen else MtaColors.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { onStartRideFromStation(selectedStation.id) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0039A6)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Log Ride From Here", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { onEndRideAtStation(selectedStation.id) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00933C)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Log Ride To Here", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
