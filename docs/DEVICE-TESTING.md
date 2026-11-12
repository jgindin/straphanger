# Testing on a real phone

Three features have only been tested with simulated input. They need a real phone and a real trip:

1. The Google Maps navigation notification listener.
2. Passive capture from station geofences while walking or riding.
3. Re-registering geofences after a reboot or after the app process is killed.

## Install

Turn on Developer options and USB debugging on the phone, connect it, and accept the prompt on the phone. Then, from the repository root:

```bash
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"   # once per terminal, so adb is found
adb devices                                            # the phone must show as "device"
./gradlew :androidApp:installDebug
adb shell am start -n com.straphanger.app.debug/com.straphanger.app.MainActivity
```

The debug build installs as `com.straphanger.app.debug`, so it sits beside any other build and keeps its own data. It also shows the developer buttons (sample rides, simulate ride) that the release build hides. If `adb devices` lists more than one device, add `-s <serial>` to the `adb` commands and set `ANDROID_SERIAL=<serial>` before `./gradlew`.

To watch what the app does while you test, keep this running in a second terminal:

```bash
adb logcat -s StraphangerGeofence:I StraphangerMapsListener:I
```

To start over with empty data:

```bash
adb shell pm clear com.straphanger.app.debug
```

## Check 0: the app starts (2 minutes)

1. Open the app. The header sits below the status bar, not under the clock.
2. Tap **Rides**, then **Load sample rides**. Rides appear. Open **Stats** and **Map**; both show numbers and lines.
3. Delete one ride from the Rides list. A confirmation appears, and the ride is gone after you confirm.
4. Force-stop the app (long press the icon > App info > Force stop) and open it again. The remaining rides are still there. If they are not, stop here: storage is broken.

## Check 1: Google Maps notifications

Why it matters: the app reads the notification Google Maps shows during transit navigation. The notification text has never been compared with real Google Maps output.

Steps:

1. In the app, open **Import & Backup**. Under **Google Maps notifications**, turn it on. Android opens its notification access settings. Allow **Straphanger**, then go back.
2. The app now says "Notification access granted."
3. In Google Maps, get **transit** directions for a subway trip and tap **Start**. Leave navigation running until you reach the destination, or press **Exit** at the end.
4. Open Straphanger > **Rides**.

What working looks like:

- Exactly **one** new ride appears for the whole trip, not one per notification update.
- The origin and destination stations match your trip. The line (route) matches the one you rode.
- The ride's start time is about when you pressed Start and the end time is about when you ended navigation.
- Logcat shows `StraphangerMapsListener` lines while navigating and nothing about errors.
- A needs-review banner, if shown, offers the real candidates (for example two lines) plus **Keep as is**.

What a problem looks like, and what to send me:

- No ride appears: run `adb logcat -d -s StraphangerMapsListener:V` and send the output. Also note the exact text of the Google Maps notification (pull down the shade and take a screenshot while navigating).
- Several rides for one trip: note how many and their times.
- A wrong station: send a screenshot of the ride and of the notification.
- To be sure the listener sees only Maps: start navigation in another app. Nothing should be recorded.

## Check 2: passive capture while moving

Why it matters: geofences are only tested with simulated location. Real GPS jitter, indoor signal loss and Android's battery handling are untested.

Setup:

1. Open **Import & Backup** > **Passive Capture** and turn it on. Read the disclosure screens, then allow precise location, and **Allow all the time** for background location.
2. In Android settings > Apps > Straphanger > Battery, choose **Unrestricted** (or "Not optimized") so Android does not delay events.
3. Check logcat. You should see `Registered N geofences: ...` with N up to 19. Seeing nothing means registration failed; look for `Geofence registration refused`.

Test walk (no train needed): walk to a subway station entrance and past it.

- Expected in logcat: entering a station geofence logs an Entered event, and moving about 70% of the outer radius away logs a window refresh (`Registered N geofences` again with a new set).
- This should not create a ride. Walking is below the speed floor, so no ride is expected.

Test ride: take a subway trip of at least two stops, with the phone in your pocket, screen off, app closed (swipe it away from recents).

- Expected afterwards: open the app. A ride appears from the station you entered to the station you left, within a few minutes of leaving the last station. The app records a ride about 15 minutes after the last station event, so wait that long, or open the app and give it a minute.
- The ride's distance and time look plausible (not 0 mi, not hours).
- Short single-stop hops under about 0.5 mi are not recorded. That is expected.
- Underground phones often lose GPS. If a ride is missing, note which stations it should have covered; that tells me whether the geofence radius needs changing.

What to send me if it fails: `adb logcat -d -s StraphangerGeofence:V`, the trip's stations and times, and whether the phone's battery saver was on.

## Check 3: surviving process death and reboot

1. With passive capture on, kill the app process without force-stopping it:

   ```bash
   adb shell am kill com.straphanger.app.debug
   ```

   Then walk to a different station (or travel a few blocks). Logcat should still show `Registered N geofences` for a new window even though the app was not opened. That proves the background refresh works. Do not use Force stop for this test: Android blocks every receiver of a force-stopped app until you open it again.

2. Reboot the phone:

   ```bash
   adb reboot
   ```

   Unlock the phone, then run `adb logcat -d -s StraphangerGeofence:I`. After boot you should see a line from the boot receiver and `Registered N geofences`. If there is no last known location, the log says it skipped; open any map app for a moment and reboot again.

3. Turn passive capture off in the app, reboot, and confirm there are **no** `Registered` lines. The setting must stick.

## Check 4: backup and restore

1. **Import & Backup** > export a backup. Choose a place in Files.
2. Delete a ride, then import the backup file. The ride comes back and nothing is duplicated.

## Reporting results

For each check write pass or fail. For a fail, send the logcat output named above, your phone model and Android version, and the steps you did. The three things most likely to need a tweak are the notification text format, geofence radius indoors, and battery-saver behavior.
