package com.straphanger.app.data.sync

import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.data.local.SubwayDao
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock

@Serializable
data class StraphangerBackupBundle(
    val formatVersion: Int = 1,
    val exportedAtEpochMs: Long,
    val rides: List<CompleteRideRecord>
)

class BackupExportManager(
    private val dao: SubwayDao,
    private val clock: Clock = Clock.System
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun exportToJson(): String {
        val bundle = StraphangerBackupBundle(
            formatVersion = 1,
            exportedAtEpochMs = clock.now().toEpochMilliseconds(),
            rides = dao.getAllRides()
        )
        return json.encodeToString(bundle)
    }

    /**
     * Restores rides from a backup produced by [exportToJson]. Rides are matched by id, so
     * importing the same backup again does not create duplicates, and a backup that lists a ride
     * id twice keeps the last entry. Returns a failure, without changing any rides, when the text
     * is not a Straphanger backup. A failure to save the rides is thrown, not returned.
     */
    fun importFromJson(backupJson: String): Result<Int> {
        val rides = try {
            val bundle = json.decodeFromString<StraphangerBackupBundle>(backupJson)
            require(bundle.formatVersion in 1..SUPPORTED_FORMAT_VERSION) {
                "Unsupported backup format version ${bundle.formatVersion}"
            }
            bundle.rides.associateBy { it.ride.rideId }.values.toList()
        } catch (e: IllegalArgumentException) {
            return Result.failure(e)
        }
        dao.insertOrReplaceRides(rides)
        return Result.success(rides.size)
    }

    private companion object {
        const val SUPPORTED_FORMAT_VERSION = 1
    }
}
