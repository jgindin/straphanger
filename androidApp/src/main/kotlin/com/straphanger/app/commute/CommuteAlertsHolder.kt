package com.straphanger.app.commute

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.straphanger.app.MainActivity
import com.straphanger.app.R
import com.straphanger.app.capture.CaptureRuntimeHolder
import com.straphanger.app.data.commute.CommuteAlerts
import com.straphanger.app.data.commute.CommuteStore
import com.straphanger.app.data.commute.CommuteTap
import com.straphanger.app.data.commute.FileCommuteStore
import com.straphanger.app.data.commute.stationComplexes
import com.straphanger.app.data.realtime.RealtimeFeedClient
import com.straphanger.app.diagnostics.DiagnosticsLog
import com.straphanger.app.notify.AlarmCommuteScheduler
import com.straphanger.app.notify.AndroidCommuteNotifier
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path

/**
 * The process's one [CommuteAlerts], and so its one writer of `commutes.json`. Station complexes
 * come from the bundled network, loaded once by [CaptureRuntimeHolder]'s runtime without starting
 * passive capture; the alarm and reschedule receivers wait for it.
 */
object CommuteAlertsHolder {
  private const val TAG = "StraphangerCommutes"

  /** A broadcast has about 10 s; the fetch inside [CommuteAlerts.fire] gives up after 8 s. */
  private const val RECEIVER_TIMEOUT_MS = 9_500L

  @Volatile private var alerts: CommuteAlerts? = null

  @Volatile private var store: CommuteStore? = null

  /** One reschedule at a time, so the pass for the rider's last change is the one that sticks. */
  private val rescheduleLock = Mutex()

  fun get(context: Context): CommuteAlerts =
    alerts
      ?: synchronized(this) { alerts ?: build(context.applicationContext).also { alerts = it } }

  /** The process's one `commutes.json`, shared by the UI and [get]'s alerts. */
  fun store(context: Context): CommuteStore =
    store
      ?: synchronized(this) {
        store
          ?: FileCommuteStore(Path(context.applicationContext.filesDir.path, "commutes.json"))
            .also { store = it }
      }

  /**
   * Sets the alarms again after the UI has written the commutes to [store]. The UI's write already
   * dropped [removedIds] from the file, so their alarms are cancelled here; the rest are set by
   * [CommuteAlerts.rescheduleAll], which may wait for the network to load.
   */
  fun commutesChanged(context: Context, removedIds: Collection<String>) {
    val appContext = context.applicationContext
    val scheduler = scheduler(appContext)
    removedIds.forEach(scheduler::cancel)
    val alerts = get(appContext)
    CaptureRuntimeHolder.get(appContext).scope.launch {
      rescheduleLock.withLock { runSafely(alerts, "Rescheduling commutes") { it.rescheduleAll() } }
    }
  }

  /**
   * Runs [work] on the runtime's scope from a receiver's [BroadcastReceiver.onReceive], holding the
   * broadcast with [BroadcastReceiver.goAsync] until it finishes or times out. Failures are logged.
   */
  fun runInBackground(
    receiver: BroadcastReceiver,
    context: Context,
    reason: String,
    work: suspend (CommuteAlerts) -> Unit,
  ) {
    val appContext = context.applicationContext
    val alerts = get(appContext)
    val pending = receiver.goAsync()
    CaptureRuntimeHolder.get(appContext).scope.launch {
      try {
        val done = withTimeoutOrNull(RECEIVER_TIMEOUT_MS) { runSafely(alerts, reason, work) }
        if (done == null) Log.w(TAG, "$reason: timed out")
      } finally {
        pending.finish()
      }
    }
  }

  @Suppress("TooGenericExceptionCaught") // Any failure must not take the receiver's process down.
  private suspend fun runSafely(
    alerts: CommuteAlerts,
    reason: String,
    work: suspend (CommuteAlerts) -> Unit,
  ) {
    try {
      work(alerts)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      DiagnosticsLog.warn(TAG, "$reason failed", e)
    }
  }

  /** The scheduler the app's commutes use; the UI asks it whether alarms will be exact. */
  fun scheduler(context: Context): AlarmCommuteScheduler =
    AlarmCommuteScheduler(context.applicationContext, CommuteAlarmReceiver::class.java)

  private fun build(context: Context): CommuteAlerts {
    val feed = RealtimeFeedClient.mta()
    return CommuteAlerts(
      store = store(context),
      complexes = {
        CaptureRuntimeHolder.get(context).network().map(::stationComplexes).getOrNull()
      },
      fetch = feed::fetch,
      notifier =
        AndroidCommuteNotifier(
          context,
          smallIcon = R.drawable.ic_stat_ride,
          tapIntent = { tap, commuteId -> commuteTapIntent(context, tap, commuteId) },
        ),
      scheduler = scheduler(context),
    )
  }

  /** Opens the Status tab on [tap]; one intent per commute, so another does not replace it. */
  private fun commuteTapIntent(
    context: Context,
    tap: CommuteTap,
    commuteId: String,
  ): PendingIntent {
    val intent =
      Intent(context, MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_COMMUTE_COMPLEX, tap.complexId)
        .putExtra(MainActivity.EXTRA_COMMUTE_ROUTE, tap.routeId)
        .putExtra(MainActivity.EXTRA_COMMUTE_DIRECTION, tap.direction.name)
        .putExtra(MainActivity.EXTRA_COMMUTE_NOTIFICATION, commuteId)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      intent.setIdentifier("commute:$commuteId")
    }
    return PendingIntent.getActivity(
      context,
      commuteId.hashCode(),
      intent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
  }
}
