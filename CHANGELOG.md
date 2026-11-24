# Changelog

Newest first. The version matches `version.properties`. The Play release notes for each version go
in `androidApp/src/main/play/release-notes/en-US/default.txt` (500 characters at most).

## 0.26 (not yet released)

- Commute widget: a home-screen widget that shows the next live departures for the commutes you pick, and refreshes itself during the commute's window. Place as many as you like; each opens a "Choose commutes" screen (and can be reconfigured from the launcher's widget settings). The default size is 3×2; two commutes are shown when the widget is at least 140 dp tall and two windows overlap.
- Commutes have an optional "Show on widget until" time. The window runs from the alert time to that time on the commute's days (an end at or before the start runs past midnight), and the Commutes list shows it, such as "Weekdays 7:45–8:30". A commute with no end time is not shown on the widget.
- During a window the widget refreshes every 2 minutes while the screen is on. With the screen off it does not wake the phone, so a refresh comes within seconds of waking it. With "Alarms & reminders" allowed refreshes are on time, otherwise within about 30 seconds.
- Times stay on the widget for 15 minutes. When it has no times from the last 15 minutes it says "Tap to refresh", and outside a window it fetches only when tapped. If the last fetch failed it says "Couldn't get train times" until then. The title line shows "Updated 7:52" and a refresh icon. Tapping a row opens that station filtered to the line and direction.
- The departures in the commute alert notification now read "3 min · 9 min · 14 min" (they were "3, 9, 14 min"), the same as on the widget.

## 0.25 (not yet released)

- Commute alerts: on a station's arrivals card on the Status tab, "Add commute alert" sets one line, one direction, the days (Monday to Friday by default) and a time (8:00 by default). At that time on those days the phone shows one notification with the next live departures, such as "A Southbound · 168 St" and "3, 9, 14 min", with the first service alert for that line if there is one. "See trains", or tapping the notification, opens the Status tab on that station showing only that line and direction; "Show all" clears it. A Commutes section at the top of the Status tab lists every commute with an on/off switch; tap one to edit or delete it. Alerts are posted on the new "Commute alerts" channel, even with the app closed, and are set again after a reboot, an app update or a clock or time zone change.
- Saving the first commute asks for notifications (Android 13 and later), then opens "Alarms & reminders" so alerts come on the minute. Without it they may be a few minutes late, and the Commutes section says so. An alert more than 15 minutes late (phone off) is skipped.

## 0.24 (not yet released)

- Notifications: turn on "Notify me when a ride is recorded" on the Data sources screen (off by default; Android 13 and later asks for permission, and when notifications are blocked the switch opens the system settings). When passive capture or Google Maps navigation records a ride, a notification on the "Rides recorded" channel shows it, such as "A · 14 St → 168 St" and "32 min", even with the app closed. A ride that needs review says "Express or local? Tap to confirm". A passive capture ride is notified about 6 to 15 minutes after it ends, because that is how long it takes to detect that the ride has ended; a Google Maps navigation ride is notified when navigation ends. Tapping it opens the Rides tab with that ride highlighted. If notifications are blocked in system settings while the switch is on, allowing them there later brings ride notifications back.
- An idle ride now closes on a system alarm when the app is not running.

## 0.23 (not yet released)

- Rides to review: "Looks right" (was "Keep as is") marks a ride as correct, and the review buttons also appear on the ride in the list.
- The review box shows each ride's date and time.
- "Combine with next ride" joins two rides that are really one trip, such as 168 St → 103 St and 103 St → 14 St, into a single ride.
- Edit stations: after choosing the stations, choose which train it was (for example A Express or C Local), or let the app decide.

## 0.22 (not yet released)

- Map: every line is drawn in its MTA colour, ridden or not (ridden ones are thicker with a glow); water is dark blue, land lighter charcoal and parks green; stations and line widths are bigger and scale with the screen.

## 0.21 (not yet released)

- Edit stations: every station is offered, so "14" finds the 14 St on the A/C/E as well as the 1/2/3 and F/M ones. Stations nearest the one being replaced come first, with their lines and distance, and the dialog uses the app's dark colours.

## 0.20 (not yet released)

- Status tab: star a station to keep it as a favorite. Favorites are listed above the search box and open with one tap. They are kept on the device in `favorite-stations.json` and are not part of backups.

## 0.19 (not yet released)

- Status tab: pick a station to see the next trains in each direction, with the MTA's current service alerts (delays first, planned work on request).
- Map: pinch to zoom and drag after zooming work; track, stations and dark line colours are easier to see.
- The version is `0.<pull request number>`, shown on the About screen.

- Subway map, ride statistics and a ride list, with the network generated from the MTA GTFS feed.
- Manual ride logging, Google Maps Timeline import, and shared Google Maps route text.
- Optional passive capture with station geofences, and Google Maps navigation notifications.
- Backup and restore as a file you choose.
- Rides are saved to a file with a backup copy; a damaged file is set aside instead of overwritten.
