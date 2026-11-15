package com.straphanger.app.ui

/** Page with the full steps for getting a Timeline file out of Google Maps. */
const val TIMELINE_EXPORT_HELP_URL =
  "https://github.com/jgindin/straphanger/blob/main/docs/export-timeline.md"

/** What the Timeline import accepts, for [platform] only. */
fun timelineImportDescription(platform: PhonePlatform): String {
  val source =
    when (platform) {
      PhonePlatform.ANDROID -> "the Timeline.json file that Google Maps on this phone exports"
      PhonePlatform.IOS -> "the Timeline file that Google Maps on this iPhone exports"
    }
  return "Imports $source, or a Google Takeout Semantic Location History file. Transfers made " +
    "within 15 minutes are merged into one ride, and rides already stored are skipped."
}
