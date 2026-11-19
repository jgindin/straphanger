package com.straphanger.app.data.realtime

/** What kind of disruption an alert describes. */
enum class AlertKind {
  /** Trains are delayed, rerouted or not running right now. */
  DELAY,
  /** Scheduled work, announced ahead of time. */
  PLANNED,
  OTHER,
}

/** One MTA service alert, for the lines it names. */
data class ServiceAlert(
  val id: String,
  val routes: Set<String>,
  val kind: AlertKind,
  val header: String,
  val description: String,
)

/** The alerts in this feed that are in effect at [nowEpochSeconds], delays before planned work. */
internal fun activeAlerts(feed: FeedMessage, nowEpochSeconds: Long): List<ServiceAlert> =
  feed.entity
    .mapNotNull { entity -> entity.alert?.let { entity.id to it } }
    .filter { (_, alert) -> alert.isActiveAt(nowEpochSeconds) }
    .map { (id, alert) ->
      ServiceAlert(
        id = id,
        routes = alert.informedEntity.map { it.routeId }.filter { it.isNotEmpty() }.toSet(),
        kind = alert.kind(id),
        header = alert.headerText?.text().orEmpty(),
        description = alert.descriptionText?.text().orEmpty(),
      )
    }
    .filter { it.header.isNotEmpty() }
    .sortedBy { it.kind.ordinal }

private fun Alert.isActiveAt(now: Long): Boolean =
  activePeriod.isEmpty() || activePeriod.any { it.start <= now && (it.end == 0L || now <= it.end) }

private fun Alert.kind(id: String): AlertKind {
  val type = mercury?.alertType.orEmpty()
  return when {
    // The MTA's ids say where an alert came from: planned work has `planned_work` in it.
    "planned_work" in id || type.startsWith("Planned", ignoreCase = true) -> AlertKind.PLANNED
    type.contains("Delays", ignoreCase = true) ||
      type.contains("Detour", ignoreCase = true) ||
      type.contains("Suspended", ignoreCase = true) ||
      type.contains("Reduced", ignoreCase = true) ||
      type.contains("Skipped", ignoreCase = true) -> AlertKind.DELAY
    else -> AlertKind.OTHER
  }
}
