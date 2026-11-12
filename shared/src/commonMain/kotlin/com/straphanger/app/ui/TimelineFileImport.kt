package com.straphanger.app.ui

import com.straphanger.app.data.capture.TimelineImportSummary
import com.straphanger.app.data.capture.TimelineJsonImporter
import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Importing rides from Google Maps Timeline files. */
interface TimelineFileImport {
  /** Imports the two rides of [SAMPLE_TIMELINE_JSON] (a developer tool); returns how many. */
  fun importSampleGoogleMapsTimeline(): Int

  /**
   * Imports a Google Maps Timeline file. Parsing and ride resolution run on the compute dispatcher
   * so a large file does not block the UI. A file with no importable rides returns a summary of
   * zeros; so does a file that is not a Timeline file at all, which the status message reports
   * instead of an import summary.
   */
  suspend fun importTimelineFile(bytes: ByteArray): TimelineImportSummary

  /**
   * Reads and imports a Timeline file in the view model's scope, so leaving the screen does not
   * cancel it. A file that cannot be read is reported in the status message.
   */
  fun importTimelineFileInBackground(readBytes: suspend () -> ByteArray): Job
}

internal const val NOT_TEXT_TIMELINE_MESSAGE =
  "That file is not valid text, so it is not a Google Maps Timeline file"
internal const val NOT_A_TIMELINE_MESSAGE = "That file is not a Google Maps Timeline file"
private const val LOG_TAG = "StraphangerImport"

/** What reading a Timeline file produced, before anything is saved. */
private sealed interface TimelineFileOutcome {
  data class Resolved(val summary: TimelineImportSummary) : TimelineFileOutcome

  /** The file is not a Timeline file; [message] tells the user why. */
  data class Rejected(val message: String, val cause: Throwable) : TimelineFileOutcome
}

internal class TimelineImportController(
  private val session: StraphangerSession,
  private val computeDispatcher: CoroutineDispatcher,
) : TimelineFileImport {

  override fun importSampleGoogleMapsTimeline(): Int {
    val summary =
      session.services?.timelineImporter?.importTimelineJson(SAMPLE_TIMELINE_JSON, session.dao)
    if (summary == null || !session.dao.lastSaveSucceeded) return 0
    session.setStatus(
      "Google Maps Timeline: imported ${countOf(summary.ridesImported, "new ride")}, " +
        "skipped ${countOf(summary.duplicatesSkipped, "duplicate")}"
    )
    return summary.ridesImported
  }

  override suspend fun importTimelineFile(bytes: ByteArray): TimelineImportSummary {
    val state = session.state.first { it.isNetworkLoaded || it.networkLoadError != null }
    val importer = session.services?.timelineImporter
    return if (!state.isNetworkLoaded || importer == null) emptyImportSummary()
    else whileImporting { import(importer, bytes) }
  }

  private suspend fun <T> whileImporting(block: suspend () -> T): T {
    session.update { it.copy(timelineImportInProgress = true) }
    try {
      return block()
    } finally {
      session.update { it.copy(timelineImportInProgress = false) }
    }
  }

  private suspend fun import(
    importer: TimelineJsonImporter,
    bytes: ByteArray,
  ): TimelineImportSummary {
    val existingRides = session.dao.getAllRides()
    // Only parsing and resolving run on computeDispatcher; every DAO write stays on the view model
    // dispatcher.
    val outcome =
      withContext(computeDispatcher) { readTimelineFile(importer, bytes, existingRides) }
    return when (outcome) {
      is TimelineFileOutcome.Resolved -> saveImported(outcome.summary)
      is TimelineFileOutcome.Rejected -> reject(outcome)
    }
  }

  private fun saveImported(resolved: TimelineImportSummary): TimelineImportSummary {
    session.dao.insertOrReplaceRides(resolved.importedRecords)
    val saved = session.dao.lastSaveSucceeded
    // A failed save imported nothing, whatever was resolved.
    val summary =
      if (saved) resolved else resolved.copy(ridesImported = 0, importedRecords = emptyList())
    session.update {
      it.copy(
        lastImportSummary = summary.copy(importedRecords = emptyList()),
        statusMessage = if (saved) importedMessage(summary) else it.statusMessage,
      )
    }
    return summary
  }

  private fun reject(outcome: TimelineFileOutcome.Rejected): TimelineImportSummary {
    DiagnosticsLog.warn(LOG_TAG, outcome.message, outcome.cause)
    session.update { it.copy(lastImportSummary = null, statusMessage = outcome.message) }
    return emptyImportSummary()
  }

  override fun importTimelineFileInBackground(readBytes: suspend () -> ByteArray): Job =
    session.scope.launch {
      catchingFileFailure(readBytes)
        .fold(
          onSuccess = { bytes -> importTimelineFile(bytes) },
          onFailure = { e -> session.setStatus("Could not read the file: ${e.reason}") },
        )
    }
}

private fun readTimelineFile(
  importer: TimelineJsonImporter,
  bytes: ByteArray,
  existingRides: List<CompleteRideRecord>,
): TimelineFileOutcome {
  val text =
    try {
      bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (e: CharacterCodingException) {
      return TimelineFileOutcome.Rejected(NOT_TEXT_TIMELINE_MESSAGE, e)
    }
  val summary = importer.resolveTimelineJson(text, existingRides)
  // Only a file that yielded nothing is parsed again, to tell "no subway rides" from "not JSON".
  val syntaxError = if (summary.rawSegmentsFound == 0) jsonSyntaxError(text) else null
  return if (syntaxError != null) TimelineFileOutcome.Rejected(NOT_A_TIMELINE_MESSAGE, syntaxError)
  else TimelineFileOutcome.Resolved(summary)
}

private fun jsonSyntaxError(text: String): SerializationException? =
  try {
    Json.parseToJsonElement(text)
    null
  } catch (e: SerializationException) {
    e
  }

private fun importedMessage(summary: TimelineImportSummary): String =
  if (summary.rawSegmentsFound == 0) "No subway rides found in that file"
  else
    "Timeline file: imported ${countOf(summary.ridesImported, "ride")}, " +
      "skipped ${countOf(summary.duplicatesSkipped, "duplicate")}, " +
      "${countOf(summary.multiLegRidesMerged, "transfer")} merged"

private fun emptyImportSummary() = TimelineImportSummary(0, 0, 0, 0, 0, emptyList())
