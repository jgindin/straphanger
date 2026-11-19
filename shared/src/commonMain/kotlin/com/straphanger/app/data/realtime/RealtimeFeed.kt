package com.straphanger.app.data.realtime

/** The MTA's GTFS-Realtime feeds; each covers a group of lines. */
internal enum class RealtimeFeed(val path: String, val routes: Set<String>) {
  NUMBERED("nyct%2Fgtfs", setOf("1", "2", "3", "4", "5", "5X", "6", "6X", "7", "7X", "GS")),
  ACE("nyct%2Fgtfs-ace", setOf("A", "C", "E", "FS", "H")),
  BDFM("nyct%2Fgtfs-bdfm", setOf("B", "D", "F", "FX", "M")),
  G("nyct%2Fgtfs-g", setOf("G")),
  JZ("nyct%2Fgtfs-jz", setOf("J", "Z")),
  NQRW("nyct%2Fgtfs-nqrw", setOf("N", "Q", "R", "W")),
  L("nyct%2Fgtfs-l", setOf("L")),
  STATEN_ISLAND("nyct%2Fgtfs-si", setOf("SI", "SIR"));

  companion object {
    /** The feeds that carry any of [routes]; routes the MTA does not publish are ignored. */
    fun forRoutes(routes: Collection<String>): Set<RealtimeFeed> =
      entries.filterTo(mutableSetOf()) { feed -> routes.any { it in feed.routes } }
  }
}

internal const val ALERTS_FEED_PATH = "camsys%2Fsubway-alerts"
internal const val MTA_FEEDS_BASE_URL = "https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/"
