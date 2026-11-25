package com.straphanger.app.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.straphanger.app.PRIVACY_POLICY_URL
import com.straphanger.app.ui.theme.Spacing
import com.straphanger.app.ui.theme.quietButtonColors

const val ICONS_CREDIT_TEXT = "Icons: Material Symbols by Google (Apache License 2.0)."

const val NOT_AFFILIATED_TEXT =
  "Unofficial app. Not affiliated with or endorsed by the Metropolitan Transportation Authority."

/** Attribution text drafted in tools/gtfs-import/README.md. */
fun mtaAttributionText(sourceDate: String): String =
  "Subway data from the Metropolitan Transportation Authority (MTA) GTFS feed and the MTA Subway Stations " +
    "dataset on data.ny.gov, downloaded $sourceDate. Straphanger is not affiliated with or endorsed by the MTA. " +
    "The data is provided as is and may not be accurate, complete or current."

/** Attribution text drafted in tools/basemap-import/README.md. */
fun basemapAttributionText(sourceDate: String): String =
  "Map of New York City from NYC Open Data: Borough Boundaries (Department of City Planning), " +
    "Parks Properties (NYC Parks) and the Street Centerline (Office of Technology and Innovation), " +
    "downloaded $sourceDate. Provided as is, without warranty."

/**
 * The About screen's content; the app's top bar carries its title and Back.
 *
 * @param basemapSourceDate the bundled basemap's download date; null when it is not loaded, and
 *   then its attribution is not shown.
 * @param licensesAvailable whether the platform has a license list for [LicensesScreen]; when not,
 *   the licenses button is disabled.
 */
@Composable
fun AboutScreen(
  appVersion: String,
  sourceDate: String,
  onOpenLicenses: () -> Unit,
  basemapSourceDate: String? = null,
  licensesAvailable: Boolean = false,
) {
  Column(
    modifier =
      Modifier.fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .verticalScroll(rememberScrollState())
        .padding(Spacing.lg)
        .testTag("about_screen"),
    verticalArrangement = Arrangement.spacedBy(Spacing.md),
  ) {
    Text(
      "About Straphanger",
      color = MaterialTheme.colorScheme.onSurface,
      style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
    )
    Text(
      "Version $appVersion",
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.testTag("about_version"),
    )
    DataSourceText(sourceDate)
    if (basemapSourceDate != null) {
      Text(
        basemapAttributionText(basemapSourceDate),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("about_basemap_attribution"),
      )
    }
    Text(
      ICONS_CREDIT_TEXT,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.testTag("about_icons_credit"),
    )
    PrivacyPolicyLink()
    FilledTonalButton(
      onClick = onOpenLicenses,
      enabled = licensesAvailable,
      colors = quietButtonColors(),
      modifier = Modifier.testTag("about_licenses_btn"),
    ) {
      Text(
        if (licensesAvailable) "Open-source licenses"
        else "Open-source licenses are not listed on this platform",
      )
    }
  }
}

/**
 * The open-source license list, pushed over About.
 *
 * @param licensesContent the list; the platform supplies it (AboutLibraries on Android).
 */
@Composable
fun LicensesScreen(licensesContent: @Composable () -> Unit) {
  Column(
    modifier =
      Modifier.fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .testTag("licenses_screen"),
  ) {
    licensesContent()
  }
}

/** The data date, the MTA attribution and the disclaimer. */
@Composable
private fun DataSourceText(sourceDate: String) {
  Text(
    "Subway data: MTA GTFS, $sourceDate",
    color = MaterialTheme.colorScheme.onSurface,
    style = MaterialTheme.typography.titleSmall,
    modifier = Modifier.testTag("about_data_date"),
  )
  Text(
    mtaAttributionText(sourceDate),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.bodyMedium,
    modifier = Modifier.testTag("about_attribution"),
  )
  Text(
    NOT_AFFILIATED_TEXT,
    color = MaterialTheme.colorScheme.onSurface,
    style = MaterialTheme.typography.bodyMedium,
    modifier = Modifier.testTag("about_disclaimer"),
  )
}

@Composable
private fun PrivacyPolicyLink() {
  val uriHandler = LocalUriHandler.current
  TextButton(
    onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) },
    modifier = Modifier.testTag("about_privacy_policy_link"),
  ) {
    Text("Privacy policy", textDecoration = TextDecoration.Underline)
  }
}
