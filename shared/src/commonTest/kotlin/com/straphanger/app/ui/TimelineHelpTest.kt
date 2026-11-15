package com.straphanger.app.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimelineHelpTest {
  @Test
  fun theAndroidTextNeverMentionsIosAndTheIosTextNeverMentionsAndroid() {
    val android = timelineImportDescription(PhonePlatform.ANDROID)
    val ios = timelineImportDescription(PhonePlatform.IOS)
    assertFalse(android.contains("iOS", ignoreCase = true) || android.contains("iPhone"))
    assertFalse(ios.contains("Android", ignoreCase = true))
    assertTrue(android.contains("Timeline.json"))
  }

  @Test
  fun theHelpLinkPointsAtTheExportInstructions() {
    assertTrue(TIMELINE_EXPORT_HELP_URL.endsWith("docs/export-timeline.md"))
  }
}
