package com.straphanger.app.data.sync

import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.data.local.SubwayDao
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class FirebaseConfig(
    val projectId: String = "",
    val apiKey: String = "",
    val userId: String = "anonymous_local_user",
    val idToken: String? = null
) {
    val isConfigured: Boolean
        get() = projectId.isNotBlank() && apiKey.isNotBlank()
}

@Serializable
data class SyncResult(
    val uploadedCount: Int,
    val downloadedCount: Int,
    val pendingOfflineCount: Int,
    val statusMessage: String,
    val succeeded: Boolean
)

class FirestoreKmpSyncClient(
    private val dao: SubwayDao,
    private val httpClient: HttpClient = HttpClient(),
    private var config: FirebaseConfig = FirebaseConfig()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun updateConfig(newConfig: FirebaseConfig) {
        config = newConfig
    }

    fun getConfig(): FirebaseConfig = config

    /**
     * Performs anonymous Firebase Authentication if idToken is not yet present.
     */
    suspend fun signInAnonymouslyIfNeeded(): Boolean {
        if (!config.isConfigured) return false
        if (!config.idToken.isNullOrBlank()) return true

        return runCatching {
            val response = httpClient.post(
                "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=${config.apiKey}"
            ) {
                contentType(ContentType.Application.Json)
                setBody("""{"returnSecureToken":true}""")
            }
            if (!response.status.isSuccess()) return false
            val bodyObj = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val token = bodyObj["idToken"]?.jsonPrimitive?.content ?: return false
            val localId = bodyObj["localId"]?.jsonPrimitive?.content ?: config.userId
            config = config.copy(idToken = token, userId = localId)
            true
        }.getOrDefault(false)
    }

    /**
     * Pushes all unsynced local rides to Cloud Firestore (free Spark tier) and marks them synced.
     * If offline underground or Firebase is not yet configured, safely leaves rides in the local queue.
     */
    suspend fun syncPendingRides(): SyncResult {
        val unsynced = dao.getUnsyncedRides()
        if (!config.isConfigured) {
            return SyncResult(
                uploadedCount = 0,
                downloadedCount = 0,
                pendingOfflineCount = unsynced.size,
                statusMessage = "Local-first mode active (${unsynced.size} rides stored on device; add Firebase project ID & API key to enable cloud sync)",
                succeeded = false
            )
        }

        val signedIn = signInAnonymouslyIfNeeded()
        if (!signedIn) {
            return SyncResult(
                uploadedCount = 0,
                downloadedCount = 0,
                pendingOfflineCount = unsynced.size,
                statusMessage = "Offline underground — ${unsynced.size} rides queued for automatic Firestore sync",
                succeeded = false
            )
        }

        val syncedIds = mutableSetOf<String>()
        for (record in unsynced) {
            val uploaded = runCatching {
                val docUrl = "https://firestore.googleapis.com/v1/projects/${config.projectId}/databases/(default)/documents/users/${config.userId}/rides/${record.ride.rideId}"
                val payloadJson = json.encodeToString(record.copy(ride = record.ride.copy(syncedToCloud = true)))
                val firestoreDocBody = buildFirestoreDocumentJson(record.ride.rideId, record.ride.startTimeEpochMs, payloadJson)
                val resp = httpClient.patch(docUrl) {
                    contentType(ContentType.Application.Json)
                    config.idToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                    setBody(firestoreDocBody)
                }
                resp.status.isSuccess()
            }.getOrDefault(false)

            if (uploaded) {
                syncedIds.add(record.ride.rideId)
            }
        }

        dao.markRidesSynced(syncedIds)
        val remaining = dao.getUnsyncedRides().size
        return SyncResult(
            uploadedCount = syncedIds.size,
            downloadedCount = 0,
            pendingOfflineCount = remaining,
            statusMessage = if (remaining == 0) {
                "Synced ${syncedIds.size} rides to Firebase Cloud Firestore"
            } else {
                "Uploaded ${syncedIds.size} rides; $remaining still queued offline"
            },
            succeeded = remaining == 0
        )
    }

    private fun buildFirestoreDocumentJson(
        rideId: String,
        startTimeEpochMs: Long,
        serializedRecordJson: String
    ): String {
        val escapedPayload = serializedRecordJson
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        return """
            {
              "fields": {
                "rideId": { "stringValue": "$rideId" },
                "startTimeEpochMs": { "integerValue": "$startTimeEpochMs" },
                "payloadJson": { "stringValue": "$escapedPayload" }
              }
            }
        """.trimIndent()
    }
}
