package com.straphanger.app.data.capture

import com.straphanger.app.data.local.CompleteRideRecord
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
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
 * Keeps the state as JSON in [file], written through `<file>.tmp`. An unreadable file reads as
 * empty.
 */
class FileNotificationSessionStore(
  private val file: Path,
  private val fileSystem: FileSystem = SystemFileSystem,
) : NotificationSessionStore {
  private val tmpFile = Path(file.toString() + ".tmp")
  private val json = Json { ignoreUnknownKeys = true }

  init {
    file.parent?.let { fileSystem.createDirectories(it) }
  }

  override fun read(): NotificationSessionState {
    if (!fileSystem.exists(file)) return NotificationSessionState()
    return try {
      json.decodeFromString(
        NotificationSessionState.serializer(),
        fileSystem.source(file).buffered().use { it.readString() },
      )
    } catch (e: Exception) {
      NotificationSessionState()
    }
  }

  override fun write(state: NotificationSessionState) {
    if (state.open == null && state.completed.isEmpty()) {
      if (fileSystem.exists(file)) fileSystem.delete(file)
      return
    }
    val text = json.encodeToString(NotificationSessionState.serializer(), state)
    fileSystem.sink(tmpFile).buffered().use { it.writeString(text) }
    fileSystem.atomicMove(tmpFile, file)
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

  fun onRemoved(key: String, removedMs: Long): Boolean {
    val state = store.read()
    val open = state.open ?: return false
    if (open.key != key) return false
    store.write(
      NotificationSessionState(
        open = null,
        completed = state.completed + open.copy(endMs = removedMs),
      )
    )
    return true
  }

  /**
   * Ends the open session if its notification is no longer showing, e.g. it was removed while the
   * listener was disconnected. [activeKeys] are the keys of the notifications showing now.
   */
  fun onActiveNotifications(activeKeys: Set<String>): Boolean {
    val state = store.read()
    val open = state.open ?: return false
    if (open.key in activeKeys) return false
    store.write(
      NotificationSessionState(
        open = null,
        completed = state.completed + open.copy(endMs = open.lastPostMs),
      )
    )
    return true
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
