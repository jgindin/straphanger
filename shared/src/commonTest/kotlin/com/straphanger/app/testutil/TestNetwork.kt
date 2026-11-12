package com.straphanger.app.testutil

import com.straphanger.app.data.gtfs.Borough
import com.straphanger.app.data.gtfs.NycSubwayNetworkCatalog
import com.straphanger.app.data.gtfs.ServicePeriod
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.gtfs.SubwayNetworkData
import com.straphanger.app.data.gtfs.SubwayRouteInfo
import com.straphanger.app.data.gtfs.SubwaySegment
import com.straphanger.app.data.gtfs.SubwayStation
import kotlin.math.roundToInt

/**
 * A small hand-written slice of the real network, using GTFS station and complex IDs:
 * - Lexington Av 14 St-Union Sq (635) to 86 St (626): 6 local, 4 express (14 St, Grand Central, 59
 *   St, 86 St by day; all stops late at night).
 * - 8 Av / Central Park West 14 St (A31) to 125 St (A15): C local, A express (14 St, 34 St, 42 St,
 *   59 St, 125 St by day; all stops late at night).
 * - L from Bedford Av (L08) to 14 St-Union Sq (L03), which shares complex 602 with 635.
 * - 42 St Shuttle (GS) Times Sq (902) to Grand Central (901). Segment lengths are haversine x 1.08,
 *   the generator's no-shape fallback.
 */
object TestNetwork {

  val lexLocal = listOf("635", "634", "633", "632", "631", "630", "629", "628", "627", "626")
  val lexExpressDaytime = listOf("635", "631", "629", "626")
  val eighthAvLocal =
    listOf(
      "A31",
      "A30",
      "A28",
      "A27",
      "A25",
      "A24",
      "A22",
      "A21",
      "A20",
      "A19",
      "A18",
      "A17",
      "A16",
      "A15",
    )
  val eighthAvExpressDaytime = listOf("A31", "A28", "A27", "A24", "A15")
  val canarsie = listOf("L08", "L06", "L05", "L03")
  val shuttle42 = listOf("902", "901")

  private val stationRows =
    listOf(
      station(
        "L08",
        "Bedford Av",
        "120",
        "Bedford Av",
        Borough.BROOKLYN,
        40.717304,
        -73.956872,
        listOf("L"),
      ),
      station("L06", "1 Av", "119", "1 Av", Borough.MANHATTAN, 40.730953, -73.981628, listOf("L")),
      station("L05", "3 Av", "118", "3 Av", Borough.MANHATTAN, 40.732849, -73.986122, listOf("L")),
      station(
        "L03",
        "14 St-Union Sq",
        "602",
        "14 St-Union Sq",
        Borough.MANHATTAN,
        40.734789,
        -73.99073,
        listOf("L"),
      ),
      station(
        "635",
        "14 St-Union Sq",
        "602",
        "14 St-Union Sq",
        Borough.MANHATTAN,
        40.734673,
        -73.989951,
        listOf("4", "6"),
      ),
      station(
        "634",
        "23 St-Baruch College",
        "405",
        "23 St-Baruch College",
        Borough.MANHATTAN,
        40.739864,
        -73.986599,
        listOf("6"),
      ),
      station("633", "28 St", "404", "28 St", Borough.MANHATTAN, 40.74307, -73.984264, listOf("6")),
      station(
        "632",
        "33 St",
        "403",
        "33 St",
        Borough.MANHATTAN,
        40.746081,
        -73.982076,
        listOf("6"),
      ),
      station(
        "631",
        "Grand Central-42 St",
        "610",
        "Grand Central-42 St",
        Borough.MANHATTAN,
        40.751776,
        -73.976848,
        listOf("4", "6"),
      ),
      station("630", "51 St", "612", "51 St", Borough.MANHATTAN, 40.757107, -73.97192, listOf("6")),
      station(
        "629",
        "59 St",
        "613",
        "59 St",
        Borough.MANHATTAN,
        40.762526,
        -73.967967,
        listOf("4", "6"),
      ),
      station(
        "628",
        "68 St-Hunter College",
        "399",
        "68 St-Hunter College",
        Borough.MANHATTAN,
        40.768141,
        -73.96387,
        listOf("6"),
      ),
      station("627", "77 St", "398", "77 St", Borough.MANHATTAN, 40.77362, -73.959874, listOf("6")),
      station(
        "626",
        "86 St",
        "397",
        "86 St",
        Borough.MANHATTAN,
        40.779492,
        -73.955589,
        listOf("4", "6"),
      ),
      station(
        "A31",
        "14 St",
        "618",
        "14 St / 8 Av",
        Borough.MANHATTAN,
        40.740893,
        -74.00169,
        listOf("A", "C"),
      ),
      station(
        "A30",
        "23 St",
        "165",
        "23 St",
        Borough.MANHATTAN,
        40.745906,
        -73.998041,
        listOf("C"),
      ),
      station(
        "A28",
        "34 St-Penn Station",
        "164",
        "34 St-Penn Station",
        Borough.MANHATTAN,
        40.752287,
        -73.993391,
        listOf("A", "C"),
      ),
      station(
        "A27",
        "42 St-Port Authority Bus Terminal",
        "611",
        "Times Sq-42 St",
        Borough.MANHATTAN,
        40.757308,
        -73.989735,
        listOf("A", "C"),
      ),
      station(
        "A25",
        "50 St",
        "162",
        "50 St",
        Borough.MANHATTAN,
        40.762456,
        -73.985984,
        listOf("C"),
      ),
      station(
        "A24",
        "59 St-Columbus Circle",
        "614",
        "59 St-Columbus Circle",
        Borough.MANHATTAN,
        40.768296,
        -73.981736,
        listOf("A", "C"),
      ),
      station("A22", "72 St", "160", "72 St", Borough.MANHATTAN, 40.775594, -73.97641, listOf("C")),
      station(
        "A21",
        "81 St-Museum of Natural History",
        "159",
        "81 St-Museum of Natural History",
        Borough.MANHATTAN,
        40.781433,
        -73.972143,
        listOf("C"),
      ),
      station(
        "A20",
        "86 St",
        "158",
        "86 St",
        Borough.MANHATTAN,
        40.785868,
        -73.968916,
        listOf("C"),
      ),
      station(
        "A19",
        "96 St",
        "157",
        "96 St",
        Borough.MANHATTAN,
        40.791642,
        -73.964696,
        listOf("C"),
      ),
      station(
        "A18",
        "103 St",
        "156",
        "103 St",
        Borough.MANHATTAN,
        40.796092,
        -73.961454,
        listOf("C"),
      ),
      station(
        "A17",
        "Cathedral Pkwy (110 St)",
        "155",
        "Cathedral Pkwy (110 St)",
        Borough.MANHATTAN,
        40.800603,
        -73.958161,
        listOf("C"),
      ),
      station(
        "A16",
        "116 St",
        "154",
        "116 St",
        Borough.MANHATTAN,
        40.805085,
        -73.954882,
        listOf("C"),
      ),
      station(
        "A15",
        "125 St",
        "153",
        "125 St",
        Borough.MANHATTAN,
        40.811109,
        -73.952343,
        listOf("A", "C"),
      ),
      station(
        "902",
        "Times Sq-42 St",
        "611",
        "Times Sq-42 St",
        Borough.MANHATTAN,
        40.755983,
        -73.986229,
        listOf("GS"),
      ),
      station(
        "901",
        "Grand Central-42 St",
        "610",
        "Grand Central-42 St",
        Borough.MANHATTAN,
        40.752769,
        -73.979189,
        listOf("GS"),
      ),
    )

  private fun station(
    id: String,
    name: String,
    complexId: String,
    complexName: String,
    borough: Borough,
    lat: Double,
    lng: Double,
    routes: List<String>,
  ) = SubwayStation(id, name, complexId, complexName, borough, lat, lng, routes, routes)

  private val stationById = stationRows.associateBy { it.id }

  private fun corridor(
    trunk: String,
    stops: List<String>,
    routes: List<String>,
  ): List<SubwaySegment> =
    stops.zipWithNext().map { (a, b) ->
      val sa = stationById.getValue(a)
      val sb = stationById.getValue(b)
      val miles = NycSubwayNetworkCatalog.haversineMiles(sa.lat, sa.lng, sb.lat, sb.lng) * 1.08
      SubwaySegment(
        segmentId = "$a-$b",
        trunkId = trunk,
        fromStationId = a,
        toStationId = b,
        distanceMiles = (miles * 10_000).roundToInt() / 10_000.0,
        routes = routes,
      )
    }

  private val segmentRows =
    corridor("IRT_LEXINGTON", lexLocal, listOf("4", "6")) +
      corridor("IND_8AV", eighthAvLocal, listOf("A", "C")) +
      corridor("BMT_CANARSIE", canarsie, listOf("L")) +
      corridor("IRT_42ST_SHUTTLE", shuttle42, listOf("GS"))

  private fun route(
    id: String,
    name: String,
    color: String,
    trunk: String,
    division: String,
    express: Boolean,
    stopsAt: Map<ServicePeriod, List<String>>,
  ) =
    SubwayRouteInfo(
      routeId = id,
      name = name,
      hexColor = color,
      trunkGroup = trunk,
      division = division,
      isExpressCapable = express,
      totalRouteMiles = segmentRows.filter { id in it.routes }.sumOf { it.distanceMiles },
      stopsAt = stopsAt,
    )

  val data: SubwayNetworkData =
    SubwayNetworkData(
      sourceDate = "2026-11-10",
      routes =
        listOf(
          route(
            "A",
            "8 Av Express",
            "#0062CF",
            "IND_8AV",
            "IND",
            true,
            mapOf(
              ServicePeriod.DAYTIME to eighthAvExpressDaytime,
              ServicePeriod.LATE_NIGHT to eighthAvLocal,
              ServicePeriod.WEEKEND to eighthAvExpressDaytime,
            ),
          ),
          route(
            "C",
            "8 Av Local",
            "#0062CF",
            "IND_8AV",
            "IND",
            false,
            mapOf(
              ServicePeriod.DAYTIME to eighthAvLocal,
              ServicePeriod.WEEKEND to eighthAvLocal,
            ),
          ),
          route(
            "L",
            "14 St-Canarsie Local",
            "#7C858C",
            "BMT_CANARSIE",
            "BMT",
            false,
            mapOf(
              ServicePeriod.DAYTIME to canarsie,
              ServicePeriod.LATE_NIGHT to canarsie,
              ServicePeriod.WEEKEND to canarsie,
            ),
          ),
          route(
            "GS",
            "42 St Shuttle",
            "#7C858C",
            "IRT_42ST_SHUTTLE",
            "IRT",
            false,
            mapOf(
              ServicePeriod.DAYTIME to shuttle42,
              ServicePeriod.WEEKEND to shuttle42,
            ),
          ),
          route(
            "4",
            "Lexington Av Express",
            "#009952",
            "IRT_LEXINGTON",
            "IRT",
            true,
            mapOf(
              ServicePeriod.DAYTIME to lexExpressDaytime,
              ServicePeriod.LATE_NIGHT to lexLocal,
              ServicePeriod.WEEKEND to lexExpressDaytime,
            ),
          ),
          route(
            "6",
            "Lexington Av Local",
            "#009952",
            "IRT_LEXINGTON",
            "IRT",
            false,
            mapOf(
              ServicePeriod.DAYTIME to lexLocal,
              ServicePeriod.LATE_NIGHT to lexLocal,
              ServicePeriod.WEEKEND to lexLocal,
            ),
          ),
        ),
      stations = stationRows,
      segments = segmentRows,
    )

  fun engine(): SubwayGraphEngine = SubwayGraphEngine(data)
}
