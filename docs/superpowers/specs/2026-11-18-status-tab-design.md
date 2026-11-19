# Status tab: next trains and service alerts (design)

Approved by the owner on 2026-11-18 ("build the train schedule as described with feed client, status screen and alerts"). The fuller background is in `docs/REALTIME-DESIGN.md` on the `realtime-design` branch.

## Intent

Answer "when is my next train" and "is my line having problems" without leaving the app, using the MTA's public real-time feeds. The first version covers arrivals at a chosen station and the current service alerts.

## Goals

- Pick a station by name and see the next trains per direction, in minutes.
- See current delays, with planned work available but out of the way.
- Never show stale numbers as fresh: say when the data is old, partial, or unreachable.

## Non-goals and anti-goals

- Favorites, "notify me before my train" alerts, and lateness against the schedule are not part of this version.
- Nothing about the user is sent anywhere; the request names a group of lines, never a station or a place.
- No server, no API key, no new account.

## Constraints

- Shared Kotlin Multiplatform code; the MTA feeds are protobuf, decoded with `kotlinx-serialization-protobuf` and a hand-written schema.
- The privacy policy, README and Play data-safety notes change in the same pull request as the first network call.

## Approaches

1. **Read the MTA feeds directly from the phone (chosen).** No hosting or cost; the MTA sees the phone's IP address and the feed asked for.
2. Our own server relaying the feeds. Rejected: needs hosting and gives a second party the request stream.
3. Bundle the static schedule and show lateness. Rejected for now: large data for a small gain.

## Design

- `data/realtime`: protobuf models, `RealtimeFeed` (line to feed), `RealtimeFeedClient.fetch(routes)` (the feeds for those lines plus alerts, concurrently; partial failure is flagged, total failure throws), `ArrivalBoard`, and `activeAlerts`.
- `ui/status`: a Status tab with station search over station complexes, arrivals per direction (north/south), a freshness line with Refresh, and alerts (delays first, those for the station's lines first, planned work behind a toggle). It refetches every 30 seconds while shown.
- Left to the builder: layout details and wording.
