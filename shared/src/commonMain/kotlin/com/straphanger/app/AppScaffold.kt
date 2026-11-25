package com.straphanger.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.straphanger.app.ui.AppTab
import com.straphanger.app.ui.theme.Spacing
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import straphanger.shared.generated.resources.Res
import straphanger.shared.generated.resources.ic_arrow_back
import straphanger.shared.generated.resources.ic_bar_chart
import straphanger.shared.generated.resources.ic_directions_subway
import straphanger.shared.generated.resources.ic_list
import straphanger.shared.generated.resources.ic_map
import straphanger.shared.generated.resources.ic_settings

/**
 * The Material scaffold: the top app bar, the snackbar host, the bottom navigation bar and, between
 * them, the storage error banner over [content]. Insets come from the scaffold and its bars.
 *
 * @param pushed the pushed screen on top, or null when [activeTab] is showing.
 * @param pendingReviewCount rides waiting for review, shown as a badge on the Rides tab.
 * @param storageError why the saved rides could not be read; null when they were.
 * @param settingsAvailable whether the gear is shown; it is hidden until the network has loaded.
 */
@Composable
internal fun AppScaffold(
  activeTab: AppTab,
  pushed: AppDestination?,
  pendingReviewCount: Int,
  storageError: String?,
  settingsAvailable: Boolean,
  snackbarHostState: SnackbarHostState,
  onOpenSettings: () -> Unit,
  onBack: () -> Unit,
  onSelectTab: (AppTab) -> Unit,
  content: @Composable () -> Unit,
) {
  Scaffold(
    modifier = Modifier.fillMaxSize().testTag("straphanger_root"),
    topBar = { AppTopBar(activeTab, pushed, onOpenSettings.takeIf { settingsAvailable }, onBack) },
    bottomBar = { AppNavigationBar(activeTab, pendingReviewCount, onSelectTab) },
    snackbarHost = {
      SnackbarHost(snackbarHostState, modifier = Modifier.testTag("backup_snackbar"))
    },
    containerColor = MaterialTheme.colorScheme.background,
    // A punch-hole or notch in landscape must not cover the content's sides.
    contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
  ) { innerPadding ->
    // The keyboard pushes the content up (the Status search results stay visible); the bars'
    // padding already counted is not added twice.
    Column(
      modifier =
        Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding).imePadding(),
    ) {
      storageError?.let { StorageErrorBanner(it) }
      Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
    }
  }
}

/**
 * The current screen's name; a gear to Settings on the tabs, Back on a pushed screen.
 *
 * @param onOpenSettings null hides the gear.
 */
@OptIn(ExperimentalMaterial3Api::class) // TopAppBar is still experimental in Material 3
@Composable
private fun AppTopBar(
  activeTab: AppTab,
  pushed: AppDestination?,
  onOpenSettings: (() -> Unit)?,
  onBack: () -> Unit,
) {
  TopAppBar(
    title = {
      Text(
        text = pushed?.title ?: activeTab.label,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag("header_title"),
      )
    },
    navigationIcon = {
      if (pushed != null) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("top_bar_back")) {
          Icon(painterResource(Res.drawable.ic_arrow_back), contentDescription = "Back")
        }
      }
    },
    actions = {
      if (pushed == null && onOpenSettings != null) {
        IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("top_bar_settings")) {
          Icon(painterResource(Res.drawable.ic_settings), contentDescription = "Settings")
        }
      }
    },
    colors =
      TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
      ),
    windowInsets =
      TopAppBarDefaults.windowInsets.union(
        WindowInsets.displayCutout.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
      ),
    modifier = Modifier.testTag("top_app_bar"),
  )
}

private fun AppTab.icon(): DrawableResource =
  when (this) {
    AppTab.STATUS -> Res.drawable.ic_directions_subway
    AppTab.MAP -> Res.drawable.ic_map
    AppTab.RIDES -> Res.drawable.ic_list
    AppTab.STATS -> Res.drawable.ic_bar_chart
  }

/** The four tabs; the Rides tab carries a badge with how many rides need review. */
@Composable
private fun AppNavigationBar(
  activeTab: AppTab,
  pendingReviewCount: Int,
  onSelectTab: (AppTab) -> Unit,
) {
  NavigationBar(
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    windowInsets =
      NavigationBarDefaults.windowInsets.union(
        WindowInsets.displayCutout.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
      ),
    modifier = Modifier.testTag("navigation_bar"),
  ) {
    AppTab.entries.forEach { tab ->
      val badgeCount = if (tab == AppTab.RIDES) pendingReviewCount else 0
      NavigationBarItem(
        selected = activeTab == tab,
        onClick = { onSelectTab(tab) },
        icon = {
          BadgedBox(
            badge = {
              if (badgeCount > 0) {
                Badge(
                  modifier =
                    Modifier.testTag("nav_badge_${tab.name}").clearAndSetSemantics {
                      contentDescription = reviewBadgeDescription(badgeCount)
                    },
                ) {
                  Text(reviewBadgeText(badgeCount))
                }
              }
            },
          ) {
            Icon(painterResource(tab.icon()), contentDescription = null)
          }
        },
        label = { Text(tab.label) },
        alwaysShowLabel = true,
        modifier = Modifier.testTag("nav_tab_${tab.name}"),
      )
    }
  }
}

/** The Rides badge's text: the count, or "99+" above 99. */
internal fun reviewBadgeText(count: Int): String =
  if (count > MAX_BADGE_COUNT) "$MAX_BADGE_COUNT+" else "$count"

/** What a screen reader says for the Rides badge, such as "1 ride needs review". */
internal fun reviewBadgeDescription(count: Int): String =
  if (count == 1) "1 ride needs review" else "$count rides need review"

private const val MAX_BADGE_COUNT = 99

@Composable
private fun StorageErrorBanner(storageError: String) {
  Box(
    modifier =
      Modifier.fillMaxWidth()
        .background(MaterialTheme.colorScheme.errorContainer)
        .border(1.dp, MaterialTheme.colorScheme.error)
        .padding(Spacing.md)
        .testTag("storage_error_banner"),
  ) {
    Text(
      text = storageError,
      color = MaterialTheme.colorScheme.onErrorContainer,
      style = MaterialTheme.typography.bodySmall,
    )
  }
}
