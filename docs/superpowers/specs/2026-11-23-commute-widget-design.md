# Commute widget: design

## Intent

A home-screen widget that shows the next live departures of the rider's commute while that commute
is happening, so a glance at the phone answers "when do I need to leave?" without opening the app.
The rider can place several widgets, each showing the commutes they ticked for it. A commute shows
on a widget only during its time window: from its alert time to a new end time, on its days. So
one widget can show the A Southbound at 168 St on weekday mornings and the 1 Northbound at 34 St on
weekday evenings.

This is the third of three features. Ride-completed notifications (0.24) and departure alerts
(0.25) are done. The widget reuses the alerts' **commutes**.

## How it works today

- `Commute` (`shared/commonMain/.../data/commute/Commute.kt`) is a line, a station complex, a
  `Direction`, a set of days, a local hour and minute, and `enabled`. Commutes are stored as JSON
  in `FileCommuteStore` and reached on Android through `CommuteAlertsHolder`.
- `CommuteAlerts` keeps one exact alarm per enabled commute (`AlarmCommuteScheduler`,
  `setExactAndAllowWhileIdle` when allowed, else `setAndAllowWhileIdle`). `CommuteAlarmReceiver`
  handles each alarm with `goAsync()`, fetches with an 8 s timeout (`COMMUTE_FETCH_TIMEOUT_MS`) in
  parallel with loading the station complexes, and posts one notification.
  `CommuteRescheduleReceiver` sets every alarm again after boot, time and time-zone changes, app
  updates and exact-alarm permission changes; `MainActivity.onResume` does too.
- `commuteAlertText` builds the departure text: at most 3 departures of the line and its express
  variant (`<line>X`), in the direction, at any of the complex's stations, soonest first, through
  `minutesText`; the first active alert naming the line; "No A trains listed southbound right now";
  "Couldn't get train times".
- The Status tab's **Commutes** section lists commutes with an on/off switch;
  `CommuteEditorDialog` edits one. The notification's **See trains** opens the Status tab on that
  station filtered to that line and direction (`CommuteTap`, `MainActivity.EXTRA_COMMUTE_*`).
- The app has no widget, and no Glance dependency.

## Goals

- **Commute end time.** A commute gets an optional end time (hour and minute). The editor gets a
  "Show on widget until" field, empty by default. A commute with no end time never shows on a
  widget. Its **window** is from its time to its end time, on each of its days. An end time at or
  before the start time means the window runs past midnight and ends the next day.
- **The alert switch** stays the commute's alert switch. A commute with its alert turned off still
  shows on widgets during its window.
- **Placing a widget** opens a configuration screen listing every commute that has an end time,
  each with a checkbox. The rider ticks the ones this widget shows and taps Done. Commutes without
  an end time are listed greyed out, with "Set an end time to show it on a widget". With none at
  all, the screen says so and offers to open the app. The configuration can be opened again later
  from the launcher's widget settings (`widgetFeatures="reconfigurable"`). Each widget instance
  keeps its own list.
- **During a window**, the widget shows, for that commute:

  ```
  A  Southbound · 168 St
  3 min · 9 min · 14 min
  Delays: signal problems at 125 St
  Updated 7:52  ⟳
  ```

  - The departures and alert line are the same text the notification shows, built by the same
    shared code.
  - "Updated 7:52" is the local time the shown times were fetched.
  - ⟳ fetches again at once.
- **Refreshing during a window:** every 2 minutes while the screen is on, the widget fetches and
  redraws. When the refresh alarm runs while the screen is off, it does not fetch; it only sets
  the next alarm 1 minute ahead. Refresh alarms do not wake the phone (see "Calls made after the
  builder check"); one that came due while the phone slept runs as soon as it wakes, so the first
  refresh after taking the phone out comes within seconds.
- **Outside every window,** the widget shows its commute's name and "Tap to refresh", and fetches
  nothing on its own. Tapping fetches once and shows the times with "Updated" and ⟳. The commute
  shown is the one whose window starts next.
- **Times more than 15 minutes old** are replaced by "Tap to refresh". During a window the widget
  also fetches on its own as soon as the screen is on, and shows the new times when they arrive.
- **Two windows at once:** if two of a widget's commutes are in their windows, it shows both, one
  under the other, when the widget is tall enough; otherwise the one whose window started first.
- **Tapping the widget** anywhere except ⟳ or "Tap to refresh" opens the app on the Status tab, on
  that station, showing only that line and direction, like **See trains**.
- **When things go wrong:**
  - A fetch that fails or times out keeps the last times on screen; "Updated" shows their age, and
    past 15 minutes the widget shows "Tap to refresh".
  - A tapped refresh that fails shows "Couldn't get train times" with ⟳.
  - A deleted commute drops off every widget. A widget left with no commutes says "Tap to choose
    commutes", which opens its configuration screen.
  - A commute whose station is no longer listed shows "Station no longer listed".
- **Staying scheduled:** the widget's alarms are set again whenever the alerts' are (boot, time
  and time-zone changes, app update, exact-alarm permission change, app opened), when a commute is
  saved or deleted, and when a widget is placed, reconfigured or removed.

## Non-goals

- iOS. Shared code stays platform-neutral; iOS gets no widget.
- Walking time or "leave by" times.
- A widget that shows a station's whole board, or a line not set up as a commute.
- Refreshing faster than every 2 minutes.
- Lock-screen or Wear OS widgets.

## Anti-goals

- Times that look current but aren't: every set of times shown carries its "Updated" time, and
  times more than 15 minutes old are never shown.
- Draining the battery: no fetch outside a window unless tapped, no fetch while the screen is off,
  no foreground service.
- Changing how alerts behave for anyone who never places a widget, beyond the new departures
  format below.

## Constraints

- The window, "which commute is showing now" and the widget's text live in `shared/commonMain`
  and are unit-tested there; the widget, its receivers and its alarms are Android code.
- The widget uses Jetpack Glance (`androidx.glance:glance-appwidget`), a new dependency.
- Refresh alarms are non-waking (`RTC`): exact when `canScheduleExactAlarms()`, else `setWindow`
  with a window of at most 30 s. `SCHEDULE_EXACT_ALARM` is already declared; never
  `USE_EXACT_ALARM`. Each fetch runs in a receiver with `goAsync()` and the same 8 s timeout.
- One refresh alarm chain per widget instance, whose request codes never collide with each other
  or with the alerts'.
- The end time is stored in the existing commutes file. Files written by 0.25 (no end time) read
  as commutes with no end time.
- `minSdk` 26, `targetSdk` 37.
- Project rules: ktfmt, detekt, kover thresholds, Robolectric tests in `androidHostTest`; the PR
  sets `version.properties` to `0.<PR number>` and runs `./gradlew syncIosVersion`; CHANGELOG,
  README, `docs/CODE-TOUR.md` and `docs/privacy-policy.md` are updated (the widget reads MTA
  feeds; nothing about the rider is sent).

## Approaches

1. **An alarm chain during each window, with Glance (chosen).** Reuses the alerts' alarm pattern,
   runs while the app is closed, and skips the fetch while the screen is off. Glance is written
   like the app's Compose screens. Costs one new library; times can be up to a minute or two old
   right after unlocking, which ⟳ covers.
2. **The same alarm chain with classic `RemoteViews` XML layouts.** No new library, but layouts
   written separately from the app's UI code and more code to keep in step.
3. **WorkManager.** Rejected: periodic work runs at most every 15 minutes, and often later.
   A foreground service for the length of a window was also rejected: it needs a permanent
   notification.

The rider chose approach 1, with 1-minute checks while the screen is off.

## Calls made after the builder check

These were settled by the designer, not the rider; each can be changed.

- **Refresh alarms don't wake the phone.** A waking alarm every minute would wake the phone all
  window long just to see the screen is off. Doze would also hold it to about one every 9
  minutes, so it couldn't deliver a quick refresh on unlock anyway. A non-waking alarm costs
  nothing while the phone sleeps and runs the moment it wakes. So "1-minute checks while the
  screen is off" happen only while the phone is awake for some other reason.
- **Clearing old times while asleep:** each successful fetch also sets one inexact *waking* alarm
  (`setAndAllowWhileIdle`) for 15 minutes after it, replacing the previous one. When it fires, it
  redraws times that are still old as "Tap to refresh". So a phone left in a pocket wakes about
  once per window for this, not once a minute. Doze can delay it, so old times may still show,
  with their "Updated" time, for the second or two before the refresh alarm runs on unlock.
- **Old times during a window** fetch on their own as soon as the screen is on. "Tap to refresh"
  without a fetch is only for outside windows. This follows "every minute or two during a window,
  automatically".
- **The departures text is the same on the widget and the notification.** At the rider's request
  both use "3 min · 9 min · 14 min": each departure through `minutesText` ("now" for 0), joined
  with " · ". This changes the alert notification from 0.25's "3, 9, 14 min"; `commuteAlertText`,
  `CommuteAlertTextTest` and the CHANGELOG are updated to match.

## Left to the builder

- File and class names, following the alerts' patterns.
- The widget's sizes and exact layout, as long as it shows the fields above and stays legible at
  the launcher's smallest default size.
- How the end time is picked in the editor (time picker or field), as long as it can be cleared.
- How a widget instance's commute list is stored.
- How the alarm chain is keyed to widget instances, as long as codes never collide.
