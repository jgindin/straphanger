package com.straphanger.app.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.straphanger.app.PRIVACY_POLICY_URL
import com.straphanger.app.ui.theme.MtaColors

const val NOT_AFFILIATED_TEXT =
  "Unofficial app. Not affiliated with or endorsed by the Metropolitan Transportation Authority."

/** Attribution text drafted in tools/gtfs-import/README.md. */
fun mtaAttributionText(sourceDate: String): String =
  "Subway data from the Metropolitan Transportation Authority (MTA) GTFS feed and the MTA Subway Stations " +
    "dataset on data.ny.gov, downloaded $sourceDate. Straphanger is not affiliated with or endorsed by the MTA. " +
    "The data is provided as is and may not be accurate, complete or current."

/**
 * @param licensesContent the open-source license list; the platform supplies it (AboutLibraries on
 *   Android). Null where the platform has none.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AboutScreen(
  appVersion: String,
  sourceDate: String,
  onBack: () -> Unit,
  licensesContent: (@Composable () -> Unit)? = null,
) {
  var showLicenses by rememberSaveable { mutableStateOf(false) }
  // Back closes the licenses view first; otherwise App's handler closes About.
  BackHandler(enabled = showLicenses) { showLicenses = false }
  if (showLicenses && licensesContent != null) {
    LicensesView(onBack = { showLicenses = false }, licensesContent = licensesContent)
  } else {
    AboutContent(
      appVersion = appVersion,
      sourceDate = sourceDate,
      onBack = onBack,
      licensesAvailable = licensesContent != null,
      onOpenLicenses = { showLicenses = true },
    )
  }
}

@Composable
private fun LicensesView(onBack: () -> Unit, licensesContent: @Composable () -> Unit) {
  Column(
    modifier = Modifier.fillMaxSize().background(MtaColors.DarkBg).testTag("licenses_screen")
  ) {
    BackArrowButton(
      onClick = onBack,
      modifier = Modifier.padding(8.dp).testTag("licenses_back_btn"),
    )
    licensesContent()
  }
}

@Composable
private fun AboutContent(
  appVersion: String,
  sourceDate: String,
  onBack: () -> Unit,
  licensesAvailable: Boolean,
  onOpenLicenses: () -> Unit,
) {
  Column(
    modifier =
      Modifier.fillMaxSize()
        .background(MtaColors.DarkBg)
        .verticalScroll(rememberScrollState())
        .padding(16.dp)
        .testTag("about_screen"),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    BackArrowButton(onClick = onBack, modifier = Modifier.testTag("about_back_btn"))
    Text(
      "About Straphanger",
      color = Color.White,
      fontSize = 20.sp,
      fontWeight = FontWeight.ExtraBold,
    )
    Text(
      "Version $appVersion",
      color = MtaColors.TextSecondary,
      fontSize = 13.sp,
      modifier = Modifier.testTag("about_version"),
    )
    DataSourceText(sourceDate)
    PrivacyPolicyLink()
    Button(
      onClick = onOpenLicenses,
      enabled = licensesAvailable,
      colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B3548)),
      modifier = Modifier.testTag("about_licenses_btn"),
    ) {
      Text(
        if (licensesAvailable) "Open-source licenses"
        else "Open-source licenses are not listed on this platform"
      )
    }
  }
}

/** The data date, the MTA attribution and the disclaimer. */
@Composable
private fun DataSourceText(sourceDate: String) {
  Text(
    "Subway data: MTA GTFS, $sourceDate",
    color = Color.White,
    fontSize = 14.sp,
    fontWeight = FontWeight.SemiBold,
    modifier = Modifier.testTag("about_data_date"),
  )
  Text(
    mtaAttributionText(sourceDate),
    color = MtaColors.TextSecondary,
    fontSize = 13.sp,
    modifier = Modifier.testTag("about_attribution"),
  )
  Text(
    NOT_AFFILIATED_TEXT,
    color = Color.White,
    fontSize = 13.sp,
    modifier = Modifier.testTag("about_disclaimer"),
  )
}

@Composable
private fun PrivacyPolicyLink() {
  val uriHandler = LocalUriHandler.current
  Text(
    "Privacy policy",
    color = Color(0xFF8AB4F8),
    fontSize = 14.sp,
    textDecoration = TextDecoration.Underline,
    modifier =
      Modifier.clickable { uriHandler.openUri(PRIVACY_POLICY_URL) }
        .padding(vertical = 8.dp)
        .testTag("about_privacy_policy_link"),
  )
}
