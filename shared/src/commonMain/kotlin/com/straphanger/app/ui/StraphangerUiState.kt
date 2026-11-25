package com.straphanger.app.ui

import com.straphanger.app.data.capture.TimelineImportSummary
import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.domain.stats.StationGroupingMode
import com.straphanger.app.domain.stats.StationVisitFilterMode
import com.straphanger.app.domain.stats.SubwayStatsSnapshot
import com.straphanger.app.ui.map.ProjectedBasemap

enum class MapColorMode(val label: String) {
  OFFICIAL_MTA_COLORS("MTA Line Colors"),
  FREQUENCY_HEATMAP("Frequency Heatmap"),
}

/** The bottom navigation tabs, in the order the bar shows them. */
enum class AppTab(val label: String) {
  STATUS("Status"),
  MAP("Map"),
  RIDES("Rides"),
  STATS("Stats"),
}

data class StraphangerUiState(
  val activeTab: AppTab = AppTab.STATUS,
  /** The ride a tapped notification opened; the Rides tab scrolls to it until the tab changes. */
  val highlightedRideId: String? = null,
  val rides: List<CompleteRideRecord> = emptyList(),
  val stats: SubwayStatsSnapshot,
  val mapColorMode: MapColorMode = MapColorMode.OFFICIAL_MTA_COLORS,
  val selectedRouteFilter: String? = null,
  val selectedStationId: String? = null,
  /** When set, the Rides tab lists only the rides with a leg on this route. */
  val ridesRouteFilter: String? = null,
  /** The land, parks and roads under the map; null while loading or if it could not be loaded. */
  val basemap: ProjectedBasemap? = null,
  val stationGroupingMode: StationGroupingMode = StationGroupingMode.INDIVIDUAL_STATIONS,
  val stationVisitFilterMode: StationVisitFilterMode = StationVisitFilterMode.STOPPED_AT,
  val isLogRideSheetOpen: Boolean = false,
  // GTFS station IDs: Bedford Av (L), 14 St-Union Sq (L), 86 St (4/5/6)
  val logOriginStationId: String = StationIds.BEDFORD_AV_L,
  val logTransferStationId: String? = StationIds.UNION_SQ_L,
  val logDestinationStationId: String = StationIds.EIGHTY_SIXTH_LEX,
  val logPreferredRouteId: String? = null,
  val logForceExpress: Boolean? = null,
  /** True when station geofences are registered with the platform. */
  val passiveGeofenceEnabled: Boolean = false,
  /** True when the Google Maps notification listener has notification access. */
  val googleMapsNotificationEnabled: Boolean = false,
  /** The user's choice to be notified when a ride is recorded. */
  val rideNotificationsTurnedOn: Boolean = false,
  /** False when the user chose notifications but the system blocks them for the app. */
  val postNotificationsGranted: Boolean = true,
  val activeGeofenceCount: Int = 0,
  /** Why station geofences are not registered, or null. */
  val passiveCaptureError: String? = null,
  val statusMessage: String = "Rides are stored on this device",
  /** True when [statusMessage] reports a failure (shown as a warning). */
  val statusIsFailure: Boolean = false,
  val loadError: String? = null,
  val isNetworkLoaded: Boolean = false,
  /** Set when the bundled subway network could not be loaded; the app cannot work without it. */
  val networkLoadError: String? = null,
  val timelineImportInProgress: Boolean = false,
  /** Counts from the most recent Timeline file import, without the imported records. */
  val lastImportSummary: TimelineImportSummary? = null,
)

/** Permission and access state reported by the platform; refreshed whenever the app resumes. */
data class CaptureAccessState(
  /**
   * Fine location is granted. Background location may still be missing; registration then reports
   * it.
   */
  val locationGranted: Boolean = false,
  /** "Allow all the time"; Android 10 and later need it for geofences. */
  val backgroundLocationGranted: Boolean = false,
  /** The device location setting is on. Unknown counts as on. */
  val locationServicesEnabled: Boolean = true,
  /**
   * Times the platform reported that it dropped every geofence (e.g. GEOFENCE_NOT_AVAILABLE). A new
   * count means the geofences must be registered again.
   */
  val geofencesDroppedCount: Int = 0,
  val notificationAccessEnabled: Boolean = false,
  /** The user's saved choice to be notified when a ride is recorded. */
  val rideNotificationsTurnedOn: Boolean = false,
  /** The system lets the app post notifications. Unknown counts as allowed. */
  val postNotificationsGranted: Boolean = true,
  /** The user's saved choice for passive capture; when false no geofences are registered. */
  val passiveCaptureTurnedOn: Boolean = true,
  val lastKnownLat: Double? = null,
  val lastKnownLng: Double? = null,
)

/** GTFS parent-station IDs used by the log form defaults, presets and sample rides. */
object StationIds {
  const val BEDFORD_AV_L = "L08"
  const val UNION_SQ_L = "L03"
  const val UNION_SQ_LEX = "635"
  const val EIGHTY_SIXTH_LEX = "626"
  const val TIMES_SQ_SHUTTLE = "902"
  const val GRAND_CENTRAL_SHUTTLE = "901"
  const val COLUMBUS_CIRCLE_8AV = "A24"
  const val HARLEM_125_8AV = "A15"
  const val FOURTEENTH_ST_8AV = "A31"
  const val YANKEE_STADIUM_D = "D11"
  const val ATLANTIC_AV_D = "R31"
}

/**
 * Two subway rides in Android Timeline.json format: 6 from 86 St to 14 St-Union Sq, then A from 125
 * St to 59 St-Columbus Circle.
 */
internal const val SAMPLE_TIMELINE_JSON =
  """
    {
      "semanticSegments": [
        {
          "startTime": "2026-11-05T08:30:00.000-05:00",
          "endTime": "2026-11-05T08:48:00.000-05:00",
          "activity": {
            "start": { "latLng": "40.7795°, -73.9556°" },
            "end": { "latLng": "40.7347°, -73.9900°" },
            "topCandidate": { "type": "IN_SUBWAY", "transitLine": "6 Train" }
          }
        },
        {
          "startTime": "2026-11-06T18:15:00.000-05:00",
          "endTime": "2026-11-06T18:27:00.000-05:00",
          "activity": {
            "start": { "latLng": "40.8111°, -73.9523°" },
            "end": { "latLng": "40.7683°, -73.9817°" },
            "topCandidate": { "type": "IN_SUBWAY", "transitLine": "A Express" }
          }
        }
      ]
    }
"""
