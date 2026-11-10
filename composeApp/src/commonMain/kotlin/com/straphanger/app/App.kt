package com.straphanger.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag

@Composable
fun App() {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF0B0E14),
            surface = Color(0xFF131822),
            primary = Color(0xFF0039A6)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0E14))
                .testTag("straphanger_root"),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Straphanger NYC Subway Tracker",
                color = Color.White
            )
        }
    }
}
