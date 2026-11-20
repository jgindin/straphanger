# Ride-completed notification: design

## Intent

When the app records a subway ride, the phone shows a notification right away, even when the app is
closed. For example: "A · 14 St → 168 St · 32 min". The user wants to see that a ride was caught
without opening the app, and to fix it while it's fresh if it needs review.

This is the first of three features, in this order:

1. ride-completed notification (this design);
2. departure alerts;
3. the home-screen widget.

Features 2 and 3 will share "commute slots". This feature builds the notification permission and
channel that alerts will reuse.

## How it works today

- Passive capture uses station geofences. `GeofenceBroadcastReceiver` appends each station entry
  and exit to `PendingGeofenceEventStore`, which is a file.
- Rides are built only by `PassiveCaptureCoordinator`. That coordinator lives in the view model's
  scope, so it exists only while the app process has a view model. It replays the pending store
  at start.
- When the app is closed, nothing becomes a ride until the user next opens it.
- A chain of stations closes into a ride in three cases:
  - an exit from the last station after dwelling there for 6 minutes (`DWELL_SPLIT_MS`);
  - 15 minutes with no new station (`DEFAULT_IDLE_CLOSE_MS`, run on a coroutine timer);
  - a station entry that isn't a transit hop.
- Google Maps navigation sessions, from the notification listener, are also only turned into rides
  by the view model.
- The app doesn't post any notifications today. It doesn't have `POST_NOTIFICATIONS`, WorkManager
  or AlarmManager. `targetSdk` is 37 and `minSdk` is 26.

## Goals

- A notification for every ride that passive capture or a Google Maps navigation session records,
  whether the app is open, in the background, or not running.
- The text has the line(s), the origin and destination station names, and the duration. A
  transfer ride shows each leg's line, e.g. "A → L".
- A ride that needs review says so, e.g. "Express or local? Tap to confirm".
- Tapping the notification opens the app on the Rides tab with that ride in view.
- A "Notify me when a ride is recorded" switch on the Data sources screen, next to passive capture.
  Off by default.
  - Turning it on asks for the notification permission on Android 13 and later.
  - If the user denies it, the switch stays off and the screen says how to allow it in system
    settings.
  - If the permission is revoked later, the screen shows the switch as blocked.
- One notification channel, "Rides recorded", at default importance. Departure alerts will get
  their own channel later.

## Non-goals

- No notification for rides the user enters themselves: manual entry, shared routes, Timeline
  import, or station edits. The user already knows about those.
- No actions on the notification itself, such as "Confirm express". The tap opens the app, which
  already has the review banner.
- iOS. The shared code stays platform-neutral and iOS gets nothing new.
- Departure alerts and the widget.

## Anti-goals

- Losing or duplicating a ride. Background building must never overwrite rides the app saved, or
  get overwritten by them.
- Notifying for a ride that then isn't in the ride list.
- Draining the battery. Work runs only on a geofence event or a scheduled check, never as a polling
  loop or a foreground service.

## Timing

A ride is only known once its chain closes, so the notification arrives some time after the user
leaves the train:

- about 6 minutes after they leave the destination station area;
- otherwise up to 15 minutes after the last station.

This design keeps the reconstructor's rules unchanged, so this delay stays. Shortening it would
mean changing how rides are detected, which is out of scope.

## Approaches considered

1. **Build rides in the background on each geofence event, with a scheduled check for the idle
   close (chosen).** Passive capture moves out of the view model into one process-wide owner that
   both the app and the receivers use. Rides get built whether or not the UI runs, and there is one
   writer of the rides file.
2. **Notify only while the view model runs.** Small, but the app is usually closed during a ride,
   so the user would rarely see it. Rejected.
3. **Post a vague "you may have ridden" notice from the receiver on a station exit.** Cheap, but it
   has no line or stations. Rejected by the user.

## Design (chosen approach)

### One owner of capture and of the rides file per process

Today the view model owns the DAO, which is an in-memory snapshot saved to `rides.json`. If a
background job loaded and saved the same file separately, the two writers would overwrite each
other.

The fix is an application-scoped holder, created lazily on first use, that owns:

- the loaded `SubwayNetworkData` and graph engine;
- the `SubwayDao` over `FileRidePersistenceBackend("rides.json")`;
- a `PassiveCaptureCoordinator`-style ride builder;
- a single-threaded dispatcher for DAO work.

The view model gets its DAO and network from this holder instead of building its own. The receivers
use the same holder. The geofence and boot receivers already run in the app process, so a process
singleton is enough and no cross-process lock is needed.

### Background trigger

- `GeofenceBroadcastReceiver` keeps appending to the pending store, as today.
- When no view model is collecting, the receiver uses `goAsync()` with a bounded timeout, like
  `BackgroundGeofenceRefresher`. It asks the holder to drain the pending events, which can close a
  chain and record a ride.
- The idle close needs a timer that survives the process dying. Whenever a chain is open, schedule
  a one-shot check at `chainLastTimeMs + idleCloseMs` using AlarmManager (inexact, allowed while
  idle, without exact-alarm permission) or WorkManager with an initial delay. The check runs
  `closeIfIdle` through the holder.
  - When the process is alive, the in-process coroutine timer can still fire first.
  - A late or duplicate check is harmless, because `closeIfIdle` is idempotent.
- `BootCompletedReceiver` reschedules the check if a chain is open.

### Google Maps navigation sessions

Ended sessions are drained by the holder too, not only by the view model. The listener service
triggers that drain when a session ends, so these rides also notify while the app is closed.

### Posting

- A platform-neutral interface, `RideNotifier { fun rideRecorded(record: CompleteRideRecord) }`,
  is called by the holder after a successful save.
- It is not called when `lastSaveSucceeded` is false.
- It is not called for a duplicate that was skipped.
- The text is built in common code, so it is unit-testable. The Android implementation posts on the
  "Rides recorded" channel.
- It posts nothing when the switch is off or the permission is missing.
- Each ride gets its own notification, keyed by ride id, and auto-cancels on tap.
- The tap uses a `PendingIntent` to `MainActivity` with the ride id. The app opens the Rides tab
  scrolled to that ride.

### Permission and settings

- Add `POST_NOTIFICATIONS` to the manifest.
- The switch's on/off is stored like the passive capture flag, readable from receivers.
- The permission request and denial handling follow the pattern of the existing location permission
  flow.

## Testing

- Common tests, in the existing style with the in-memory backend and `FixedClock`:
  - notification text for one leg, a transfer, and a ride that needs review;
  - the notifier is called once per saved ride, not on a failed save, and not on a duplicate;
  - draining pending events through the holder with no view model records the ride and notifies;
  - the scheduled idle check closes the chain and notifies once even if it runs twice.
- Android Robolectric tests:
  - the notifier posts on the channel with the expected text and tap intent;
  - it posts nothing when the switch is off or the permission is denied.
- The view model tests keep passing with the DAO coming from the holder.

## Left to the builder

- Whether the idle check uses AlarmManager or WorkManager. Prefer no new dependency if AlarmManager
  covers it.
- The exact name and file layout of the process-wide holder.
- How the Rides tab scrolls to or highlights the tapped ride.
- Version 0.22 and the CHANGELOG, README, CODE-TOUR and privacy-policy updates. The privacy policy
  needs a note that notifications are posted locally and nothing leaves the phone.
