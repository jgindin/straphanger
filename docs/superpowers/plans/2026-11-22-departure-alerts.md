# Departure Alerts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** At a time and on days the rider picks, post one notification with the next live
departures of one line at one station in one direction, with a **See trains** action that opens
the Status tab filtered to that line and direction.

**Architecture:**
- Shared code (`data/commute/`) holds the `Commute` model, its JSON store, the next-occurrence
  calculation, the notification text, and `CommuteAlerts`, which fires one commute and keeps
  every enabled commute's alarm set. Platform pieces sit behind two small interfaces,
  `CommuteNotifier` and `CommuteAlarmScheduler`.
- Android implements them with `NotificationManagerCompat` and an exact `AlarmManager` alarm per
  commute (inexact when exact alarms aren't allowed), plus receivers for the alarm and for
  boot / clock / time zone / update / permission changes. One `CommuteAlerts` per process lives in
  a holder, like `CaptureRuntimeHolder`.
- The Status tab gets a Commutes section, an editor dialog, an **Add commute alert** button and a
  line-and-direction filter. A tapped alert reaches it through `CommuteTapInbox`, like
  `RideTapInbox`.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, kotlinx-datetime 0.8, kotlinx-serialization,
coroutines, Android `AlarmManager` / `NotificationManagerCompat`, Robolectric (androidHostTest), jj.

**Spec:** `docs/superpowers/specs/2026-11-22-departure-alerts-design.md`

## Global Constraints

- Shared code lives under `shared/src/commonMain/kotlin/com/straphanger/app/data/commute/` and
  must compile for iOS targets; iOS gets `CommuteAlarmScheduler.None` / `CommuteNotifier.None`.
- No new Gradle dependency. kotlinx-datetime and kotlinx-serialization are already present.
- Commutes are stored as JSON through `JsonStateFile` in `commutes.json` in `filesDir`; a corrupt
  file is moved aside and read as empty (what `JsonStateFile` already does).
- Channel id `commute_alerts`, name "Commute alerts", `IMPORTANCE_DEFAULT`. Each commute posts
  with tag = its id.
- Notification title "<line> <Northbound|Southbound> · <complex name>", e.g.
  "A Southbound · 168 St". Text: up to 3 departures, "3, 9, 14 min" ("now" for 0, via
  `minutesText`). No trains: "No <line> trains listed <northbound|southbound> right now". Feed
  failure or timeout: "Couldn't get train times". Action label "See trains".
- A commute's line matches `routeId == line` or `routeId == line + "X"`.
- Exact alarms: `setExactAndAllowWhileIdle` when `AlarmManager.canScheduleExactAlarms()` (always
  true below API 31), else `setAndAllowWhileIdle`. Manifest declares `SCHEDULE_EXACT_ALARM`; never
  `USE_EXACT_ALARM`.
- Fetch timeout 8 s (`COMMUTE_FETCH_TIMEOUT_MS = 8_000L`); the receiver uses `goAsync()`.
- An occurrence fired more than 15 min (`LATE_SKIP_MS = 15 * 60_000L`) after its time posts
  nothing.
- Defaults in the editor: days Monday–Friday, time 08:00, enabled.
- Copy: "Add commute alert", "Commutes", "Alerts may be a few minutes late. Tap to allow exact
  alarms.", "Notifications are blocked for Straphanger. Tap to allow them in system settings.",
  "Station no longer listed", "Weekdays" for Mon–Fri, "Every day" for all seven, otherwise
  abbreviated days "Mon, Wed".
- Version: `version.properties` is `0.25`; run `./gradlew syncIosVersion`.
- Commits use jj (`jj commit -m …`); messages end with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Before every commit: `./gradlew ktfmtFormat detekt :shared:testAndroidHostTest` (and
  `:androidApp:testDebugUnitTest` if androidApp tests exist for the task). Detekt: file name
  matches its single top-level class; at most 11 functions per file (tests too).
- Run shell deletes as `command rm -f`.

## Review Focus

1. **Spring-forward / fall-back days.** 02:30 on the US spring-forward day must fire at 03:00
   local; on fall-back day it fires once. Pinned in Task 1 (`springForwardFiresAtFirstValidMinute`,
   `fallBackFiresOnce`).
2. **Alarm fires late after Doze or boot.** More than 15 min late posts nothing but sets the next
   occurrence. Pinned in Task 2 (`aLateFireIsSkippedAndTheNextIsScheduled`).
3. **Two commutes at the same minute.** Each gets its own alarm and notification; editing one
   doesn't cancel the other. Pinned in Task 3 (`twoCommutesHaveDistinctAlarms`).
4. **The MTA feed hangs.** After 8 s the alert says "Couldn't get train times" rather than
   nothing. Pinned in Task 2 (`aHangingFetchPostsCouldNotGetTrainTimes`).
5. **Rotation / reopening from recents after tapping See trains.** The filter is applied once and
   not re-applied. Pinned in Task 4 (`seeTrainsFilterIsAppliedOnce`) and by the existing
   `MainActivity` guard reused in Task 6.

---

### Task 1: Commute model, store and schedule

**Files:**
- Create: `shared/src/commonMain/kotlin/com/straphanger/app/data/commute/Commute.kt`
- Create: `.../data/commute/CommuteStore.kt` (interface `CommuteStore`, `InMemoryCommuteStore`, `FileCommuteStore`)
- Create: `.../data/commute/CommuteSchedule.kt`
- Test: `shared/src/androidHostTest/kotlin/com/straphanger/app/data/commute/CommuteScheduleTest.kt`, `CommuteStoreTest.kt`

**Interfaces:**
- Produces:
  - `@Serializable data class Commute(val id: String, val routeId: String, val complexId: String, val direction: Direction, val days: Set<DayOfWeek>, val hour: Int, val minute: Int, val enabled: Boolean = true)`.
    `Direction` is `data.realtime.Direction` (make it `@Serializable`); `DayOfWeek` is
    `kotlinx.datetime.DayOfWeek`.
  - `fun newCommuteId(): String` — random, e.g. `"c_" + Random.nextLong().toULong().toString(36)`.
  - `interface CommuteStore { fun read(): List<Commute>; fun write(commutes: List<Commute>) }`;
    never throws for storage failures (log via `DiagnosticsLog`, tag `StraphangerCommutes`).
  - `class FileCommuteStore(file: Path, fileSystem: FileSystem = SystemFileSystem, clock: Clock = Clock.System)`.
  - `fun Commute.nextOccurrenceMs(afterEpochMs: Long, zone: TimeZone): Long?` — the first instant
    strictly after `afterEpochMs` whose local date's day is in `days` and whose local time is
    `hour:minute`; a nonexistent local time resolves to the first valid instant after it
    (`LocalDateTime.toInstant(zone)` already does this). `null` when `days` is empty.
  - `fun Commute.occurrenceAtOrBeforeMs(epochMs: Long, zone: TimeZone): Long?` — the latest
    occurrence `<= epochMs`, used to measure lateness.
  - `fun daysText(days: Set<DayOfWeek>): String` — "Weekdays", "Every day", or "Mon, Wed"
    (Monday-first order, three-letter names).

- [ ] **Step 1: Write failing tests** in `CommuteScheduleTest` (zone `America/New_York`):
  - `nextOccurrenceIsLaterTodayWhenTimeNotPassed` (Mon 07:00 → Mon 07:45 for a 07:45 weekday commute)
  - `nextOccurrenceSkipsToMondayFromFridayEvening` (Fri 18:00 → Mon 07:45)
  - `nextOccurrenceIsStrictlyAfter` (exactly Mon 07:45 → Tue 07:45)
  - `springForwardFiresAtFirstValidMinute` (02:30 daily on 2027-03-14 → 2027-03-14T03:00 local, i.e. 07:00Z)
  - `fallBackFiresOnce` (01:30 daily, from 2027-11-07T00:00 local: next is 01:30 EDT; the one after is 2027-11-08 01:30)
  - `emptyDaysHasNoOccurrence`
  - `occurrenceAtOrBeforeFindsTodaysWhenJustAfter`
  - `daysTextNamesWeekdaysEveryDayAndLists`
  - In `CommuteStoreTest`: `roundTripsThroughTheFile` (with a temp dir), `corruptFileReadsAsEmpty`, `emptyListDeletesTheFile`.
- [ ] **Step 2:** Run `./gradlew :shared:testAndroidHostTest --tests '*Commute*'`; expect failures (unresolved).
- [ ] **Step 3:** Implement the three files. Mirror `FileFavoriteStationsStore` for the store.
- [ ] **Step 4:** Run the tests; expect PASS.
- [ ] **Step 5:** Format, detekt, full host tests; `jj commit -m "feat: commutes and their schedule"`.

### Task 2: Firing a commute alert (shared)

**Files:**
- Create: `.../data/commute/CommuteAlertText.kt` (`data class CommuteAlertText(val title: String, val text: String, val alertLine: String?)` and `fun commuteAlertText(...)`)
- Create: `.../data/commute/CommuteNotifier.kt`, `.../data/commute/CommuteAlarmScheduler.kt`
- Create: `.../data/commute/CommuteAlerts.kt`
- Move: `minutesText` from `ui/status/StatusState.kt` to `data/commute/CommuteAlertText.kt` (public, keep the Status tab using it).
- Test: `shared/src/androidHostTest/.../data/commute/CommuteAlertTextTest.kt`, `CommuteAlertsTest.kt`

**Interfaces:**
- Consumes: Task 1 (`Commute`, `CommuteStore`, `nextOccurrenceMs`, `occurrenceAtOrBeforeMs`);
  `RealtimeSnapshot`, `ArrivalBoard.arrivalsAt`, `ServiceAlert`, `AlertKind`; `stationComplexes`
  from `ui/status/StatusState.kt` (move `StationComplex` and `stationComplexes` into
  `data/commute/StationComplex.kt`, public, and update imports).
- Produces:
  - `fun commuteAlertText(commute: Commute, complex: StationComplex, snapshot: RealtimeSnapshot?, nowEpochSeconds: Long): CommuteAlertText`
    — `snapshot == null` means the fetch failed or timed out. Departures across `complex.stationIds`
    matching the line rule and direction, soonest first, max 3. `alertLine`: the header of the
    first matching-line alert, preferring `DELAY`, then `OTHER`, then `PLANNED`; null if none.
    (Ruling: the Status tab shows no kind prefix, so none is added.)
  - `fun interface CommuteNotifier { fun post(commuteId: String, text: CommuteAlertText, tap: CommuteTap); companion object { val None } }`
  - `data class CommuteTap(val complexId: String, val routeId: String, val direction: Direction)`
  - `interface CommuteAlarmScheduler { fun schedule(commuteId: String, atEpochMs: Long); fun cancel(commuteId: String); companion object { val None } }`
  - `class CommuteAlerts(store: CommuteStore, complexes: () -> List<StationComplex>?, fetch: suspend (Collection<String>) -> RealtimeSnapshot, notifier: CommuteNotifier, scheduler: CommuteAlarmScheduler, clock: Clock = Clock.System, zone: () -> TimeZone = TimeZone::currentSystemDefault)`
    - `suspend fun fire(commuteId: String)`: unknown or disabled commute → cancel its alarm, post
      nothing. Otherwise schedule the next occurrence first, then: late by more than
      `LATE_SKIP_MS` → post nothing; complex missing from `complexes()` → post nothing; else fetch
      `[line, line + "X"]` under `withTimeoutOrNull(COMMUTE_FETCH_TIMEOUT_MS)` (catch non-cancellation
      exceptions as failure) and post.
    - `fun rescheduleAll()`: for each commute, schedule its next occurrence if enabled, has days
      and its complex exists (or `complexes()` is null, meaning not loaded yet); else cancel.
    - `fun save(commutes: List<Commute>)`: writes the store, cancels alarms of removed commutes,
      then `rescheduleAll()`.
- [ ] **Step 1: Failing tests** in `CommuteAlertTextTest`: `listsUpToThreeDeparturesSoonestFirst`,
  `includesExpressVariant`, `ignoresOtherDirectionAndLines`, `noTrainsText`, `fetchFailedText`,
  `prefersDelayAlertHeader`, `titleNamesLineDirectionAndStation`. In `CommuteAlertsTest` (fake
  clock, fake scheduler recording calls, fake notifier): `firePostsAndSchedulesNext`,
  `aLateFireIsSkippedAndTheNextIsScheduled`, `aHangingFetchPostsCouldNotGetTrainTimes` (fetch
  that `awaitCancellation()`s, run with `runTest` virtual time), `aThrowingFetchPostsCouldNotGetTrainTimes`,
  `disabledOrDeletedCommuteCancelsAndPostsNothing`, `missingStationPostsNothing`,
  `rescheduleAllSchedulesEnabledAndCancelsOthers`, `saveCancelsRemovedCommutes`.
- [ ] **Step 2:** Run; expect failures.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run; expect PASS. Existing `StatusScreenTest` still passes after the moves.
- [ ] **Step 5:** Format, detekt, full host tests; commit `"feat: commute alerts fire with live departures"`.

### Task 3: Android alarms, notification and receivers

**Files:**
- Create: `shared/src/androidMain/kotlin/com/straphanger/app/notify/AndroidCommuteNotifier.kt`
- Create: `shared/src/androidMain/kotlin/com/straphanger/app/notify/AlarmCommuteScheduler.kt` (in `:shared` so Robolectric host tests cover it; `androidApp` has no unit tests)
- Create: `androidApp/.../commute/CommuteAlarmReceiver.kt`, `CommuteRescheduleReceiver.kt`, `CommuteAlertsHolder.kt`
- Modify: `androidApp/src/main/AndroidManifest.xml` (permission `SCHEDULE_EXACT_ALARM`; the two receivers, `exported=false` for the alarm one; the reschedule one listens to `BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED`, `MY_PACKAGE_REPLACED`, `android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`)
- Test: `shared/src/androidHostTest/.../notify/AndroidCommuteNotifierTest.kt`, `AlarmCommuteSchedulerTest.kt`

**Interfaces:**
- Consumes: Task 2 interfaces.
- Produces:
  - `AndroidCommuteNotifier(context, @DrawableRes smallIcon: Int, tapIntent: (CommuteTap, commuteId: String) -> PendingIntent)`; posts on channel `commute_alerts` with content intent and a "See trains" action using the same `PendingIntent`; uses `BigTextStyle` when `alertLine` is set (text + "\n" + alertLine); never throws; posts nothing when notifications are disabled.
  - `AlarmCommuteScheduler(context, receiver: Class<out BroadcastReceiver>)`: one `PendingIntent` per commute to `receiver` (the app passes `CommuteAlarmReceiver`) with extra `EXTRA_COMMUTE_ID`, request code from a stable map of commute id → int (e.g. stored in shared prefs `commute_alarms`, next free int), and `Intent.setIdentifier(commuteId)` on API 29+ so intents never collide. `canScheduleExact()` exposed for the UI.
  - `CommuteAlertsHolder.get(context): CommuteAlerts` — one per process; complexes from the bundled network through `CaptureRuntimeHolder.get(context)`'s loaded network (null until loaded); fetch is `RealtimeFeedClient(HttpClient(OkHttp)).fetch`.
  - `CommuteAlarmReceiver`: `goAsync()`, `CommuteAlertsHolder.get(ctx).fire(id)` in a process scope, `finish()` in `finally`; logs failures.
  - `CommuteRescheduleReceiver`: `rescheduleAll()`.
  - Tap intent: opens `MainActivity` with extras `EXTRA_COMMUTE_COMPLEX`, `EXTRA_COMMUTE_ROUTE`, `EXTRA_COMMUTE_DIRECTION` (constants on `MainActivity.Companion`).
- [ ] **Step 1: Failing tests:** `AndroidCommuteNotifierTest`: `postsOnTheCommuteChannelWithSeeTrains`,
  `bigTextIncludesTheAlertLine`, `postsNothingWhenBlocked`, `twoCommutesPostSeparately`. Scheduler:
  `twoCommutesHaveDistinctAlarms`, `cancelRemovesOnlyThatCommute`, `fallsBackToInexactWithoutPermission`
  (Robolectric `ShadowAlarmManager`).
- [ ] **Step 2:** Run; expect failures.
- [ ] **Step 3:** Implement and register in the manifest. Ensure `CaptureRuntimeHolder` exposes the
  loaded network (add a small accessor if needed) without starting capture.
- [ ] **Step 4:** Run; expect PASS. `./gradlew :androidApp:assembleDebug lint` passes.
- [ ] **Step 5:** Commit `"feat: commute alarms and notifications on Android"`.

### Task 4: Status tab filter and See trains deep link

**Files:**
- Create: `shared/src/commonMain/kotlin/com/straphanger/app/ui/CommuteTapInbox.kt` (same shape as `RideTapInbox`, holding `CommuteTap?`)
- Modify: `ui/status/StatusScreen.kt`, `App.kt`
- Test: `shared/src/androidHostTest/.../StatusScreenTest.kt` (or a new `StatusFilterScreenTest.kt` if detekt's function cap bites)

**Interfaces:**
- Consumes: `CommuteTap` (Task 2).
- Produces: `StatusScreen(..., tap: CommuteTap? = null, onTapConsumed: () -> Unit = {})`; when `tap` arrives, the screen selects `tap.complexId` and sets a filter `(routeId, direction)`; the arrivals card then shows only that direction block with only that line (and its X variant), with a chip "Showing A Southbound · Show all" (testTag `status_filter_clear`) that clears it. Selecting another station clears the filter. App collects `CommuteTapInbox`, switches to `AppTab.STATUS`, and passes the tap down once.
- [ ] **Step 1: Failing tests:** `seeTrainsOpensStatusFilteredToLineAndDirection`,
  `showAllClearsTheFilter`, `pickingAnotherStationClearsTheFilter`, `seeTrainsFilterIsAppliedOnce`
  (recreate with `StateRestorationTester` or re-compose; filter cleared by Show all stays cleared).
- [ ] **Step 2–4:** Run, implement, pass.
- [ ] **Step 5:** Commit `"feat: status tab can show one line and direction"`.

### Task 5: Commutes section and editor

**Files:**
- Create: `shared/src/commonMain/kotlin/com/straphanger/app/ui/status/CommutesSection.kt`, `CommuteEditorDialog.kt`
- Modify: `ui/status/StatusScreen.kt`, `App.kt` (`PlatformHooks` and the public `App(...)` gain the commute parameters)
- Test: `shared/src/androidHostTest/.../CommuteScreenTest.kt`

**Interfaces:**
- Consumes: Task 1 store/model, Task 2 `CommuteAlerts.save` (through a callback).
- Produces: public `App(..., commutes: CommuteStore = InMemoryCommuteStore(), onCommutesChanged: (List<Commute>) -> Unit = {}, commuteAccess: CommuteAccess = CommuteAccess(), onFirstCommuteCreated: () -> Unit = {}, onOpenExactAlarmSettings: () -> Unit = {})` where `data class CommuteAccess(val notificationsAllowed: Boolean = true, val exactAlarmsAllowed: Boolean = true)`. `onOpenNotificationSettings` already exists and is reused.
  - Commutes section at the top of the Status tab, shown only when there is at least one commute:
    header "Commutes", one row per commute "A Southbound · 168 St · Weekdays 7:45" with a `Switch`
    (testTags `commute_row_<id>`, `commute_switch_<id>`), "Station no longer listed" in place of the
    name for a missing complex, and the blocked / late notes (testTags `commute_notifications_blocked`,
    `commute_exact_alarms_note`) that call the settings callbacks.
  - Arrivals card button "Add commute alert" (testTag `status_add_commute`).
  - Editor dialog (testTag `commute_editor`): line chips from the complex's routes (`commute_line_<route>`),
    direction chips (`commute_direction_NORTH` / `_SOUTH`), seven day chips (`commute_day_MONDAY`…),
    a time field (hour/minute, 24-hour or Material `TimePicker` — builder's choice, testTag
    `commute_time`), Save (`commute_save`, disabled with no days), Delete when editing
    (`commute_delete`), Cancel.
  - Every save, toggle or delete writes the store and calls `onCommutesChanged(list)`. Saving the
    first commute ever (store was empty) calls `onFirstCommuteCreated()`.
  - Time shown as "7:45" (no leading zero on the hour, 24-hour clock, e.g. "18:10").
- [ ] **Step 1: Failing tests:** `addCommuteFromStationCardPrefillsStationAndDefaults`,
  `savingAddsARowAndReportsTheList`, `firstCommuteAsksForPermissionsOnce`, `toggleDisables`,
  `editingAndDeleting`, `saveIsDisabledWithNoDays`, `blockedAndLateNotesOpenSettings`,
  `missingStationShowsNoLongerListed`.
- [ ] **Step 2–4:** Run, implement, pass.
- [ ] **Step 5:** Commit `"feat: commutes on the status tab"`.

### Task 6: MainActivity wiring, docs and version

**Files:**
- Modify: `androidApp/src/main/kotlin/com/straphanger/app/MainActivity.kt`
- Modify: `CHANGELOG.md` (0.25 entry), `README.md`, `docs/CODE-TOUR.md` (new section after §5d), `docs/privacy-policy.md`, `version.properties` (`0.25`), then `./gradlew syncIosVersion`.
- Test: an emulator test in `androidApp/src/androidTest/.../CommuteAlertEmulatorTest.kt`:
  `seeTrainsIntentOpensFilteredStatus` (launch `MainActivity` with the three extras, assert the
  `status_filter_clear` chip shows the line).

**Interfaces:**
- Consumes: everything above.
- Produces:
  - `FileCommuteStore(Path(filesDir.path, "commutes.json"))` passed to `App`; `onCommutesChanged = { CommuteAlertsHolder.get(this).save(it) }` — but `App`'s editor already wrote the store, so `save` must be idempotent (it is: write + reschedule).
  - `commuteAccess` refreshed in `onResume` (`areNotificationsEnabled()`, `AlarmCommuteScheduler.canScheduleExact()`).
  - `onFirstCommuteCreated`: request `POST_NOTIFICATIONS` (reuse `rememberPostNotificationsPermissionRequest`); after its result, if exact alarms are not allowed, start `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` with `package:` URI (API 31+).
  - `onOpenExactAlarmSettings`: the same intent.
  - `handleCommuteTapIntent` beside `handleRideTapIntent`, under the same `savedInstanceState == null && !launchedFromHistory` guard and in `onNewIntent`.
  - Docs: what commute alerts are, the exact-alarm permission, and that alerts read only the MTA's public feeds.
- [ ] **Step 1:** Write the emulator test.
- [ ] **Step 2:** Wire `MainActivity`.
- [ ] **Step 3:** Docs and version; `./gradlew syncIosVersion checkIosVersion`.
- [ ] **Step 4:** `./gradlew ktfmtCheck detekt :shared:testAndroidHostTest koverVerify :androidApp:assembleDebug lint` passes.
- [ ] **Step 5:** Commit `"feat: commute alerts wired into the app, docs"`.
