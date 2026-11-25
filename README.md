# Straphanger

Straphanger records the NYC subway rides you take and shows which lines and stations you have covered. You can log a ride by hand, import a Google Maps Timeline file, share a route from Google Maps, or (on Android, if you turn it on) record rides from station geofences. The app shows the rides on a map, computes statistics and achievements, and keeps everything in a file on the device. It is an unofficial app and is not affiliated with or endorsed by the Metropolitan Transportation Authority (MTA).

The code is Kotlin Multiplatform with Compose Multiplatform UI. The Android app is the only one that has been built and run. There is no Xcode project, and nothing has been built or tested on iOS.

## Feature status

`Works` means it was built and exercised. `Partial` means the code exists but was not built or run on that platform. `Not started` means there is no code.

| Feature | Android | iOS | Notes |
| --- | --- | --- | --- |
| Subway map with ridden lines, "Line colors" / "Heatmap" modes, line filter chips | Works | Partial | Shared Compose code; never built for iOS. |
| Basemap under the subway map (land, parks, highways, major streets) | Works | Partial | Bundled NYC Open Data, drawn offline with no tiles, keys or network requests; it is always shown. Shared code; never built for iOS. |
| Manual ride logging, including transfers | Works | Partial | The Log ride button on the Rides tab opens a sheet: From, an optional change and To, each chosen by searching, plus one-tap chips for your own most frequent and recent trips. The app picks express or local; Edit on the ride changes it. Swiping a ride away deletes it, with Undo. Shared code; never built for iOS. |
| Statistics, achievements, stations vs. complexes toggle | Works | Partial | Shared code; never built for iOS. |
| Local ride storage (atomic JSON file, previous copy kept, corrupt file set aside) | Works | Partial | `rides.json` in app storage; each save keeps the previous file as `rides.json.bak`, which is loaded if `rides.json` is empty or unreadable. The iOS file location was never run. |
| Backup export and restore | Works | Partial | JSON file chosen through FileKit. Export and restore were checked by hand on the emulator; the file picker is not covered by automated tests. Android Auto Backup also includes `rides.json`. |
| Google Maps Timeline file import (three formats) | Works | Partial | File picker; merges transfers within 15 minutes and skips duplicates. |
| Status tab: next trains at a station and MTA service alerts | Works | Partial | Reads the MTA's public GTFS-Realtime feeds (no key) every 30 seconds while the tab is open; shows when the data is old or the MTA cannot be reached. Shared code; never built for iOS. Stations can be starred as favorites (kept on the device, not in backups). |
| Commute alerts: live departures at a chosen time and days | Works | Not started | Android only. A commute is one line, station, direction, days and time; at that time the phone posts the next live departures, fetched from the MTA's public feeds when the alarm fires. Exact on the minute with "Alarms & reminders" allowed, otherwise up to a few minutes late. Settings shows whether notifications and exact alarms are allowed, and opens the system setting when one is not. "See trains" opens the Status tab filtered to that line and direction. Commutes are kept on the device in `commutes.json`, not in backups. Not checked on a real phone. |
| Commute widget: live departures on the home screen during a commute window | Works | Not started | Android only. A commute can have an end time ("Show on widget until"); from its alert time to then, a Glance widget refreshes every 2 minutes while the screen is on and shows the next departures, fetched from the MTA's public feeds. With no times from the last 15 minutes it shows "Tap to refresh" (or "Couldn't get train times" after a failed fetch); outside a window it fetches only when tapped. Refreshes are on time with "Alarms & reminders" allowed; without it a refresh, and the start of a window, may be up to about 10 minutes late. Several widgets can be placed, each with its own commutes. Widget state is kept on the device in `commute_widgets.json`. Not checked on a real phone. |
| Google Maps share intent and short-link resolution | Works | Partial | Short links are followed over the network, and the Status tab, commute alerts and the commute widget read the MTA's feeds; these are the only network uses. The iOS URL-scheme handler in `iosApp` was never built. |
| Google Maps navigation notification listener | Partial: not verified against real Google Maps notifications | Not started | Android only. Needs notification access, granted on a settings screen. Records one ride per navigation notification, from its first post to its removal; the text format it parses was not checked against current Google Maps. |
| Passive geofence capture | Works | Not started | Rolling window of 18 stations plus 1 anchor, with a chain rule and disclosure screens. Verified with simulated location on the emulator, not with real travel. iOS geofencing is not implemented. |
| Ride-recorded notification | Works | Not started | Android only; off by default, switch in Settings, behind the gear at the top right (asks for permission on Android 13+). Posted on the phone when passive capture or Google Maps navigation records a ride, even with the app closed: for passive capture about 6 to 15 minutes after the ride, because detecting its end takes that long, and for Google Maps navigation when navigation ends. With the switch on, allowing notifications in system settings after blocking them brings ride notifications back. Tapping it opens the Rides tab with the ride highlighted. Not checked on a real phone. |
| About screen, privacy policy, data attribution | Works | Partial | Shared code. |
| Cloud sync | Not started | Not started | Firebase was removed. There is no account and no server. |
| Play Store release pipeline | Works | Not started | See [docs/RELEASING.md](docs/RELEASING.md). |

## Setup

- JDK 17.
- Android SDK with platform 37 (`compileSdk`; `targetSdk` is 36, `minSdk` is 26).
- Gradle 9.9.1 (wrapper), Kotlin 2.4.21, Android Gradle Plugin 9.5.1, Compose Multiplatform 1.12.2.
- iOS targets are `iosArm64` and `iosSimulatorArm64`. Building them needs macOS with Xcode; no Xcode version has been used, because no iOS build has been made. `iosX64` is not a target because Compose 1.12 does not publish it.

## Build and test

```bash
./gradlew :shared:testAndroidHostTest             # shared unit tests on the JVM
./gradlew :androidApp:assembleDebug               # debug APK
./gradlew :androidApp:connectedDebugAndroidTest   # instrumented tests; needs a device or emulator
./gradlew :shared:iosSimulatorArm64Test           # iOS simulator tests; macOS only, not run so far
./gradlew ktfmtCheck                              # formatting check (Google style, 100 columns)
./gradlew ktfmtFormat                             # fix formatting
./gradlew detekt                                  # static analysis (config/detekt/detekt.yml)
scripts/check-listing.sh                          # Play listing text checks
```

Modules: `:shared` (library), `:androidApp`, `:tools:gtfs-import` (JVM generator for the network data), `:tools:basemap-import` (JVM generator for the basemap; `./gradlew :tools:basemap-import:test`). Release builds, signing and the Play listing are described in [docs/RELEASING.md](docs/RELEASING.md).

## Data sources

The subway network (496 stations in 445 complexes) is generated from the MTA's static GTFS feed and the MTA Subway Stations dataset on data.ny.gov, downloaded 2026-11-10. The generator, source URLs, checksums and terms are in [tools/gtfs-import/README.md](tools/gtfs-import/README.md). The attribution shown in the app is:

> Subway data from the Metropolitan Transportation Authority (MTA) GTFS feed and the MTA Subway Stations dataset on data.ny.gov. Straphanger is not affiliated with or endorsed by the MTA.

The basemap (borough land, parks, highways and major streets) is generated from three NYC Open Data datasets, downloaded 2026-11-15: Borough Boundaries clipped to the shoreline (Department of City Planning), Parks Properties (NYC Parks) and the NYC Street Centerline (Office of Technology and Innovation). NYC Open Data has no use restrictions under the City's Open Data Law; no OpenStreetMap data is used. The generator, download commands, checksums and terms are in [tools/basemap-import/README.md](tools/basemap-import/README.md). The About screen shows:

> Map of New York City from NYC Open Data: Borough Boundaries (Department of City Planning), Parks Properties (NYC Parks) and the Street Centerline (Office of Technology and Innovation), downloaded 2026-11-15. Provided as is, without warranty.

The privacy policy is [docs/privacy-policy.md](docs/privacy-policy.md).

## Known limitations

- Passive capture needs background location access. Google Play restricts this permission and reviews apps that request it, so the app shows a disclosure first and the Play Console declaration must match it.
- There is no sync between devices or platforms. Move rides with the backup file.
- After a reboot the app registers the station geofences again, if passive capture is on and fine and background location are granted. It centres the window on the last known location, or on the last registered window when the phone has no location yet; with neither, nothing is registered until the app is opened.
- When the app process is not running, leaving the window anchor still moves the geofence window: the geofence receiver registers a new window around the location that triggered the exit.
- Force stop stops passive capture. Android delivers no geofence events or boot broadcast to a force-stopped app until the user opens it again.
- The notification for a passive capture ride arrives about 6 to 15 minutes after the ride ends, not at arrival: the app waits that long to be sure the ride is over. A Google Maps navigation ride is notified when navigation ends.
- A single-stop ride shorter than about 0.5 mi is not recorded automatically.
- The MTA data terms say the data may not be modified. The generator rewrites the feed into the app's own JSON format, so the maintainer must review the terms before a public release. This is not legal advice.
- The app draws colored route circles with letters that resemble MTA route bullets. The MTA asserts trademarks on its symbols; the maintainer should check this before a public release.
- iOS is untested, as the status table shows.
