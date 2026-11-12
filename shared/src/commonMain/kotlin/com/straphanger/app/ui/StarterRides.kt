package com.straphanger.app.ui

import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.gtfs.TripLegRequest
import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.data.local.RideSource
import com.straphanger.app.data.local.SubwayDao

private const val MS_PER_MINUTE = 60_000L
private const val MS_PER_DAY = 24 * 60 * MS_PER_MINUTE
private const val STARTER_DAYS_BEFORE_NOW = 3
private const val BEDFORD_TO_86_ST_MINUTES = 24
private const val HARLEM_TO_14_ST_MINUTES = 19
private const val SHUTTLE_MINUTES = 4

/**
 * Three sample rides on the three days ending at [nowEpochMs], oldest first: Bedford Av (L) to 86
 * St through 14 St-Union Sq, the A Express from 125 St to 14 St, and the 42 St Shuttle.
 */
internal fun starterNycRides(
  engine: SubwayGraphEngine,
  nowEpochMs: Long,
): List<CompleteRideRecord> {
  val baseTime = nowEpochMs - STARTER_DAYS_BEFORE_NOW * MS_PER_DAY
  return listOf(
    bedfordToEightySixthSt(engine, baseTime),
    harlemToFourteenthStExpress(engine, baseTime + MS_PER_DAY),
    timesSqShuttle(engine, baseTime + 2 * MS_PER_DAY),
  )
}

private fun bedfordToEightySixthSt(engine: SubwayGraphEngine, start: Long) =
  SubwayDao.buildCompleteRideRecord(
    rideId = "starter_1",
    startTimeEpochMs = start,
    endTimeEpochMs = start + BEDFORD_TO_86_ST_MINUTES * MS_PER_MINUTE,
    source = RideSource.TIMELINE_IMPORT,
    resolved =
      engine.resolveOriginToDestinationAuto(
        originStationId = StationIds.BEDFORD_AV_L,
        destinationStationId = StationIds.EIGHTY_SIXTH_LEX,
        timestampEpochMs = start,
      ),
    graphEngine = engine,
  )

private fun harlemToFourteenthStExpress(engine: SubwayGraphEngine, start: Long) =
  SubwayDao.buildCompleteRideRecord(
    rideId = "starter_2",
    startTimeEpochMs = start,
    endTimeEpochMs = start + HARLEM_TO_14_ST_MINUTES * MS_PER_MINUTE,
    source = RideSource.GMAPS_NOTIFICATION,
    resolved =
      engine.resolveTrip(
        legs =
          listOf(
            TripLegRequest(
              StationIds.HARLEM_125_8AV,
              StationIds.FOURTEENTH_ST_8AV,
              preferredRouteId = "A",
              forceExpress = true,
            )
          ),
        timestampEpochMs = start,
      ),
    graphEngine = engine,
  )

private fun timesSqShuttle(engine: SubwayGraphEngine, start: Long) =
  SubwayDao.buildCompleteRideRecord(
    rideId = "starter_3",
    startTimeEpochMs = start,
    endTimeEpochMs = start + SHUTTLE_MINUTES * MS_PER_MINUTE,
    source = RideSource.TIMELINE_IMPORT,
    resolved =
      engine.resolveTrip(
        legs =
          listOf(
            TripLegRequest(
              StationIds.TIMES_SQ_SHUTTLE,
              StationIds.GRAND_CENTRAL_SHUTTLE,
              preferredRouteId = "GS",
            )
          ),
        timestampEpochMs = start,
      ),
    graphEngine = engine,
  )
