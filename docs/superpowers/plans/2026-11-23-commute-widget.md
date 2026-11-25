# Commute Widget Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A home-screen widget, placeable many times, that shows the live departures of the
commutes the rider ticked for it during each commute's time window, refreshing every 2 minutes
while the screen is on, with "Updated 7:52" and a ⟳ refresh icon.

**Architecture:**
- Shared code adds an optional end time to `Commute` and the window calculations
  (`data/commute/CommuteWindow.kt`). It also adds `CommuteWidgets` (`data/widget/`), which owns
  each widget's commute list and the last fetched times. It decides what each widget shows,
  fetches, and keeps one refresh alarm chain per widget. As in `CommuteAlerts`, platform pieces
  sit behind small interfaces: `WidgetRefreshScheduler` and `WidgetRenderer`.
- Android implements the scheduler with non-waking `AlarmManager` alarms, plus one waking inexact
  "stale redraw" alarm per widget. The widget itself is a Jetpack Glance `GlanceAppWidget`:
  - its layout composable lives in `shared/androidMain` so Robolectric can test it;
  - the receiver, the configuration activity and `CommuteWidgetsHolder` live in `androidApp`.
- The commute editor gets "Show on widget until". Every place that reschedules alerts also
  reschedules widgets.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Jetpack Glance
(`androidx.glance:glance-appwidget`, plus `glance-appwidget-testing` for tests),
kotlinx-datetime, kotlinx-serialization, coroutines, `AlarmManager`, Robolectric
(androidHostTest), jj.

**Spec:** `docs/superpowers/specs/2026-11-23-commute-widget-design.md`

## Global Constraints

- **Shared code:** it must compile for iOS. iOS gets `WidgetRefreshScheduler.None` and
  `WidgetRenderer.None`, and no widget.
- **New dependencies:** the only ones allowed are `androidx.glance:glance-appwidget` and
  `androidx.glance:glance-appwidget-testing` (test only).
  - Use the latest stable version on Google Maven that was released at least two weeks ago. Add
    it to `gradle/libs.versions.toml` as `glance`.
  - If its Compose runtime clashes with the project's (`androidx-compose = 1.12.1`), pick the
    newest stable Glance that doesn't clash, and say so in the report.
- **Commute end time:** `Commute` gains `endHour: Int? = null` and `endMinute: Int? = null`.
  - A 0.25 file (no end fields) reads with both null.
  - An end time outside 0..23 / 0..59, or only one of the two set, reads as no end time. The
    commute itself is kept.
- **Window:** it runs from the occurrence at `hour:minute` to `endHour:endMinute`:
  - on the same local date when the end is later in the day than the start;
  - on the next date when the end is at or before the start. End == start is a 24-hour window.
- **Departure text, notification and widget alike:** "3 min · 9 min · 14 min". Each departure
  goes through `minutesText` ("now" for 0), joined with `" · "`. Everything else in
  `commuteAlertText` is unchanged.
- **Widget copy:**
  - Title: the alert's title, "A Southbound · 168 St".
  - The time line: "Updated 7:52", using `commuteTimeText(hour, minute)`. Move that helper from
    `ui/status/CommutesSection.kt` into `data/commute/`.
  - The refresh icon: ⟳, with content description "Refresh".
  - "Tap to refresh", "Couldn't get train times", "Station no longer listed",
    "Tap to choose commutes".
- **Configuration screen copy:** "Choose commutes", "Done", "Set an end time to show it on a
  widget", "No commutes yet. Open Straphanger to add one.", "Open Straphanger".
- **Editor field label:** "Show on widget until", with hour and minute fields that may both be
  empty (no end time). One empty and one filled, or out of range, disables Save. The Commutes
  list shows "Weekdays 7:45–8:30" when an end time is set; it uses an en dash.
- **Timing constants** (in `data/widget/`):
  - `WIDGET_REFRESH_MS = 2 * 60_000L`: the next refresh after a fetch with the screen on.
  - `WIDGET_SCREEN_OFF_CHECK_MS = 60_000L`: the next check when the alarm finds the screen off.
  - `WIDGET_STALE_MS = 15 * 60_000L`: older times show as "Tap to refresh".
  - `WIDGET_FETCH_REUSE_MS = 60_000L`: an automatic refresh skips a commute fetched this recently,
    for example by another widget. A tapped refresh always fetches.
- **Fetching:** every fetch uses `COMMUTE_FETCH_TIMEOUT_MS` (8 s), run in parallel with loading
  the complexes, as `CommuteAlerts.post` does.
  - Receivers run through `goAsync()` with the 9.5 s cap.
  - A failed fetch never overwrites the last good times.
- **Alarms:**
  - Refresh alarms: `AlarmManager.RTC`, never waking. `setExact` when `canScheduleExactAlarms()`
    (always below API 31); otherwise `setWindow(RTC, at, 30_000L, …)`.
  - Stale redraw: `setAndAllowWhileIdle(RTC_WAKEUP, fetchedAt + WIDGET_STALE_MS, …)`. Each widget
    has one, and each fetch replaces it.
  - Never `USE_EXACT_ALARM`.
  - Request codes go in their own prefs file, `commute_widget_alarms`, keyed `refresh:<widgetId>`
    and `stale:<widgetId>`, and are written with `commit()`. Set `setIdentifier` on API 29+.
- **Storage:** widget state lives in `commute_widgets.json` in `filesDir`, through `JsonStateFile`.
  It is read with `ignoreUnknownKeys`. A corrupt file is read as empty, and `JsonStateFile`
  already moves it aside.
- **Commits:** use jj (`jj commit -m …`). Messages end with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Before every commit:** run `./gradlew ktfmtFormat detekt :shared:testAndroidHostTest`, plus
  `:androidApp:assembleDebug` for tasks that touch androidApp.
  - Detekt: a file's name matches its single top-level class, at most 11 functions per file
    (tests too), and `LongMethod`. `MainActivity.kt` and `StatusScreen.kt` are already at the
    function cap.
  - Run shell deletes as `command rm -f`.
- **Version:** `version.properties` becomes `0.<PR number>`, expected `0.26`. Then run
  `./gradlew syncIosVersion`.

## Review Focus

1. **A window across midnight.** A Friday 23:30–00:30 commute is showing at 00:10 on Saturday,
   even though Saturday isn't one of its days. Pinned in Task 1:
   `overnightWindowIsActiveAfterMidnightOnTheNextDay`.
2. **A commute deleted, or its end time cleared, while a widget shows it.** It drops off every
   widget. A widget left with no shown commutes cancels its refresh chain. Pinned in Task 2:
   `aDeletedCommuteDropsOffAndItsChainStops`.
3. **Two widgets showing the same commute.** Both show the same times, and the second widget's
   automatic refresh within 60 s doesn't fetch again. Pinned in Task 2:
   `aSecondWidgetReusesARecentFetch`.
4. **Screen off for 20 minutes during a window, then on.** The first alarm after the screen comes
   on fetches; meanwhile the content is "Tap to refresh", never 20-minute-old times. Pinned in
   Task 2: `staleTimesShowTapToRefreshAndTheNextScreenOnAlarmFetches`.
5. **A reboot or app update in the middle of a window.** `rescheduleAll` sets an immediate
   refresh for every widget whose window is active, and the next window start for the rest.
   Pinned in Task 2: `rescheduleAllRefreshesActiveWidgetsAtOnce`.

---

### Task 1: End time, windows and the new departure format (shared)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/straphanger/app/data/commute/Commute.kt`,
  `CommuteStore.kt` (validation on read), `CommuteAlertText.kt` (join format)
- Create: `.../data/commute/CommuteWindow.kt`
- Move: `commuteTimeText` from `ui/status/CommutesSection.kt` to `.../data/commute/CommuteWindow.kt`
  (public)
- Test: `shared/src/androidHostTest/.../data/commute/CommuteWindowTest.kt`; update
  `CommuteStoreTest.kt`, `CommuteAlertTextTest.kt`, and any screen test asserting "3, 9, 14 min"

**Interfaces:**
- Produces:
  - `Commute(..., val endHour: Int? = null, val endMinute: Int? = null)`, added after `enabled`.
  - `val Commute.hasWindow: Boolean`: true when both end fields are set.
  - `fun Commute.windowAt(epochMs: Long, zone: TimeZone): LongRange?` gives the active window
    containing `epochMs`, as `startMs until endMs`, or null.
    - The start is `occurrenceAtOrBeforeMs`.
    - The end is resolved with the same spring-forward rule as the start.
    - It returns null when `!hasWindow`.
  - `fun Commute.nextWindowStartMs(afterEpochMs: Long, zone: TimeZone): Long?`: null when
    `!hasWindow`, else `nextOccurrenceMs`.
  - `fun commuteTimeText(hour: Int, minute: Int): String`, e.g. "7:05".

- [ ] **Step 1: Write failing tests** in `CommuteWindowTest`, in zone `America/New_York`:
  - `windowIsActiveBetweenStartAndEnd`: weekdays 07:45–08:30, Mon 08:00, gives Mon
    07:45..08:30.
  - `windowEndsExclusive`: Mon 08:30 gives null.
  - `noWindowWithoutAnEndTime`.
  - `overnightWindowIsActiveAfterMidnightOnTheNextDay`: Fri-only 23:30–00:30, Sat 00:10, gives
    Fri 23:30..Sat 00:30.
  - `endEqualToStartIsTwentyFourHours`.
  - `nextWindowStartSkipsTheActiveOne`: Mon 08:00, gives Tue 07:45.
  - `windowOnSpringForwardDay`: daily 02:30–03:30 on 2027-03-14 gives 03:00..03:30 EDT.
  - `CommuteStoreTest.aZeroTwentyFiveFileReadsWithNoEndTime`: JSON without the end fields.
  - `CommuteStoreTest.aBadEndTimeIsDroppedButTheCommuteKept`: `endHour` 25, or only `endHour`
    set.
  - `CommuteAlertTextTest`: change the expected text to `"3 min · 9 min · 14 min"` and
    `"now · 6 min · 12 min"`.
- [ ] **Step 2:** Run `./gradlew :shared:testAndroidHostTest --tests '*Commute*'`. Expect failures.
- [ ] **Step 3:** Implement. The window helpers reuse `resolveLocal`; make it `internal`.
- [ ] **Step 4:** Run the tests and expect PASS. Then run the full host tests, and fix any screen
  test that asserted the old format.
- [ ] **Step 5:** Format and run detekt, then
  `jj commit -m "feat: commute end times and windows; departures read 3 min · 9 min · 14 min"`.

### Task 2: What a widget shows, and when it refreshes (shared)

**Files:**
- Create: `.../data/widget/CommuteWidgetStore.kt`, with the `CommuteWidgetStore` interface,
  `InMemoryCommuteWidgetStore` and `FileCommuteWidgetStore`.
- Create: `.../data/widget/CommuteWidgetContent.kt`, holding the content model and the pure
  `commuteWidgetContent` function.
- Create: `.../data/widget/WidgetRefreshScheduler.kt` and `.../data/widget/WidgetRenderer.kt`.
- Create: `.../data/widget/CommuteWidgets.kt`.
- Test: `shared/src/androidHostTest/.../data/widget/CommuteWidgetContentTest.kt`,
  `CommuteWidgetsTest.kt` and `CommuteWidgetStoreTest.kt`.

**Interfaces:**
- Consumes: from Task 1, `windowAt`, `nextWindowStartMs`, `hasWindow` and `commuteTimeText`.
  Also `commuteAlertText`, `CommuteTap`, `StationComplex`, `RealtimeSnapshot`, `CommuteStore` and
  `COMMUTE_FETCH_TIMEOUT_MS`.
- Produces:
  - `@Serializable data class CommuteTimes(val text: String? = null, val alertLine: String? = null, val fetchedAtMs: Long? = null, val failedAtMs: Long? = null)`.
    These are the last good times and the last failure for one commute.
  - `interface CommuteWidgetStore` has these synchronous members, which never throw:
    - `fun widgets(): Map<Int, List<String>>`
    - `fun setWidget(widgetId: Int, commuteIds: List<String>)`
    - `fun removeWidgets(widgetIds: Collection<Int>)`
    - `fun times(): Map<String, CommuteTimes>`
    - `fun setTimes(commuteId: String, times: CommuteTimes)`
  - `FileCommuteWidgetStore(file: Path, fileSystem: FileSystem = SystemFileSystem, clock: Clock = Clock.System)`
    keeps everything in one `@Serializable` state object.
  - The content model:

    ```kotlin
    sealed interface WidgetRow {
      val commuteId: String; val title: String; val tap: CommuteTap
      data class Times(..., val text: String, val alertLine: String?, val updatedText: String) : WidgetRow
      data class TapToRefresh(...) : WidgetRow
      data class Failed(...) : WidgetRow        // "Couldn't get train times" + ⟳
      data class StationGone(...) : WidgetRow   // "Station no longer listed"
    }
    data class CommuteWidgetContent(val rows: List<WidgetRow>) // empty = "Tap to choose commutes"
    ```

  - `fun commuteWidgetContent(commuteIds: List<String>, commutes: List<Commute>, complexes: List<StationComplex>?, times: Map<String, CommuteTimes>, nowMs: Long, zone: TimeZone): CommuteWidgetContent`
    applies these rules:
    - **Which commutes:** the shown commutes are those with an active window, ordered by window
      start, at most 2. The layout shows 1 or 2 depending on height. If none is active, it shows
      the one with the earliest `nextWindowStartMs`. Unknown ids, and commutes without a window,
      are ignored.
    - **What each row is:**
      - `Times`: the commute has text with `nowMs - fetchedAtMs <= WIDGET_STALE_MS`. This holds
        even if a later fetch failed.
      - `Failed`: there are no fresh times, but a `failedAtMs` within `WIDGET_STALE_MS` that is
        later than `fetchedAtMs`.
      - `TapToRefresh`: otherwise.
      - `StationGone`: the complex isn't in a non-null `complexes`.
    - **Titles:** a row title uses the complex name. When `complexes` is null, it uses the
      commute's line and direction with no station.
  - `fun interface WidgetRenderer { suspend fun update(widgetId: Int) }`, with `None`.
  - `interface WidgetRefreshScheduler` has `fun scheduleRefresh(widgetId: Int, atEpochMs: Long)`,
    `fun scheduleStaleRedraw(widgetId: Int, atEpochMs: Long)` and `fun cancel(widgetId: Int)`,
    which cancels both. It also has `None`.
  - `class CommuteWidgets(store: CommuteWidgetStore, commutes: CommuteStore, complexes: suspend () -> List<StationComplex>?, fetch: suspend (Collection<String>) -> RealtimeSnapshot, scheduler: WidgetRefreshScheduler, renderer: WidgetRenderer, screenOn: () -> Boolean, clock: Clock = Clock.System, zone: () -> TimeZone = TimeZone::currentSystemDefault)`.
    Its members:
    - `suspend fun content(widgetId: Int): CommuteWidgetContent`.
    - `fun commuteIds(widgetId: Int): List<String>`.
    - `suspend fun configure(widgetId: Int, commuteIds: List<String>)`: saves the list, then
      reschedules and redraws that widget.
    - `fun removed(widgetIds: Collection<Int>)`: drops each widget and cancels its alarms.
    - `suspend fun onRefreshAlarm(widgetId: Int)`. The rules:
      - With no active window, it schedules the refresh at the earliest next window start, or
        cancels when there is none.
      - With the screen off, it schedules `now + WIDGET_SCREEN_OFF_CHECK_MS`.
      - Otherwise it fetches the shown commutes, skipping any fetched within
        `WIDGET_FETCH_REUSE_MS`. Then it schedules `now + WIDGET_REFRESH_MS`, and the stale redraw
        at the newest `fetchedAtMs + WIDGET_STALE_MS`.
      - It always ends with `renderer.update(widgetId)`.
    - `suspend fun onStaleAlarm(widgetId: Int)`: only `renderer.update`.
    - `suspend fun tapRefresh(widgetId: Int)`: fetches the shown commutes unconditionally, sets
      the stale redraw and updates the widget. If a window is active, it then schedules the next
      refresh.
    - `suspend fun rescheduleAll()`: for every widget, first drops ids of deleted commutes from
      its list. Then it does `scheduleRefresh(now)` if a window is active, else schedules the
      next start or cancels. Then it updates the widget.
    - **A fetch:** for each commute, it calls `fetch(listOf(line, line + "X"))` under the 8 s
      timeout.
      - On success, it stores `text` and `alertLine` from `commuteAlertText`, with
        `fetchedAtMs = now`.
      - On a null snapshot, an exception or a timeout, it stores `failedAtMs = now` and keeps the
        old text.
      - Pull the fetch-with-timeout out of `CommuteAlerts` into an internal shared function if
        that avoids duplicating it.

- [ ] **Step 1: Write failing tests:**
  - **`CommuteWidgetContentTest`:**
    - `showsFreshTimesWithUpdatedTime`: fetched at 07:52, now 07:55, gives `Times`, "Updated 7:52".
    - `timesOlderThanFifteenMinutesShowTapToRefresh`.
    - `outsideEveryWindowShowsTheNextCommuteAsTapToRefresh`.
    - `outsideAWindowFreshTappedTimesStillShow`.
    - `aFailureKeepsFreshTimes`, then `aFailureWithNoFreshTimesShowsFailed`.
    - `twoActiveWindowsShowBothInStartOrder`, then `threeActiveShowTwo`.
    - `noUsableCommutesGivesNoRows`: no ids, unknown ids, or no end times.
    - `aMissingStationShowsStationGone`.
  - **`CommuteWidgetsTest`**, with fakes for the scheduler, the renderer, the fetch and the clock:
    - `aRefreshWithTheScreenOnFetchesAndSchedulesInTwoMinutes`.
    - `aRefreshWithTheScreenOffOnlySchedulesInOneMinute`.
    - `outsideAWindowTheRefreshIsScheduledForTheNextStart`.
    - `aSecondWidgetReusesARecentFetch`.
    - `tapRefreshAlwaysFetches`.
    - `aFetchSetsTheStaleRedraw`.
    - `aFailedFetchKeepsTheLastTimes`.
    - `aHangingFetchTimesOutAsFailed`: uses the virtual-time test dispatcher.
    - `aDeletedCommuteDropsOffAndItsChainStops`.
    - `staleTimesShowTapToRefreshAndTheNextScreenOnAlarmFetches`.
    - `rescheduleAllRefreshesActiveWidgetsAtOnce`.
    - `removedWidgetsAreForgottenAndCancelled`.
  - **`CommuteWidgetStoreTest`:** `roundTripsThroughTheFile`, `corruptFileReadsAsEmpty`.
- [ ] **Step 2:** Run `./gradlew :shared:testAndroidHostTest --tests '*Widget*'`. Expect failures.
- [ ] **Step 3:** Implement. Mirror `FileCommuteStore` for the store.
- [ ] **Step 4:** Run the tests and expect PASS.
- [ ] **Step 5:** Format and run detekt and the full host tests, then
  `jj commit -m "feat: what a commute widget shows and when it refreshes"`.

### Task 3: End time in the editor and the Commutes list (shared UI)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/straphanger/app/ui/status/CommuteEditorDialog.kt` and
  `CommutesSection.kt`.
- Test: update `shared/src/androidHostTest/.../ui/status/CommuteScreenTest.kt`, or add
  `CommuteEndTimeScreenTest.kt` if the existing file would exceed 11 functions.

**Interfaces:**
- Consumes: from Task 1, `Commute.endHour` and `endMinute`, `hasWindow` and `commuteTimeText`.
- Produces: the editor saves `endHour` and `endMinute`, or both null. The test tags are
  `commute_end_hour` and `commute_end_minute`.

- [ ] **Step 1: Write failing tests:**
  - `endTimeIsEmptyByDefaultAndSavesAsNone`.
  - `endTimeSavesBothFields`.
  - `aHalfFilledEndTimeDisablesSave`.
  - `anOutOfRangeEndTimeDisablesSave`.
  - `editingKeepsTheEndTime`.
  - `commutesListShowsTheWindow`: "Weekdays 7:45–8:30".
- [ ] **Step 2:** Run them and expect failures.
- [ ] **Step 3:** Implement. Reuse the existing `TimeField` for the two end fields, under a
  "Show on widget until" label.
- [ ] **Step 4:** Run them and expect PASS. Then format, run detekt and the full host tests.
- [ ] **Step 5:** `jj commit -m "feat: commutes can have an end time for the widget"`.

### Task 4: The Glance widget, its configuration screen, and tapping it (Android)

**Files:**
- Modify: `gradle/libs.versions.toml`, `shared/build.gradle.kts` (Glance in `androidMain`; the
  testing artifact in `androidHostTest`), and `androidApp/build.gradle.kts` (Glance).
- Create: `shared/src/androidMain/kotlin/com/straphanger/app/widget/CommuteWidgetLayout.kt`.
  This is a `@Composable fun CommuteWidgetLayout(content: CommuteWidgetContent, refresh: Action, open: (CommuteTap) -> Action, choose: Action)`
  written with Glance composables.
  - It shows 2 rows when the widget is at least 140 dp tall (`LocalSize`), otherwise 1.
  - Use `SizeMode.Responsive` with a small size and a tall size.
- Create: `shared/src/commonMain/kotlin/com/straphanger/app/ui/widget/WidgetCommutePicker.kt`.
  This is a Compose screen,
  `@Composable fun WidgetCommutePicker(commutes: List<Commute>, complexName: (Commute) -> String?, initiallySelected: Set<String>, onDone: (List<String>) -> Unit, onOpenApp: () -> Unit)`.
- Create, under `androidApp/src/main/kotlin/com/straphanger/app/widget/`:
  - `CommuteGlanceWidget.kt`: a `GlanceAppWidget` whose `provideGlance` loads
    `CommuteWidgetsHolder.get(ctx).content(id)` before `provideContent`.
  - `CommuteWidgetReceiver.kt`: a `GlanceAppWidgetReceiver`. Its `onDeleted` calls `removed`.
  - `RefreshWidgetAction.kt`: an `ActionCallback` that calls `tapRefresh`.
  - `CommuteWidgetConfigActivity.kt`.
  - `CommuteWidgetsHolder.kt`: the process's one `CommuteWidgets`, with
    `GlanceWidgetRenderer` and `WidgetRefreshScheduler.None` until Task 5.
- Create: `androidApp/src/main/res/xml/commute_widget_info.xml`, with
  `android:configure=".widget.CommuteWidgetConfigActivity"`,
  `widgetFeatures="reconfigurable"`, a 3×1 default, resizable both ways, and
  `updatePeriodMillis="0"`.
- Modify: `AndroidManifest.xml` to add the receiver with the `APPWIDGET_UPDATE` filter and the
  metadata, and the config activity (`exported="true"`, with the `APPWIDGET_CONFIGURE` filter).
- Modify: `androidApp/.../commute/CommuteActivitySupport.kt` to add
  `fun commuteTapActivityIntent(context: Context, tap: CommuteTap): Intent`. It carries the same
  `EXTRA_COMMUTE_*` extras as the notification without `EXTRA_COMMUTE_NOTIFICATION`, so the
  existing `handleCommuteTapIntent` applies the filter.
- Test: `shared/src/androidHostTest/.../widget/CommuteWidgetLayoutTest.kt` (uses
  `runGlanceAppWidgetUnitTest`) and `.../ui/widget/WidgetCommutePickerTest.kt`.

**Interfaces:**
- Consumes: from Task 2, `CommuteWidgets`, `CommuteWidgetContent`, `WidgetRow`, `WidgetRenderer`
  and `WidgetRefreshScheduler.None`. Also `CommuteAlertsHolder.store` and the station complexes,
  loaded the way `CommuteAlertsHolder.build` loads them.
- Produces:
  - `CommuteWidgetsHolder.get(context): CommuteWidgets`.
  - `CommuteWidgetsHolder.runInBackground(receiver, context, reason, work)`. It has the same
    shape as `CommuteAlertsHolder.runInBackground`; share the implementation if that is simple.
  - `GlanceWidgetRenderer(context)`: `update(widgetId)` maps the id to a `GlanceId` through
    `GlanceAppWidgetManager` and calls `CommuteGlanceWidget().update`.

- [ ] **Step 1: Write failing tests:**
  - **`CommuteWidgetLayoutTest`:**
    - `timesRowShowsTitleTimesAlertAndUpdated`.
    - `refreshIconHasRefreshDescriptionAndAction`.
    - `tapToRefreshRowRunsRefresh`.
    - `failedRowShowsCouldNotGetTrainTimes`.
    - `stationGoneRowShowsStationNoLongerListed`.
    - `noRowsShowsTapToChooseCommutes`.
    - `twoRowsOnlyWhenTall`.
  - **`WidgetCommutePickerTest`:**
    - `listsCommutesWithEndTimesAsCheckable`.
    - `commutesWithoutEndTimeAreDisabledWithHint`.
    - `doneReturnsTickedIdsInListOrder`.
    - `noCommutesShowsOpenStraphanger`.
    - `startsWithTheCurrentSelection`.
- [ ] **Step 2:** Run them and expect failures.
- [ ] **Step 3:** Implement. The config activity follows these rules:
  - It reads `EXTRA_APPWIDGET_ID` and starts with `setResult(RESULT_CANCELED)`.
  - It shows the picker with the widget's current list.
  - On Done, it calls `configure` and sets `RESULT_OK` with the id, then finishes.
  - "Open Straphanger" starts `MainActivity`.
  - A widget row's `open` action is `actionStartActivity(commuteTapActivityIntent(ctx, row.tap))`.
  - `choose` opens the config activity for that widget id.
- [ ] **Step 4:** Run the tests and expect PASS. Then run `./gradlew :androidApp:assembleDebug`
  and `lintDebug` if CI runs it.
- [ ] **Step 5:** Format and run detekt, then
  `jj commit -m "feat: commute widget and its configuration screen"`.

### Task 5: Refresh alarms and keeping widgets scheduled (Android)

**Files:**
- Create: `shared/src/androidMain/kotlin/com/straphanger/app/widget/AlarmWidgetRefreshScheduler.kt`.
  Signature: `(context, refreshReceiver: Class<out BroadcastReceiver>)`. It has
  `canScheduleExact()`, like `AlarmCommuteScheduler`, and sends `EXTRA_WIDGET_ID`, plus
  `EXTRA_WIDGET_ALARM` set to `"refresh"` or `"stale"`.
- Create: `androidApp/.../widget/WidgetRefreshReceiver.kt` (`exported="false"`). It calls
  `onRefreshAlarm` or `onStaleAlarm` through `CommuteWidgetsHolder.runInBackground`.
- Modify: `CommuteWidgetsHolder` to use the real scheduler. `screenOn` is
  `PowerManager.isInteractive`.
- Modify: `CommuteAlertsHolder.commutesChanged`, which also runs `CommuteWidgetsHolder`'s
  `rescheduleAll` under the same mutex, after the alerts' pass.
- Modify: `CommuteRescheduleReceiver`, which also runs widgets' `rescheduleAll` for the same
  actions. `MainActivity.onResume` is covered through `commutesChanged`.
- Modify: `CommuteWidgetReceiver.onUpdate`, which calls `rescheduleAll`. This covers placement
  and launcher restores.
- Modify: `AndroidManifest.xml` to add the refresh receiver.
- Test: `shared/src/androidHostTest/.../widget/AlarmWidgetRefreshSchedulerTest.kt`, using
  Robolectric `ShadowAlarmManager`.

**Interfaces:**
- Consumes: Task 2's `WidgetRefreshScheduler` and Task 4's holder.
- Produces: the companions `AlarmWidgetRefreshScheduler.EXTRA_WIDGET_ID` and
  `EXTRA_WIDGET_ALARM`.

- [ ] **Step 1: Write failing tests:**
  - `refreshIsANonWakingExactAlarmWhenAllowed`: type `RTC`, exact.
  - `refreshUsesAThirtySecondWindowWhenExactIsNotAllowed`.
  - `staleRedrawIsAWakingInexactAllowWhileIdleAlarm`.
  - `aNewRefreshReplacesTheOld`.
  - `twoWidgetsHaveDistinctAlarms`.
  - `cancelRemovesBoth`.
  - `widgetAlarmsNeverCollideWithCommuteAlarms`: one commute alarm and one widget alarm with the
    same numeric code are both pending.
- [ ] **Step 2:** Run them and expect failures.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run them and expect PASS. Then run `./gradlew :androidApp:assembleDebug`.
- [ ] **Step 5:** Format and run detekt and the full host tests, then
  `jj commit -m "feat: commute widgets refresh during their windows"`.

### Task 6: Docs and version

**Files:**
- Modify: `CHANGELOG.md`, adding a `0.26` entry for the widget. It also says the alert now reads
  "3 min · 9 min · 14 min".
- Modify: `README.md`, `docs/CODE-TOUR.md` (a new section after §5f: windows, `CommuteWidgets`,
  the alarm chain, Glance) and `docs/privacy-policy.md` (the widget reads MTA feeds; nothing
  about the rider is sent).
- Modify: `version.properties` to `0.<PR number>`, then run `./gradlew syncIosVersion`.

- [ ] **Step 1:** Write the docs. Match each file's existing tone and structure.
- [ ] **Step 2:** Run the full local CI set: `./gradlew ktfmtCheck detekt :shared:testAndroidHostTest koverVerify :androidApp:assembleDebug`.
- [ ] **Step 3:** `jj commit -m "docs: commute widget"`.
