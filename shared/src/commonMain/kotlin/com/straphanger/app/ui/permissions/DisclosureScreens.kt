package com.straphanger.app.ui.permissions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.straphanger.app.ui.theme.MtaColors

const val BACKGROUND_LOCATION_DISCLOSURE_TEXT =
    "Straphanger uses your location in the background to notice when you enter and leave subway stations, " +
        "even when the app is closed. Your location is used only on this phone to record rides and is never sent anywhere."

const val NOTIFICATION_ACCESS_DISCLOSURE_TEXT =
    "Straphanger can read Google Maps transit navigation notifications to record the ride you're taking. " +
        "It ignores notifications from every other app, and nothing leaves your phone."

/** Shown before the system location permission prompts. "Continue" is the only path to them. */
@Composable
fun BackgroundLocationDisclosure(onContinue: () -> Unit, onNotNow: () -> Unit) {
    DisclosureScreen(
        title = "Location in the background",
        body = BACKGROUND_LOCATION_DISCLOSURE_TEXT,
        screenTag = "background_location_disclosure",
        onContinue = onContinue,
        onNotNow = onNotNow
    )
}

/** Shown before the system notification access settings screen opens. */
@Composable
fun NotificationAccessDisclosure(onContinue: () -> Unit, onNotNow: () -> Unit) {
    DisclosureScreen(
        title = "Google Maps notifications",
        body = NOTIFICATION_ACCESS_DISCLOSURE_TEXT,
        screenTag = "notification_access_disclosure",
        onContinue = onContinue,
        onNotNow = onNotNow
    )
}

@Composable
private fun DisclosureScreen(
    title: String,
    body: String,
    screenTag: String,
    onContinue: () -> Unit,
    onNotNow: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MtaColors.DarkBg)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .testTag(screenTag),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Text(text = body, color = Color.White, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.testTag("${screenTag}_text"))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(onClick = onNotNow, modifier = Modifier.testTag("disclosure_not_now")) {
                Text("Not now", color = Color.White)
            }
            Button(
                onClick = onContinue,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0039A6)),
                modifier = Modifier.testTag("disclosure_continue")
            ) {
                Text("Continue")
            }
        }
    }
}
