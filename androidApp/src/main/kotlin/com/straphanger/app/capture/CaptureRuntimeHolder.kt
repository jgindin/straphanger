package com.straphanger.app.capture

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.straphanger.app.MainActivity
import com.straphanger.app.R
import com.straphanger.app.data.capture.RideCaptureRuntime
import com.straphanger.app.data.local.FileRidePersistenceBackend
import com.straphanger.app.notify.AndroidRideNotifier
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path

/**
 * The process's one [RideCaptureRuntime], and so its one writer of `rides.json`. The activity, the
 * geofence, boot and idle-close receivers, and the notification listener all use it. It runs on
 * [Dispatchers.Main.immediate], the dispatcher the view model is built with.
 */
object CaptureRuntimeHolder {
  private const val TAG = AndroidGeofenceRegistrar.TAG
  private const val CATCH_UP_TIMEOUT_MS = 9_000L

  @Volatile private var runtime: RideCaptureRuntime? = null

  fun get(context: Context): RideCaptureRuntime =
    runtime
      ?: synchronized(this) { runtime ?: build(context.applicationContext).also { runtime = it } }

  /**
   * Turns what was recorded while nothing ran into rides, from a receiver's [onReceive]: holds the
   * broadcast with [BroadcastReceiver.goAsync] until the bounded catch-up and [alongside] (e.g. a
   * geofence refresh) finish. Station entries are replayed only while passive capture is allowed;
   * Google Maps sessions are drained either way.
   */
  fun catchUpInBackground(
    receiver: BroadcastReceiver,
    context: Context,
    reason: String,
    alongside: (suspend () -> Unit)? = null,
  ) {
    val appContext = context.applicationContext
    val runtime = get(appContext)
    val pending = receiver.goAsync()
    runtime.scope.launch {
      try {
        val other = alongside?.let { launch { it() } }
        val passive = BackgroundGeofenceRefresher.captureAllowed(appContext)
        val done =
          withTimeoutOrNull(CATCH_UP_TIMEOUT_MS) { runtime.catchUp(passiveCapture = passive) }
        if (done == null) Log.w(TAG, "$reason: catch-up timed out")
        other?.join()
      } finally {
        pending.finish()
      }
    }
  }

  private fun build(context: Context): RideCaptureRuntime =
    RideCaptureRuntime(
      backend =
        FileRidePersistenceBackend(
          Path(context.filesDir.path, "rides.json"),
          syncFile = ::syncToStorage,
        ),
      dispatcher = Dispatchers.Main.immediate,
      registrar = AndroidGeofenceRegistrar(context),
      pendingEvents = PassiveCaptureStorage.pendingEvents(context),
      notificationSessions = PassiveCaptureStorage.notificationSessions(context),
      notifier =
        AndroidRideNotifier(
          context,
          smallIcon = R.drawable.ic_stat_ride,
          turnedOn = { PassiveCaptureStorage.rideNotificationsTurnedOn(context) },
          tapIntent = { rideTapIntent(context, it) },
        ),
      idleCloseScheduler = AlarmIdleCloseScheduler(context),
    )

  /** Opens the app on [rideId]; one intent per ride, so a later ride does not replace it. */
  private fun rideTapIntent(context: Context, rideId: String): PendingIntent =
    PendingIntent.getActivity(
      context,
      rideId.hashCode(),
      Intent(context, MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_RIDE_ID, rideId)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

  /** Forces a written file to storage; fsync on any descriptor of the file flushes its data. */
  private fun syncToStorage(path: Path) {
    FileOutputStream(path.toString(), true).use { it.fd.sync() }
  }
}
