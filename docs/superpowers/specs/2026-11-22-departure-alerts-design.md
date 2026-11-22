# Departure alerts: design

## Intent

At a time the rider chooses, on the days they choose, the phone shows one notification with the next
live departures of one line at one station in one direction. For example: "A Southbound · 168 St ·
3, 9, 14 min". The rider uses it to decide when to leave home. A **See trains** action opens the app
on that station's live arrivals, showing only that line and direction.

This is the second of three features. The first (ride-completed notification, 0.24) is done. The
third is the home-screen widget. The widget will reuse the **commutes** this feature introduces,
adding an end time to each.

## How it works today

- The Status tab (`ui/status/StatusScreen.kt`) searches station complexes, keeps starred ones in
  `FavoriteStationsStore` (a JSON file through `JsonStateFile`), and shows live arrivals for a
  selected complex, grouped Northbound / Southbound, refreshed every `REFRESH_MS`.
- Live data comes from `RealtimeFeedClient.fetch(routes)`, which reads the MTA's public
  GTFS-Realtime feeds (no key) and returns a `RealtimeSnapshot` with an `ArrivalBoard` and the
  active `ServiceAlert`s. `ArrivalBoard.arrivalsAt(stationId, now)` returns `Arrival(routeId,
  stationId, direction, epochSeconds)`. A complex has several `stationIds`.
- The app has no bundled timetable; only live predictions.
- The app already has `POST_NOTIFICATIONS`, the "Rides recorded" channel, `RECEIVE_BOOT_COMPLETED`,
  an `AlarmManager` user (`AlarmIdleCloseScheduler`, inexact `setAndAllowWhileIdle`) and a
  tap-to-open path (`RideTapInbox`, `MainActivity` intent handling guarded against rotation and
  history relaunch).

## Goals

- **A commute** is: one line (`routeId`), one station complex (`complexId`), one `Direction`, a set
  of days of the week, a time of day (hour and minute, local time), and an on/off switch. It has a
  stable id.
- **Creating one:** the Status tab's arrivals card gets an **Add commute alert** button. It opens an
  editor prefilled with that station. The rider picks:
  - the line, from that complex's routes;
  - the direction, labelled "Northbound" / "Southbound" as the card already does;
  - the days, Monday to Friday by default;
  - the time, 8:00 by default.
- **Managing them:** a **Commutes** section at the top of the Status tab lists every commute as
  "A Southbound · 168 St · Weekdays 7:45" with an on/off switch. Tapping one opens the editor, which
  can also delete it. Morning and evening are simply two commutes. Any number is allowed.
- **Firing:** at the commute's time on each chosen day, the app fetches live arrivals for that line
  and posts one notification. It does not update afterwards. It is posted even when the app is not
  running.
- **Notification content:**
  - Title: "A Southbound · 168 St".
  - Text: the next departures of that line, in that direction, at any of the complex's stations,
    soonest first, at most 3: "3, 9, 14 min" ("now" for 0, as `minutesText` does).
  - If any active service alert names that line, a second line: the first such alert's header,
    prefixed by its kind as the Status tab shows it (e.g. "Delays: signal problems at 125 St").
  - No trains listed: "No A trains listed southbound right now".
  - The feed for that line could not be read: "Couldn't get train times".
  - Action **See trains**, and tapping the notification body, both open the app on the Status tab
    with that complex selected and only that line and direction shown. The rider can clear the
    filter to see the whole station.
- **Channel:** a new "Commute alerts" channel, default importance. Each commute posts under its own
  notification id, so a new one replaces that commute's previous notification.
- **Permissions:**
  - Creating or turning on the first commute asks for the notification permission on Android 13+
    if it isn't granted, the same way the ride-notification switch does.
  - On-the-minute delivery uses `setExactAndAllowWhileIdle`, which needs
    `SCHEDULE_EXACT_ALARM` ("Alarms & reminders"). The app requests it by opening
    `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` when the rider creates the first commute.
  - Without it, alerts are scheduled with `setAndAllowWhileIdle` and may be a few minutes late. The
    Commutes section then shows "Alerts may be a few minutes late. Tap to allow exact alarms." which
    opens that setting.
  - With notifications blocked, the Commutes section shows "Notifications are blocked for
    Straphanger. Tap to allow them in system settings." like the ride-notification switch.
- **Staying scheduled:** each enabled commute always has an alarm set for its next occurrence.
  Alarms are set again after: firing, any edit, boot, `TIME_SET`, `TIMEZONE_CHANGED`,
  `MY_PACKAGE_REPLACED`, and the exact-alarm permission changing
  (`ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`).

## Non-goals

- Walking time or "leave by" times. The rider chose plain train times.
- A printed timetable; only live predictions.
- Repeating or updating notifications; one per occurrence.
- An end time on commutes; the widget adds it.
- Arrival at the destination, or alerts about whole trips.
- iOS. Shared code stays platform-neutral; iOS gets no scheduler.

## Anti-goals

- An alert that silently stops firing (after a reboot, time change or app update) is a failure even
  if every test passes.
- A late alert that shows trains that already left. Departures are fetched when the alarm fires,
  never in advance.
- Asking for exact alarms before the rider has created a commute.

## Constraints

- Kotlin Multiplatform: the commute model, the store, the "next occurrence" calculation, the
  notification text and the firing logic live in `shared/commonMain`; Android wiring in
  `androidApp` / `shared/androidMain`, following the ride-notification pattern (`RideNotifier`,
  `AndroidRideNotifier`, `AlarmIdleCloseScheduler`, `IdleCloseReceiver`).
- The fetch happens in a `BroadcastReceiver` using `goAsync()`; it must finish well within the
  receiver's time limit. Use a timeout of 8 s on the fetch (the receiver has about 10 s); on timeout, post "Couldn't get train
  times".
- `minSdk` 26, `targetSdk` 37. `SCHEDULE_EXACT_ALARM` is declared in the manifest. Do not use
  `USE_EXACT_ALARM` (Play reserves it for alarm-clock and calendar apps).
- Commutes are stored as JSON through `JsonStateFile`, like favourite stations. A corrupt file is
  moved aside and read as empty.
- The next occurrence is computed in the phone's current time zone. Across a DST change the alert
  fires at the chosen wall-clock time; a time that doesn't exist that day (spring forward) fires at
  the first valid minute after it.
- Copy and labels as written above.
- Project rules: ktfmt, detekt, kover thresholds, Robolectric tests in `androidHostTest`; the PR sets
  `version.properties` to `0.<PR number>` and runs `./gradlew syncIosVersion`; CHANGELOG, README,
  `docs/CODE-TOUR.md` and `docs/privacy-policy.md` are updated (the alert reads MTA feeds; nothing
  about the rider is sent).

## Approaches

1. **Exact `AlarmManager` alarm per commute, fetch on fire (chosen).** On time to the minute when the
   rider grants "Alarms & reminders"; falls back to inexact. One fetch per alert.
2. **WorkManager periodic or one-time work.** Rejected: can run 15 minutes or more late, which
   defeats an alert used to decide when to leave.
3. **A foreground service that stays running.** Rejected: a permanent notification and battery cost
   for one alert a day.

The rider approved approach 1.

## Calls made after the builder check

These were settled by the designer, not the rider; each can be changed.

- **The line-and-direction filter on the Status tab is new work** in this feature. Clearing it shows
  the station's usual grouped view.
- **Both directions are always offered**, since every line's stops carry both N and S. At a
  terminal one direction simply has no trains, and the alert says so.
- **Lines and their variants:** the line picker offers the complex's routes as the Status tab lists
  them. A commute's line matches arrivals and alerts whose `routeId` is that line or its express
  variant (`<line>X`, e.g. 6 and 6X). Feeds are chosen with `RealtimeFeed.forRoutes`.
- **Permission order:** creating the first commute asks for notifications first (Android 13+), then,
  if `AlarmManager.canScheduleExactAlarms()` is false, opens the exact-alarm setting. If
  notifications are denied the commute is still saved, and the Commutes section shows the blocked
  note.
- **Late alarms are skipped:** an occurrence fired more than 15 minutes after its time (phone off,
  deep idle) posts nothing; the next occurrence is scheduled as usual.
- **The alert-kind prefix** is the text the Status tab shows for `AlertKind`. If that text lives in
  Compose code, it moves to shared code so the notification can use it.
- **A station that no longer exists** after a data update: the commute shows "Station no longer
  listed" in the Commutes section and posts nothing until edited or deleted.

## Left to the builder

- File and class names, beyond following the existing capture/notification patterns.
- The editor's layout (dialog or screen), as long as it has the fields above and a Delete button
  when editing.
- How the time is picked (Material time picker or a simple field).
- How the Status tab carries the line-and-direction filter, and its "show all" control.
- How alarm request codes map to commute ids, as long as two commutes never collide.
