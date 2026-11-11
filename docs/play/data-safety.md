# Draft Play Console Data safety answers

These are drafts for the maintainer to enter and confirm in Play Console (App content > Data safety). Play's definition: "collect" means data is transmitted off the device. Data that stays on the device is not collected. Check each answer against the form's current wording before submitting.

## Form answers

| Question | Draft answer | Reasoning |
| --- | --- | --- |
| Does the app collect or share any of the required user data types? | No | Rides, locations and notification contents are processed and stored only on the device. The developer runs no server and receives nothing. |
| Is all of the user data collected by the app encrypted in transit? | Not applicable if the form skips it after "No" | No user data is collected. |
| Do you provide a way for users to request data deletion? | Not applicable | No data is collected. The privacy policy explains how to clear local data. |
| Location (approximate, precise) | Not collected, not shared | Used on-device to detect station entries. Station entries are written to the app's private storage. |
| Files and docs | Not collected, not shared | Timeline and backup files are read on-device when the user picks them. |
| App activity, personal info, financial info, health, messages, photos, audio, contacts, calendar, web browsing, device or other IDs | Not collected | The app does not read or send them. |
| Account creation | None | The app has no accounts. |
| Privacy policy URL | The published copy of `docs/privacy-policy.md` | Required for apps that use sensitive permissions. The URL constant is `PRIVACY_POLICY_URL` in `AppInfo.kt`. |

## Points to decide before submitting

1. **Short-link resolution.** When the user shares a `maps.app.goo.gl` link to the app, the app requests that link (`GoogleMapsShareParser`) and reads the redirect. The user starts this by sharing, the request goes to Google (a third party), and it carries only the link the user shared plus the normal IP address. Play's data safety guidance exempts data a user sends to a third party by their own action, so the draft answer is "not collected". If you prefer to be conservative, declare nothing extra but say so in the privacy policy, which already does.
2. **Google Play services geofencing.** Station geofences are registered with the Play services location API. Play services holds the geofences and wakes the app on entry. The app does not send location to the developer. Draft answer: not collected. Play services' own handling is covered by Google's policy.
3. **Android Auto Backup.** `rides.json` is included in Android cloud backup and device transfer (`backup_rules.xml`, `data_extraction_rules.xml`). The copy goes to the user's own Google account backup, not to the developer. Draft answer: not collected. Confirm against the current Play guidance on system backup.
4. **Notification access.** The listener reads only Google Maps transit navigation notifications and ignores others. Nothing leaves the device.

## Reviewed, no change

The `play-policy-insights` skill could not delegate to subagents in this session, so its prompts were read and applied directly against the manifest, `MainActivity`, `CapturePermissions`, `GeofenceBroadcastReceiver`, `PassiveCaptureStorage`, the pending-event store and `GoogleMapsShareParser`. Findings other than the disclosure gap, which this change fixes:

- **Background location (location_access_policy).** Declared and used only for passive capture, which is off until the user turns it on. The foreground-only alternative cannot record rides while the app is closed, which is the feature. No change.
- **Fine and coarse location.** Station geofences and speed checks need precise location. Both are requested together, as Android requires for precise location. No change.
- **Passive capture default.** `PassiveCaptureStorage.isTurnedOn` defaults to true, but no location is read until the user grants permission, and permission is requested only after the disclosure. No change.
- **Disclosure wording.** The copy names location and the app being closed. It says "even when the app is closed" and does not use the words "or not in use". Play's wording check suggests naming both. The copy is fixed by the task brief, so it is unchanged. Revisit if Play review asks.
- **Network use.** `INTERNET` is used for short-link resolution only. There is no analytics, ad or crash library in the dependencies. No change.
- **Silent transfers.** None found. Station entries and rides stay in `filesDir`.
- **Foreground location on Android 17 (targetSdk 37).** The app targets 36, so the Location Button suggestion does not apply yet.
- **Notification listener.** Not a Play-restricted permission. The disclosure covers it and the privacy policy names it.
- **Sample and simulation buttons.** The Sources and Rides tabs have "Import ... Sample" and "simulate" buttons that write demo rides. They are not a policy issue but are visible to users. Consider hiding them in release builds.
