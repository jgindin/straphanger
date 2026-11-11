package com.straphanger.app.data.sync

import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.data.local.SubwayDao
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class StraphangerBackupBundle(
    val formatVersion: Int = 1,
    val exportedAtEpochMs: Long,
    val rides: List<CompleteRideRecord>
)

class BackupExportManager(
    private val dao: SubwayDao
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun exportToJson(exportedAtEpochMs: Long = 1794250800000L): String {
        val bundle = StraphangerBackupBundle(
            formatVersion = 1,
            exportedAtEpochMs = exportedAtEpochMs,
            rides = dao.getAllRides()
        )
        return json.encodeToString(bundle)
    }

    fun importFromJson(backupJson: String): Int {
        val bundle = json.decodeFromString<StraphangerBackupBundle>(backupJson)
        dao.insertOrReplaceRides(bundle.rides)
        return bundle.rides.size
    }

    fun exportToCsv(): String {
        val header = "ride_id,start_time_epoch_ms,origin_station_id,destination_station_id,routes,distance_miles,duration_min,source"
        val rows = dao.getAllRides().map { rec ->
            val routes = rec.legs.joinToString("+") { leg ->
                if (leg.isExpress) "${leg.routeId}X" else leg.routeId
            }
            "${rec.ride.rideId},${rec.ride.startTimeEpochMs},${rec.ride.originStationId},${rec.ride.destinationStationId},$routes,${rec.ride.totalDistanceMiles},${rec.ride.durationMinutes},${rec.ride.source.name}"
        }
        return (listOf(header) + rows).joinToString("\n")
    }
}
