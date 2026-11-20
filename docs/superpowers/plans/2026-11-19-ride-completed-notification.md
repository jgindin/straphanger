# Ride-Completed Notification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Post a notification for every ride recorded by passive capture or by a Google Maps
navigation session, even when the app is closed. Tapping the notification opens the ride.

**Architecture:**
- A new process-wide `RideCaptureRuntime` in `:shared` owns:
  - the `SubwayDao`;
  - the network load;
  - the `PassiveCaptureCoordinator`;
  - the Google Maps session recorder.
- The view model uses the runtime instead of building its own. Android keeps one runtime per
  process in a holder.
- The geofence, boot and listener receivers call `runtime.catchUp()`, and so does a new
  idle-close alarm receiver. `catchUp()` turns stored events into rides when no UI runs.
- Each saved ride produces text from shared code. An Android `RideNotifier` posts it.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, coroutines, Android `NotificationManagerCompat`, `AlarmManager`, Robolectric (androidHostTest), jj.

**Spec:** `docs/superpowers/specs/2026-11-19-ride-completed-notification-design.md`

## Global Constraints

- The rides file has exactly one `SubwayDao` per process. Nothing else loads or saves
  `rides.json`.
- Every DAO write runs on the runtime's dispatcher. The view model must be built with the same
  dispatcher: `Dispatchers.Main.immediate` on Android, `Dispatchers.Unconfined` in tests.
- Notify only after a successful save (`dao.lastSaveSucceeded`), never for a skipped duplicate,
  and once per ride.
- Notify only for rides from `RideSource.PASSIVE_GPS` (passive capture) and from Google Maps
  navigation sessions. Never notify for manual entry, shared routes, Timeline import or station
  edits.
- Reconstructor rules (`DEFAULT_IDLE_CLOSE_MS = 15 min`, `DWELL_SPLIT_MS = 6 min`) are unchanged.
- No new Gradle dependency (no WorkManager): use `AlarmManager.setAndAllowWhileIdle(RTC_WAKEUP, …)`.
- The channel id is `rides_recorded`, its name is "Rides recorded", and its importance is
  `IMPORTANCE_DEFAULT`.
- The setting switch reads "Notify me when a ride is recorded". It is off by default.
- Android 13+ asks for `POST_NOTIFICATIONS` when the switch is turned on. A denial leaves the
  switch off.
- iOS gets nothing new, but `:shared` common code must still compile for iOS targets.
- Version: `version.properties` is `0.22`. Run `./gradlew syncIosVersion`.
- Commits use jj (`jj commit -m …`). Each message ends with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Before every commit, run: `./gradlew ktfmtFormat detekt :shared:testAndroidHostTest`.
  - Detekt requires the file name to match its single top-level class.
  - Detekt allows at most 11 functions per file.
- Run shell deletes as `command rm -f` (`rm` is aliased to interactive).

## Review Focus

1. **Two rides close in one drain** (e.g. a replay of a long backlog). Expect two notifications,
   not just the last ride. Pinned in Task 2 (`aBacklogOfTwoRidesNotifiesTwice`).
2. **The process died mid-chain, then the idle alarm fires in a fresh process.** Expect the ride
   recorded and notified once. Opening the app afterwards must not record or notify it again.
   Pinned in Task 3 (`catchUpInAFreshRuntimeRecordsOnceAndTheAppDoesNotRepeatIt`).
3. **A save fails** (disk full). Expect no notification. The next catch-up records the ride and
   notifies once. Pinned in Task 2 (`aFailedSaveDoesNotNotifyAndTheReplayDoes`).
4. **The switch is off, or the permission was revoked in system settings.** Expect nothing posted
   and no crash. Pinned in Task 4.
5. **Tapping the notification while the app process is dead.** Expect the app to open on the
   Rides tab with that ride highlighted. Pinned in Task 5 (`tappedRideOpensRidesTabHighlighted`).

---

### Task 1: Notification text

**Files:**
- Create: `shared/src/commonMain/kotlin/com/straphanger/app/data/capture/RideNotificationText.kt`
- Test: `shared/src/commonTest/kotlin/com/straphanger/app/data/capture/RideNotificationTextTest.kt`

**Interfaces:**
- Produces:
  - `data class RideNotificationText(val title: String, val text: String)`
  - `fun rideNotificationText(record: CompleteRideRecord, stationName: (String) -> String): RideNotificationText`

Rules:

| Part | Rule | Example |
|---|---|---|
| Title | `"<lines> · <origin name> → <destination name>"` | `"A · 14 St → 168 St"` |
| Lines | Leg route ids in `legIndex` order. Drop a trailing `X` from multi-character ids (`6X` → `6`). Collapse consecutive repeats. Join with `" → "`. | `"A → L"` |
| Text | `"<n> min"`, where `n = max(1, round(durationMinutes))` | `"32 min"` |
| Text, ride needs review and has candidates | add `" · Express or local? Tap to confirm"` | |
| Text, ride needs review, no candidates | add `" · Tap to check this ride"` | |

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun oneLegRideShowsLineStationsAndMinutes() {
  assertEquals(RideNotificationText("A · 14 St → 168 St", "32 min"), rideNotificationText(record(legs = listOf("A"), minutes = 31.6), names))
}
@Test fun transferJoinsLinesAndDropsExpressSuffix() // legs "6X","L" -> title starts "6 → L · "
@Test fun repeatedLineIsShownOnce()                   // legs "A","A" -> "A · …"
@Test fun ambiguousRideAsksExpressOrLocal()          // needsReview + candidates -> "32 min · Express or local? Tap to confirm"
@Test fun flaggedRideWithoutCandidatesAsksToCheck()  // needsReview, no candidates -> "32 min · Tap to check this ride"
@Test fun aVeryShortRideSaysOneMinute()              // 0.2 min -> "1 min"
```

Build `record(...)` in the test from `RideEntity` and `RideLegEntity`, following the
`RideReviewTest` style. `names` maps `A31` to "14 St" and `A09` to "168 St".

- [ ] **Step 2: Run them and see them fail.**
  - Run: `./gradlew :shared:testAndroidHostTest --tests '*RideNotificationTextTest*'`
  - Expected: compilation failure (unresolved reference).
- [ ] **Step 3: Implement `rideNotificationText`.**
- [ ] **Step 4: Run the tests again.** Same command; expected PASS.
- [ ] **Step 5: Commit.** `jj commit -m "feat: text for the ride-recorded notification …"`

### Task 2: `RideCaptureRuntime` owns the DAO, network, capture and Maps sessions; the view model uses it

**Files:**
- Create:
  - `shared/src/commonMain/kotlin/com/straphanger/app/data/capture/RideCaptureRuntime.kt`
  - `shared/src/commonMain/kotlin/com/straphanger/app/data/capture/RideNotifier.kt`
  - `shared/src/commonMain/kotlin/com/straphanger/app/data/capture/NavigationSessionRecorder.kt`:
    the Google Maps session draining moved out of `CapturedRideRecorder`.
- Modify:
  - `PassiveCaptureCoordinator.kt`:
    - add an `onRecorded: (CompleteRideRecord) -> Unit = {}` constructor parameter, called from
      `recorded()` for every ride;
    - add public `fun catchUp()`, which runs `drainPending()` then `closeIfIdle()` without
      registering or collecting.
  - `ui/StraphangerViewModel.kt`, `ui/StraphangerSession.kt`, `ui/PassiveCapture.kt`,
    `ui/CapturedRideRecorder.kt` (keeps only the shared-route inbox), `App.kt`.
- Test:
  - Create `shared/src/commonTest/kotlin/com/straphanger/app/data/capture/RideCaptureRuntimeTest.kt`.
  - All existing tests must still pass unchanged, apart from the constructor wiring changes
    named below.

**Interfaces:**
- Consumes: `rideNotificationText` (Task 1).
- Produces:
  ```kotlin
  fun interface RideNotifier { fun rideRecorded(rideId: String, text: RideNotificationText) }
  // RideNotifier.None posts nothing (companion val).

  class RideCaptureRuntime(
    backend: RidePersistenceBackend,
    val clock: Clock = Clock.System,
    val dispatcher: CoroutineDispatcher,
    networkLoader: suspend () -> SubwayNetworkData = { NycSubwayNetworkCatalog.loadBundled() },
    registrar: GeofenceRegistrar? = null,
    geofenceEvents: Flow<GeofenceEvent> = GeofenceEventBus.events,
    pendingEvents: PendingGeofenceEventStore = InMemoryPendingGeofenceEventStore(),
    notificationSessions: NotificationSessionStore = InMemoryNotificationSessionStore(),
    notificationSessionsEnded: Flow<Int> = NotificationSessionSignal.endedCount,
    notifier: RideNotifier = RideNotifier.None,
    idleCloseScheduler: IdleCloseScheduler = IdleCloseScheduler.None, // Task 3; add the parameter there
  ) {
    val scope: CoroutineScope          // SupervisorJob() + dispatcher; never cancelled by a view model
    val dao: SubwayDao
    /** Loads the network once; later calls return the same result. */
    suspend fun network(): Result<SubwayNetworkData>
    /** The loaded network's services, or null while loading or after a failed load. */
    val services: NetworkServices?     // move NetworkServices out of ui/StraphangerSession.kt into this package if needed; keep its API
    /** Null until the network loads, or when [registrar] is null. */
    val coordinator: PassiveCaptureCoordinator?
    /** Every ride passive capture or a navigation session saved, as it is saved. */
    val recordedRides: SharedFlow<CompleteRideRecord>   // replay = 0, extraBufferCapacity = 16
    /** Starts the network load and the navigation-session collector; idempotent. */
    fun start()
    /** start(); waits for the network; then coordinator?.catchUp() and drains ended navigation sessions. */
    suspend fun catchUp()
  }
  ```
- The view model gains a constructor parameter, `runtime: RideCaptureRuntime? = null`.
  - When it is null, the view model builds one from its existing `backend`, `clock`,
    `dispatcher`, `networkLoader`, `geofenceRegistrar`, `geofenceEvents`, `pendingGeofenceEvents`,
    `notificationSessions` and `notificationSessionsEnded`. Existing tests then keep working.
  - `StraphangerSession` takes the runtime's `dao` instead of building its own.
  - The view model's scope stays its own, on the same dispatcher.
  - `PassiveCaptureController` takes `runtime.coordinator` instead of creating one. It still
    starts and stops it according to `CaptureAccessState`.
  - The view model collects `runtime.recordedRides` to show its existing status messages:
    "Recorded ride from Google Maps navigation: …" and the passive one.
- `App(...)` gains `captureRuntime: RideCaptureRuntime? = null` and passes it into the view model.
- The notifier is called inside the runtime, for each ride in `recordedRides`:
  `notifier.rideRecorded(id, rideNotificationText(record, services::stationName))`.

- [ ] **Step 1: Write failing tests** in `RideCaptureRuntimeTest`. Use `Dispatchers.Unconfined`,
  `TestNetwork.data`, a recording `RideNotifier`, a `FixedClock`, and a fake `GeofenceRegistrar`
  (reuse the one existing coordinator tests use).
  - `catchUpTurnsStoredStationEventsIntoARideAndNotifiesWithNoViewModel`:
    - Pending store has the exit at 635 at t0, then the entry at 626 at t0+10min.
    - Clock is t0+30min.
    - `catchUp()` saves one ride.
    - The notifier got its id and a title starting with `"4 · "` or `"5 · "` or `"6 · "`. Assert it
      contains "→".
  - `aBacklogOfTwoRidesNotifiesTwice`: two separate chains in the pending store, two
    notifications.
  - `aFailedSaveDoesNotNotifyAndTheReplayDoes`:
    - Use a backend that throws on save. Pattern: `TimelineImportFileTest.failedSaveDoesNotReportImportedRides`.
    - First `catchUp()`: zero notifications.
    - Stop failing; second `catchUp()`: exactly one notification.
  - `endedNavigationSessionIsRecordedAndNotified`, and a duplicate of an existing ride is not
    notified. Reuse the session fixture from the existing `CapturedRideRecorder` tests:
    `grep -rl NavigationNotificationSession shared/src/commonTest`.
  - `viewModelAndBackgroundShareOneDao`:
    - Build a runtime and a view model with `runtime = it`.
    - `assertSame(runtime.dao, vm.dao)`.
    - A ride from `catchUp()` appears in `vm.uiState.value.rides`.
  - `manualRidesAreNotNotified`: `vm.logManualRide(...)`, or whichever `RideLogging` action exists,
    sends zero notifications.
- [ ] **Step 2: Run them and see them fail.**
  `./gradlew :shared:testAndroidHostTest --tests '*RideCaptureRuntimeTest*'`; expected
  compilation failure.
- [ ] **Step 3: Implement.**
  - Add `onRecorded` and `catchUp()` to the coordinator.
  - Create `RideNotifier` and `RideCaptureRuntime`.
  - Move Maps-session draining into `NavigationSessionRecorder(dao, servicesProvider, store, onSaved)`.
  - Rewire `ViewModelParts` and `App`.
- [ ] **Step 4: Run the full suite.** `./gradlew :shared:testAndroidHostTest`; expected all PASS,
  including the existing view model, capture and screen tests.
- [ ] **Step 5: Commit.** `jj commit -m "refactor: one process-wide ride capture runtime that notifies on recorded rides …"`

### Task 3: Idle close survives the process dying

**Files:**
- Create: `shared/src/commonMain/kotlin/com/straphanger/app/data/capture/IdleCloseScheduler.kt`
- Modify: `PassiveCaptureCoordinator.kt` and `RideCaptureRuntime.kt`, which passes the scheduler
  through.
- Test:
  - `shared/src/commonTest/kotlin/com/straphanger/app/data/capture/PassiveCaptureCoordinatorTest.kt`
    (existing; add tests);
  - `RideCaptureRuntimeTest.kt`.

**Interfaces:**
- Produces:
  ```kotlin
  interface IdleCloseScheduler {
    /** Asks the platform to call RideCaptureRuntime.catchUp() at or after [atEpochMs], replacing any earlier request. */
    fun schedule(atEpochMs: Long)
    fun cancel()
    companion object { val None: IdleCloseScheduler }
  }
  ```
- The coordinator gets an `idleCloseScheduler: IdleCloseScheduler = IdleCloseScheduler.None`
  parameter.
  - In `scheduleIdleClose()`: when a chain is open, call `schedule(chainLastTimeMs + idleCloseMs)`;
    when none is open, call `cancel()`.
  - `catchUp()` also ends in `scheduleIdleClose()`.

- [ ] **Step 1: Write failing tests.**
  - `openChainSchedulesTheIdleCloseAtLastEntryPlusIdlePeriod`: an entry at t schedules
    `t + DEFAULT_IDLE_CLOSE_MS`; a second hop at t2 replaces it with `t2 + DEFAULT_IDLE_CLOSE_MS`.
  - `closingTheChainCancelsTheIdleClose`.
  - `catchUpInAFreshRuntimeRecordsOnceAndTheAppDoesNotRepeatIt`:
    - Use a file-free shared `InMemoryPersistenceBackend` and pending store across two runtimes,
      so runtime 2 simulates a fresh process.
    - Runtime 1 handles the entries, then is dropped with the chain still open.
    - Runtime 2, with the clock past idle, runs `catchUp()`: one ride, one notification.
    - Then a view model on runtime 2 starts and capture starts: still one ride, no further
      notification.
  - `catchUpTwiceAfterIdleNotifiesOnce`.
- [ ] **Step 2: Run them and see them fail.** `./gradlew :shared:testAndroidHostTest --tests '*PassiveCaptureCoordinatorTest*' --tests '*RideCaptureRuntimeTest*'`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the full `:shared:testAndroidHostTest`;** expected PASS.
- [ ] **Step 5: Commit.** `jj commit -m "feat: idle rides close on a platform alarm when no app is running …"`

### Task 4: Android notifier, runtime holder, alarm and receivers

**Files:**
- Create:
  - `shared/src/androidMain/kotlin/com/straphanger/app/notify/AndroidRideNotifier.kt`
  - `androidApp/src/main/kotlin/com/straphanger/app/capture/CaptureRuntimeHolder.kt`
  - `androidApp/src/main/kotlin/com/straphanger/app/capture/AlarmIdleCloseScheduler.kt`
  - `androidApp/src/main/kotlin/com/straphanger/app/capture/IdleCloseReceiver.kt`
- Modify:
  - `androidApp/src/main/AndroidManifest.xml`: `POST_NOTIFICATIONS` and the `IdleCloseReceiver`,
    which is not exported.
  - `GeofenceBroadcastReceiver.kt`, `BootCompletedReceiver.kt`,
    `GoogleMapsTransitNotificationListener.kt`.
  - `PassiveCaptureStorage.kt`: add `rideNotificationsTurnedOn(context)` and
    `setRideNotificationsTurnedOn(context, on)`, under the same prefs as the capture flag.
  - `MainActivity.kt`: pass `captureRuntime = CaptureRuntimeHolder.get(applicationContext)` and
    stop building its own `FileRidePersistenceBackend`.
- Test: `shared/src/androidHostTest/kotlin/com/straphanger/app/notify/AndroidRideNotifierTest.kt`
  (Robolectric).

**Interfaces:**
- Consumes: `RideNotifier`, `RideNotificationText`, `RideCaptureRuntime`, `IdleCloseScheduler`.
- Produces:
  ```kotlin
  class AndroidRideNotifier(
    context: Context,
    private val turnedOn: () -> Boolean,
    private val tapIntent: (rideId: String) -> PendingIntent,
  ) : RideNotifier
  // CHANNEL_ID = "rides_recorded"; creates the channel on first post. Posts nothing when
  // turnedOn() is false or NotificationManagerCompat.areNotificationsEnabled() is false.
  // Notification id = rideId.hashCode(); tag = rideId; auto-cancel; small icon = the app's existing notification-safe icon (add a vector drawable ic_stat_ride in shared resources if none exists).
  object CaptureRuntimeHolder { fun get(context: Context): RideCaptureRuntime }  // synchronized lazy; Dispatchers.Main.immediate
  const val EXTRA_RIDE_ID = "com.straphanger.app.RIDE_ID"  // in MainActivity companion
  ```
- Holder wiring:
  - `FileRidePersistenceBackend(Path(filesDir, "rides.json"), syncFile = fsync)`, moving
    `syncToStorage` out of `MainActivity` into the holder;
  - `AndroidGeofenceRegistrar`;
  - `PassiveCaptureStorage.pendingEvents` and `notificationSessions`;
  - `AndroidRideNotifier(turnedOn = { PassiveCaptureStorage.rideNotificationsTurnedOn(ctx) }, tapIntent = activity PendingIntent with EXTRA_RIDE_ID, FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT, requestCode = rideId.hashCode())`;
  - `AlarmIdleCloseScheduler(ctx)`.
- `AlarmIdleCloseScheduler` uses `AlarmManager.setAndAllowWhileIdle(RTC_WAKEUP, atEpochMs, pi)`,
  where `pi` is a broadcast to `IdleCloseReceiver` with `FLAG_IMMUTABLE`. `cancel()` cancels that
  `pi`.
- Every receiver uses the same pattern: `goAsync()`, then launch on the holder runtime's scope,
  then `withTimeoutOrNull(9_000) { runtime.catchUp() }`, then `pending.finish()`. Each one also
  checks `BackgroundGeofenceRefresher.captureAllowed(context)` before catching up passive events.
  - `IdleCloseReceiver` always runs the pattern.
  - `GeofenceBroadcastReceiver` runs it after appending a station event, and only when
    `!GeofenceEventBus.hasCollectors`. The existing `BackgroundGeofenceRefresher` call stays.
  - `BootCompletedReceiver` runs it in addition to the existing refresh.
  - `GoogleMapsTransitNotificationListener` calls `CaptureRuntimeHolder.get(ctx).start()` in
    `onListenerConnected`, so the runtime's session collector drains ended sessions. The service
    is long-lived, so `goAsync` isn't needed there.

- [ ] **Step 1: Write failing Robolectric tests** in `AndroidRideNotifierTest`, using
  `ShadowNotificationManager` (`shadowOf(notificationManager).allNotifications`):
  - `postsOnTheRidesRecordedChannelWithTitleTextAndTapIntent`:
    - Expect channel `rides_recorded` with name "Rides recorded" and `IMPORTANCE_DEFAULT`.
    - Expect the title and text from `RideNotificationText`.
    - Expect `contentIntent` to be the supplied one.
    - Expect auto-cancel.
  - `postsNothingWhenTurnedOff`.
  - `postsNothingWhenNotificationsAreDisabled`:
    `shadowOf(notificationManager).setNotificationsEnabled(false)`.
  - `twoRidesMakeTwoNotifications`.
- [ ] **Step 2: Run and see them fail.** `./gradlew :shared:testAndroidHostTest --tests '*AndroidRideNotifierTest*'`
- [ ] **Step 3: Implement** the notifier, holder, scheduler, receivers and manifest entries.
- [ ] **Step 4: Run the notifier tests and the whole build.** Run
  `./gradlew :shared:testAndroidHostTest :androidApp:assembleDebug :androidApp:lint`; expected
  PASS with no new lint errors. Check lint for `MissingPermission` around `notify`: guard it with
  `areNotificationsEnabled()` and suppress with a comment, as `BootCompletedReceiver` does.
- [ ] **Step 5: Commit.** `jj commit -m "feat: Android posts a notification when a ride is recorded, even with the app closed …"`

### Task 5: Settings switch, permission request, and opening the tapped ride

**Files:**
- Modify:
  - `shared/src/commonMain/kotlin/com/straphanger/app/ui/CaptureAccessState.kt`, wherever it is
    defined: add `rideNotificationsTurnedOn: Boolean = false` and
    `postNotificationsGranted: Boolean = true`.
  - `ui/rides/DataSourcesAndSyncScreen.kt`: a new switch in the passive-capture section.
  - `App.kt` and `PlatformHooks`: `onRequestRideNotifications: () -> Unit = {}` and
    `onDisableRideNotifications: () -> Unit = {}`.
  - `ScreenNavigation` and its controller: `fun showRide(rideId: String)`.
  - `StraphangerUiState`: `highlightedRideId: String? = null`.
  - The rides list composable, which scrolls to and highlights `highlightedRideId`. The highlight
    is cleared on the next tab change.
  - `MainActivity.kt`:
    - the permission launcher (API 33+);
    - stores the choice;
    - reads `EXTRA_RIDE_ID` in `onCreate` and `onNewIntent` into a `RideTapInbox`.
- Create: `shared/src/commonMain/kotlin/com/straphanger/app/ui/RideTapInbox.kt`. It is like
  `SharedRouteInbox`: a `MutableStateFlow<String?>` with `fun open(rideId: String)` and
  `fun consume(): String?`. `App` collects it and calls `viewModel.showRide(id)`.
- Test: `shared/src/androidHostTest/kotlin/com/straphanger/app/SourcesAndAboutScreenTest.kt` and
  `RidesScreenTest.kt` (add tests).

**Interfaces:**
- Consumes: `PassiveCaptureStorage.setRideNotificationsTurnedOn` (Task 4) and `EXTRA_RIDE_ID`.
- Switch behaviour:

  | Element | Value |
  |---|---|
  | testTag | `ride_notifications_toggle` |
  | Title | "Notify me when a ride is recorded" |
  | Subtitle when on | "A notification appears when passive capture or Google Maps records a ride." |
  | Subtitle when off | "Off." |
  | Subtitle when turned on but permission missing | "Notifications are blocked for Straphanger in system settings." (shown checked = false) |

- Turning the switch on calls `onRequestRideNotifications`. Android stores "on" and, on API 33+,
  launches the permission request; a denial stores "off". On older versions, it just stores "on".
- After the result, Android refreshes `CaptureAccessState` the same way `refreshCaptureAccess()`
  does. `postNotificationsGranted` comes from `NotificationManagerCompat.areNotificationsEnabled()`.

- [ ] **Step 1: Write failing tests.**
  - `rideNotificationSwitchCallsRequestWhenTurnedOnAndDisableWhenOff`.
  - `rideNotificationSwitchExplainsWhenBlocked`: turned on, not granted, so the blocked text is
    shown and the switch is unchecked.
  - `tappedRideOpensRidesTabHighlighted`:
    - Call `RideTapInbox.open(id)` before composing `App`.
    - Expect the active tab to be Rides.
    - Expect the ride row with that id to be displayed, with its highlight tag
      `ride_row_highlighted`.
- [ ] **Step 2: Run them and see them fail.** `./gradlew :shared:testAndroidHostTest --tests '*SourcesAndAboutScreenTest*' --tests '*RidesScreenTest*'`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run `./gradlew :shared:testAndroidHostTest :androidApp:assembleDebug`;** expected PASS.
- [ ] **Step 5: Commit.** `jj commit -m "feat: switch for ride notifications, and tapping one opens the ride …"`

### Task 6: Version and docs

**Files:**
- Modify:
  - `version.properties` → `0.22`, then run `./gradlew syncIosVersion`;
  - `CHANGELOG.md`: a 0.22 entry;
  - `README.md`: the feature table row;
  - `CODE-TOUR.md`: `RideCaptureRuntime`, the receivers and the alarm;
  - the privacy policy: notifications are posted on the phone only, and nothing is sent anywhere.

- [ ] **Step 1: Edit the files.**
- [ ] **Step 2: Run the full CI equivalent.**
  - Run: `./gradlew ktfmtCheck detekt checkIosVersion :shared:testAndroidHostTest koverVerify :androidApp:assembleDebug :androidApp:lint`
  - Expected: BUILD SUCCESSFUL.
- [ ] **Step 3: Commit.** `jj commit -m "docs: ride notifications in the changelog, readme, code tour and privacy policy …"`
