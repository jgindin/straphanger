package com.straphanger.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import com.straphanger.app.ui.permissions.BACKGROUND_LOCATION_DISCLOSURE_TITLE
import com.straphanger.app.ui.permissions.NOTIFICATION_ACCESS_DISCLOSURE_TITLE

/** A screen pushed over the tabs, with the title the top app bar shows for it. */
internal enum class AppDestination(val title: String) {
  SETTINGS("Settings"),
  ABOUT("About"),
  LICENSES("Open-source licenses"),
  BACKGROUND_LOCATION_DISCLOSURE(BACKGROUND_LOCATION_DISCLOSURE_TITLE),
  NOTIFICATION_DISCLOSURE(NOTIFICATION_ACCESS_DISCLOSURE_TITLE),
}

/**
 * The screens pushed over the current tab, bottom first; empty when a tab is showing. It survives
 * recreation.
 */
internal class AppBackStack(initial: List<AppDestination> = emptyList()) {
  var entries by mutableStateOf(initial)
    private set

  /** The screen on top, or null when a tab is showing. */
  val top: AppDestination?
    get() = entries.lastOrNull()

  fun push(destination: AppDestination) {
    entries = entries + destination
  }

  fun pop() {
    entries = entries.dropLast(1)
  }

  /** Back to the tab, as a tab tap does. */
  fun clear() {
    entries = emptyList()
  }

  companion object {
    val saver: Saver<AppBackStack, List<String>> =
      Saver(
        save = { stack -> stack.entries.map { it.name } },
        restore = { names -> AppBackStack(names.map { AppDestination.valueOf(it) }) },
      )
  }
}

@Composable
internal fun rememberAppBackStack(): AppBackStack =
  rememberSaveable(saver = AppBackStack.saver) { AppBackStack() }

/**
 * System Back pops one pushed screen; a disclosure popped this way acts as "Not now". Registered
 * before the content so screens' own handlers, composed later, take precedence.
 */
@OptIn(
  ExperimentalComposeUiApi::class,
) // ui-backhandler's BackHandler is still marked experimental in CMP 1.12
@Composable
internal fun BackStackHandler(backStack: AppBackStack) {
  BackHandler(enabled = backStack.top != null) { backStack.pop() }
}
