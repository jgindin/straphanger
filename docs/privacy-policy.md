# Straphanger privacy policy

Last updated: <date>

Straphanger is an app that records the subway rides you take in New York City. It has no accounts, no analytics and no ads. This page says what the app accesses, what it stores, and what it sends.

## What the app accesses

- **Precise and background location.** If you turn on passive capture, the app uses your precise location, including when the app is closed, to notice when you enter and leave subway stations. The app asks for this only after it shows you a screen that explains it. Passive capture is off until you turn it on.
- **Google Maps notifications.** If you turn on notification access, the app reads Google Maps transit navigation notifications to record the ride you are taking. It ignores notifications from every other app.
- **Files you choose.** The app reads a Google Maps Timeline file or a Straphanger backup file only when you pick one. It writes a backup file only where you tell it to.
- **Links you share.** If you share a Google Maps route to the app, it reads the text of the share.
- **Alarms & reminders.** If you create a commute alert, the app asks for the "Alarms & reminders" permission (`SCHEDULE_EXACT_ALARM`) so the alert arrives on the minute. It uses the permission only to wake the app at your commute times. Without it, alerts may be a few minutes late. The commute widget uses the same permission to refresh on time during a commute's window.

## What the app stores

- Your rides, and the station entries passive capture has not yet turned into rides, are stored on your phone in the app's private storage. So are the stations you star as favorites on the Status tab.
- While you use Google Maps navigation with notification access on, the app keeps the text of that navigation notification (the route, the stations and the start time) on your phone until the trip ends and the ride is saved.
- To keep passive capture following you, the app remembers the centre point of its current set of station geofences. This is a location on your phone and is never sent anywhere.
- If passive capture is on, the app registers its geofences again after your phone restarts. It does this in the background, using your phone's last known location, and does not need the app to be open.
- If you turn on "Notify me when a ride is recorded" (off by default), the app posts a notification on your phone when a ride is recorded, even when the app is closed. The notification is made and shown on your phone only; nothing about it is sent anywhere.
- If you create commute alerts, each commute (a line, a station, a direction, the days and a time) is stored only on your phone, in `commutes.json` in the app's private storage. It is not part of backups and is never sent anywhere. The notification each one posts is made and shown on your phone only.
- If you place a commute widget, which commutes each widget shows is stored only on your phone, in `commute_widgets.json` in the app's private storage. It is not part of backups and is never sent anywhere. The widget is drawn on your phone only.
- If Android backup is on for your phone, Android copies your rides file to your Google account backup and to a new phone during device transfer. Android controls that copy under your Google account settings. The app does not run its own backup service.

## What the app sends

The app sends nothing about you to the developer or to any analytics or advertising service. It makes two kinds of network request.

- **Shortened Google Maps links.** To resolve a shortened link (`maps.app.goo.gl`) that you shared to the app, it asks Google where the link goes. The request contains the shortened link you shared.
- **Live train times and service alerts.** While the Status tab is open, the app downloads the MTA's public real-time feeds (`api-endpoint.mta.info`) every 30 seconds: the alerts feed, and the feeds for the lines at the station you picked. At a commute alert's time, the app downloads the same public feeds once: the alerts feed and the feed for that commute's line. The request names a feed (a whole group of lines), never a station, a place or you. The MTA does not require an account or a key. Commute alerts read only the MTA's public feeds. The commute widget downloads the same public feeds: during a commute's window it fetches the alerts feed and the feed for that commute's line about every 2 minutes while the screen is on, and when you tap it outside a window. Requests never name a station, a commute or you; nothing about you is sent.

Like any network request, each shows your phone's IP address to the server it goes to (Google or the MTA). Your location, your rides, your notifications, your commutes and the station you picked are never sent.

Station geofencing uses Google Play services on your phone. Google Play services handles that location under Google's own privacy policy.

## Accounts, analytics and ads

The app has no accounts, no analytics, no crash reporting service and no ads.

## Deleting your data

Clear the app's storage in Android settings (Settings > Apps > Straphanger > Storage > Clear storage), or uninstall the app. Either removes your rides and commutes from the phone. To remove the Android backup copy, delete the app's backup in your Google account backup settings. Files you exported yourself stay where you saved them until you delete them.

## Subway data

Subway data comes from the Metropolitan Transportation Authority (MTA). Straphanger is an unofficial app. It is not affiliated with or endorsed by the MTA.

## Changes and contact

If this policy changes, the new version is published at this address with a new date. Questions: <contact-email>
