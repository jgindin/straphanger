# basemap-import

Generates `shared/src/commonMain/composeResources/files/nyc_basemap.json`, the map of New York City the
Map tab draws under the subway lines: land, parks, highways and major streets. The data is bundled with
the app; drawing it makes no network request and needs no API key.

## Inputs

Downloaded inputs go in `tools/basemap-import/input/`, which is git-ignored. Only the generated JSON is
committed. All three are NYC Open Data.

| File | Dataset | Downloaded |
| --- | --- | --- |
| `input/borough_boundaries.geojson` | "Borough Boundaries" (clipped to shoreline), Department of City Planning, dataset `gthc-hcne` | 2026-11-15 |
| `input/parks_properties.geojson` | "Parks Properties", NYC Department of Parks and Recreation, dataset `enfh-gkve` | 2026-11-15 |
| `input/major_roads.geojson` | "Centerline" (NYC Street Centerline, CSCL), Office of Technology and Innovation, dataset `inkn-q76z`, filtered on the server to highways, bridges and the major streets | 2026-11-15 |

SHA-256 of the inputs used for the committed file:

- `borough_boundaries.geojson`: `32a75ef2175193c7be7b2d164231d61ff4206f8439b5d0a642d2caf518d50005`
- `parks_properties.geojson`: `cf4951b7b75de86ae7cdfd23feb3f948920d2d1161e295f55c97b524842e53ba`
- `major_roads.geojson`: `b119abf9dce7880facab3c87a9d72c9acf70c6f70a92c501c6b3fba106348c6a`

The datasets are updated from time to time, so a new download will usually differ.

## Terms and attribution

- NYC Open Data is published under the City's Open Data Law (NYC Administrative Code, Title 23, Chapter 5),
  which says public data sets are made available without any registration requirement, license
  requirement "or restrictions on their use" (section 23-502(d)). The NYC Open Data Terms of Use
  (https://opendata.cityofnewyork.us/overview/#termsofuse) provide the data as is, for informational
  purposes, with no warranty of accuracy or completeness. The same subdivision lets the City require an
  application using a data set to identify its source and version, so the app names each data set and
  its download date.
- No OpenStreetMap data is used, so no ODbL attribution or share-alike obligation applies.

Attribution text shown in the app (About screen, `basemapAttributionText` in `AboutScreen.kt`):

> Map of New York City from NYC Open Data: Borough Boundaries (Department of City Planning), Parks
> Properties (NYC Parks) and the Street Centerline (Office of Technology and Innovation), downloaded
> <sourceDate>. Provided as is, without warranty.

## Regenerating

```sh
mkdir -p tools/basemap-import/input
curl -L -o tools/basemap-import/input/borough_boundaries.geojson \
  'https://data.cityofnewyork.us/api/geospatial/gthc-hcne?method=export&format=GeoJSON'
curl -L -o tools/basemap-import/input/parks_properties.geojson \
  'https://data.cityofnewyork.us/api/geospatial/enfh-gkve?method=export&format=GeoJSON'
curl -L -G -o tools/basemap-import/input/major_roads.geojson \
  'https://data.cityofnewyork.us/resource/inkn-q76z.geojson' \
  --data-urlencode "\$where=rw_type in (2, 3) OR (rw_type = 1 AND carto_display_level in ('10', '20'))" \
  --data-urlencode '$select=the_geom,rw_type,carto_display_level,physicalid,objectid' \
  --data-urlencode '$order=objectid' --data-urlencode '$limit=100000'
./gradlew :tools:basemap-import:run --args="--boroughs tools/basemap-import/input/borough_boundaries.geojson \
  --parks tools/basemap-import/input/parks_properties.geojson \
  --roads tools/basemap-import/input/major_roads.geojson \
  --out shared/src/commonMain/composeResources/files/nyc_basemap.json \
  --source-date YYYY-MM-DD"
./gradlew :tools:basemap-import:test :shared:testAndroidHostTest
```

Update the dates and checksums above. The road query returns about 14,400 pieces; if it returns exactly
100,000 the limit was hit and must be raised. Running the tool twice on the same inputs gives a
byte-identical file.

## Generator rules

- **Coordinates** are rounded to 1e-5 degree (about 1.1 m north-south, 0.85 m east-west) and stored as
  integers. Each ring or line is delta-encoded: the first point absolute, then each point as the
  difference from the one before.
- **Simplification.** Douglas-Peucker with a 2 m tolerance, measured in metres on a local flat projection
  at 40.7 degrees N. The Map's largest zoom is 5x (`MAX_ZOOM` in `SubwayMapScreen.kt`); on a 1080 x 2160
  phone one screen pixel is then about 5 m north-south and 8 m east-west, so the error is under half a
  pixel. A ring is split at the point farthest from its start and the two halves simplified separately.
- **Land.** Every polygon of every borough, sorted by borough code. Outer rings run anticlockwise and
  holes clockwise, so the app can fill all rings with the non-zero rule. Rings under 400 m² after
  simplification (rocks, specks) are dropped.
- **Parks.** Every Parks property (the `minParkAcres` rule is 0), sorted by `gispropnum`, same rings rules
  as land.
- **Roads.** From the Street Centerline's `rw_type` and `carto_display_level`: highways (`rw_type` 2) and
  bridges at display level 10 are `highways`; streets (`rw_type` 1) at levels 10 and 20 and bridges at
  20 and 30 are `arterials`. Tunnels, ramps, paths and minor streets are left out. Pieces are visited in
  `objectid` order and joined end to end wherever exactly two pieces of the same class meet, then
  simplified.
- **Extent.** Nothing is clipped. The data covers the five boroughs, which reach slightly beyond the
  map's projection box in `MapGeometry.kt` (eastern Queens to -73.70, Tottenville to 40.496, the north
  Bronx to 40.918); the app projects the basemap without clamping, so it keeps its true shape there.

## Output (2026-11-15 download)

5 boroughs (105 land rings), 2,061 parks (3,277 rings), 741 highway lines and 674 arterial lines;
286,287 input points simplified to 67,340; 501 KB.
