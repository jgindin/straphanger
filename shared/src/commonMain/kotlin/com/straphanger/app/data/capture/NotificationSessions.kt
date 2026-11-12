package com.straphanger.app.data.capture

import com.straphanger.app.data.local.CompleteRideRecord
import kotlin.math.abs
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One Google Maps navigation notification, from its first post to its removal. [firstText] and
 * [latestText] are the notification's text when first posted and when last updated.
 */
@Serializable
data class NavigationNotificationSession(
  val key: String,
  val firstPostMs: Long,
  val lastPostMs: Long,
  val firstText: String,
  val latestText: String,
  /**
   * Set when the session has ended: the removal time, or the last post when a new key replaced it.
   */
  val endMs: Long? = null,
)

@Serializable
data class NotificationSessionState(
  val open: NavigationNotificationSession? = null,
  val completed: List<NavigationNotificationSession> = emptyList(),
)

/**
 * Where the notification listener keeps the session in progress and the sessions that ended but
 * have not yet been turned into rides. Calls are synchronous and not thread-safe; on Android the
 * listener and the view model both run on the main thread.
 */
interface NotificationSessionStore {
  fun read(): NotificationSessionState

  fun write(state: NotificationSessionState)
}

class InMemoryNotificationSessionStore(
  private var state: NotificationSessionState = NotificationSessionState()
) : NotificationSessionStore {
  override fun read(): NotificationSessionState = state

  override fun write(state: NotificationSessionState) {
    this.state = state
  }
}

/**
 * Keeps the state as JSON in [file], written through `<file>.tmp`. An unreadable file is moved
 * aside, logged, and then read as empty.
 */
class FileNotificationSessionStore(
  file: Path,
  fileSystem: FileSystem = SystemFileSystem,
  clock: Clock = Clock.System,
) : NotificationSessionStore {
  private val stateFile = JsonStateFile(file, fileSystem, clock, LOG_TAG)
  private val json = Json { ignoreUnknownKeys = true }

  override fun read(): NotificationSessionState =
    stateFile.read { json.decodeFromString(NotificationSessionState.serializer(), it) }
      ?: NotificationSessionState()

  override fun write(state: NotificationSessionState) {
    val empty = state.open == null && state.completed.isEmpty()
    stateFile.write(
      if (empty) null else json.encodeToString(NotificationSessionState.serializer(), state)
    )
  }

  private companion object {
    const val LOG_TAG = "StraphangerMapsSessions"
  }
}

/**
 * Turns notification posts and removals into navigation sessions. Google Maps updates one ongoing
 * notification many times during a trip; each update keeps the session open. The session ends when
 * the notification is removed, or when a post with another key arrives. Ended sessions go to the
 * store's completed list for the view model to turn into rides. Returns true when a session ended.
 */
class NotificationSessionTracker(private val store: NotificationSessionStore) {

  fun onPosted(key: String, postTimeMs: Long, text: String): Boolean {
    if (text.isBlank()) return false
    val state = store.read()
    val open = state.open
    return when {
      open == null -> {
        store.write(
          state.copy(open = NavigationNotificationSession(key, postTimeMs, postTimeMs, text, text))
        )
        false
      }
      open.key == key -> {
        store.write(
          state.copy(
            open = open.copy(lastPostMs = maxOf(open.lastPostMs, postTimeMs), latestText = text)
          )
        )
        false
      }
      else -> {
        store.write(
          NotificationSessionState(
            open = NavigationNotificationSession(key, postTimeMs, postTimeMs, text, text),
            completed = state.completed + open.copy(endMs = open.lastPostMs),
          )
        )
        true
      }
    }
  }

  fun onRemoved(key: String, removedMs: Long): Boolean =
    endOpenSessionIf({ it.key == key }) { removedMs }

  /**
   * Ends the open session if its notification is no longer showing, e.g. it was removed while the
   * listener was disconnected. [activeKeys] are the keys of the notifications showing now.
   */
  fun onActiveNotifications(activeKeys: Set<String>): Boolean =
    endOpenSessionIf({ it.key !in activeKeys }) { it.lastPostMs }

  /**
   * Moves the open session to the completed list, ending at [endMs], when [shouldEnd] holds for it.
   * Returns true when it did.
   */
  private fun endOpenSessionIf(
    shouldEnd: (NavigationNotificationSession) -> Boolean,
    endMs: (NavigationNotificationSession) -> Long,
  ): Boolean {
    val state = store.read()
    val open = state.open?.takeIf(shouldEnd)
    if (open != null) {
      store.write(
        NotificationSessionState(
          open = null,
          completed = state.completed + open.copy(endMs = endMs(open)),
        )
      )
    }
    return open != null
  }
}

/** Tells a running view model that the listener ended a session. */
object NotificationSessionSignal {
  private val _endedCount = MutableStateFlow(0)
  val endedCount: StateFlow<Int> = _endedCount.asStateFlow()

  fun sessionEnded() {
    _endedCount.value = _endedCount.value + 1
  }
}

/**
 * True when [existing] already holds a ride with the same origin and destination starting within
 * two minutes of [candidate].
 */
fun isDuplicateRide(existing: List<CompleteRideRecord>, candidate: CompleteRideRecord): Boolean =
  existing.any {
    it.ride.originStationId == candidate.ride.originStationId &&
      it.ride.destinationStationId == candidate.ride.destinationStationId &&
      abs(it.ride.startTimeEpochMs - candidate.ride.startTimeEpochMs) <= DUPLICATE_START_WINDOW_MS
  }

private const val DUPLICATE_START_WINDOW_MS = 2 * 60_000L
