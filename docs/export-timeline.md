# Getting your Timeline file out of Google Maps

Straphanger imports rides from a file that Google Maps writes. Nothing is uploaded: you export the file on your phone and pick it in **gear > Settings > Your data > Import Google Maps Timeline**. The file is read on the device.

Google changes its menus from time to time. If a step below does not match what you see, search the Google Maps help for "export Timeline data".

## Android

1. Open **Google Maps** and tap your profile picture.
2. Open **Your Timeline**.
3. Tap the **⋯** (more) menu, then **Settings and privacy**, then **Export Timeline data**. You may need to confirm your screen lock.
4. Choose where to save it (for example **Downloads**). The file is called `Timeline.json`.
5. In Straphanger, open the gear, then **Settings > Your data > Import Google Maps Timeline**, and pick that file.

This works only if Timeline is turned on and saving to your device. If you do not see the export option, check **Your Timeline > Settings** first.

## iPhone

1. Open **Google Maps** and tap your profile picture.
2. Open **Your Timeline**, then the **⋯** menu and **Settings and privacy**.
3. Choose **Export Timeline data** and save the file to **Files**.
4. In Straphanger, open the gear, then **Settings > Your data > Import Google Maps Timeline**, and pick it.

## From Google Takeout (any device)

If your Timeline is stored in your Google account rather than on the phone, use [Google Takeout](https://takeout.google.com): select only **Location History (Timeline)**, export, unzip, and choose the `Semantic Location History` month files (`.json`). Straphanger reads them the same way.

## What gets imported

Only subway trips. Transfers within 15 minutes are merged into one ride, and a ride you already have is skipped, so importing the same file twice is safe. Rides imported this way show the stations and line Google recorded; check anything marked **needs review** on the Rides tab.

*These steps were written from memory of the Google Maps app and have not been re-checked against the current release.*
