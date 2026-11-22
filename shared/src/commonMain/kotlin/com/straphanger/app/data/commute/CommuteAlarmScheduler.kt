package com.straphanger.app.data.commute

/** Wakes the app at a commute's time; one alarm per commute, a new one replacing the last. */
interface CommuteAlarmScheduler {
  fun schedule(commuteId: String, atEpochMs: Long)

  fun cancel(commuteId: String)

  companion object {
    val None: CommuteAlarmScheduler =
      object : CommuteAlarmScheduler {
        override fun schedule(commuteId: String, atEpochMs: Long) = Unit

        override fun cancel(commuteId: String) = Unit
      }
  }
}
