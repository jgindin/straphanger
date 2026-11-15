package com.straphanger.app.ui

/** Which phone platform the app runs on; the Timeline import text names only that one. */
enum class PhonePlatform {
  ANDROID,
  IOS,
}

/** The platform this build runs on. */
expect val currentPhonePlatform: PhonePlatform
