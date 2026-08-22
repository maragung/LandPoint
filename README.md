# LandPoint

**Save Your Land. Know Your Location.**

A free, offline-first Android app for saving, managing, sharing and exporting the
GPS coordinates of land plots, fields, gardens and any other place worth
remembering.

---

## Install

1. Download `LandPoint-<version>-release.apk` from the
   [latest release](https://github.com/maragung/LandPoint/releases/latest), and copy
   it to your phone (USB, Bluetooth, Google Drive, email — whatever you prefer).
2. Open it with the phone's file manager.
3. Android will ask permission to install apps from this source — allow it for
   the file manager / browser you used. This is normal for any app that does not
   come from the Play Store.
4. Tap **Install**, then **Open**.

Requires Android 9 (Pie) or newer. It runs on any phone — no Google Play
Services needed.

On first use the app asks for **location permission**. Grant "While using the
app" so it can read GPS coordinates. Everything else is optional.

---

## What it does

**Capture** — one tap records latitude, longitude, accuracy, altitude and the
time. Optionally look up the street address, and attach photos from the camera
or gallery.

**Manage** — save each spot with a name, description and notes. Search, sort by
name, date or distance, and see how far each one is from where you are standing.

**Measure** — a plot is more than one point, so record its corners and get the
shape. Stand on each corner and let the GPS average a fix, tap them out on the
map, walk the boundary and let it record as you go, or type coordinates straight
off a survey letter. Corners can be reordered, corrected and deleted one at a
time — the order is the outline — and once three are in, the area and perimeter
are worked out and the plot's pin moves to its centroid.

**Map** — every saved location as a marker, with mapped plots drawn as filled
shapes. Maps fill the screen: no app bar, no bottom bar, just the ground and a
few floating controls — zoom in and out, jump to where you are standing, fit
everything back on screen, and change the map style. Tap a marker or a plot to
open the record; open a record's boundary full screen to see its numbered
corners, area and perimeter. Tiles you have already viewed stay cached, so a map
you loaded at home still works in a field with no signal.

**Four map styles**, chosen once and used by every map in the app:

| Style | What it shows | Source |
| --- | --- | --- |
| Street | Roads, names, buildings. The default, and the lightest on data. | OpenStreetMap |
| Satellite | The ground itself — roofs, trees, field edges — so a corner can be checked against something you can see. | Esri World Imagery (Esri, Maxar, Earthstar Geographics) |
| Terrain | Contours and hill shading, for sloping or terraced land. | OpenTopoMap (CC-BY-SA) |
| Offline map | A vector map file you imported yourself. The only style that needs no network at all. | your `.map` file |

Aerial imagery is a **visual reference, not evidence**: it is a photograph of one
day, it can be a year or more old, and a fence built since will not be in it.
Use it to recognise your land — never to decide where a boundary runs.

**Navigate** — hand off to Google Maps (or any installed maps app), or use the
built-in compass mode: an arrow that points at the plot with live distance and
bearing, which needs no internet at all.

**Share** — send coordinates as text through WhatsApp, SMS, email or anything
else in the Android share sheet.

**Export and back up**
- **JSON** — complete backup, restores everything exactly.
- **CSV** — opens in Excel, Google Sheets or LibreOffice.
- **PDF** — a formatted report, either for one location or for all of them.

**Import** — restore a JSON backup or bring in a CSV from elsewhere. Column
names are matched flexibly (`lat`/`latitude`, `lng`/`longitude`/`long`,
`title`/`name`), and you choose what happens to duplicates: skip them, replace
them, or keep both.

---

## Privacy

Everything stays on the phone.

- No account, no sign-up, no server.
- No analytics, no tracking, no ads.
- Nothing is uploaded, ever. Backups go only where you point them.
- The only permission the app requires is location. Camera and photo access are
  asked for only when you actually attach a photo, and files are read and written
  through Android's document picker, so no storage permission is needed at all.
- Internet access is used for one thing: downloading OpenStreetMap map tiles.
  Skip the map screen and the app never touches the network.

### Encryption at rest

The database — coordinates, names, addresses, notes — is encrypted with SQLCipher.
The key is 32 random bytes wrapped by an AES key that lives in the phone's
hardware keystore (StrongBox where the phone has one), so it is never written to
disk in a usable form and never leaves the device. Photos are not in the database;
they stay in the app's private storage, reachable only by the app or by root.

An existing unencrypted database is migrated on the first launch after updating.
The migration copies rather than converts, verifies the row counts through the new
key, and only then replaces the old file — a failure at any point leaves the
original untouched and the app carries on reading it.

A phone that cannot manage encryption at all — no SQLCipher build for its ABI, or
a keystore that refuses to produce a key — opens the database unencrypted rather
than refusing to start. Settings › Privacy and security says which of the two is
happening, and it is worth checking once.

**The trade-off, stated plainly:** a key locked to one phone's hardware cannot be
exported, so the database cannot be carried to a new phone. Android's
device-to-device transfer no longer copies it, because delivering a file the new
phone could never open would look like a successful transfer and would not be one.
Uninstalling deletes everything too.

**A backup archive is the only way off this phone. Make one before you change
phones, and before you uninstall.** Archives hold JSON and photo files rather than
the database, so they open on any device.

---

## Important

GPS coordinates recorded by this app are for **personal reference only**. They
are not a substitute for an official land certificate, a cadastral record, or a
survey performed by a licensed surveyor. Consumer phone GPS is typically accurate
to a few metres, and that margin matters when boundaries are in dispute. Use this
app to *find* your land again — not to *prove* where it ends.

---

## Building from source

The project builds with a standard Android toolchain: JDK 17, Android SDK 35,
Gradle 8.11.1.

```bash
source env.sh            # sets JAVA_HOME, ANDROID_HOME, PATH
gradle assembleDebug     # debug APK
gradle testDebugUnitTest # unit tests
gradle assembleRelease   # signed release APK
```

Release signing reads `keystore.properties` in the project root:

```properties
storeFile=landpoint-release.jks
storePassword=…
keyAlias=landpoint
keyPassword=…
```

Without that file the release build still runs; it just produces an unsigned APK.

### Build memory

`gradle.properties` is tuned for a machine with limited RAM: a 1 GB Gradle heap,
a 768 MB Kotlin daemon, serial GC, and single-threaded execution. A clean release
build takes a few minutes, but it will not exhaust memory and get killed.

If you build on a larger machine and want it faster, raise `org.gradle.jvmargs`
and set `org.gradle.parallel=true`. On anything with 8 GB or less, leave it alone —
parallel builds multiply peak memory, and once the machine starts swapping the
build gets *slower*, not faster.

Run `gradle --stop` between heavy builds to release the daemon heap.

> **Keep `landpoint-release.jks` and its password safe.** Android will only let a
> user update an installed app with a new APK signed by the *same* key. Lose the
> key and the only upgrade path is uninstall-and-reinstall, which wipes the
> saved locations.

### Architecture

Kotlin, Jetpack Compose and Material 3 throughout, MVVM layered
UI → ViewModel → Repository → Room.

| Layer | What is there |
|---|---|
| `data/model` | Room entities and the domain model |
| `data/db` | Database and DAO |
| `data/export` | JSON/CSV/PDF import and export |
| `location` | GPS and reverse geocoding via `LocationManager` |
| `ui/*` | One package per screen, each with its ViewModel |
| `util` | Distance, bearing, coordinate formatting, sharing |

Deliberate choices worth knowing:

- **osmdroid instead of the Google Maps SDK** — no API key to manage, no Play
  Services dependency, and it opens the door to the offline-tiles feature. The
  aerial and terrain styles are keyless tile servers for the same reason, fetched
  two tiles at a time with no bulk download and no prefetching: they are somebody
  else's bandwidth, given freely, and the app credits them on every map it draws
  them on.
- **`LocationManager` instead of the fused provider** — same reason: the app
  works on devices without Google services.
- **Hand-rolled DI (`AppContainer`)** — the graph is small enough that Hilt would
  cost more in build time and APK size than it returns.
- **Storage Access Framework for every file** — the reason the app never asks for
  a storage permission.
- **Geometry stored as GeoJSON in one column** — `geometryType`, `geometryJson`
  and `areaSqm` have been in the table since version 1, so boundaries arrived
  without a migration, and a ring is one text field rather than a second table
  and a join. `parcelNumber` is there on the same terms, still unused.

### Tests

```bash
gradle testDebugUnitTest
```

Covers the distance/bearing maths, the CSV reader and writer (quoting, CRLF,
alternative column names, malformed rows), and import duplicate handling for all
three strategies, including a full export-and-restore round trip against an
in-memory database.

---

## Roadmap

Planned, and already accounted for in the data model:

- GPX, KML and GeoJSON export
- Downloadable offline map areas
- GPS track recording
- QR codes for sharing a location
- Parcel numbering and grouping
