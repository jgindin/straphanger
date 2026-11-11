# gtfs-import

Generates `shared/src/commonMain/composeResources/files/nyc_subway_network.json`, the subway network
the app loads at startup, from MTA GTFS data. Station and complex totals, system miles, route stop
patterns and segment lengths all come from this file; nothing in the app hard-codes them.

## Inputs

Downloaded inputs go in `tools/gtfs-import/input/`, which is git-ignored. Only the generated JSON is committed.

| File | Source | Downloaded |
| --- | --- | --- |
| `input/gtfs_subway.zip` | NYCT Subway static GTFS ("Regular GTFS"), linked from https://www.mta.info/developers: https://rrgtfsfeeds.s3.amazonaws.com/gtfs_subway.zip | 2026-11-10 |
| `input/mta_subway_stations.csv` | data.ny.gov "MTA Subway Stations" (dataset `39hk-dx4f`): https://data.ny.gov/api/views/39hk-dx4f/rows.csv?accessType=DOWNLOAD | 2026-11-10 |

The 2026-11-10 GTFS download has `feed_info.txt` version `20261028-fall-2026-pick-with-long-term-supplements`,
valid 2026-11-01 to 2027-05-15. SHA-256 of the inputs used for the committed file:

- `gtfs_subway.zip`: `acb122715bd90aeacf61f4559722ccf0b7de9c63317200a6dd7480246488f1e6`
- `mta_subway_stations.csv`: `03fc3107c9863fd77ad17bd124cb1cb5a0755d40f474a8ab3de239a90641edad`

`sourceDate` in the generated file is the download date.

## Terms and attribution

- MTA developer resources (https://www.mta.info/developers) say the data feeds are free to use, and that
  using MTA logos, maps, symbols or other intellectual property needs a license
  (https://www.mta.info/doing-business-with-us/licensing-program).
- The MTA data feed terms (https://www.mta.info/developers/terms-and-conditions, read 2026-11-10) provide the
  data "as is" with no warranty; an app must not state or imply that the data is accurate, complete or timely,
  or that the app is licensed or endorsed by the MTA; the data must be served from your own servers, not the
  MTA's; and the terms allow saying the data "was obtained by you from an MTA server". The terms say you
  may use part of the data but not modify it. The MTA can change the terms or revoke access at any time.
- The terms give no required attribution wording. The data.ny.gov dataset lists no license; its
  attribution field is "Metropolitan Transportation Authority" (https://www.mta.info/open-data).

Attribution text to show in the app (Task 13, About screen):

> Subway data from the Metropolitan Transportation Authority (MTA) GTFS feed and the MTA Subway Stations
> dataset on data.ny.gov, downloaded <sourceDate>. Straphanger is not affiliated with or endorsed by the MTA.
> The data is provided as is and may not be accurate, complete or current.

## Regenerating

```sh
mkdir -p tools/gtfs-import/input
curl -L -o tools/gtfs-import/input/gtfs_subway.zip https://rrgtfsfeeds.s3.amazonaws.com/gtfs_subway.zip
curl -L -o tools/gtfs-import/input/mta_subway_stations.csv \
  'https://data.ny.gov/api/views/39hk-dx4f/rows.csv?accessType=DOWNLOAD'
./gradlew :tools:gtfs-import:run --args="--gtfs tools/gtfs-import/input/gtfs_subway.zip \
  --stations tools/gtfs-import/input/mta_subway_stations.csv \
  --out shared/src/commonMain/composeResources/files/nyc_subway_network.json \
  --source-date YYYY-MM-DD"
./gradlew :tools:gtfs-import:test :shared:testAndroidHostTest
```

Update the URLs, dates and checksums above. The run prints station, complex and mile totals, how many
express pairs were expanded, how many segments fell back to haversine, and each cross-trunk expansion;
compare them with the previous run. A route ID that is missing from `route-metadata.json` stops the run.

`route-metadata.json` is hand-maintained: for each GTFS `route_id`, its trunk group, division and display
name. Colors come from GTFS `routes.txt`.

## Generator rules

- **Stations** are `stops.txt` rows with `location_type = 1`. Complex ID and borough come from the station
  CSV, joined on `GTFS Stop ID`. A complex's name is its stations' distinct names joined with " / ".
- **Periods.** A trip's departure is its first stop's departure time. Weekday-service trips departing
  06:00-21:59 are DAYTIME and 00:00-04:59 are LATE_NIGHT (GTFS times past 24:00 count by hour mod 24, so
  25:30 is late night). Saturday-service trips departing 10:00-17:59 are WEEKEND. RUSH_PEAK gets no pattern;
  the app falls back to DAYTIME for any period a route has no pattern for.
- **stopsAt.** For each route and period, the most common stop sequence among that window's trips. The
  direction-0 pattern is used; if direction 0 has no trips in the window, the direction-1 pattern reversed.
  A route with no trips in a window has no entry for it.
- **Station routes.** `daytimeRoutes`/`lateNightRoutes` are the routes with any trip in that window stopping
  at the station (all patterns, both directions). `isTransferHub` is true when the station's complex has more
  than one station, or its routes span more than one trunk group.
- **Segments.** Every consecutive stop pair in every distinct trip pattern in the feed. Pairs are processed
  shortest straight-line distance first, per (pair, trunk group of the routes using it). A pair (A, B) with a
  path of already-kept pairs of the same trunk group no longer than 1.3 x the straight-line distance A-B is
  replaced by that path, and its routes are credited to the path's segments. Otherwise it is kept as its
  own segment. Segments are oriented the way direction-0 trips run them.
- **Distance and polyline.** For each segment, the most common shape among trips running it in its
  orientation. The distance is the length of that shape between the shape points nearest each stop
  (searching for the second stop after the first), and the polyline is that stretch, thinned to at most one
  point per 50 m (first and last points kept). Without a shape: haversine x 1.08 and a straight line.

### Deviations from the plan's rules

1. **Cross-trunk expansion.** When an express pair has no same-trunk local path, a path through kept pairs
   of any trunk group (still within 1.3x) is used. Without this the B on the Brighton line (IND 6 Av trunk,
   whose local there is the BMT Q) and rush-hour R/W trips running express on the West End line (local there
   is the D) became parallel segments over the local corridor. On the 2026-11-10 feed this applies to exactly
   six pairs, all on those two lines; the run prints them.
2. **Shared track.** When a pair is already kept by another trunk group (the B and C on Central Park West,
   the A and H on the Rockaway line), the second trunk joins that segment instead of treating the pair as an
   express run. The segment's `trunkId` is the owning trunk group with the most routes on it.
3. **Implausible shape stretch.** If the matched shape stretch is shorter than 0.95x the straight line or
   longer than 3x it, the segment uses the no-shape fallback. Two segments hit this on the 2026-11-10 feed.

## Output (2026-11-10 feed)

496 stations, 445 complexes, 503 segments, 253.53 system miles, 288 KB. Routes:
A C E B D F FX M G J Z L N Q R W GS FS H 1 2 3 4 5 6 6X 7 7X SI.
