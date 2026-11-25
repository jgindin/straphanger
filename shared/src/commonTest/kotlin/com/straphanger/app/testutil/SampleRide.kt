package com.straphanger.app.testutil

import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.ui.RideLogging
import com.straphanger.app.ui.StationIds

/**
 * Logs Bedford Av (L) to 86 St with a change at 14 St-Union Sq, a two-leg ride, as the log form
 * would; the form starts empty, so tests name the stations.
 */
fun RideLogging.logSampleRide(timestampEpochMs: Long? = null): CompleteRideRecord? =
  submitQuickRide(
    originStationId = StationIds.BEDFORD_AV_L,
    transferStationId = StationIds.UNION_SQ_L,
    destinationStationId = StationIds.EIGHTY_SIXTH_LEX,
    preferredRouteId = null,
    forceExpress = null,
    timestampEpochMs = timestampEpochMs,
  )
