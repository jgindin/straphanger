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
     * importing the same backup again does not create duplicates. Fails, without changing
     * any rides, when the text is not a Straphanger backup.
     */
    fun importFromJson(backupJson: String): Result<Int> = runCatching {
        val bundle = json.decodeFromString<StraphangerBackupBundle>(backupJson)
        require(bundle.formatVersion in 1..SUPPORTED_FORMAT_VERSION) {
            "Unsupported backup format version ${bundle.formatVersion}"
        }
        dao.insertOrReplaceRides(bundle.rides)
        bundle.rides.size
    }

    private companion object {
        const val SUPPORTED_FORMAT_VERSION = 1
    }
}
