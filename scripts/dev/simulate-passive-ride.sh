#!/usr/bin/env bash
# Drives an EMULATOR's GPS from 14 St-Union Sq to 86 St (Lexington) so the real geofence path
# records a passive ride. Needs: a debug build installed, passive capture turned on with location
# set to "Allow all the time", and the emulator (not a phone: `adb emu geo` is emulator-only).
# The ride is recorded about 15 minutes after the last station event; open the app to nudge it.
set -euo pipefail
steps=${STEPS:-12}            # points between the two stations (12 x 12 s is about 2.6 min; rides under 2 min are ignored)
pause=${PAUSE_SECONDS:-12}     # seconds between points
from_lat=40.735736; from_lng=-73.990568   # 14 St-Union Sq
to_lat=40.779492;   to_lng=-73.955589     # 86 St (Lexington)
for i in $(seq 0 "$steps"); do
  lat=$(awk -v a="$from_lat" -v b="$to_lat" -v i="$i" -v n="$steps" 'BEGIN{printf "%.6f", a+(b-a)*i/n}')
  lng=$(awk -v a="$from_lng" -v b="$to_lng" -v i="$i" -v n="$steps" 'BEGIN{printf "%.6f", a+(b-a)*i/n}')
  adb emu geo fix "$lng" "$lat"      # note: longitude first
  echo "fix $i/$steps: $lat, $lng"
  sleep "$pause"
done
echo "Done. Watch: adb logcat -s StraphangerGeofence:I"
