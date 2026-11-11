# Straphanger Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn Straphanger from a demo that only looks complete into an app that keeps your rides, knows the real subway network, actually captures rides, is described honestly, and is ready to publish on Google Play.

**Architecture:** Keep the existing shape: shared Kotlin Multiplatform domain and Compose UI, with thin Android and iOS entry points. Split the single `composeApp` module into `:shared` (KMP library) and `:androidApp` (Android application), as AGP 9 expects. Replace the hand-typed station catalog with JSON generated offline from MTA GTFS data. Replace in-memory storage with an atomically written JSON file, backed up by Android Auto Backup and manual file export. Remove Firebase. Platform location and file-picker code go behind small common interfaces.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, AGP 9.5.1, Gradle 9.6.0→9.9.1, kotlinx-serialization, kotlinx-io, kotlin.time, Ktor client (short-link redirects only), Google Play Services Location (Android), CoreLocation (iOS), FileKit (file pickers), AboutLibraries (license screen), Gradle Play Publisher, jj (version control, colocated with git).

**Spec:** No separate spec. The "Findings" and "User decisions" sections below are the requirement list. Every item maps to a task.

## User decisions (2026-11-10)

- Storage: a single JSON file on the device. No Room.
- Firebase: remove it completely. Backup is file export/import plus Android Auto Backup.
- compileSdk and targetSdk: 36.
- Add a Play Store readiness phase: a real release build, versioning, signing, policy compliance, a store listing, and a release pipeline.
- Execution: subagent-driven.

## Findings (from code review + build/test run on 2026-11-10)

Build & test results with the working copy (AGP 9.5.1 upgrade not yet committed; compileSdk set to 36):
- `./gradlew :composeApp:testDebugUnitTest :composeApp:assembleDebug` passes, 14/14 unit tests. The original `compileSdk = 34` failed because platform android-34 isn't installed.
- `:composeApp:lintDebug`: 0 errors, 29 warnings: outdated dependencies, `targetSdk 34` (OldTargetApi), missing `android:icon`.
- `:composeApp:connectedDebugAndroidTest` on `Pixel_3a_API_34` fails 1 of 16. `loggingRideAndConfirmingPassiveAmbiguousRideUpdatesStatsAndToggles` fails at `SubwayTrackerEmulatorTest.kt:64` because `backup_json_preview` is below the fold and the test never scrolls to it.
- The build only works because of AGP 9 opt-out flags in `gradle.properties` (`android.builtInKotlin=false`, `android.newDsl=false`, …). These are deprecated and are removed in AGP 10.
- iOS was not built: this machine is Linux, and the repo has no `.xcodeproj`.

Code findings:
- F1 Rides are lost when the app restarts: `App.kt:39` builds a `StraphangerViewModel()` that defaults to `InMemoryPersistenceBackend`, and sample rides are seeded on every launch.
- F2 If the saved data fails to decode, `SubwayDao` silently starts empty, and the next save overwrites the user's file.
- F3 The network has 161 hand-typed stations, but stats divide by a hard-coded 472/424/248. Route assignments per segment are wrong (e.g. the whole Lex corridor is tagged 4/5/6, including Woodlawn). `nyc_subway_network.json` holds only totals.
- F4 Hard-coded epoch timestamps stand in for "now": manual logs (`StraphangerViewModel.kt:190,251,353`), shared routes (`GoogleMapsShareParser.kt:30`), backup export (`BackupExportManager.kt:25`), and the engine defaults (`SubwayGraphEngine.kt:114,182`).
- F5 Geofencing is maths only. No code calls GeofencingClient or CLLocationManager, no runtime permission is ever requested, `PassiveRideReconstructor.observedUndergroundSignalDrop` is written but never read, and the UI shows `passiveGeofenceEnabled = true` / `googleMapsNotificationEnabled = true` as hard-coded defaults.
- F6 There is no UI to grant notification-listener access, so the Google Maps listener never receives anything.
- F7 Custom Timeline import only works by pasting JSON into a text field. Real Timeline files are megabytes, so this is unusable.
- F8 Firebase sync is unconfigured and broken (its token is never persisted or refreshed). Per the user's decision, it is removed. The backup "export" is a 280-character preview.
- F9 There is no Xcode project, so the iOS app can't be built. CI only links the framework.
- F10 The `.kotlin/` build cache (156 files) is staged, and `.gitignore` doesn't exclude it.
- F11 The README, commit messages, code comments and UI text use marketing language and make false claims ("Room SQLite", "472 stations", "60fps", "Solves Adversarial Vulnerability #1", the UI string "Local-first SQLite active • Offline-ready underground").
- F12 The ViewModel is a plain class on `Dispatchers.Unconfined`, not an androidx `ViewModel`, so its state doesn't survive the process being recreated.
- F13 The manifest declares `POST_NOTIFICATIONS` and `ACCESS_NETWORK_STATE`, but nothing uses them. `ktor-client-content-negotiation` / `ktor-serialization-kotlinx-json` are declared but unused.
- F14 There's no release configuration: `versionCode 1` / `versionName "1.0.0"` are hard-coded, there's no signing config, R8 is off (`isMinifyEnabled = false`), and there's no AAB, privacy policy, store listing or release workflow.

## Global Constraints

- compileSdk = 36 and targetSdk = 36; minSdk stays 26.
- applicationId stays `com.straphanger.app`.
- JDK 17 toolchain (CI already uses Temurin 17).
- AGP 9.5.1. When Task 2 finishes, `gradle.properties` contains none of the AGP opt-out flags listed in Findings.
- Every task must leave the repo passing these three commands: `./gradlew :shared:testAndroidHostTest` (after Task 2; before that, `:composeApp:testDebugUnitTest`), `./gradlew :androidApp:assembleDebug`, and `./gradlew :androidApp:lintDebug` with 0 errors.
- Commits use jj (the repo is colocated): `jj commit -m "<conventional message>"`. Commit messages are plain and factual: no adjectives like "comprehensive" or "60fps", and no feature lists.
- Tasks 10 and 11 need macOS with Xcode. Everything else runs on Linux.
- Never commit secrets: upload keystores (`*.jks`, `*.keystore`), `keystore.properties`, and Play service-account JSON. Subagents never create or handle signing passwords; key creation is a user step.
- No Firebase, Google Analytics or other telemetry SDKs. The only network traffic is resolving `maps.app.goo.gl` short links.
- User-facing text (UI, store listing, README) follows the Task 15 style rules from Task 1 onward. Task 15 also sweeps up anything left over.

## Review Focus

1. **A corrupt or half-written rides file** must never wipe the user's rides. The bad file is set aside, and the app says so (test in Task 6).
2. **Importing the same Timeline file or backup twice** must add zero rides the second time (tests in Tasks 5 and 8).
3. **Rides at the DST change and around midnight** in America/New_York must get the correct service period. 01:30 on 2026-11-01 (an hour that happens twice) is LATE_NIGHT both times (test in Task 4).
4. **Walking between two nearby stations** with location on must not record a subway ride (test in Task 9).
5. **R8 removing serializers or reflection targets from the release build**: the minified release build must load, save and import rides the same way debug does (instrumented tests run against the release build in Task 12).

---

## Phase 0: Build baseline

### Task 1: Commit the AGP 9 baseline, untrack `.kotlin/`, fix the scrolling UI test, add an icon

**Files:**
- Modify: `.gitignore` (add `.kotlin/`)
- Modify: `composeApp/src/androidInstrumentedTest/kotlin/com/straphanger/app/SubwayTrackerEmulatorTest.kt:62-64`
- Create: `composeApp/src/androidMain/res/mipmap-anydpi/ic_launcher.xml` (adaptive icon) and `composeApp/src/androidMain/res/drawable/ic_launcher_foreground.xml` (vector: a generic train/route-circle shape, **not** an MTA bullet or logo)
- Modify: `composeApp/src/androidMain/AndroidManifest.xml` (`android:icon="@mipmap/ic_launcher"`)
- Already modified (uncommitted, keep): `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `gradle.properties`, `settings.gradle.kts`, `gradle/gradle-daemon-jvm.properties`

- [ ] **Step 1:** Add `.kotlin/` to `.gitignore`, then run `git rm -r --cached --quiet .kotlin` so the files stay on disk but are no longer tracked. Check: `jj st` lists no `.kotlin/` paths.
- [ ] **Step 2:** Change the last two lines of the failing test to `onNodeWithTag("export_backup_btn").performScrollTo().performClick()` and `onNodeWithTag("backup_json_preview").performScrollTo().assertIsDisplayed()`. Also add `performScrollTo()` before every `performClick()` / `assertIsDisplayed()` on the Stats and Sources screens, which both scroll.
- [ ] **Step 3:** Add the adaptive icon and set `android:icon` in the manifest.
- [ ] **Step 4:** Verify. Boot `Pixel_3a_API_34_extension_level_7_x86_64` headless (`emulator -avd <name> -no-window -no-audio -no-snapshot-save`), then run `./gradlew :composeApp:testDebugUnitTest :composeApp:assembleDebug :composeApp:lintDebug :composeApp:connectedDebugAndroidTest`. Expected: BUILD SUCCESSFUL, 16/16 device tests pass, and lint no longer reports `MissingApplicationIcon`. Afterwards, stop the emulator with `adb emu kill`.
- [ ] **Step 5:** `jj commit -m "build: upgrade to AGP 9.5.1/Gradle 9.6/compileSdk 36, untrack .kotlin, fix UI test scrolling, add icon"`

### Task 2: Split into `:shared` + `:androidApp`; remove the AGP opt-out flags

**Files:**
- Move: `composeApp/src/{commonMain,commonTest,iosMain}` → `shared/src/...`; `composeApp/src/androidMain/kotlin/.../{MainActivity.kt,capture/}`, `AndroidManifest.xml` and `res/` → `androidApp/src/main/...`; `composeApp/src/androidInstrumentedTest` → `androidApp/src/androidTest`
- Create: `shared/build.gradle.kts` (plugins `kotlinMultiplatform`, `com.android.kotlin.multiplatform.library`, `jetbrainsCompose`, `compose.compiler`, `kotlinSerialization`; `kotlin { androidLibrary { namespace = "com.straphanger.shared"; compileSdk = 36; minSdk = 26; androidResources.enable = true; withHostTest {}; withDeviceTest {} } … }`. `androidResources.enable` is required, or Compose resources such as `nyc_subway_network.json` are not packaged.)
- Create: `androidApp/build.gradle.kts` (plugins `androidApplication`, `compose.compiler`; `applicationId = "com.straphanger.app"`, `targetSdk = 36`; depends on `project(":shared")`)
- Modify: `settings.gradle.kts` (include `:shared`, `:androidApp`; remove `:composeApp`)
- Modify: `gradle/libs.versions.toml` (`android-targetSdk = "36"`, add plugin alias `androidKmpLibrary = { id = "com.android.kotlin.multiplatform.library", version.ref = "agp" }`)
- Modify: `gradle.properties` (delete every `android.*` line added by the AGP 9 upgrade)
- Modify: `.github/workflows/kmp-ci.yml` (task paths → `:shared:testAndroidHostTest`, `:androidApp:assembleDebug`, `:androidApp:connectedDebugAndroidTest`, `:shared:iosSimulatorArm64Test`, `:shared:linkDebugFrameworkIosSimulatorArm64`; APK path → `androidApp/build/outputs/apk/debug/androidApp-debug.apk`)
- Modify: `iosApp/Configuration/Config.xcconfig` and anything else that references the `ComposeApp` framework. Keep `baseName = "ComposeApp"` so the Swift `import ComposeApp` doesn't change.

**Interfaces:**
- Produces: module paths `:shared` and `:androidApp`. Every later task uses these paths and `shared/src/...` / `androidApp/src/main/...` locations.

- [ ] **Step 1:** Move the files (plain `mv`; jj notices renames).
- [ ] **Step 2:** Write the two build files and update settings and the version catalog. Note: `withHostTest` replaces `androidUnitTest`, so commonTest now runs through `:shared:testAndroidHostTest`.
- [ ] **Step 3:** Delete the AGP opt-out flags from `gradle.properties`.
- [ ] **Step 4:** Verify. Run `./gradlew :shared:testAndroidHostTest :androidApp:assembleDebug :androidApp:lintDebug` and expect BUILD SUCCESSFUL, 14 tests, and no `UNSUPPORTED_PROJECT_OPTION_USE` / `DEPRECATED_DSL` warnings in the output. Then run `:androidApp:connectedDebugAndroidTest` on the emulator and expect 2/2 pass.
- [ ] **Step 5:** `jj commit -m "build: split into :shared KMP library and :androidApp for AGP 9; drop compatibility flags"`

### Task 3: Upgrade dependencies; drop unused ones

**Files:**
- Modify: `gradle/libs.versions.toml`. Set: `kotlin = "2.4.21"`, `compose-multiplatform = "1.12.2"`, `ktor = "3.6.1"`, `kotlinx-coroutines = "1.11.0"`, `kotlinx-serialization = "1.12.0"`, `kotlinx-datetime = "0.8.0"`, `androidx-lifecycle = "2.11.0"`, `androidx-activityCompose = "1.13.0"`, `compose-android-test = "1.12.1"`, `androidx-test-junit = "1.3.0"`, `androidx-espresso = "3.7.0"`, `play-services-location = "21.4.0"`. Remove the `ktor-client-content-negotiation` and `ktor-serialization-kotlinx-json` entries (F13). Gradle wrapper → 9.9.1.
- Modify: `shared/build.gradle.kts` (drop those two dependencies)
- Modify: every file that imports `kotlinx.datetime.Instant` or `kotlinx.datetime.Clock`. In datetime 0.7+ these became `kotlin.time.Instant` / `kotlin.time.Clock`. Find them with `grep -rln 'kotlinx.datetime.Instant\|kotlinx.datetime.Clock' shared/src`.

- [ ] **Step 1:** Bump the versions and run `./gradlew wrapper --gradle-version 9.9.1`.
- [ ] **Step 2:** Fix compile errors: the Instant/Clock imports, plus any Compose 1.12 API deprecations reported as errors. Treat warnings as follow-ups, not blockers.
- [ ] **Step 3:** Verify with the three Global Constraints commands, and expect lint to show no `GradleDependency`/`NewerVersionAvailable` warnings for the bumped coordinates.
- [ ] **Step 4:** `jj commit -m "build: upgrade Kotlin 2.4.21, Compose MP 1.12.2, Ktor 3.6.1 and libraries; drop unused Ktor modules"`

## Phase 1: Correctness

### Task 4: Use a real clock instead of hard-coded timestamps (F4)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/straphanger/app/ui/StraphangerViewModel.kt` (constructor, `submitQuickRide`, `simulateAmbiguousPassiveRide`, `seedStarterNycRides`)
- Modify: `.../data/capture/GoogleMapsShareParser.kt` (constructor, `parseAndResolveSharedRoute`)
- Modify: `.../data/sync/BackupExportManager.kt` (constructor, `exportToJson`)
- Modify: `.../data/gtfs/SubwayGraphEngine.kt:114,182` (remove the `timestampEpochMs` defaults so it becomes a required parameter)
- Create: `shared/src/commonTest/kotlin/com/straphanger/app/testutil/FixedClock.kt`
- Test: `shared/src/commonTest/kotlin/com/straphanger/app/data/gtfs/ServicePeriodTest.kt`

**Interfaces:**
- Produces: `StraphangerViewModel(backend: RidePersistenceBackend, clock: kotlin.time.Clock = Clock.System, …)`, `GoogleMapsShareParser(graphEngine, httpClient: HttpClient = HttpClient(), clock: Clock = Clock.System)`, `BackupExportManager(dao, clock: Clock = Clock.System)`, `class FixedClock(var now: Instant) : Clock` (test only).

- [ ] **Step 1:** Write `ServicePeriodTest` cases. `inferServicePeriod` at 2026-11-01T05:30Z and at 2026-11-01T06:30Z: both are 01:30 local time, once in EDT and once in EST, and both must be `LATE_NIGHT`. Wednesday 2026-11-11 at 23:44 local is `DAYTIME` and 23:45 local is `LATE_NIGHT`. Saturday 2026-11-14 at 12:00 local is `WEEKEND`.
- [ ] **Step 2:** Write `submitQuickRideUsesClockTime`: with `FixedClock(Instant.parse("2026-11-11T14:00:00Z"))`, the logged ride's `startTimeEpochMs == 1794405600000`. Write `exportUsesClockTime`: the exported JSON's `exportedAtEpochMs` equals the clock's value.
- [ ] **Step 3:** Run them and expect `submitQuickRideUsesClockTime`/`exportUsesClockTime` to FAIL (the ServicePeriod cases may already pass, which is fine).
- [ ] **Step 4:** Inject the `Clock` and delete every hard-coded epoch literal. Check: `grep -rnE '1[67][0-9]{11}L' shared/src/commonMain` finds nothing.
- [ ] **Step 5:** Run `./gradlew :shared:testAndroidHostTest` and expect everything to PASS (update existing tests that relied on the defaults so they pass explicit timestamps).
- [ ] **Step 6:** `jj commit -m "fix: use injected Clock for ride, share and backup timestamps"`

### Task 5: Remove Firebase; back up and restore through files (F8)

**Files:**
- Delete: `shared/src/commonMain/kotlin/com/straphanger/app/data/sync/FirestoreKmpSyncClient.kt`
- Modify: `.../data/local/Entities.kt` (remove `RideEntity.syncedToCloud`)
- Modify: `.../data/local/SubwayDao.kt` (remove `getUnsyncedRides`, `markRidesSynced`)
- Modify: `.../data/sync/BackupExportManager.kt` (`fun importFromJson(backupJson: String): Result<Int>`, where failure means the input is not a Straphanger backup; delete `exportToCsv` unless the UI uses it)
- Modify: `.../ui/StraphangerViewModel.kt` (remove `firestoreSync` and `triggerCloudSync`; add `suspend fun exportBackup(): String`, `suspend fun importBackup(bytes: ByteArray): Result<Int>`; remove `exportedBackupPreview`)
- Modify: `.../ui/rides/RidesAndCaptureScreen.kt` + `.../App.kt` (remove the cloud-sync button and copy; "Export backup…" (tag `export_backup_btn`) uses `FileKit.openFileSaver(suggestedName = "straphanger-backup-<yyyy-MM-dd>", extension = "json")`; "Restore from backup…" (tag `import_backup_btn`) uses `rememberFilePickerLauncher(type = FileKitType.File(listOf("json")))`; show the result in a snackbar; remove the 280-character preview)
- Modify: `gradle/libs.versions.toml` + `shared/build.gradle.kts`: add `io.github.vinceglb:filekit-dialogs-compose`, pinned to the newest release whose notes say it supports Compose Multiplatform 1.12, as `filekit`.
- Modify: `androidApp/src/androidTest/.../SubwayTrackerEmulatorTest.kt` (remove the `backup_json_preview` assertion; assert `export_backup_btn` and `import_backup_btn` are displayed after `performScrollTo()`)
- Modify: `shared/src/commonTest/.../data/local/SubwayPersistenceAndSyncTest.kt` (delete `offlineUndergroundQueueSurvivesFailureAndSyncsWhenBackOnline`; rename the file to `SubwayPersistenceTest.kt`)
- Test: `shared/src/commonTest/kotlin/com/straphanger/app/data/sync/BackupRoundTripTest.kt`

**Interfaces:**
- Consumes: `Clock` (Task 4).
- Produces: FileKit in the version catalog (Task 8 uses it); `BackupExportManager.exportToJson(): String`, `importFromJson(String): Result<Int>`.

- [ ] **Step 1:** Write the tests:
  - `backupRoundTripRestoresAllRides`: export → `dao.clearAll()` → import gives `Result.success(n)` and a ride list equal to the original.
  - `importingSameBackupTwiceAddsNothing` (Review Focus 2): the second import leaves `dao.getAllRides().size` unchanged.
  - `importRejectsNonBackupJson`: `importFromJson("""{"hello":1}""").isFailure` and the DAO is unchanged.
  - `legacyBackupWithSyncedToCloudFieldStillImports`: a backup string containing `"syncedToCloud":true` imports successfully.
- [ ] **Step 2:** Run them and expect FAIL (signature change).
- [ ] **Step 3:** Remove Firebase and implement the backup changes and UI. Check: `grep -rniE 'firebase|firestore|syncedToCloud|cloud sync' shared androidApp` finds nothing.
- [ ] **Step 4:** Run the three Global Constraints commands and the emulator tests, and expect them all to pass. On the emulator, export a backup to Downloads, clear the rides, restore it, and check the rides come back.
- [ ] **Step 5:** `jj commit -m "refactor: remove Firebase sync; add file backup export and restore"`

### Task 6: Persistent storage, protection against corrupt files, Auto Backup, lifecycle ViewModel (F1, F2, F12)

**Files:**
- Create: `shared/src/commonMain/kotlin/com/straphanger/app/data/local/FileRidePersistenceBackend.kt`
- Modify: `.../data/local/SubwayDao.kt` (init load path)
- Modify: `.../ui/StraphangerViewModel.kt` (extend `androidx.lifecycle.ViewModel`, use `viewModelScope`, `seedInitialSampleRides` default `false`, add `fun loadDemoRides()`, `syncStatusMessage` → rename `statusMessage`, default `"Rides are stored on this device"`)
- Modify: `.../App.kt` (`fun App(backend: RidePersistenceBackend)`; create the VM with `viewModel { StraphangerViewModel(backend) }`)
- Modify: `androidApp/src/main/kotlin/com/straphanger/app/MainActivity.kt` (pass `FileRidePersistenceBackend(Path(filesDir.path, "rides.json"))`)
- Modify: `shared/src/iosMain/.../MainViewController.kt` (pass a backend rooted at `NSDocumentDirectory`)
- Modify: `.../ui/rides/RidesAndCaptureScreen.kt` (add a "Load sample rides" button with tag `load_demo_rides_btn`; show a `loadError` banner with tag `storage_error_banner`)
- Create: `androidApp/src/main/res/xml/data_extraction_rules.xml` (API 31+) and `androidApp/src/main/res/xml/backup_rules.xml` (API ≤30); both include `domain="file" path="rides.json"` and exclude `rides.json.tmp` and `rides.corrupt-*`
- Modify: `androidApp/src/main/AndroidManifest.xml` (`android:allowBackup="true"`, `android:dataExtractionRules="@xml/data_extraction_rules"`, `android:fullBackupContent="@xml/backup_rules"`)
- Modify: `gradle/libs.versions.toml` + `shared/build.gradle.kts` (add `org.jetbrains.kotlinx:kotlinx-io-core` explicitly, pinned to the version Ktor 3.6.1 already pulls in; check with `./gradlew :shared:dependencies --configuration commonMainCompileClasspath`)
- Test: `shared/src/commonTest/kotlin/com/straphanger/app/data/local/FileRidePersistenceBackendTest.kt`

**Interfaces:**
- Consumes: `Clock` from Task 4.
- Produces: `class FileRidePersistenceBackend(file: kotlinx.io.files.Path, fileSystem: FileSystem = SystemFileSystem) : RidePersistenceBackend`. Its `saveSnapshotJson` writes `<file>.tmp` and then calls `fileSystem.atomicMove(tmp, file)`. `RidePersistenceBackend.quarantineCorruptSnapshot(suffix: String)` (no-op in memory). `SubwayDao.loadError: StateFlow<String?>`. `fun App(backend: RidePersistenceBackend)`.

- [ ] **Step 1:** Write tests (they use `SystemTemporaryDirectory` with a unique subfolder per test):
  - `ridesSurviveNewDaoInstance`: insert 2 rides, build a new `SubwayDao` on the same file, `getAllRides().size == 2`.
  - `corruptFileIsPreservedAndNotOverwritten` (Review Focus 1): write `"{not json"` to `rides.json`, build the DAO, then `loadError.value != null`, a file matching `rides.corrupt-*.json` exists and contains `"{not json"`, and after `insertOrReplaceRide(r)` that corrupt copy is unchanged.
  - `noLeftoverTmpFileAfterSave`: after saving, `<file>.tmp` does not exist.
  - `viewModelDoesNotSeedByDefault`: `StraphangerViewModel(InMemoryPersistenceBackend()).uiState.value.rides.isEmpty()`.
- [ ] **Step 2:** Run them and expect FAIL (`FileRidePersistenceBackend` doesn't exist yet).
- [ ] **Step 3:** Implement the backend. In `SubwayDao.init`, on decode failure: call `quarantineCorruptSnapshot("corrupt-<clock epochMs>")`, set `loadError`, and start empty.
- [ ] **Step 4:** Wire `App(backend)` on both platforms; add the demo-rides button, the error banner and the backup rules.
- [ ] **Step 5:** Run the tests and expect PASS. Add the instrumented test `rideSurvivesActivityRecreation` (log a ride, `composeTestRule.activityRule.scenario.recreate()`, the ride is still listed). On the emulator: log a ride, `adb shell am force-stop com.straphanger.app`, relaunch, and confirm the ride is still there. Run `adb shell bmgr backupnow com.straphanger.app` and expect `Backup finished with result: Success`.
- [ ] **Step 6:** `jj commit -m "fix: persist rides to an atomically written file, keep corrupt files, enable Auto Backup"`

### Task 7: Generate the subway network from MTA GTFS (F3)

**Files:**
- Create: `tools/gtfs-import/build.gradle.kts` (Kotlin JVM `application`, depends on kotlinx-serialization and `project(":shared")` for the model classes; `mainClass = "com.straphanger.tools.gtfs.MainKt"`); include it in `settings.gradle.kts` as `:tools:gtfs-import`
- Create: `tools/gtfs-import/src/main/kotlin/com/straphanger/tools/gtfs/{Main.kt,GtfsReader.kt,NetworkBuilder.kt}`
- Create: `tools/gtfs-import/route-metadata.json`, hand-maintained: `routeId → trunkGroup, division, display name`. Colors come from GTFS `routes.txt`.
- Create: `tools/gtfs-import/README.md` (source URLs, download date, data license/terms and the required attribution text, how to regenerate)
- Create: `tools/gtfs-import/src/test/resources/mini-gtfs/` (a 6-station, 2-route fixture: one local route and one express route on the same trunk) + `src/test/kotlin/.../NetworkBuilderTest.kt`
- Replace: `shared/src/commonMain/composeResources/files/nyc_subway_network.json` (generated output; includes a top-level `"sourceDate": "YYYY-MM-DD"`)
- Modify: `shared/.../data/gtfs/SubwayModels.kt` (delete the `totalOfficial*` fields; add `val sourceDate: String`, `val stationCount: Int get() = stations.size`, `val complexCount: Int get() = stations.distinctBy { it.complexId }.size`, `val systemMiles: Double get() = segments.sumOf { it.distanceMiles }`; add `val stopsAt: Map<ServicePeriod, List<String>>` to `SubwayRouteInfo`, giving each route's ordered stop list per period)
- Replace: `shared/.../data/gtfs/NycSubwayNetworkCatalog.kt` (~390 lines) with a loader
- Modify: `.../data/gtfs/SubwayGraphEngine.kt` (decide express/local stops from `stopsAt` instead of `expressRoutes` flags)
- Modify: `.../domain/stats/SubwayStatsCalculator.kt` (`StationGroupingMode` → `INDIVIDUAL_STATIONS`, `STATION_COMPLEXES`, no hard-coded totals), plus the matching test tags in `ui/stats/StatsDashboardScreen.kt` and the instrumented test (`toggle_grouping_INDIVIDUAL_STATIONS`, `toggle_grouping_STATION_COMPLEXES`)
- Modify: `.../ui/StraphangerViewModel.kt` (load the network asynchronously; `isNetworkLoaded` in the UI state; default log-form station IDs and presets use GTFS stop IDs)
- Create: `shared/src/commonTest/kotlin/com/straphanger/app/testutil/TestNetwork.kt` (a small hand-written `SubwayNetworkData` that the existing engine/stats/capture tests switch to)
- Test: `shared/src/androidHostTest/kotlin/com/straphanger/app/data/gtfs/BundledNetworkTest.kt` (reads the real generated JSON from disk)

**Inputs (the implementer downloads these; record URL + date in the tools README):**
- NYCT Subway static GTFS zip from the MTA developer resources page (new.mta.info/developers → "Regular GTFS – NYC Subway").
- data.ny.gov "MTA Subway Stations" CSV (dataset `39hk-dx4f`): `GTFS Stop ID`, `Complex ID`, `Borough`, `Stop Name`.

**Interfaces:**
- Produces: `object NycSubwayNetworkCatalog { suspend fun loadBundled(): SubwayNetworkData; fun parse(json: String): SubwayNetworkData }`; `SubwayGraphEngine(network: SubwayNetworkData)` (no default, since the network is loaded first); `StationGroupingMode.INDIVIDUAL_STATIONS / STATION_COMPLEXES`; `SubwayNetworkData.sourceDate` (Task 13 shows it on the About screen). Station IDs are GTFS parent-station IDs (e.g. `"626"` = 86 St on the Lexington line).

**Generator rules (the code and tests can't decide these on their own):**
- Stations come from `stops.txt` rows with `location_type = 1`. The complex and borough come from the stations CSV, joined on GTFS Stop ID.
- For each `route_id` × `direction_id`, take the most common stop sequence among weekday trips that depart 06:00–21:59 (→ DAYTIME) and 00:00–04:59 (→ LATE_NIGHT). Do the same for Saturday trips that depart 10:00–17:59 (→ WEEKEND). A route with no trips in a window has no entry for that period.
- Segments: every consecutive stop pair in every pattern. If a pair (A, B) has a path through *local* pairs (pairs whose stations are all adjacent in some pattern) of the same `trunkGroup` that is ≤ 1.3 × the straight-line distance A→B, replace the pair with that path. Otherwise keep it as its own segment. This is how express rides light up the local corridor they run alongside.
- Segment distance is the length of the GTFS shape between the two stops' nearest shape points. If the shape is missing, fall back to haversine × 1.08. The polyline is that stretch of the shape, simplified to ≤ 1 point per 50 m.

- [ ] **Step 1:** Write `NetworkBuilderTest` against the mini fixture: 6 stations, the express route's DAYTIME `stopsAt` skips stations 2 and 4, express pair (1,3) expands to segments (1,2) and (2,3), and `systemMiles` equals the sum of the fixture's shape lengths to within ±0.01.
- [ ] **Step 2:** Run `./gradlew :tools:gtfs-import:test` and expect FAIL.
- [ ] **Step 3:** Implement `GtfsReader`/`NetworkBuilder`/`Main` (args: `--gtfs <zip> --stations <csv> --out <json> --source-date <YYYY-MM-DD>`). Expect PASS.
- [ ] **Step 4:** Generate the real file with `./gradlew :tools:gtfs-import:run --args="…"`.
- [ ] **Step 5:** Write `BundledNetworkTest`: `stationCount in 470..500`; `complexCount in 420..450`; every segment's endpoints are known station IDs; a BFS over segments from Times Sq-42 St reaches every station except those served only by route `SI`; routes `1`–`7`, `A C E B D F M G J Z L N Q R W` are all present; route `4` DAYTIME `stopsAt` does not contain 116 St (Lexington) but LATE_NIGHT does.
- [ ] **Step 6:** Write `shareTextWithAmbiguousStationUsesNamedLine`: parsing `"86 St to Grand Central via 4 train"` resolves the origin to the 4/5/6 86 St, not the 1, Q or R one. Parsing `"86 St to Grand Central"` with no line named sets `needsReview = true`.
- [ ] **Step 7:** Replace the catalog with the loader, switch the engine to `stopsAt`, rename the grouping modes, and move the existing tests to `TestNetwork`. Delete `expressRoutes`/`isTransferHub` from `SubwayStation` if nothing reads them after the switch.
- [ ] **Step 8:** Run `./gradlew :shared:testAndroidHostTest :tools:gtfs-import:test` and the emulator tests. Expect everything to pass, and the Stats screen to show the counts from the data.
- [ ] **Step 9:** `jj commit -m "feat: generate subway network from MTA GTFS and derive station totals from data"`

## Phase 2: Ride capture

### Task 8: Import Timeline files with a file picker (F7)

**Files:**
- Modify: `.../ui/StraphangerViewModel.kt` (replace `importCustomTimelineJson(String)` with `suspend fun importTimelineFile(bytes: ByteArray): TimelineImportSummary`, which runs in `withContext(Dispatchers.Default)`; add `timelineImportInProgress: Boolean` and `lastImportSummary: TimelineImportSummary?` to the UI state)
- Modify: `.../ui/rides/RidesAndCaptureScreen.kt` (remove the paste text field; add a "Choose Timeline file…" button with tag `pick_timeline_file_btn` using the FileKit picker from Task 5; show the summary counts and a progress indicator)
- Modify: `.../App.kt` (update the callback wiring)
- Test: `shared/src/commonTest/kotlin/com/straphanger/app/data/capture/TimelineImportFileTest.kt`

**Interfaces:**
- Consumes: FileKit (Task 5); `TimelineJsonImporter.importTimelineJson(...)` and `TimelineImportSummary(rawSegmentsFound, ridesImported, multiLegRidesMerged, duplicatesSkipped, unmatchedSegmentsSkipped, importedRecords)` (existing); `TestNetwork` (Task 7).

- [ ] **Step 1:** Write `importingSameFileTwiceAddsNothing` (Review Focus 2): import the sample Timeline twice; the second call returns `ridesImported == 0`, `duplicatesSkipped == first.ridesImported`, and `dao.getAllRides().size` is unchanged.
- [ ] **Step 2:** Write `largeTimelineImportsWithinBudget`: build a 20,000-segment `semanticSegments` file in the test (alternating IN_SUBWAY and WALKING between two real `TestNetwork` stations, 1 hour apart). The import finishes and `ridesImported == 10000`. Keep it bounded with `runTest(timeout = 60.seconds)`.
- [ ] **Step 3:** Run them and expect FAIL (`importTimelineFile` doesn't exist). If dedupe turns out to be broken, fix it in `TimelineJsonImporter`.
- [ ] **Step 4:** Implement the VM function and the picker UI. Expect PASS.
- [ ] **Step 5:** On the emulator, `adb push` a sample Timeline.json to `/sdcard/Download`, pick it in the app, and confirm the rides appear.
- [ ] **Step 6:** `jj commit -m "feat: import Google Maps Timeline from a file picker"`

### Task 9: Android notification access and passive geofencing (F5, F6, F13)

**Files:**
- Create: `shared/src/commonMain/kotlin/com/straphanger/app/data/capture/GeofenceRegistrar.kt`. It holds `interface GeofenceRegistrar { suspend fun replaceAll(specs: List<ActiveGeofenceSpec>); suspend fun clear() }`, `sealed interface GeofenceEvent { val geofenceId: String; val epochMs: Long; val lat: Double; val lng: Double }` with `data class Entered(...)` and `data class Exited(...)`, and `object GeofenceEventBus { val events: SharedFlow<GeofenceEvent>; fun emit(event: GeofenceEvent) }`, built like `SharedRouteInbox`.
- Create: `shared/.../data/capture/PassiveCaptureCoordinator.kt`
- Modify: `.../data/capture/DynamicGeofenceWindowManager.kt` (delete `onUndergroundSignalDropDetected` and `observedUndergroundSignalDrop`; `PassiveRideReconstructor` gets the plausibility rule below)
- Create: `androidApp/src/main/kotlin/com/straphanger/app/capture/{AndroidGeofenceRegistrar.kt,GeofenceBroadcastReceiver.kt,NotificationAccess.kt}`
- Create: `androidApp/src/main/kotlin/com/straphanger/app/permissions/CapturePermissions.kt` (Compose launchers: fine location, then background location as a separate request on API 29+. Task 13 adds the prominent-disclosure screens in front of these.)
- Modify: `androidApp/src/main/AndroidManifest.xml` (`<receiver android:name=".capture.GeofenceBroadcastReceiver" android:exported="false"/>`; remove `POST_NOTIFICATIONS` and `ACCESS_NETWORK_STATE` per F13)
- Modify: `.../ui/StraphangerViewModel.kt` (`passiveGeofenceEnabled` and `googleMapsNotificationEnabled` default to `false` and are set from real permission and registration state; delete the fixed `activeGeofenceCount = 19` initializer)
- Modify: `.../ui/rides/RidesAndCaptureScreen.kt` (the toggles start the permission flow or open `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`, passed in as callbacks `onRequestPassiveCapture: () -> Unit` and `onRequestNotificationAccess: () -> Unit` from `App`)
- Test: `shared/src/commonTest/kotlin/com/straphanger/app/data/capture/PassiveCaptureCoordinatorTest.kt`

**Interfaces:**
- Consumes: `DynamicGeofenceWindowManager.computeRollingWindow(lat, lng)`, `shouldRefreshWindow(lat, lng)`, `MACRO_ANCHOR_ID`; `Clock` (Task 4); `TestNetwork` (Task 7).
- Produces: `class PassiveCaptureCoordinator(windowManager: DynamicGeofenceWindowManager, reconstructor: PassiveRideReconstructor, registrar: GeofenceRegistrar, events: Flow<GeofenceEvent>, scope: CoroutineScope)` with `fun start(initialLat: Double, initialLng: Double)`; the `App` callbacks `onRequestPassiveCapture` / `onRequestNotificationAccess` (Task 13 wraps them). Task 11 reuses `GeofenceRegistrar`/`GeofenceEventBus` unchanged.

**Plausibility rule (in `PassiveRideReconstructor`):** entering station A's geofence starts a candidate. Entering a *different* complex B ends it, and a ride is recorded only if the duration is 2–120 minutes **and** straight-line distance ÷ duration ≥ 6 mph. Otherwise B becomes the new candidate. `Exited` events for station geofences are ignored. `Exited` for `MACRO_ANCHOR_ID` triggers a window refresh at the event's lat/lng.

- [ ] **Step 1:** Write the coordinator tests with a `FakeGeofenceRegistrar` that records `replaceAll` calls:
  - `startRegistersAtMost19Regions`: after `start(...)`, one `replaceAll` call with `size <= 19` that includes `MACRO_ANCHOR_ID`.
  - `exitingMacroAnchorRefreshesWindowAroundNewLocation`: emit `Exited(MACRO_ANCHOR_ID, lat/lng 2 km away)`, which causes a second `replaceAll` whose nearest station is the one closest to the new point.
  - `subwaySpeedTripRecordsRide`: Entered(A) then Entered(B) 12 min later, 3.5 miles apart → exactly 1 ride with `source == PASSIVE_GPS`.
  - `walkingBetweenNearbyStationsRecordsNothing` (Review Focus 4): Entered(A) then Entered(B) 9 min later, 0.4 miles apart (2.7 mph) → 0 rides, and B is the active candidate.
- [ ] **Step 2:** Run them and expect FAIL.
- [ ] **Step 3:** Implement the common code. Expect PASS.
- [ ] **Step 4:** Implement the Android registrar (it maps `ActiveGeofenceSpec` → `Geofence.Builder().setCircularRegion(...).setTransitionTypes(ENTER or EXIT).setExpirationDuration(NEVER_EXPIRE)`), the receiver (`GeofencingEvent.fromIntent` → `GeofenceEventBus.emit`, using `triggeringLocation` for lat/lng), `NotificationAccess.isEnabled(context)` via `NotificationManagerCompat.getEnabledListenerPackages`, and the permission flow.
- [ ] **Step 5:** Verify on the emulator. Grant permissions through the UI, then use the emulator's extended-controls location route (Union Sq → 86 St Lex at driving speed). A ride appears. Also confirm the notification-access toggle opens system settings and shows enabled after you grant access.
- [ ] **Step 6:** `jj commit -m "feat: register station geofences on Android and record passive rides"`

### Task 10: iOS Xcode project and CI app build (F9) — macOS required

**Files:**
- Create: `iosApp/iosApp.xcodeproj` (generate from the JetBrains KMP wizard template's `iosApp`, then point the "Compile Kotlin Framework" run-script phase at `./gradlew :shared:embedAndSignAppleFrameworkForXcode`)
- Modify: `iosApp/iosApp/Info.plist` (`NSLocationWhenInUseUsageDescription`, `NSLocationAlwaysAndWhenInUseUsageDescription`, with plain one-sentence reasons)
- Modify: `.github/workflows/kmp-ci.yml` (add the step `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO`)

- [ ] **Step 1:** Create the project and build it in Xcode for an iPhone simulator. The app launches and shows the map.
- [ ] **Step 2:** Run the `xcodebuild` command above locally and expect `** BUILD SUCCEEDED **`.
- [ ] **Step 3:** Run `xcrun simctl openurl booted "straphanger://share?origin=..."` and confirm a ride is captured.
- [ ] **Step 4:** `jj commit -m "build: add iOS Xcode project and build the app in CI"`

### Task 11: iOS geofencing (F5) — macOS required

**Files:**
- Create: `shared/src/iosMain/kotlin/com/straphanger/app/capture/IosGeofenceRegistrar.kt` (a `CLLocationManager` plus an `NSObject` delegate implementing `CLLocationManagerDelegateProtocol`; `replaceAll` stops monitoring every region in `monitoredRegions` and then calls `startMonitoringForRegion(CLCircularRegion(center, radius, identifier))`; `didEnterRegion`/`didExitRegion` emit to `GeofenceEventBus` with the manager's `location`)
- Modify: `shared/src/iosMain/.../MainViewController.kt` (build the registrar and `PassiveCaptureCoordinator`; `onRequestPassiveCapture` calls `requestWhenInUseAuthorization()` and then `requestAlwaysAuthorization()`)

**Interfaces:**
- Consumes: `GeofenceRegistrar`, `GeofenceEventBus`, `PassiveCaptureCoordinator`, `onRequestPassiveCapture` from Task 9, unchanged.

- [ ] **Step 1:** Run `./gradlew :shared:iosSimulatorArm64Test` and expect the Task 9 common tests to pass on iOS.
- [ ] **Step 2:** Implement the registrar. In the simulator, grant "Always", use Features → Location → a custom GPX route from Union Sq to 86 St, and confirm a ride appears.
- [ ] **Step 3:** `jj commit -m "feat: monitor station regions on iOS"`

## Phase 3: Play Store readiness (F14)

### Task 12: Release build: versioning, signing, R8, App Bundle

**Files:**
- Create: `version.properties` (repo root): `versionName=0.1.0`
- Modify: `androidApp/build.gradle.kts`:
  - Read `version.properties`. `versionName` comes from the file, and `versionCode = major * 1_000_000 + minor * 1_000 + patch` (0.1.0 → 1000), computed in a top-level function `fun versionCodeFor(name: String): Int` that rejects anything that isn't `^\d+\.\d+\.\d+$`.
  - `signingConfigs.create("release")` reads from `keystore.properties` (location: Gradle property `straphanger.keystoreProperties`, default `rootProject.file("keystore.properties")`; keys `storeFile`, `storePassword`, `keyAlias`, `keyPassword`). If the file is missing, it falls back to the env vars `STRAPHANGER_UPLOAD_STORE_FILE`, `STRAPHANGER_UPLOAD_STORE_PASSWORD`, `STRAPHANGER_UPLOAD_KEY_ALIAS`, `STRAPHANGER_UPLOAD_KEY_PASSWORD`.
  - If neither is present and `-Pstraphanger.allowDebugSignedRelease=true` is set, sign release with the debug key and log a warning. If neither is present and the flag isn't set, `bundleRelease`/`assembleRelease` fail at execution time with the message `Release signing not configured: see docs/RELEASING.md`.
  - `release { isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"); signingConfig = signingConfigs["release"] }`.
  - `testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")`.
- Create: `androidApp/proguard-rules.pro` (only the rules R8 actually needs, found in Step 3)
- Modify: `.gitignore` (add `keystore.properties`, `*.jks`, `*.keystore`, `play-service-account*.json`)
- Create: `docs/RELEASING.md` (how to create the upload key, the `keystore.properties` format, how to bump the version, how to build the AAB, where `mapping.txt` goes. Tasks 14 and 15 extend it.)

- [ ] **Step 1:** Run `./gradlew :androidApp:bundleRelease` without a keystore and expect it to FAIL with `Release signing not configured`.
- [ ] **Step 2:** Run `./gradlew :androidApp:bundleRelease -Pstraphanger.allowDebugSignedRelease=true` and expect `androidApp/build/outputs/bundle/release/androidApp-release.aab` to exist. Then run `bundletool dump manifest --bundle <aab> | grep -E 'versionCode|versionName|debuggable'` and expect `versionCode="1000"`, `versionName="0.1.0"`, and no `debuggable="true"`.
- [ ] **Step 3:** Run the instrumented suite against the minified build (Review Focus 5): `./gradlew :androidApp:connectedReleaseAndroidTest -PtestBuildType=release -Pstraphanger.allowDebugSignedRelease=true`. Expect all to pass. If any fail with a missing class or serializer, add the narrowest keep rule and re-run. Then use the `r8-analyzer` skill on `proguard-rules.pro` and remove any redundant rules it finds.
- [ ] **Step 4:** Check 16 KB page alignment. Build `assembleRelease` with the same flag, then run `$ANDROID_HOME/build-tools/36.0.0/zipalign -c -P 16 -v 4 androidApp/build/outputs/apk/release/androidApp-release.apk` and expect `Verification successful`. If the APK has no `lib/` entries, record that in RELEASING.md instead.
- [ ] **Step 5:** Write `docs/RELEASING.md`. Include the user's key-creation command: `keytool -genkeypair -v -keystore ~/keys/straphanger-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000`. The user runs this and keeps the passwords; subagents never do.
- [ ] **Step 6:** `jj commit -m "build: add release signing, version.properties versioning, R8 and AAB output"`

### Task 13: Play policy compliance: disclosures, privacy policy, About screen

**Files:**
- Create: `shared/src/commonMain/kotlin/com/straphanger/app/ui/permissions/DisclosureScreens.kt` with `@Composable fun BackgroundLocationDisclosure(onContinue: () -> Unit, onNotNow: () -> Unit)` (tag `background_location_disclosure`) and `@Composable fun NotificationAccessDisclosure(onContinue: () -> Unit, onNotNow: () -> Unit)` (tag `notification_access_disclosure`). Exact copy:
  - Background location: **"Straphanger uses your location in the background to notice when you enter and leave subway stations, even when the app is closed. Your location is used only on this phone to record rides and is never sent anywhere."** Buttons: "Not now", "Continue".
  - Notification access: **"Straphanger can read Google Maps transit navigation notifications to record the ride you're taking. It ignores notifications from every other app, and nothing leaves your phone."** Buttons: "Not now", "Continue".
- Modify: `.../App.kt` (`onRequestPassiveCapture` / `onRequestNotificationAccess` from Task 9 show the matching disclosure first; the system prompt or settings screen opens only after "Continue")
- Create: `shared/.../ui/about/AboutScreen.kt` (tag `about_screen`, reachable from the Sources tab: app version; `"Subway data: MTA GTFS, ${network.sourceDate}"`; the attribution text from `tools/gtfs-import/README.md`; **"Unofficial app. Not affiliated with or endorsed by the Metropolitan Transportation Authority."**; a privacy policy link; open-source licenses via AboutLibraries)
- Modify: `gradle/libs.versions.toml` + build files: AboutLibraries (`com.mikepenz.aboutlibraries.plugin` + `aboutlibraries-compose-m3`), pinned to the newest release that supports Compose Multiplatform 1.12
- Create: `shared/.../AppInfo.kt` (`const val PRIVACY_POLICY_URL = "https://<github-user>.github.io/straphanger/privacy-policy"`; the user confirms the host in Task 14)
- Create: `docs/privacy-policy.md` (plain language: what's accessed (precise and background location, Google Maps notifications, files you choose), what's stored (rides on the device and in Android backup), what's sent (only `maps.app.goo.gl` link resolution requests), no accounts, no analytics, no ads, how to delete data (clear the app's storage or uninstall), a contact email placeholder `<contact-email>` for the user to fill in)
- Create: `docs/play/data-safety.md` (draft answers for the Play Console Data safety form, with the reasoning for each)
- Create: `docs/play/permissions-declaration.md` (background-location declaration text, plus a shot list for the demo video Play requires)
- Modify: `androidApp/src/androidTest/.../SubwayTrackerEmulatorTest.kt`

- [ ] **Step 1:** Write instrumented tests:
  - `passiveCaptureShowsDisclosureBeforePermissionPrompt`: tapping the passive-capture toggle shows `background_location_disclosure`. "Not now" closes it, and the toggle stays off.
  - `notificationAccessShowsDisclosureFirst`: tapping the notification toggle shows `notification_access_disclosure`.
  - `aboutScreenShowsDisclaimerAndDataDate`: `about_screen` contains the text "Not affiliated with or endorsed by" and `"MTA GTFS, "`.
- [ ] **Step 2:** Run them on the emulator and expect FAIL.
- [ ] **Step 3:** Implement the screens and wiring. Expect PASS.
- [ ] **Step 4:** Run the `play-policy-insights` skill against the project. Fix every finding it classifies as a violation, and list the rest in `docs/play/data-safety.md` under "Reviewed, no change".
- [ ] **Step 5:** Run the three Global Constraints commands and expect them to pass.
- [ ] **Step 6:** `jj commit -m "feat: add Play-required disclosures, About screen and privacy policy"`

### Task 14: Release pipeline and store listing

**Files:**
- Modify: `gradle/libs.versions.toml` + `androidApp/build.gradle.kts`: Gradle Play Publisher (`com.github.triplet.play`), pinned to the newest release that supports AGP 9.5. `play { track.set("internal"); defaultToAppBundles.set(true); serviceAccountCredentials.set(file(System.getenv("STRAPHANGER_PLAY_CREDENTIALS") ?: "play-service-account.json")) }`.
- Create: `androidApp/src/main/play/listings/en-US/title.txt` ("Straphanger"), `short-description.txt` (≤ 80 chars), `full-description.txt` (≤ 4000 chars, following the Task 15 style rules and including the not-affiliated sentence from Task 13), `androidApp/src/main/play/release-notes/en-US/default.txt`
- Create: `androidApp/src/main/play/listings/en-US/graphics/icon/icon.png` (512×512 from the launcher icon), `feature-graphic/feature.png` (1024×500), `phone-screenshots/1.png`…`4.png` (Map, Stats, Rides, About), captured with `adb exec-out screencap -p` on the `Pixel_9a` AVD after "Load sample rides"
- Create: `scripts/check-listing.sh` (fails if `title.txt` > 30, `short-description.txt` > 80 or `full-description.txt` > 4000 characters, or if any file in `listings/` matches the Task 15 banned-word grep)
- Create: `.github/workflows/release.yml`. It triggers on `push: tags: ['v*']`. Steps: checkout; JDK 17; fail unless `${GITHUB_REF_NAME#v}` equals `versionName` in `version.properties`; `scripts/check-listing.sh`; decode secret `UPLOAD_KEYSTORE_BASE64` to `$RUNNER_TEMP/upload.jks` and export the four `STRAPHANGER_UPLOAD_*` env vars from secrets; `./gradlew :shared:testAndroidHostTest :androidApp:bundleRelease`; write secret `PLAY_SERVICE_ACCOUNT_JSON` to `$RUNNER_TEMP/play.json` and run `./gradlew :androidApp:publishReleaseBundle` with `STRAPHANGER_PLAY_CREDENTIALS` pointing at it; upload the AAB and `androidApp/build/outputs/mapping/release/mapping.txt` as workflow artifacts.
- Modify: `docs/RELEASING.md` (the release steps: bump `version.properties`, update release notes, `jj commit`, tag `vX.Y.Z`, push; a list of the GitHub secrets; one-time Play Console setup)

- [ ] **Step 1:** Run `scripts/check-listing.sh` against a deliberately 81-character short description and expect a non-zero exit. Restore the real text and expect exit 0.
- [ ] **Step 2:** Run `./gradlew :androidApp:tasks --group publishing` and expect `publishReleaseBundle` in the list.
- [ ] **Step 3:** Check the workflow with `actionlint .github/workflows/release.yml` (install it if it's missing) and expect no output.
- [ ] **Step 4:** Write the one-time **user** checklist in RELEASING.md. These are steps the user does in the Play Console, not subagents: create the app `com.straphanger.app`; enable Play App Signing; upload the first AAB by hand to Internal testing; finish the content rating, target audience, Data safety (from `docs/play/data-safety.md`) and background-location declaration (from `docs/play/permissions-declaration.md`, plus the recorded video); create a service account with release permissions and store its JSON as `PLAY_SERVICE_ACCOUNT_JSON`; enable GitHub Pages for `docs/` and confirm `PRIVACY_POLICY_URL`; fill in `<contact-email>`.
- [ ] **Step 5:** `jj commit -m "build: add Play listing metadata and tag-triggered release workflow"`

## Phase 4: Documentation

### Task 15: Rewrite the docs and remove marketing language (F11)

**Files:**
- Rewrite: `README.md`
- Modify: KDoc and comments under `shared/src`, `androidApp/src`, `tools/` (find them with `grep -rniE 'adversarial|solves|60 ?fps|strictly|vibrant|comprehensive|deep system|seamless|blazing|tri-format|powerful|effortless' shared androidApp tools docs androidApp/src/main/play`)
- Modify: user-facing strings in `shared/.../ui/**` found by the same grep, plus any string claiming SQLite, cloud sync, "offline-ready underground" or similar
- Keep and link: `tools/gtfs-import/README.md`, `docs/RELEASING.md`, `docs/privacy-policy.md`

**README must contain, in this order:** a one-paragraph description of what the app does; a **feature status table** with columns *Feature | Android | iOS | Notes*, where each cell is one of `Works`, `Partial`, `Not started`, and the table matches the real state after Tasks 1–14 (any task not done is marked as such); setup (JDK 17, Android SDK platform 36, the Xcode version used in Task 10); build/test commands using the `:shared`/`:androidApp` paths; data sources, their date and the attribution; a link to RELEASING.md; known limitations (e.g. the background-location policy, no cross-platform sync).

**Style rules (these apply to all user-facing text from Task 1 onward):** plain declarative sentences; no adjectives that are only praise; no performance claims without a measurement; no hex color lists; no emoji; numbers only when they come from the code or data (e.g. "N stations, generated from GTFS dated YYYY-MM-DD").

- [ ] **Step 1:** Run the grep and expect it to list every hit.
- [ ] **Step 2:** Rewrite the README to the structure above, and fix every hit in comments, UI strings and listing text.
- [ ] **Step 3:** Verify. Re-run the grep and expect no output. Re-run the three Global Constraints commands, `:androidApp:connectedDebugAndroidTest` and `scripts/check-listing.sh`, and expect them all to pass.
- [ ] **Step 4:** Check the table by hand: every `Works` cell names a feature you exercised in Tasks 5–14.
- [ ] **Step 5:** `jj commit -m "docs: rewrite README to match the implementation; remove promotional wording"`

---

## User-only steps (subagents must not do these)

- Create the upload keystore and choose its passwords (Task 12, Step 5 command).
- Everything in the Play Console, GitHub Secrets and GitHub Pages (Task 14, Step 4).
- Run Tasks 10–11 on a Mac.
- Decide on trademark risk. The app draws colored route circles with letters, which look like MTA route bullets, and the MTA asserts trademarks on its marks. The plan already avoids MTA logos in the icon and adds a not-affiliated statement. Before a public launch, check the MTA's current trademark and data-feed terms and decide whether the in-app route symbols need to change.
