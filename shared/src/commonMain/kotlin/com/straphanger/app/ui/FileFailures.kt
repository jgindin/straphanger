package com.straphanger.app.ui

import io.github.vinceglb.filekit.exceptions.FileKitException
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException

/**
 * Runs [block], which reads or writes a file the user chose through FileKit, and returns its
 * failure instead of throwing it: FileKit's own exception, an I/O error, a refused access, or the
 * [IllegalStateException] FileKit throws for a URI it cannot inspect. Cancellation is rethrown.
 */
internal suspend fun <T> catchingFileFailure(block: suspend () -> T): Result<T> =
  try {
    Result.success(block())
  } catch (e: FileKitException) {
    Result.failure(e)
  } catch (e: IOException) {
    Result.failure(e)
  } catch (e: FileAccessDeniedException) {
    Result.failure(e)
  } catch (e: IllegalStateException) {
    if (e is CancellationException) throw e
    Result.failure(e)
  }

/**
 * Runs [block], which saves rides, and returns the save failure instead of throwing it: the
 * backend's [IOException], or the [IllegalStateException] of a backend that refused the write or of
 * saves that are blocked. Cancellation is rethrown.
 */
internal suspend fun <T> catchingSaveFailure(block: suspend () -> T): Result<T> =
  try {
    Result.success(block())
  } catch (e: IOException) {
    Result.failure(e)
  } catch (e: IllegalStateException) {
    if (e is CancellationException) throw e
    Result.failure(e)
  }

/** The failure's message, or "unknown error" when it has none. */
internal val Throwable.reason: String
  get() = message ?: "unknown error"
