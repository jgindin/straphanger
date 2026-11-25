package com.straphanger.app.commute

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.os.Build
import com.straphanger.app.MainActivity
import com.straphanger.app.R
import com.straphanger.app.capture.CaptureRuntimeHolder
import com.straphanger.app.data.commute.CommuteAlerts
import com.straphanger.app.data.commute.CommuteStore
import com.straphanger.app.data.commute.CommuteTap
import com.straphanger.app.data.commute.FileCommuteStore
import com.straphanger.app.data.commute.stationComplexes
import com.straphanger.app.data.realtime.RealtimeFeedClient
import com.straphanger.app.notify.AlarmCommuteScheduler
import com.straphanger.app.notify.AndroidCommuteNotifier
import com.straphanger.app.widget.CommuteWidgetsHolder
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path

/**
 * The process's one [CommuteAlerts], and so its one writer of `commutes.json`. Station complexes
 * come from the bundled network, loaded once by [CaptureRuntimeHolder]'s runtime without starting
 * passive capture; the alarm and reschedule receivers wait for it.
 */
object CommuteAlertsHolder {
  private const val TAG = "StraphangerCommutes"

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
      rescheduleLock.withLock {
        runLogged(TAG, "Rescheduling commutes") { alerts.rescheduleAll() }
        CommuteWidgetsHolder.run(appContext, "Rescheduling widgets") { it.rescheduleAll() }
      }
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
    val alerts = get(context)
    runReceiverWork(receiver, context, TAG, reason) { work(alerts) }
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
      commuteTapActivityIntent(context, tap)
        .putExtra(MainActivity.EXTRA_COMMUTE_NOTIFICATION, commuteId)
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
