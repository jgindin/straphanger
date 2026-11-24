package com.straphanger.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.os.PowerManager
import com.straphanger.app.capture.CaptureRuntimeHolder
import com.straphanger.app.commute.CommuteAlertsHolder
import com.straphanger.app.commute.runCapped
import com.straphanger.app.commute.runLogged
import com.straphanger.app.commute.runReceiverWork
import com.straphanger.app.data.commute.stationComplexes
import com.straphanger.app.data.realtime.RealtimeFeedClient
import com.straphanger.app.data.widget.CommuteWidgets
import com.straphanger.app.data.widget.FileCommuteWidgetStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path

/**
 * The process's one [CommuteWidgets], and so its one writer of `commute_widgets.json`. The store
 * does no locking, so every call made through [runInBackground], [run] and [launch] holds one lock.
 * Commutes come from [CommuteAlertsHolder.store] and station complexes from the bundled network.
 */
object CommuteWidgetsHolder {
  private const val TAG = "StraphangerWidgets"

  @Volatile private var widgets: CommuteWidgets? = null

  /** One call into [widgets] at a time. */
  private val lock = Mutex()

  fun get(context: Context): CommuteWidgets =
    widgets
      ?: synchronized(this) { widgets ?: build(context.applicationContext).also { widgets = it } }

  /**
   * Runs [work] under the lock on the runtime's scope from a receiver's
   * [BroadcastReceiver.onReceive], holding the broadcast until it finishes or times out, as
   * [CommuteAlertsHolder.runInBackground] does. Failures are logged.
   */
  fun runInBackground(
    receiver: BroadcastReceiver,
    context: Context,
    reason: String,
    work: suspend (CommuteWidgets) -> Unit,
  ) {
    val widgets = get(context)
    runReceiverWork(receiver, context, TAG, reason) { lock.withLock { work(widgets) } }
  }

  /**
   * Runs [work] under the lock for at most a broadcast's time, for a caller that already holds its
   * broadcast, such as a Glance action. Failures and timeouts are logged.
   */
  suspend fun run(context: Context, reason: String, work: suspend (CommuteWidgets) -> Unit) {
    val widgets = get(context)
    runCapped(TAG, reason) { lock.withLock { work(widgets) } }
  }

  /**
   * Runs [work] under the lock on the runtime's scope, outliving the caller. Failures are logged.
   */
  fun launch(context: Context, reason: String, work: suspend (CommuteWidgets) -> Unit): Job {
    val widgets = get(context)
    return CaptureRuntimeHolder.get(context.applicationContext).scope.launch {
      runLogged(TAG, reason) { lock.withLock { work(widgets) } }
    }
  }

  private fun build(context: Context): CommuteWidgets {
    val feed = RealtimeFeedClient.mta()
    val power = context.getSystemService(PowerManager::class.java)
    return CommuteWidgets(
      store = FileCommuteWidgetStore(Path(context.filesDir.path, "commute_widgets.json")),
      commutes = CommuteAlertsHolder.store(context),
      complexes = {
        CaptureRuntimeHolder.get(context).network().map(::stationComplexes).getOrNull()
      },
      fetch = feed::fetch,
      scheduler = AlarmWidgetRefreshScheduler(context, WidgetRefreshReceiver::class.java),
      // A stale id is dropped once the current call has released the lock.
      renderer =
        GlanceWidgetRenderer(context, CommuteGlanceWidget()) { gone ->
          launch(context, "Forgetting widget $gone") { it.removed(listOf(gone)) }
        },
      screenOn = { power?.isInteractive == true },
    )
  }
}
