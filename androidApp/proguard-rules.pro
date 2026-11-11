# No app-level keep rules are needed. Verified with connectedReleaseAndroidTest against the minified app:
# - kotlinx-serialization, Ktor, OkHttp, FileKit, Compose resources and play-services-location ship consumer rules.
# - GeofenceBroadcastReceiver and GoogleMapsTransitNotificationListener are kept via the manifest.
# Add a rule here only with a comment naming the class and why R8 cannot see its use.
