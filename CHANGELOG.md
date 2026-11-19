# Changelog

Newest first. The version matches `version.properties`. The Play release notes for each version go
in `androidApp/src/main/play/release-notes/en-US/default.txt` (500 characters at most).

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
