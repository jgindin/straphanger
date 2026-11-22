package com.straphanger.app.data.realtime

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** What the MTA feeds said at [fetchedAtEpochSeconds]. */
data class RealtimeSnapshot(
  val arrivals: ArrivalBoard,
  val alerts: List<ServiceAlert>,
  val fetchedAtEpochSeconds: Long,
  /** True when some of the feeds asked for could not be read, so the data may be incomplete. */
  val partial: Boolean,
)

/**
 * Reads the MTA's public GTFS-Realtime feeds: no key, no account, nothing about the user is sent.
 */
class RealtimeFeedClient(
  private val http: HttpClient,
  private val baseUrl: String = MTA_FEEDS_BASE_URL,
  private val nowEpochSeconds: () -> Long,
) {
  /**
   * The arrivals on the feeds that carry [routes] and the current service alerts, fetched together.
   * A feed that fails leaves the rest usable and sets [RealtimeSnapshot.partial]; when every feed
   * fails this throws the last error so the caller can say the MTA is unreachable.
   */
  suspend fun fetch(routes: Collection<String>): RealtimeSnapshot = coroutineScope {
    val trains = RealtimeFeed.forRoutes(routes).map { feed -> async { read(feed.path) } }
    val alerts = async { read(ALERTS_FEED_PATH) }
    val results = (trains + alerts).awaitAll()
    val feeds = results.mapNotNull { it.getOrNull() }
    if (feeds.isEmpty())
      throw results.first().exceptionOrNull() ?: IllegalStateException("no feeds")
    val now = nowEpochSeconds()
    val trainResults = results.dropLast(1)
    val alertFeed = results.last().getOrNull()
    RealtimeSnapshot(
      arrivals = ArrivalBoard.from(trainResults.mapNotNull { it.getOrNull() }),
      alerts = alertFeed?.let { activeAlerts(it, now) }.orEmpty(),
      fetchedAtEpochSeconds = now,
      partial = results.any { it.isFailure },
    )
  }

  private suspend fun read(path: String): Result<FeedMessage> =
    try {
      val response: HttpResponse = http.get(baseUrl + path)
      if (!response.status.isSuccess()) error("MTA feed $path answered ${response.status}")
      Result.success(decodeFeed(response.body<ByteArray>()))
    } catch (e: CancellationException) {
      throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
      Result.failure(e)
    }

  companion object {
    /** A client of the MTA's feeds over the platform's default HTTP engine. */
    fun mta(): RealtimeFeedClient =
      RealtimeFeedClient(HttpClient(), nowEpochSeconds = { Clock.System.now().epochSeconds })
  }
}
