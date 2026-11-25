package com.straphanger.app.ui.rides

import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.local.CompleteRideRecord

/** What the rows of the Rides list show and do, besides the rows themselves. */
internal class RideListContent(
  val graphEngine: SubwayGraphEngine,
  val pendingReviewRides: List<CompleteRideRecord>,
  /** The ride each ride could be combined with, by ride id. */
  val nextRides: Map<String, CompleteRideRecord?>,
  val reviewActions: RideReviewActions,
  val highlightedRideId: String?,
  val onClearRouteFilter: () -> Unit,
  /** Deletes the ride and the app offers to undo it; false when the deletion was not saved. */
  val onDelete: (CompleteRideRecord) -> Boolean,
)
