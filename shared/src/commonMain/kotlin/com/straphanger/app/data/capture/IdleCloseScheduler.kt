package com.straphanger.app.data.capture

/**
 * A platform alarm that closes an idle ride chain when no process is running: the coordinator's
 * in-process timer dies with the process, so it also asks the platform to call
 * [RideCaptureRuntime.catchUp] when the open chain would go idle.
 */
interface IdleCloseScheduler {
  /**
   * Asks the platform to call [RideCaptureRuntime.catchUp] at or after [atEpochMs], replacing any
   * earlier request.
   */
  fun schedule(atEpochMs: Long)

  /** Withdraws the request, e.g. once no chain is open. */
  fun cancel()

  companion object {
    /** Schedules nothing; for platforms and tests with no alarm. */
    val None: IdleCloseScheduler =
      object : IdleCloseScheduler {
        override fun schedule(atEpochMs: Long) = Unit

        override fun cancel() = Unit
      }
  }
}
