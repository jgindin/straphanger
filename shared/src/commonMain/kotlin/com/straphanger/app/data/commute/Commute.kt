package com.straphanger.app.data.commute

import com.straphanger.app.data.realtime.Direction
import kotlin.random.Random
import kotlinx.datetime.DayOfWeek
import kotlinx.serialization.Serializable

/**
 * A recurring trip the rider wants a departure alert for: the [routeId] train toward [direction] at
 * station [complexId], on [days] at the local wall-clock [hour]:[minute]. With an end time
 * ([endHour]:[endMinute], both or neither) it also shows on a widget until then.
 */
@Serializable
data class Commute(
  val id: String,
  val routeId: String,
  val complexId: String,
  val direction: Direction,
  val days: Set<DayOfWeek>,
  val hour: Int,
  val minute: Int,
  val enabled: Boolean = true,
  val endHour: Int? = null,
  val endMinute: Int? = null,
)

private const val ID_RADIX = 36

fun newCommuteId(): String = "c_" + Random.nextLong().toULong().toString(ID_RADIX)
