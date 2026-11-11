# Straphanger

Straphanger records the NYC subway rides you take and shows which lines and stations you have covered. You can log a ride by hand, import a Google Maps Timeline file, share a route from Google Maps, or (on Android, if you turn it on) record rides from station geofences. The app shows the rides on a map, computes statistics and achievements, and keeps everything in a file on the device. It is an unofficial app and is not affiliated with or endorsed by the Metropolitan Transportation Authority (MTA).

The code is Kotlin Multiplatform with Compose Multiplatform UI. The Android app is the only one that has been built and run. There is no Xcode project, and nothing has been built or tested on iOS.

## Feature status

`Works` means it was built and exercised. `Partial` means the code exists but was not built or run on that platform. `Not started` means there is no code.

| Feature | Android | iOS | Notes |
| --- | --- | --- | --- |
| Subway map with ridden lines, frequency heatmap, line filter | Works | Partial | Shared Compose code; never built for iOS. |
| Manual ride logging, including transfers and express/local | Works | Partial | Shared code; never built for iOS. |
| Statistics, achievements, stations vs. complexes toggle | Works | Partial | Shared code; never built for iOS. |
| Local ride storage (atomic JSON file, corrupt file set aside) | Works | Partial | `rides.json` in app storage. The iOS file location was never run. |
| Backup export and restore | Works | Partial | JSON file chosen through FileKit. Android Auto Backup also includes `rides.json`. |
| Google Maps Timeline file import (three formats) | Works | Partial | File picker; merges transfers within 15 minutes and skips duplicates. |
| Google Maps share intent and short-link resolution | Works | Partial | Short links are followed over the network; this is the only network use. The iOS URL-scheme handler in `iosApp` was never built. |
| Google Maps navigation notification listener | Works | Not started | Android only. Needs notification access, granted on a settings screen. |
| Passive geofence capture | Works | Not started | Rolling window of 18 stations plus 1 anchor, with a chain rule and disclosure screens. iOS geofencing is not implemented. |
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
scripts/check-listing.sh                          # Play listing text checks
```

Modules: `:shared` (library), `:androidApp`, `:tools:gtfs-import` (JVM generator for the network data). Release builds, signing and the Play listing are described in [docs/RELEASING.md](docs/RELEASING.md).

## Data sources

The subway network (496 stations in 445 complexes) is generated from the MTA's static GTFS feed and the MTA Subway Stations dataset on data.ny.gov, downloaded 2026-11-10. The generator, source URLs, checksums and terms are in [tools/gtfs-import/README.md](tools/gtfs-import/README.md). The attribution shown in the app is:

> Subway data from the Metropolitan Transportation Authority (MTA) GTFS feed and the MTA Subway Stations dataset on data.ny.gov. Straphanger is not affiliated with or endorsed by the MTA.

The privacy policy is [docs/privacy-policy.md](docs/privacy-policy.md).

## Known limitations

- Passive capture needs background location access. Google Play restricts this permission and reviews apps that request it, so the app shows a disclosure first and the Play Console declaration must match it.
- There is no sync between devices or platforms. Move rides with the backup file.
- Geofences are not registered again after a reboot until the app is opened, so passive capture stops until then.
- A single-stop ride shorter than about 0.5 mi is not recorded automatically.
- The MTA data terms say the data may not be modified. The generator rewrites the feed into the app's own JSON format, so the maintainer must review the terms before a public release. This is not legal advice.
- The app draws colored route circles with letters that resemble MTA route bullets. The MTA asserts trademarks on its symbols; the maintainer should check this before a public release.
- iOS is untested, as the status table shows.
