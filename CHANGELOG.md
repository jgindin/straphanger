# Changelog

Newest first. The version matches `version.properties`. The Play release notes for each version go
in `androidApp/src/main/play/release-notes/en-US/default.txt` (500 characters at most).

## 0.19 (not yet released)

- Status tab: pick a station to see the next trains in each direction, with the MTA's current service alerts (delays first, planned work on request).
- Map: pinch to zoom and drag after zooming work; track, stations and dark line colours are easier to see.
- The version is `0.<pull request number>`, shown on the About screen.

- Subway map, ride statistics and a ride list, with the network generated from the MTA GTFS feed.
- Manual ride logging, Google Maps Timeline import, and shared Google Maps route text.
- Optional passive capture with station geofences, and Google Maps navigation notifications.
- Backup and restore as a file you choose.
- Rides are saved to a file with a backup copy; a damaged file is set aside instead of overwritten.
