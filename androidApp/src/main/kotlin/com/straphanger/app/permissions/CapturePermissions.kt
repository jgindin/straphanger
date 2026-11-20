package com.straphanger.app.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

object CapturePermissions {
  fun fineLocationGranted(context: Context): Boolean =
    granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

  /** Always true before Android 10, where foreground location also covers the background. */
  fun backgroundLocationGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
      granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

  private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/**
 * Returns a function that asks for the location permissions passive capture needs: fine location
 * first, then, on Android 10 and later, background location as a separate request. From Android 11
 * the background request opens the app's location settings. [onResult] runs after each answer.
 */
@Composable
fun rememberPassiveCapturePermissionRequest(onResult: () -> Unit): () -> Unit {
  val context = LocalContext.current
  val backgroundLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult() }
  val foregroundLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
      results ->
      onResult()
      if (
        results[Manifest.permission.ACCESS_FINE_LOCATION] == true &&
          !CapturePermissions.backgroundLocationGranted(context)
      ) {
        backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
      }
    }
  return {
    when {
      !CapturePermissions.fineLocationGranted(context) ->
        foregroundLauncher.launch(
          arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
          ),
        )
      !CapturePermissions.backgroundLocationGranted(context) ->
        backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
      else -> onResult()
    }
  }
}

/**
 * Returns a function that asks for permission to post notifications, which Android 13 and later
 * require. [onResult] runs with whether the permission is now granted; before Android 13 nothing is
 * asked and it runs at once with true.
 */
@Composable
fun rememberPostNotificationsPermissionRequest(onResult: (granted: Boolean) -> Unit): () -> Unit {
  val launcher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      onResult(granted)
    }
  return {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
      onResult(true)
    }
  }
}
