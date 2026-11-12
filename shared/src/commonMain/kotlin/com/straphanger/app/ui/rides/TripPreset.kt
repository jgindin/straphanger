package com.straphanger.app.ui.rides

import com.straphanger.app.ui.StationIds

/** A log form filled in with one tap. */
internal data class TripPreset(
  val label: String,
  val testTag: String,
  val originStationId: String,
  val transferStationId: String?,
  val destinationStationId: String,
  val routeId: String,
  val express: Boolean,
)

internal val TRIP_PRESETS =
  listOf(
    TripPreset(
      label = "Bedford (L) → 14 St → 86 St (4X)",
      testTag = "preset_transfer_l_4",
      originStationId = StationIds.BEDFORD_AV_L,
      transferStationId = StationIds.UNION_SQ_L,
      destinationStationId = StationIds.EIGHTY_SIXTH_LEX,
      routeId = "4",
      express = true,
    ),
    TripPreset(
      label = "14 St → 86 St (6 Local)",
      testTag = "preset_lex_6_local",
      originStationId = StationIds.UNION_SQ_LEX,
      transferStationId = null,
      destinationStationId = StationIds.EIGHTY_SIXTH_LEX,
      routeId = "6",
      express = false,
    ),
    TripPreset(
      label = "Times Sq →Grand Central (S Shuttle)",
      testTag = "preset_42_shuttle",
      originStationId = StationIds.TIMES_SQ_SHUTTLE,
      transferStationId = null,
      destinationStationId = StationIds.GRAND_CENTRAL_SHUTTLE,
      routeId = "GS",
      express = false,
    ),
    TripPreset(
      label = "Yankee Stadium → Atlantic-Barclays (D)",
      testTag = "preset_bronx_bk_d",
      originStationId = StationIds.YANKEE_STADIUM_D,
      transferStationId = null,
      destinationStationId = StationIds.ATLANTIC_AV_D,
      routeId = "D",
      express = true,
    ),
  )
