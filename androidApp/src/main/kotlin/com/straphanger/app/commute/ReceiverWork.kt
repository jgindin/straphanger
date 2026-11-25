package com.straphanger.app.commute

import android.content.BroadcastReceiver
import android.content.Context
import android.util.Log
import com.straphanger.app.capture.CaptureRuntimeHolder
import com.straphanger.app.diagnostics.DiagnosticsLog
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A broadcast has about 10 s; the fetches inside the work give up after 8 s. */
private const val RECEIVER_TIMEOUT_MS = 9_500L

/**
 * Runs [work] on the runtime's scope from a receiver's [BroadcastReceiver.onReceive], holding the
 * broadcast with [BroadcastReceiver.goAsync] until it finishes or times out. Failures are logged
 * under [tag].
 */
internal fun runReceiverWork(
  receiver: BroadcastReceiver,
  context: Context,
  tag: String,
  reason: String,
  work: suspend () -> Unit,
) {
  val pending = receiver.goAsync()
  CaptureRuntimeHolder.get(context.applicationContext).scope.launch {
    try {
      runCapped(tag, reason, work)
    } finally {
      pending.finish()
    }
  }
}

/** Runs [work] for at most a broadcast's time, logging a timeout or failure under [tag]. */
internal suspend fun runCapped(tag: String, reason: String, work: suspend () -> Unit) {
  val done = withTimeoutOrNull(RECEIVER_TIMEOUT_MS) { runLogged(tag, reason, work) }
  if (done == null) Log.w(tag, "$reason: timed out")
}

/** Runs [work], logging any failure but cancellation under [tag]. */
@Suppress("TooGenericExceptionCaught") // Any failure must not take the receiver's process down.
internal suspend fun runLogged(tag: String, reason: String, work: suspend () -> Unit) {
  try {
    work()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    DiagnosticsLog.warn(tag, "$reason failed", e)
  }
}
