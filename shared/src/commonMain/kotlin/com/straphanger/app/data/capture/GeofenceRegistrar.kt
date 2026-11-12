package com.straphanger.app.data.capture

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Registers geofences with the platform. Each call replaces every geofence this app registered
 * before.
 */
interface GeofenceRegistrar {
  /** Throws [GeofenceRegistrationException] when the platform refuses the geofences. */
  suspend fun replaceAll(specs: List<ActiveGeofenceSpec>)

  /** Throws [GeofenceRegistrationException] when the platform refuses to remove the geofences. */
  suspend fun clear()
}

/** The platform refused to register geofences; [message] is shown to the user. */
class GeofenceRegistrationException(message: String, cause: Throwable? = null) :
  Exception(message, cause)

/** A geofence transition reported by the platform, with the location that triggered it. */
sealed interface GeofenceEvent {
  val geofenceId: String
  val epochMs: Long
  val lat: Double
  val lng: Double

  data class Entered(
    override val geofenceId: String,
    override val epochMs: Long,
    override val lat: Double,
    override val lng: Double,
  ) : GeofenceEvent

  data class Exited(
    override val geofenceId: String,
    override val epochMs: Long,
    override val lat: Double,
    override val lng: Double,
  ) : GeofenceEvent
}

/**
 * Carries geofence transitions from the platform receiver to common code. Events emitted while
 * nothing is collecting are dropped.
 */
object GeofenceEventBus {
  private val _events =
    MutableSharedFlow<GeofenceEvent>(
      extraBufferCapacity = 64,
      onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
  val events: SharedFlow<GeofenceEvent> = _events.asSharedFlow()

  /**
   * True while something collects [events], i.e. a passive capture coordinator is running in this
   * process.
   */
  val hasCollectors: Boolean
    get() = _events.subscriptionCount.value > 0

  fun emit(event: GeofenceEvent) {
    _events.tryEmit(event)
  }
}
