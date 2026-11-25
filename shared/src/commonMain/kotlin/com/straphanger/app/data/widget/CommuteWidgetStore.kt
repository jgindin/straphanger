package com.straphanger.app.data.widget

import com.straphanger.app.data.capture.JsonStateFile
import com.straphanger.app.data.commute.COMMUTE_LOG_TAG
import com.straphanger.app.diagnostics.DiagnosticsLog
import kotlin.time.Clock
import kotlinx.io.IOException
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One commute's last good times, [text] and [alertLine] as fetched at [fetchedAtMs], and when a
 * fetch last failed, [failedAtMs]. [departures] holds each departure of [text], empty when [text]
 * is a message or the times were stored by 0.25. A failure never clears the times before it.
 */
@Serializable
data class CommuteTimes(
  val text: String? = null,
  val alertLine: String? = null,
  val fetchedAtMs: Long? = null,
  val failedAtMs: Long? = null,
  val departures: List<String> = emptyList(),
)

/**
 * Which commutes each placed widget shows, by widget id, and every commute's last times. Calls are
 * synchronous; a store never throws for a storage failure.
 */
interface CommuteWidgetStore {
  fun widgets(): Map<Int, List<String>>

  fun setWidget(widgetId: Int, commuteIds: List<String>)

  fun removeWidgets(widgetIds: Collection<Int>)

  fun times(): Map<String, CommuteTimes>

  fun setTimes(commuteId: String, times: CommuteTimes)
}

/** Everything a [CommuteWidgetStore] keeps. */
@Serializable
private data class CommuteWidgetState(
  val widgets: Map<Int, List<String>> = emptyMap(),
  val times: Map<String, CommuteTimes> = emptyMap(),
)

/**
 * The five calls over [load] and [save] of the whole state, which both stores below delegate to so
 * each keeps only how the state is held.
 */
private class StateCommuteWidgetStore(
  private val load: () -> CommuteWidgetState,
  private val save: (CommuteWidgetState) -> Unit,
) : CommuteWidgetStore {
  /** Keeps the state in memory only, starting from [initial]. */
  constructor(initial: CommuteWidgetState) : this(MemoryState(initial))

  private constructor(memory: MemoryState) : this({ memory.state }, { memory.state = it })

  private class MemoryState(var state: CommuteWidgetState)

  override fun widgets(): Map<Int, List<String>> = load().widgets

  override fun setWidget(widgetId: Int, commuteIds: List<String>) {
    val state = load()
    save(state.copy(widgets = state.widgets + (widgetId to commuteIds)))
  }

  override fun removeWidgets(widgetIds: Collection<Int>) {
    val state = load()
    save(state.copy(widgets = state.widgets - widgetIds.toSet()))
  }

  override fun times(): Map<String, CommuteTimes> = load().times

  override fun setTimes(commuteId: String, times: CommuteTimes) {
    val state = load()
    save(state.copy(times = state.times + (commuteId to times)))
  }
}

class InMemoryCommuteWidgetStore :
  CommuteWidgetStore by StateCommuteWidgetStore(CommuteWidgetState())

/** A newer app's extra fields are ignored rather than losing every widget. */
private val json = Json { ignoreUnknownKeys = true }

/**
 * Keeps the widget state as one JSON object in [file]. An unreadable file is moved aside, logged,
 * and then read as empty; a failed write is logged and the change is lost.
 */
class FileCommuteWidgetStore(
  file: Path,
  fileSystem: FileSystem = SystemFileSystem,
  clock: Clock = Clock.System,
) : CommuteWidgetStore by fileStore(JsonStateFile(file, fileSystem, clock, COMMUTE_LOG_TAG))

/** The state in [stateFile]; an empty state deletes the file. */
private fun fileStore(stateFile: JsonStateFile): CommuteWidgetStore =
  StateCommuteWidgetStore(
    load = {
      stateFile.read { json.decodeFromString(CommuteWidgetState.serializer(), it) }
        ?: CommuteWidgetState()
    },
    save = { state ->
      val empty = state.widgets.isEmpty() && state.times.isEmpty()
      try {
        stateFile.write(
          if (empty) null else json.encodeToString(CommuteWidgetState.serializer(), state),
        )
      } catch (e: IOException) {
        DiagnosticsLog.warn(COMMUTE_LOG_TAG, "The widget state could not be saved", e)
      }
    },
  )
