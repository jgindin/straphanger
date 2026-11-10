# Straphanger — NYC Subway Ride & Coverage Tracker (Kotlin Multiplatform)

**Straphanger** is a native **Android & iOS** application built with **Kotlin Multiplatform (KMP)** and **Compose Multiplatform** that tracks every trip you take on the NYC Subway, lights up the subway network map in vibrant official MTA line colors (or a ride-frequency heatmap), and computes deep system analytics.

---

## Key Features

### 1. Dark-Mode NYC Subway Map & Coverage Coloring
- **Official MTA Line Colors Mode**: Unridden physical tracks render in muted dark slate (`#283142`) while ridden corridors light up in vibrant official MTA trunk colors (`#EE352E`, `#00933C`, `#B933AD`, `#0039A6`, `#FF6319`, `#6CBE45`, `#996633`, `#A7A9AC`, `#FCCC0A`, `#808183`).
- **Ride Frequency Heatmap Mode**: Colors ridden segments by how many times you've ridden them (`1 ride` Cyan → `2–3 rides` Neon Green → `4–5 rides` Gold → `6+ rides` Crimson).
- **Per-Line Bullet Filter**: Filter the map by any individual subway service (`1`–`7`, `A`–`Z`, `S`, `SIR`) or tap any station to inspect visit status and log a trip.
- **60fps Pre-Projected Geometry & Zoom LOD**: Pre-projects station and segment coordinates once at load time with viewport frustum culling and 3 Zoom Levels of Detail.

### 2. Multi-Source Google Maps & On-Device Ride Capture
- **Tri-Format Google Maps `Timeline.json` Importer (`TimelineJsonImporter.kt`)**:
  - Supports **Android on-device** `Timeline.json` (`semanticSegments` with `"40.7347°, -73.9899°"` degree strings).
  - Supports **iOS on-device** `Timeline.json` (`semanticSegments` with `"geo:40.7347,-73.9899"` URIs).
  - Supports **Google Takeout** `Semantic Location History` (`timelineObjects` → `activitySegment` with `latitudeE7`/`longitudeE7` integers and `transitPath`).
  - Automatically merges consecutive `IN_SUBWAY` segments within a **15-minute in-system transfer window** into a single multi-leg ride and deduplicates against existing rides.
- **Live Google Maps Transit Notification Listener & Share Target**:
  - **Android**: `GoogleMapsTransitNotificationListener` (`NotificationListenerService`) automatically captures active Google Maps transit navigation notifications, plus `Intent.ACTION_SEND` for sharing routes from Google Maps.
  - **iOS**: `straphanger://share` / `nycsubway://share` URL scheme handler.
  - **Short-Link Redirect Unpacker (`GoogleMapsShareParser.kt`)**: Follows HTTP `302` `Location` headers on opaque `maps.app.goo.gl` links via Ktor to extract origin/destination stations.
- **Dynamic Rolling Station Geofencing (`DynamicGeofenceWindowManager.kt`)**:
  - Maintains a rolling window of the **18 nearest station complexes + 1 macro anchor geofence (19 total)** so the app stays strictly under **iOS `CoreLocation`'s 20-region limit** and **Android's 100-geofence limit**.
  - Pairs station entry/exit with underground signal-drop detection and flags ambiguous Express vs. Local rides (`4`/`5` Express vs. `6` Local) with a one-tap **"Needs Review"** confirmation banner.

### 3. Time-Aware Express/Local Routing & Multi-Leg Transfers (`SubwayGraphEngine.kt`)
- **Multi-Leg Transfers Count as 1 Ride**: A single fare entry with free in-system transfers (e.g., `L` from *Bedford Ave* → *14 St–Union Sq* → `4 Express` to *86 St*) is stored as `1 RideEntity` with `2 RideLegEntity` rows — counting as **1 ride** in *Total Rides Since Tracking Started* while crediting **both** the `L` and `4` lines with exact mileage and completion %.
- **Time-of-Day Service Periods**: Automatically switches routes like the `A`, `2`, and `4` from daytime Express to **Late Night Local** (`11:45 PM – 5:30 AM`).
- **Express Stop Skipping**: Riding an Express train colors the entire physical track corridor traversed and counts its full mileage, while excluding skipped local stations from *Stations Stopped At*.

### 4. Comprehensive Statistics & Achievements (`SubwayStatsCalculator.kt`)
- **Headline Stats**: Total Rides, Total Miles Ridden, Unique System Miles Ridden, System Completion %, and Total Hours Underground.
- **Interactive Station Counting Toggles**:
  - **472 Individual MTA Stations** vs. **424 Connected Station Complexes**.
  - **All Stations Stopped At** (excluding stations skipped by an express train) vs. **Boarded / Exited / Transferred Only**.
- **Breakdowns & Badges**: Per-line completion bars with official MTA bullets, 5-borough breakdown, top stations & O-D pairs, monthly mileage trends, and 8 unlockable NYC Subway Achievements.

### 5. Local-First Storage + Free Firebase Cloud Sync
- Works 100% offline underground out of the box via `SubwayDao`, paired with a pure-KMP Ktor **Firebase Auth & Cloud Firestore REST Sync Client (`FirestoreKmpSyncClient.kt`)** compatible with Firebase's free **Spark Plan** and portable JSON/CSV backup export/import.

---

## Project Structure

```text
straphanger/
├── .github/workflows/kmp-ci.yml       # Android APK, Android Emulator UI Tests, & iOS Simulator CI
├── composeApp/
│   ├── src/commonMain/kotlin/com/straphanger/app/
│   │   ├── data/gtfs/                 # NycSubwayNetworkCatalog, SubwayGraphEngine, SubwayModels
│   │   ├── data/local/                # Entities (Ride -> RideLegs -> Segments & Stops), SubwayDao
│   │   ├── data/capture/              # TimelineJsonImporter, GoogleMapsShareParser, DynamicGeofenceWindowManager
│   │   ├── data/sync/                 # FirestoreKmpSyncClient, BackupExportManager
│   │   ├── domain/stats/              # SubwayStatsCalculator, AchievementEngine
│   │   └── ui/                        # SubwayMapScreen, StatsDashboardScreen, RidesAndCaptureScreen, StraphangerViewModel
│   ├── src/commonTest/                # Multiplatform unit test suite
│   ├── src/androidMain/               # MainActivity, GoogleMapsTransitNotificationListener, AndroidManifest.xml
│   ├── src/androidInstrumentedTest/   # SubwayTrackerEmulatorTest (Compose UI Emulator test suite)
│   └── src/iosMain/                   # MainViewController (ComposeUIViewController)
└── iosApp/                            # Xcode SwiftUI host project for iOS
```

---

## Building & Running Tests

### Run Shared KMP Unit Tests
```bash
./gradlew :composeApp:testDebugUnitTest
```

### Build Android Debug APK
```bash
./gradlew :composeApp:assembleDebug
# Output: composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

### Run Android Emulator Instrumented Compose UI Tests
```bash
./gradlew :composeApp:connectedDebugAndroidTest
```

### Build & Test iOS Simulator Target (on macOS)
```bash
./gradlew :composeApp:iosSimulatorArm64Test :composeApp:linkDebugFrameworkIosSimulatorArm64
```
