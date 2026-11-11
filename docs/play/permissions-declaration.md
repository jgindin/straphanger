# Play Console permissions declaration (draft)

Play Console > App content > Sensitive app permissions > Location permissions in the background.

## Core feature

Straphanger records the subway rides a user takes. With passive capture turned on, the app registers geofences around nearby subway stations. When the user enters a station, Android wakes the app, which saves the entry. When the user enters a second station at subway speed, the app records a ride between the two stations.

## Why background location is needed

Riders do not keep the app open on a train. The station entry has to be noticed while the app is closed or the phone is in a pocket. Foreground location cannot do this, so without background location the passive capture feature does not work. The user can still log rides by hand and import Google Maps data without it.

## Declaration text

"Straphanger uses background location to detect when the user enters a subway station so it can record the rides they take, even when the app is closed. The feature is optional and off until the user turns on passive capture in the app. Before any location permission prompt, the app shows a full screen explaining the use, with Not now and Continue buttons. Location is used only on the device. It is never sent to the developer or any other party, and it is not used for ads or analytics."

The in-app disclosure text is:

"Straphanger uses your location in the background to notice when you enter and leave subway stations, even when the app is closed. Your location is used only on this phone to record rides and is never sent anywhere."

## Demo video shot list

The video must show the feature working and the disclosure and permission flow. Record on a real phone or emulator running Android 11 or later. Upload to YouTube as unlisted and paste the link.

1. App launch from the home screen. Show the app name.
2. Tap the Sources tab. Scroll to "Passive Capture". Show the "Station geofences" toggle in the off state.
3. Tap the toggle. The background location disclosure appears. Hold on it long enough to read the full text.
4. Tap "Continue". Show the system precise location prompt and grant it while using the app.
5. Show the second system step for "Allow all the time" (the app settings screen on Android 11 and later). Grant it.
6. Return to the app. Show the toggle on and the "regions registered" text.
7. Show a station entry being recorded with the app closed: close the app, trigger a location change on the device or emulator near a station, then reopen the app and show the entry or ride in the Rides tab.
8. Repeat the first tap on the toggle and choose "Not now" to show the toggle stays off and no permission prompt appears.
9. Show the About screen and the privacy policy link.
10. Show where the user turns passive capture off (the toggle) and where Android shows the permission (Settings > Apps > Straphanger > Permissions).

## Also in Play Console

- Privacy policy URL: the published copy of `docs/privacy-policy.md`.
- Data safety form: see `docs/play/data-safety.md`.
- Target audience: not designed for children.
