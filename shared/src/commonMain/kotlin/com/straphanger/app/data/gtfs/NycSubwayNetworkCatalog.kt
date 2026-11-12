package com.straphanger.app.data.gtfs

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import straphanger.shared.generated.resources.Res

/**
 * Loads the subway network generated from MTA GTFS by tools/gtfs-import
 * (composeResources/files/nyc_subway_network.json).
 */
object NycSubwayNetworkCatalog {

  const val BUNDLED_RESOURCE_PATH = "files/nyc_subway_network.json"

  private val json = Json

  private val mutex = Mutex()
  private var cached: SubwayNetworkData? = null

  /** Reads and parses the bundled network once; later calls return the same instance. */
  suspend fun loadBundled(): SubwayNetworkData = mutex.withLock {
    cached
      ?: withContext(Dispatchers.IO) {
          parse(Res.readBytes(BUNDLED_RESOURCE_PATH).decodeToString())
        }
        .also { cached = it }
  }

  fun parse(json: String): SubwayNetworkData =
    this.json.decodeFromString(SubwayNetworkData.serializer(), json)

  fun haversineMiles(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val rMiles = 3958.8
    val dLat = (lat2 - lat1) * PI / 180.0
    val dLon = (lon2 - lon1) * PI / 180.0
    val a =
      sin(dLat / 2.0) * sin(dLat / 2.0) +
        cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * sin(dLon / 2.0) * sin(dLon / 2.0)
    val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    return rMiles * c
  }
}
