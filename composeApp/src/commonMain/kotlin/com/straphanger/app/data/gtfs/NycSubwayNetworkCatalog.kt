package com.straphanger.app.data.gtfs

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object NycSubwayNetworkCatalog {

    val defaultNetwork: SubwayNetworkData by lazy { buildNetwork() }

    private fun buildNetwork(): SubwayNetworkData {
        val routes = listOf(
            // IRT Broadway-Seventh Ave (Red #EE352E)
            SubwayRouteInfo("1", "Broadway-7 Ave Local", "#EE352E", "#FFFFFF", "IRT_7AVE", "IRT", false, LateNightBehavior.LOCAL_ALL_NIGHT, 19.1),
            SubwayRouteInfo("2", "7 Ave Express", "#EE352E", "#FFFFFF", "IRT_7AVE", "IRT", true, LateNightBehavior.EXPRESS_TO_LOCAL_LATE_NIGHT, 28.6),
            SubwayRouteInfo("3", "7 Ave Express", "#EE352E", "#FFFFFF", "IRT_7AVE", "IRT", true, LateNightBehavior.SHUTTLE_LATE_NIGHT, 18.8),

            // IRT Lexington Ave (Green #00933C)
            SubwayRouteInfo("4", "Lexington Ave Express", "#00933C", "#FFFFFF", "IRT_LEX", "IRT", true, LateNightBehavior.EXPRESS_TO_LOCAL_LATE_NIGHT, 21.6),
            SubwayRouteInfo("5", "Lexington Ave Express", "#00933C", "#FFFFFF", "IRT_LEX", "IRT", true, LateNightBehavior.SHUTTLE_LATE_NIGHT, 23.1),
            SubwayRouteInfo("6", "Lexington Ave Local", "#00933C", "#FFFFFF", "IRT_LEX", "IRT", false, LateNightBehavior.LOCAL_ALL_NIGHT, 15.8),

            // IRT Flushing (Purple #B933AD)
            SubwayRouteInfo("7", "Flushing Local/Express", "#B933AD", "#FFFFFF", "IRT_FLUSHING", "IRT", true, LateNightBehavior.LOCAL_ALL_NIGHT, 9.7),

            // IND Eighth Ave (Blue #0039A6)
            SubwayRouteInfo("A", "8 Ave Express", "#0039A6", "#FFFFFF", "IND_8AVE", "IND", true, LateNightBehavior.EXPRESS_TO_LOCAL_LATE_NIGHT, 32.4),
            SubwayRouteInfo("C", "8 Ave Local", "#0039A6", "#FFFFFF", "IND_8AVE", "IND", false, LateNightBehavior.NO_LATE_NIGHT_SERVICE, 19.0),
            SubwayRouteInfo("E", "8 Ave Local / Queens Blvd Express", "#0039A6", "#FFFFFF", "IND_8AVE", "IND", true, LateNightBehavior.EXPRESS_TO_LOCAL_LATE_NIGHT, 16.2),

            // IND Sixth Ave (Orange #FF6319)
            SubwayRouteInfo("B", "6 Ave Express", "#FF6319", "#FFFFFF", "IND_6AVE", "IND", true, LateNightBehavior.NO_LATE_NIGHT_SERVICE, 20.1),
            SubwayRouteInfo("D", "6 Ave Express", "#FF6319", "#FFFFFF", "IND_6AVE", "IND", true, LateNightBehavior.EXPRESS_TO_LOCAL_LATE_NIGHT, 25.4),
            SubwayRouteInfo("F", "Queens Blvd Express / 6 Ave Local", "#FF6319", "#FFFFFF", "IND_6AVE", "IND", true, LateNightBehavior.LOCAL_ALL_NIGHT, 27.0),
            SubwayRouteInfo("M", "Queens Blvd / 6 Ave Local", "#FF6319", "#FFFFFF", "IND_6AVE", "IND", false, LateNightBehavior.SHUTTLE_LATE_NIGHT, 17.5),

            // IND Crosstown (Light Green #6CBE45)
            SubwayRouteInfo("G", "Brooklyn-Queens Crosstown", "#6CBE45", "#FFFFFF", "IND_CROSSTOWN", "IND", false, LateNightBehavior.LOCAL_ALL_NIGHT, 11.4),

            // BMT Nassau St (Brown #996633)
            SubwayRouteInfo("J", "Nassau St Local/Express", "#996633", "#FFFFFF", "BMT_NASSAU", "BMT", true, LateNightBehavior.LOCAL_ALL_NIGHT, 14.5),
            SubwayRouteInfo("Z", "Nassau St Rush Hour Skip-Stop", "#996633", "#FFFFFF", "BMT_NASSAU", "BMT", true, LateNightBehavior.NO_LATE_NIGHT_SERVICE, 14.5),

            // BMT Canarsie (Gray #A7A9AC)
            SubwayRouteInfo("L", "14 St-Canarsie Local", "#A7A9AC", "#FFFFFF", "BMT_CANARSIE", "BMT", false, LateNightBehavior.LOCAL_ALL_NIGHT, 10.5),

            // BMT Broadway (Yellow #FCCC0A - Black text per MTA standard)
            SubwayRouteInfo("N", "Broadway Express", "#FCCC0A", "#000000", "BMT_BROADWAY", "BMT", true, LateNightBehavior.EXPRESS_TO_LOCAL_LATE_NIGHT, 20.8),
            SubwayRouteInfo("Q", "2 Ave / Broadway Express", "#FCCC0A", "#000000", "BMT_BROADWAY", "BMT", true, LateNightBehavior.EXPRESS_TO_LOCAL_LATE_NIGHT, 19.6),
            SubwayRouteInfo("R", "Broadway / Queens Blvd Local", "#FCCC0A", "#000000", "BMT_BROADWAY", "BMT", false, LateNightBehavior.SHUTTLE_LATE_NIGHT, 22.8),
            SubwayRouteInfo("W", "Broadway Local", "#FCCC0A", "#000000", "BMT_BROADWAY", "BMT", false, LateNightBehavior.NO_LATE_NIGHT_SERVICE, 13.0),

            // Shuttles (Dark Slate #808183)
            SubwayRouteInfo("S", "42 St Shuttle", "#808183", "#FFFFFF", "IRT_SHUTTLE", "IRT", false, LateNightBehavior.NO_LATE_NIGHT_SERVICE, 0.5),
            SubwayRouteInfo("SF", "Franklin Ave Shuttle", "#808183", "#FFFFFF", "BMT_SHUTTLE", "BMT", false, LateNightBehavior.LOCAL_ALL_NIGHT, 1.4),
            SubwayRouteInfo("SR", "Rockaway Park Shuttle", "#808183", "#FFFFFF", "IND_SHUTTLE", "IND", false, LateNightBehavior.LOCAL_ALL_NIGHT, 3.1),

            // Staten Island Railway (#0039A6)
            SubwayRouteInfo("SIR", "Staten Island Railway", "#0039A6", "#FFFFFF", "SIR_MAIN", "SIR", true, LateNightBehavior.LOCAL_ALL_NIGHT, 14.0)
        )

        val stationList = mutableListOf<SubwayStation>()
        val segmentList = mutableListOf<SubwaySegment>()

        fun addStation(
            id: String,
            name: String,
            complexId: String,
            complexName: String,
            borough: Borough,
            lat: Double,
            lng: Double,
            daytimeRoutes: List<String>,
            lateNightRoutes: List<String> = daytimeRoutes,
            expressRoutes: List<String> = emptyList(),
            isTransferHub: Boolean = false
        ): SubwayStation {
            val st = SubwayStation(
                id = id,
                name = name,
                complexId = complexId,
                complexName = complexName,
                borough = borough,
                lat = lat,
                lng = lng,
                daytimeRoutes = daytimeRoutes,
                lateNightRoutes = lateNightRoutes,
                expressRoutes = expressRoutes,
                isTransferHub = isTransferHub
            )
            stationList.add(st)
            return st
        }

        fun connectCorridor(
            trunkId: String,
            stations: List<SubwayStation>,
            routes: List<String>,
            distanceFactor: Double = 1.08
        ) {
            for (i in 0 until stations.size - 1) {
                val a = stations[i]
                val b = stations[i + 1]
                val segId = "${trunkId}_${a.id}_${b.id}"
                val rawDist = haversineMiles(a.lat, a.lng, b.lat, b.lng) * distanceFactor
                val dist = ((rawDist * 100.0).toInt() / 100.0).coerceAtLeast(0.18)
                val midLat = (a.lat + b.lat) / 2.0
                val midLng = (a.lng + b.lng) / 2.0
                segmentList.add(
                    SubwaySegment(
                        segmentId = segId,
                        trunkId = trunkId,
                        fromStationId = a.id,
                        toStationId = b.id,
                        distanceMiles = dist,
                        routes = routes,
                        polyline = listOf(
                            GeoPoint(a.lat, a.lng),
                            GeoPoint(midLat, midLng),
                            GeoPoint(b.lat, b.lng)
                        )
                    )
                )
            }
        }

        // =========================================================================
        // 1. IRT LEXINGTON AVE LINE (4, 5, 6) - Bronx -> Manhattan -> Brooklyn
        // =========================================================================
        val lexStations = listOf(
            addStation("LEX_WOODLAWN", "Woodlawn", "CPLX_LEX_WOODLAWN", "Woodlawn", Borough.BRONX, 40.8860, -73.8788, listOf("4"), listOf("4"), listOf("4")),
            addStation("LEX_FORDHAM", "Fordham Rd", "CPLX_LEX_FORDHAM", "Fordham Rd", Borough.BRONX, 40.8628, -73.8901, listOf("4"), listOf("4"), listOf("4")),
            addStation("LEX_161", "161 St-Yankee Stadium", "CPLX_YANKEE_161", "161 St-Yankee Stadium", Borough.BRONX, 40.8279, -73.9257, listOf("4"), listOf("4"), listOf("4"), true),
            addStation("LEX_149", "149 St-Grand Concourse", "CPLX_149_GC", "149 St-Grand Concourse", Borough.BRONX, 40.8184, -73.9274, listOf("4", "5"), listOf("4"), listOf("4", "5"), true),
            addStation("LEX_138", "138 St-Grand Concourse", "CPLX_LEX_138", "138 St-Grand Concourse", Borough.BRONX, 40.8132, -73.9298, listOf("4", "5"), listOf("4"), listOf("4", "5")),
            addStation("LEX_125", "125 St", "CPLX_LEX_125", "125 St (Lexington)", Borough.MANHATTAN, 40.8041, -73.9376, listOf("4", "5", "6"), listOf("4", "6"), listOf("4", "5"), true),
            addStation("LEX_116", "116 St", "CPLX_LEX_116", "116 St", Borough.MANHATTAN, 40.7986, -73.9416, listOf("6"), listOf("4", "6"), emptyList()),
            addStation("LEX_110", "110 St", "CPLX_LEX_110", "110 St", Borough.MANHATTAN, 40.7950, -73.9442, listOf("6"), listOf("4", "6"), emptyList()),
            addStation("LEX_103", "103 St", "CPLX_LEX_103", "103 St", Borough.MANHATTAN, 40.7906, -73.9475, listOf("6"), listOf("4", "6"), emptyList()),
            addStation("LEX_96", "96 St", "CPLX_LEX_96", "96 St", Borough.MANHATTAN, 40.7856, -73.9510, listOf("6"), listOf("4", "6"), emptyList()),
            addStation("LEX_86", "86 St", "CPLX_LEX_86", "86 St (Lexington)", Borough.MANHATTAN, 40.7795, -73.9556, listOf("4", "5", "6"), listOf("4", "6"), listOf("4", "5"), true),
            addStation("LEX_77", "77 St", "CPLX_LEX_77", "77 St", Borough.MANHATTAN, 40.7736, -73.9599, listOf("6"), listOf("4", "6"), emptyList()),
            addStation("LEX_68", "68 St-Hunter College", "CPLX_LEX_68", "68 St-Hunter College", Borough.MANHATTAN, 40.7681, -73.9639, listOf("6"), listOf("4", "6"), emptyList()),
            addStation("LEX_59", "59 St", "CPLX_LEX_59", "Lexington Ave/59 St", Borough.MANHATTAN, 40.7625, -73.9679, listOf("4", "5", "6"), listOf("4", "6"), listOf("4", "5"), true),
            addStation("LEX_51", "51 St", "CPLX_LEX_51", "Lexington Ave/51-53 St", Borough.MANHATTAN, 40.7571, -73.9719, listOf("6"), listOf("4", "6"), emptyList(), true),
            addStation("LEX_42_GCT", "Grand Central-42 St", "CPLX_GRAND_CENTRAL", "Grand Central-42 St", Borough.MANHATTAN, 40.7518, -73.9768, listOf("4", "5", "6"), listOf("4", "6"), listOf("4", "5"), true),
            addStation("LEX_33", "33 St", "CPLX_LEX_33", "33 St", Borough.MANHATTAN, 40.7461, -73.9821, listOf("6"), listOf("4", "6"), emptyList()),
            addStation("LEX_28", "28 St", "CPLX_LEX_28", "28 St", Borough.MANHATTAN, 40.7431, -73.9843, listOf("6"), listOf("4", "6"), emptyList()),
            addStation("LEX_23", "23 St", "CPLX_LEX_23", "23 St", Borough.MANHATTAN, 40.7399, -73.9866, listOf("6"), listOf("4", "6"), emptyList()),
            addStation("LEX_14_USQ", "14 St-Union Sq", "CPLX_UNION_SQ", "14 St-Union Sq", Borough.MANHATTAN, 40.7347, -73.9899, listOf("4", "5", "6"), listOf("4", "6"), listOf("4", "5"), true),
            addStation("LEX_ASTOR", "Astor Pl", "CPLX_LEX_ASTOR", "Astor Pl", Borough.MANHATTAN, 40.7300, -73.9911, listOf("6"), listOf("4", "6"), emptyList()),
            addStation("LEX_BLEECKER", "Bleecker St", "CPLX_BWAY_LAF", "Bleecker St / Broadway-Lafayette", Borough.MANHATTAN, 40.7259, -73.9946, listOf("6"), listOf("4", "6"), emptyList(), true),
            addStation("LEX_SPRING", "Spring St", "CPLX_LEX_SPRING", "Spring St", Borough.MANHATTAN, 40.7223, -73.9971, listOf("6"), listOf("4", "6"), emptyList()),
            addStation("LEX_CANAL", "Canal St", "CPLX_CANAL_MAIN", "Canal St", Borough.MANHATTAN, 40.7188, -74.0001, listOf("6"), listOf("4", "6"), emptyList(), true),
            addStation("LEX_BK_BRIDGE", "Brooklyn Bridge-City Hall", "CPLX_CHAMBERS_BK_BR", "Brooklyn Bridge-City Hall / Chambers", Borough.MANHATTAN, 40.7131, -74.0041, listOf("4", "5", "6"), listOf("4", "6"), listOf("4", "5"), true),
            addStation("LEX_FULTON", "Fulton St", "CPLX_FULTON", "Fulton St", Borough.MANHATTAN, 40.7104, -74.0095, listOf("4", "5"), listOf("4"), listOf("4", "5"), true),
            addStation("LEX_WALL", "Wall St", "CPLX_LEX_WALL", "Wall St", Borough.MANHATTAN, 40.7075, -74.0119, listOf("4", "5"), listOf("4"), listOf("4", "5")),
            addStation("LEX_BOWLING", "Bowling Green", "CPLX_LEX_BOWLING", "Bowling Green", Borough.MANHATTAN, 40.7048, -74.0141, listOf("4", "5"), listOf("4"), listOf("4", "5")),
            addStation("LEX_BORO_HALL", "Borough Hall", "CPLX_COURT_BORO", "Borough Hall / Court St", Borough.BROOKLYN, 40.6924, -73.9901, listOf("4", "5"), listOf("4"), listOf("4", "5"), true),
            addStation("LEX_NEVINS", "Nevins St", "CPLX_LEX_NEVINS", "Nevins St", Borough.BROOKLYN, 40.6882, -73.9805, listOf("4", "5"), listOf("4"), listOf("4", "5")),
            addStation("LEX_ATLANTIC", "Atlantic Ave-Barclays Ctr", "CPLX_ATLANTIC", "Atlantic Ave-Barclays Ctr", Borough.BROOKLYN, 40.6844, -73.9776, listOf("4", "5"), listOf("4"), listOf("4", "5"), true),
            addStation("LEX_FRANKLIN", "Franklin Ave-Medgar Evers", "CPLX_FRANKLIN_BOTANIC", "Franklin Ave / Botanic Garden", Borough.BROOKLYN, 40.6707, -73.9581, listOf("4", "5"), listOf("4"), listOf("4", "5"), true),
            addStation("LEX_UTICA", "Crown Hts-Utica Ave", "CPLX_LEX_UTICA", "Crown Hts-Utica Ave", Borough.BROOKLYN, 40.6689, -73.9329, listOf("4"), listOf("4"), listOf("4"))
        )
        connectCorridor("IRT_LEX", lexStations, listOf("4", "5", "6"))

        // =========================================================================
        // 2. IND EIGHTH AVE & FULTON ST LINE (A, C, E) - Manhattan -> Brooklyn -> Queens
        // =========================================================================
        val ind8Stations = listOf(
            addStation("IND8_207", "Inwood-207 St", "CPLX_IND8_207", "Inwood-207 St", Borough.MANHATTAN, 40.8681, -73.9199, listOf("A"), listOf("A"), listOf("A")),
            addStation("IND8_168", "168 St", "CPLX_168_WASH_HTS", "168 St", Borough.MANHATTAN, 40.8407, -73.9396, listOf("A", "C"), listOf("A"), listOf("A"), true),
            addStation("IND8_145", "145 St", "CPLX_IND8_145", "145 St", Borough.MANHATTAN, 40.8248, -73.9442, listOf("A", "C"), listOf("A"), listOf("A"), true),
            addStation("IND8_125", "125 St", "CPLX_IND8_125", "125 St (8 Ave)", Borough.MANHATTAN, 40.8111, -73.9523, listOf("A", "C"), listOf("A"), listOf("A"), true),
            addStation("IND8_116", "116 St", "CPLX_IND8_116", "116 St (8 Ave)", Borough.MANHATTAN, 40.8051, -73.9549, listOf("C"), listOf("A"), emptyList()),
            addStation("IND8_110", "Cathedral Pkwy-110 St", "CPLX_IND8_110", "Cathedral Pkwy-110 St", Borough.MANHATTAN, 40.8006, -73.9582, listOf("C"), listOf("A"), emptyList()),
            addStation("IND8_96", "96 St", "CPLX_IND8_96", "96 St (8 Ave)", Borough.MANHATTAN, 40.7917, -73.9647, listOf("C"), listOf("A"), emptyList()),
            addStation("IND8_86", "86 St", "CPLX_IND8_86", "86 St (8 Ave)", Borough.MANHATTAN, 40.7859, -73.9689, listOf("C"), listOf("A"), emptyList()),
            addStation("IND8_81", "81 St-Museum of Natural History", "CPLX_IND8_81", "81 St-Museum of Natural History", Borough.MANHATTAN, 40.7814, -73.9721, listOf("C"), listOf("A"), emptyList()),
            addStation("IND8_72", "72 St", "CPLX_IND8_72", "72 St (8 Ave)", Borough.MANHATTAN, 40.7756, -73.9764, listOf("C"), listOf("A"), emptyList()),
            addStation("IND8_59_COL", "59 St-Columbus Circle", "CPLX_COLUMBUS", "59 St-Columbus Circle", Borough.MANHATTAN, 40.7682, -73.9819, listOf("A", "C"), listOf("A"), listOf("A"), true),
            addStation("IND8_50", "50 St", "CPLX_IND8_50", "50 St (8 Ave)", Borough.MANHATTAN, 40.7625, -73.9860, listOf("C", "E"), listOf("A", "E"), emptyList()),
            addStation("IND8_42_PABT", "42 St-Port Authority Bus Terminal", "CPLX_TIMES_SQ", "Times Sq-42 St / Port Authority", Borough.MANHATTAN, 40.7573, -73.9897, listOf("A", "C", "E"), listOf("A", "E"), listOf("A"), true),
            addStation("IND8_34_PENN", "34 St-Penn Station", "CPLX_IND8_34", "34 St-Penn Station (8 Ave)", Borough.MANHATTAN, 40.7523, -73.9934, listOf("A", "C", "E"), listOf("A", "E"), listOf("A"), true),
            addStation("IND8_23", "23 St", "CPLX_IND8_23", "23 St (8 Ave)", Borough.MANHATTAN, 40.7459, -73.9980, listOf("C", "E"), listOf("A", "E"), emptyList()),
            addStation("IND8_14", "14 St", "CPLX_14_8AVE", "14 St / 8 Ave", Borough.MANHATTAN, 40.7409, -74.0017, listOf("A", "C", "E"), listOf("A", "E"), listOf("A"), true),
            addStation("IND8_W4", "W 4 St-Wash Sq", "CPLX_W4", "W 4 St-Wash Sq", Borough.MANHATTAN, 40.7323, -74.0005, listOf("A", "C", "E"), listOf("A", "E"), listOf("A"), true),
            addStation("IND8_SPRING", "Spring St", "CPLX_IND8_SPRING", "Spring St (8 Ave)", Borough.MANHATTAN, 40.7262, -74.0037, listOf("C", "E"), listOf("A", "E"), emptyList()),
            addStation("IND8_CANAL", "Canal St", "CPLX_IND8_CANAL", "Canal St (8 Ave)", Borough.MANHATTAN, 40.7208, -74.0052, listOf("A", "C", "E"), listOf("A", "E"), listOf("A"), true),
            addStation("IND8_CHAMBERS", "Chambers St / World Trade Center", "CPLX_WTC_CHAMBERS", "Chambers St / WTC / Park Pl", Borough.MANHATTAN, 40.7132, -74.0093, listOf("A", "C", "E"), listOf("A", "E"), listOf("A"), true),
            addStation("IND8_FULTON", "Fulton St", "CPLX_FULTON", "Fulton St", Borough.MANHATTAN, 40.7102, -74.0077, listOf("A", "C"), listOf("A"), listOf("A"), true),
            addStation("IND8_HIGH", "High St", "CPLX_IND8_HIGH", "High St", Borough.BROOKLYN, 40.6993, -73.9905, listOf("A", "C"), listOf("A"), listOf("A")),
            addStation("IND8_JAY", "Jay St-MetroTech", "CPLX_JAY_ST", "Jay St-MetroTech", Borough.BROOKLYN, 40.6923, -73.9873, listOf("A", "C"), listOf("A"), listOf("A"), true),
            addStation("IND8_HOYT", "Hoyt-Schermerhorn Sts", "CPLX_HOYT_SCHERM", "Hoyt-Schermerhorn Sts", Borough.BROOKLYN, 40.6885, -73.9850, listOf("A", "C"), listOf("A"), listOf("A"), true),
            addStation("IND8_NOSTRAND", "Nostrand Ave", "CPLX_IND8_NOSTRAND", "Nostrand Ave", Borough.BROOKLYN, 40.6804, -73.9504, listOf("A", "C"), listOf("A"), listOf("A")),
            addStation("IND8_UTICA", "Utica Ave", "CPLX_IND8_UTICA", "Utica Ave", Borough.BROOKLYN, 40.6794, -73.9307, listOf("A", "C"), listOf("A"), listOf("A")),
            addStation("IND8_BWAY_JCT", "Broadway Junction", "CPLX_BWAY_JCT", "Broadway Junction", Borough.BROOKLYN, 40.6783, -73.9053, listOf("A", "C"), listOf("A"), listOf("A"), true),
            addStation("IND8_EUCLID", "Euclid Ave", "CPLX_IND8_EUCLID", "Euclid Ave", Borough.BROOKLYN, 40.6754, -73.8721, listOf("A", "C"), listOf("A"), listOf("A")),
            addStation("IND8_HOWARD_JFK", "Howard Beach-JFK Airport", "CPLX_IND8_JFK", "Howard Beach-JFK Airport", Borough.QUEENS, 40.6605, -73.8303, listOf("A"), listOf("A"), listOf("A")),
            addStation("IND8_BROAD_CH", "Broad Channel", "CPLX_BROAD_CHANNEL", "Broad Channel", Borough.QUEENS, 40.6084, -73.8159, listOf("A", "SR"), listOf("A", "SR"), listOf("A"), true),
            addStation("IND8_FAR_ROCK", "Far Rockaway-Mott Ave", "CPLX_IND8_FAR_ROCK", "Far Rockaway-Mott Ave", Borough.QUEENS, 40.6039, -73.7554, listOf("A"), listOf("A"), listOf("A"))
        )
        connectCorridor("IND_8AVE", ind8Stations, listOf("A", "C", "E"))

        // =========================================================================
        // 3. IRT BROADWAY-SEVENTH AVE LINE (1, 2, 3) - Bronx -> Manhattan -> Brooklyn
        // =========================================================================
        val irt7Stations = listOf(
            addStation("IRT7_242", "Van Cortlandt Park-242 St", "CPLX_IRT7_242", "Van Cortlandt Park-242 St", Borough.BRONX, 40.8892, -73.8986, listOf("1"), listOf("1")),
            addStation("IRT7_168", "168 St-Washington Hts", "CPLX_168_WASH_HTS", "168 St", Borough.MANHATTAN, 40.8405, -73.9401, listOf("1"), listOf("1"), emptyList(), true),
            addStation("IRT7_96", "96 St", "CPLX_IRT7_96", "96 St (Broadway)", Borough.MANHATTAN, 40.7939, -73.9723, listOf("1", "2", "3"), listOf("1", "2", "3"), listOf("2", "3"), true),
            addStation("IRT7_86", "86 St", "CPLX_IRT7_86", "86 St (Broadway)", Borough.MANHATTAN, 40.7886, -73.9762, listOf("1"), listOf("1", "2")),
            addStation("IRT7_79", "79 St", "CPLX_IRT7_79", "79 St", Borough.MANHATTAN, 40.7839, -73.9799, listOf("1"), listOf("1", "2")),
            addStation("IRT7_72", "72 St", "CPLX_IRT7_72", "72 St (Broadway)", Borough.MANHATTAN, 40.7785, -73.9819, listOf("1", "2", "3"), listOf("1", "2", "3"), listOf("2", "3"), true),
            addStation("IRT7_66", "66 St-Lincoln Center", "CPLX_IRT7_66", "66 St-Lincoln Center", Borough.MANHATTAN, 40.7734, -73.9822, listOf("1"), listOf("1", "2")),
            addStation("IRT7_59_COL", "59 St-Columbus Circle", "CPLX_COLUMBUS", "59 St-Columbus Circle", Borough.MANHATTAN, 40.7682, -73.9817, listOf("1"), listOf("1", "2"), emptyList(), true),
            addStation("IRT7_50", "50 St", "CPLX_IRT7_50", "50 St (Broadway)", Borough.MANHATTAN, 40.7617, -73.9838, listOf("1"), listOf("1", "2")),
            addStation("IRT7_42_TSQ", "Times Sq-42 St", "CPLX_TIMES_SQ", "Times Sq-42 St / Port Authority", Borough.MANHATTAN, 40.7553, -73.9875, listOf("1", "2", "3"), listOf("1", "2", "3"), listOf("2", "3"), true),
            addStation("IRT7_34_PENN", "34 St-Penn Station", "CPLX_IRT7_34", "34 St-Penn Station (7 Ave)", Borough.MANHATTAN, 40.7504, -73.9910, listOf("1", "2", "3"), listOf("1", "2", "3"), listOf("2", "3"), true),
            addStation("IRT7_28", "28 St", "CPLX_IRT7_28", "28 St (7 Ave)", Borough.MANHATTAN, 40.7472, -73.9934, listOf("1"), listOf("1", "2")),
            addStation("IRT7_23", "23 St", "CPLX_IRT7_23", "23 St (7 Ave)", Borough.MANHATTAN, 40.7441, -73.9957, listOf("1"), listOf("1", "2")),
            addStation("IRT7_18", "18 St", "CPLX_IRT7_18", "18 St", Borough.MANHATTAN, 40.7410, -73.9979, listOf("1"), listOf("1", "2")),
            addStation("IRT7_14", "14 St", "CPLX_14_6_7AVE", "14 St (6/7 Ave)", Borough.MANHATTAN, 40.7378, -74.0002, listOf("1", "2", "3"), listOf("1", "2", "3"), listOf("2", "3"), true),
            addStation("IRT7_CHRISTOPHER", "Christopher St-Stonewall", "CPLX_IRT7_CHRIS", "Christopher St-Stonewall", Borough.MANHATTAN, 40.7334, -74.0029, listOf("1"), listOf("1", "2")),
            addStation("IRT7_HOUSTON", "Houston St", "CPLX_IRT7_HOUSTON", "Houston St", Borough.MANHATTAN, 40.7283, -74.0054, listOf("1"), listOf("1", "2")),
            addStation("IRT7_CANAL", "Canal St", "CPLX_IRT7_CANAL", "Canal St (7 Ave)", Borough.MANHATTAN, 40.7229, -74.0063, listOf("1"), listOf("1", "2")),
            addStation("IRT7_CHAMBERS", "Chambers St", "CPLX_IRT7_CHAMBERS", "Chambers St (7 Ave)", Borough.MANHATTAN, 40.7155, -74.0093, listOf("1", "2", "3"), listOf("1", "2"), listOf("2", "3"), true),
            addStation("IRT7_PARK_PL", "Park Place", "CPLX_WTC_CHAMBERS", "Chambers St / WTC / Park Pl", Borough.MANHATTAN, 40.7131, -74.0088, listOf("2", "3"), listOf("2"), listOf("2", "3"), true),
            addStation("IRT7_FULTON", "Fulton St", "CPLX_FULTON", "Fulton St", Borough.MANHATTAN, 40.7094, -74.0066, listOf("2", "3"), listOf("2"), listOf("2", "3"), true),
            addStation("IRT7_CLARK", "Clark St", "CPLX_IRT7_CLARK", "Clark St", Borough.BROOKLYN, 40.6975, -73.9931, listOf("2", "3"), listOf("2"), listOf("2", "3")),
            addStation("IRT7_BORO_HALL", "Borough Hall", "CPLX_COURT_BORO", "Borough Hall / Court St", Borough.BROOKLYN, 40.6932, -73.9900, listOf("2", "3"), listOf("2"), listOf("2", "3"), true),
            addStation("IRT7_ATLANTIC", "Atlantic Ave-Barclays Ctr", "CPLX_ATLANTIC", "Atlantic Ave-Barclays Ctr", Borough.BROOKLYN, 40.6845, -73.9769, listOf("2", "3"), listOf("2"), listOf("2", "3"), true),
            addStation("IRT7_FLATBUSH", "Flatbush Ave-Brooklyn College", "CPLX_IRT7_FLATBUSH", "Flatbush Ave-Brooklyn College", Borough.BROOKLYN, 40.6328, -73.9476, listOf("2", "5"), listOf("2"), listOf("2", "5"))
        )
        connectCorridor("IRT_7AVE", irt7Stations, listOf("1", "2", "3"))

        // =========================================================================
        // 4. BMT CANARSIE LINE (L) - Manhattan -> Brooklyn
        // =========================================================================
        val canarsieStations = listOf(
            addStation("L_8AVE", "8 Ave", "CPLX_14_8AVE", "14 St / 8 Ave", Borough.MANHATTAN, 40.7398, -74.0026, listOf("L"), listOf("L"), emptyList(), true),
            addStation("L_6AVE", "6 Ave", "CPLX_14_6_7AVE", "14 St (6/7 Ave)", Borough.MANHATTAN, 40.7373, -73.9968, listOf("L"), listOf("L"), emptyList(), true),
            addStation("L_14_USQ", "14 St-Union Sq", "CPLX_UNION_SQ", "14 St-Union Sq", Borough.MANHATTAN, 40.7341, -73.9907, listOf("L"), listOf("L"), emptyList(), true),
            addStation("L_3AVE", "3 Ave", "CPLX_L_3AVE", "3 Ave", Borough.MANHATTAN, 40.7328, -73.9861, listOf("L"), listOf("L")),
            addStation("L_1AVE", "1 Ave", "CPLX_L_1AVE", "1 Ave", Borough.MANHATTAN, 40.7310, -73.9816, listOf("L"), listOf("L")),
            addStation("L_BEDFORD", "Bedford Ave", "CPLX_L_BEDFORD", "Bedford Ave", Borough.BROOKLYN, 40.7173, -73.9569, listOf("L"), listOf("L")),
            addStation("L_LORIMER", "Lorimer St", "CPLX_LORIMER_METRO", "Lorimer St / Metropolitan Ave", Borough.BROOKLYN, 40.7141, -73.9503, listOf("L"), listOf("L"), emptyList(), true),
            addStation("L_GRAHAM", "Graham Ave", "CPLX_L_GRAHAM", "Graham Ave", Borough.BROOKLYN, 40.7146, -73.9441, listOf("L"), listOf("L")),
            addStation("L_MYRTLE_WYCKOFF", "Myrtle-Wyckoff Aves", "CPLX_MYRTLE_WYCKOFF", "Myrtle-Wyckoff Aves", Borough.BROOKLYN, 40.6998, -73.9124, listOf("L", "M"), listOf("L", "M"), emptyList(), true),
            addStation("L_BWAY_JCT", "Broadway Junction", "CPLX_BWAY_JCT", "Broadway Junction", Borough.BROOKLYN, 40.6788, -73.9032, listOf("L"), listOf("L"), emptyList(), true),
            addStation("L_CANARSIE", "Canarsie-Rockaway Pkwy", "CPLX_L_CANARSIE", "Canarsie-Rockaway Pkwy", Borough.BROOKLYN, 40.6467, -73.9018, listOf("L"), listOf("L"))
        )
        connectCorridor("BMT_CANARSIE", canarsieStations, listOf("L"))

        // =========================================================================
        // 5. IRT FLUSHING LINE (7) - Manhattan -> Queens
        // =========================================================================
        val flushingStations = listOf(
            addStation("FL_34_HUDSON", "34 St-Hudson Yards", "CPLX_FL_HUDSON", "34 St-Hudson Yards", Borough.MANHATTAN, 40.7559, -74.0019, listOf("7"), listOf("7"), listOf("7")),
            addStation("FL_42_TSQ", "Times Sq-42 St", "CPLX_TIMES_SQ", "Times Sq-42 St / Port Authority", Borough.MANHATTAN, 40.7555, -73.9876, listOf("7"), listOf("7"), listOf("7"), true),
            addStation("FL_5AVE", "5 Ave-Bryant Park", "CPLX_BRYANT_PARK", "42 St-Bryant Park / 5 Ave", Borough.MANHATTAN, 40.7538, -73.9820, listOf("7"), listOf("7"), emptyList(), true),
            addStation("FL_42_GCT", "Grand Central-42 St", "CPLX_GRAND_CENTRAL", "Grand Central-42 St", Borough.MANHATTAN, 40.7514, -73.9760, listOf("7"), listOf("7"), listOf("7"), true),
            addStation("FL_VERNON", "Vernon Blvd-Jackson Ave", "CPLX_FL_VERNON", "Vernon Blvd-Jackson Ave", Borough.QUEENS, 40.7426, -73.9536, listOf("7"), listOf("7"), listOf("7")),
            addStation("FL_COURT_SQ", "Court Sq", "CPLX_COURT_SQ", "Court Sq", Borough.QUEENS, 40.7470, -73.9453, listOf("7"), listOf("7"), listOf("7"), true),
            addStation("FL_QBP", "Queensboro Plaza", "CPLX_QBP", "Queensboro Plaza", Borough.QUEENS, 40.7506, -73.9402, listOf("7"), listOf("7"), listOf("7"), true),
            addStation("FL_61_WOODSIDE", "61 St-Woodside", "CPLX_FL_61", "61 St-Woodside", Borough.QUEENS, 40.7456, -73.9030, listOf("7"), listOf("7"), listOf("7")),
            addStation("FL_74_JACKSON", "74 St-Broadway", "CPLX_JACKSON_HTS", "Jackson Hts-Roosevelt Ave / 74 St", Borough.QUEENS, 40.7468, -73.8914, listOf("7"), listOf("7"), emptyList(), true),
            addStation("FL_JUNCTION", "Junction Blvd", "CPLX_FL_JUNCTION", "Junction Blvd", Borough.QUEENS, 40.7491, -73.8695, listOf("7"), listOf("7"), listOf("7")),
            addStation("FL_METS", "Mets-Willets Point", "CPLX_FL_METS", "Mets-Willets Point", Borough.QUEENS, 40.7546, -73.8456, listOf("7"), listOf("7"), listOf("7")),
            addStation("FL_MAIN_ST", "Flushing-Main St", "CPLX_FL_MAIN", "Flushing-Main St", Borough.QUEENS, 40.7596, -73.8300, listOf("7"), listOf("7"), listOf("7"))
        )
        connectCorridor("IRT_FLUSHING", flushingStations, listOf("7"))

        // =========================================================================
        // 6. IND SIXTH AVE & QUEENS BLVD / CULVER (B, D, F, M)
        // =========================================================================
        val ind6Stations = listOf(
            addStation("IND6_161", "161 St-Yankee Stadium", "CPLX_YANKEE_161", "161 St-Yankee Stadium", Borough.BRONX, 40.8279, -73.9255, listOf("B", "D"), listOf("D"), listOf("D"), true),
            addStation("IND6_JHT", "Jackson Hts-Roosevelt Ave", "CPLX_JACKSON_HTS", "Jackson Hts-Roosevelt Ave / 74 St", Borough.QUEENS, 40.7466, -73.8913, listOf("E", "F", "M", "R"), listOf("E", "F"), listOf("E", "F"), true),
            addStation("IND6_COURT_SQ", "Court Sq-23 St", "CPLX_COURT_SQ", "Court Sq", Borough.QUEENS, 40.7478, -73.9460, listOf("E", "M"), listOf("E"), listOf("E"), true),
            addStation("IND6_47_50_ROCK", "47-50 Sts-Rockefeller Ctr", "CPLX_IND6_ROCK", "47-50 Sts-Rockefeller Ctr", Borough.MANHATTAN, 40.7587, -73.9813, listOf("B", "D", "F", "M"), listOf("D", "F"), listOf("B", "D")),
            addStation("IND6_42_BRYANT", "42 St-Bryant Park", "CPLX_BRYANT_PARK", "42 St-Bryant Park / 5 Ave", Borough.MANHATTAN, 40.7542, -73.9846, listOf("B", "D", "F", "M"), listOf("D", "F"), listOf("B", "D"), true),
            addStation("IND6_34_HERALD", "34 St-Herald Sq", "CPLX_HERALD_SQ", "34 St-Herald Sq", Borough.MANHATTAN, 40.7497, -73.9878, listOf("B", "D", "F", "M"), listOf("D", "F"), listOf("B", "D"), true),
            addStation("IND6_23", "23 St", "CPLX_IND6_23", "23 St (6 Ave)", Borough.MANHATTAN, 40.7429, -73.9928, listOf("F", "M"), listOf("F")),
            addStation("IND6_14", "14 St", "CPLX_14_6_7AVE", "14 St (6/7 Ave)", Borough.MANHATTAN, 40.7382, -73.9962, listOf("F", "M"), listOf("F"), emptyList(), true),
            addStation("IND6_W4", "W 4 St-Wash Sq", "CPLX_W4", "W 4 St-Wash Sq", Borough.MANHATTAN, 40.7323, -74.0004, listOf("B", "D", "F", "M"), listOf("D", "F"), listOf("B", "D"), true),
            addStation("IND6_BWAY_LAF", "Broadway-Lafayette St", "CPLX_BWAY_LAF", "Bleecker St / Broadway-Lafayette", Borough.MANHATTAN, 40.7253, -73.9962, listOf("B", "D", "F", "M"), listOf("D", "F"), listOf("B", "D"), true),
            addStation("IND6_DELANCEY", "Delancey St-Essex St", "CPLX_DELANCEY", "Delancey St-Essex St", Borough.MANHATTAN, 40.7186, -73.9874, listOf("F"), listOf("F"), emptyList(), true),
            addStation("IND6_JAY", "Jay St-MetroTech", "CPLX_JAY_ST", "Jay St-MetroTech", Borough.BROOKLYN, 40.6923, -73.9872, listOf("F"), listOf("F"), emptyList(), true),
            addStation("IND6_ATLANTIC", "Atlantic Ave-Barclays Ctr", "CPLX_ATLANTIC", "Atlantic Ave-Barclays Ctr", Borough.BROOKLYN, 40.6837, -73.9788, listOf("B", "D"), listOf("D"), listOf("B", "D"), true),
            addStation("IND6_PROSPECT_PK", "Prospect Park", "CPLX_PROSPECT_PK", "Prospect Park", Borough.BROOKLYN, 40.6616, -73.9622, listOf("B", "Q", "SF"), listOf("Q", "SF"), listOf("B"), true),
            addStation("IND6_CONEY_ISL", "Coney Island-Stillwell Ave", "CPLX_CONEY_ISLAND", "Coney Island-Stillwell Ave", Borough.BROOKLYN, 40.5774, -73.9812, listOf("D", "F", "N", "Q"), listOf("D", "F", "N", "Q"), listOf("D", "F", "N", "Q"), true)
        )
        connectCorridor("IND_6AVE", ind6Stations, listOf("B", "D", "F", "M"))

        // =========================================================================
        // 7. BMT BROADWAY LINE (N, Q, R, W) - Queens -> Manhattan -> Brooklyn
        // =========================================================================
        val bmtBwayStations = listOf(
            addStation("BMT_ASTORIA_DITMARS", "Astoria-Ditmars Blvd", "CPLX_BMT_DITMARS", "Astoria-Ditmars Blvd", Borough.QUEENS, 40.7750, -73.9120, listOf("N", "W"), listOf("N")),
            addStation("BMT_QBP", "Queensboro Plaza", "CPLX_QBP", "Queensboro Plaza", Borough.QUEENS, 40.7505, -73.9403, listOf("N", "W"), listOf("N"), emptyList(), true),
            addStation("BMT_LEX_59", "Lexington Ave/59 St", "CPLX_LEX_59", "Lexington Ave/59 St", Borough.MANHATTAN, 40.7627, -73.9673, listOf("N", "R", "W"), listOf("N"), emptyList(), true),
            addStation("BMT_57_7AVE", "57 St-7 Ave", "CPLX_BMT_57", "57 St-7 Ave", Borough.MANHATTAN, 40.7647, -73.9807, listOf("N", "Q", "R", "W"), listOf("N", "Q"), listOf("N", "Q")),
            addStation("BMT_49", "49 St", "CPLX_BMT_49", "49 St", Borough.MANHATTAN, 40.7599, -73.9841, listOf("N", "R", "W"), listOf("N", "Q")),
            addStation("BMT_42_TSQ", "Times Sq-42 St", "CPLX_TIMES_SQ", "Times Sq-42 St / Port Authority", Borough.MANHATTAN, 40.7547, -73.9868, listOf("N", "Q", "R", "W"), listOf("N", "Q"), listOf("N", "Q"), true),
            addStation("BMT_34_HERALD", "34 St-Herald Sq", "CPLX_HERALD_SQ", "34 St-Herald Sq", Borough.MANHATTAN, 40.7496, -73.9879, listOf("N", "Q", "R", "W"), listOf("N", "Q"), listOf("N", "Q"), true),
            addStation("BMT_28", "28 St", "CPLX_BMT_28", "28 St (Broadway)", Borough.MANHATTAN, 40.7455, -73.9887, listOf("R", "W"), listOf("N", "Q")),
            addStation("BMT_23", "23 St", "CPLX_BMT_23", "23 St (Broadway)", Borough.MANHATTAN, 40.7413, -73.9893, listOf("R", "W"), listOf("N", "Q")),
            addStation("BMT_14_USQ", "14 St-Union Sq", "CPLX_UNION_SQ", "14 St-Union Sq", Borough.MANHATTAN, 40.7357, -73.9906, listOf("N", "Q", "R", "W"), listOf("N", "Q"), listOf("N", "Q"), true),
            addStation("BMT_8_NYU", "8 St-NYU", "CPLX_BMT_8", "8 St-NYU", Borough.MANHATTAN, 40.7303, -73.9926, listOf("R", "W"), listOf("N", "Q")),
            addStation("BMT_PRINCE", "Prince St", "CPLX_BMT_PRINCE", "Prince St", Borough.MANHATTAN, 40.7243, -73.9977, listOf("R", "W"), listOf("N", "Q")),
            addStation("BMT_CANAL", "Canal St", "CPLX_CANAL_MAIN", "Canal St", Borough.MANHATTAN, 40.7195, -74.0018, listOf("N", "Q", "R", "W"), listOf("N", "Q"), listOf("N", "Q"), true),
            addStation("BMT_WHITEHALL", "Whitehall St-South Ferry", "CPLX_SOUTH_FERRY", "South Ferry / Whitehall St", Borough.MANHATTAN, 40.7031, -74.0130, listOf("R", "W"), listOf("N", "R"), emptyList(), true),
            addStation("BMT_JAY", "Jay St-MetroTech", "CPLX_JAY_ST", "Jay St-MetroTech", Borough.BROOKLYN, 40.6922, -73.9859, listOf("R"), listOf("N", "R"), emptyList(), true),
            addStation("BMT_ATLANTIC", "Atlantic Ave-Barclays Ctr", "CPLX_ATLANTIC", "Atlantic Ave-Barclays Ctr", Borough.BROOKLYN, 40.6836, -73.9786, listOf("N", "Q", "R"), listOf("N", "Q", "R"), listOf("N", "Q"), true)
        )
        connectCorridor("BMT_BROADWAY", bmtBwayStations, listOf("N", "Q", "R", "W"))

        // =========================================================================
        // 8. IND CROSSTOWN LINE (G) - Queens -> Brooklyn
        // =========================================================================
        val crosstownStations = listOf(
            addStation("G_COURT_SQ", "Court Sq", "CPLX_COURT_SQ", "Court Sq", Borough.QUEENS, 40.7466, -73.9438, listOf("G"), listOf("G"), emptyList(), true),
            addStation("G_GREENPOINT", "Greenpoint Ave", "CPLX_G_GREENPOINT", "Greenpoint Ave", Borough.BROOKLYN, 40.7314, -73.9544, listOf("G"), listOf("G")),
            addStation("G_METRO", "Metropolitan Ave", "CPLX_LORIMER_METRO", "Lorimer St / Metropolitan Ave", Borough.BROOKLYN, 40.7128, -73.9514, listOf("G"), listOf("G"), emptyList(), true),
            addStation("G_BEDFORD_NOSTRAND", "Bedford-Nostrand Aves", "CPLX_G_BEDFORD", "Bedford-Nostrand Aves", Borough.BROOKLYN, 40.6896, -73.9535, listOf("G"), listOf("G")),
            addStation("G_HOYT", "Hoyt-Schermerhorn Sts", "CPLX_HOYT_SCHERM", "Hoyt-Schermerhorn Sts", Borough.BROOKLYN, 40.6884, -73.9851, listOf("G"), listOf("G"), emptyList(), true),
            addStation("G_CHURCH", "Church Ave", "CPLX_G_CHURCH", "Church Ave", Borough.BROOKLYN, 40.6440, -73.9797, listOf("G", "F"), listOf("G", "F"))
        )
        connectCorridor("IND_CROSSTOWN", crosstownStations, listOf("G"))

        // =========================================================================
        // 9. BMT NASSAU ST / JAMAICA LINE (J, Z) - Manhattan -> Brooklyn -> Queens
        // =========================================================================
        val nassauStations = listOf(
            addStation("JZ_BROAD", "Broad St", "CPLX_JZ_BROAD", "Broad St", Borough.MANHATTAN, 40.7065, -74.0111, listOf("J", "Z"), listOf("J")),
            addStation("JZ_FULTON", "Fulton St", "CPLX_FULTON", "Fulton St", Borough.MANHATTAN, 40.7101, -74.0079, listOf("J", "Z"), listOf("J"), listOf("J", "Z"), true),
            addStation("JZ_CANAL", "Canal St", "CPLX_CANAL_MAIN", "Canal St", Borough.MANHATTAN, 40.7181, -73.9999, listOf("J", "Z"), listOf("J"), emptyList(), true),
            addStation("JZ_DELANCEY", "Delancey St-Essex St", "CPLX_DELANCEY", "Delancey St-Essex St", Borough.MANHATTAN, 40.7183, -73.9874, listOf("J", "M", "Z"), listOf("J", "M"), listOf("J", "Z"), true),
            addStation("JZ_MARCY", "Marcy Ave", "CPLX_JZ_MARCY", "Marcy Ave", Borough.BROOKLYN, 40.7084, -73.9578, listOf("J", "M", "Z"), listOf("J", "M"), listOf("J", "Z")),
            addStation("JZ_BWAY_JCT", "Broadway Junction", "CPLX_BWAY_JCT", "Broadway Junction", Borough.BROOKLYN, 40.6794, -73.9045, listOf("J", "Z"), listOf("J"), listOf("J", "Z"), true),
            addStation("JZ_JAMAICA_CTR", "Jamaica Center-Parsons/Archer", "CPLX_JZ_JAMAICA", "Jamaica Center-Parsons/Archer", Borough.QUEENS, 40.7021, -73.8011, listOf("E", "J", "Z"), listOf("E", "J"), listOf("E", "J", "Z"))
        )
        connectCorridor("BMT_NASSAU", nassauStations, listOf("J", "Z"))

        // =========================================================================
        // 10. SHUTTLES (S, SF, SR) & STATEN ISLAND RAILWAY (SIR)
        // =========================================================================
        val s42Tsq = stationList.first { it.id == "IRT7_42_TSQ" }
        val s42Gct = stationList.first { it.id == "LEX_42_GCT" }
        connectCorridor("IRT_SHUTTLE", listOf(s42Tsq, s42Gct), listOf("S"))

        val sfFranklin = stationList.first { it.id == "LEX_FRANKLIN" }
        val sfProspect = stationList.first { it.id == "IND6_PROSPECT_PK" }
        connectCorridor("BMT_SHUTTLE", listOf(sfFranklin, sfProspect), listOf("SF"))

        val srBroadCh = stationList.first { it.id == "IND8_BROAD_CH" }
        val srRockawayPk = addStation("SR_ROCKAWAY_116", "Rockaway Park-Beach 116 St", "CPLX_SR_116", "Rockaway Park-Beach 116 St", Borough.QUEENS, 40.5809, -73.8355, listOf("SR"), listOf("SR"))
        connectCorridor("IND_SHUTTLE", listOf(srBroadCh, srRockawayPk), listOf("SR"))

        val sirStations = listOf(
            addStation("SIR_ST_GEORGE", "St George", "CPLX_SIR_ST_GEORGE", "St George", Borough.STATEN_ISLAND, 40.6437, -74.0736, listOf("SIR"), listOf("SIR"), listOf("SIR")),
            addStation("SIR_GREAT_KILLS", "Great Kills", "CPLX_SIR_GREAT_KILLS", "Great Kills", Borough.STATEN_ISLAND, 40.5512, -74.1514, listOf("SIR"), listOf("SIR"), listOf("SIR")),
            addStation("SIR_TOTTENVILLE", "Tottenville", "CPLX_SIR_TOTTENVILLE", "Tottenville", Borough.STATEN_ISLAND, 40.5128, -74.2520, listOf("SIR"), listOf("SIR"), listOf("SIR"))
        )
        connectCorridor("SIR_MAIN", sirStations, listOf("SIR"))

        return SubwayNetworkData(
            totalOfficialStations = 472,
            totalOfficialComplexes = 424,
            totalOfficialSystemMiles = 248.0,
            routes = routes,
            stations = stationList,
            segments = segmentList
        )
    }

    fun haversineMiles(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val rMiles = 3958.8
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLon = (lon2 - lon1) * PI / 180.0
        val a = sin(dLat / 2.0) * sin(dLat / 2.0) +
            cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) *
            sin(dLon / 2.0) * sin(dLon / 2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return rMiles * c
    }
}
