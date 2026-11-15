#!/usr/bin/env bash
# Puts a two-ride Google Maps Timeline.json on a connected phone or emulator so the real import
# can be tried: open Import & Backup > "Choose Timeline file…" and pick sample-timeline.json
# from Downloads. The app has no built-in sample rides; this is the replacement.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
adb push "$here/sample-timeline.json" /sdcard/Download/sample-timeline.json
echo "Pushed. In the app: Import & Backup > Choose Timeline file… > Downloads > sample-timeline.json"
echo "Expect two rides: 6 from 86 St to 14 St-Union Sq, then A from 125 St to 59 St-Columbus Circle."
