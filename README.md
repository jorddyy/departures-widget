# Departures

Android home-screen widgets for public transport in Switzerland, with live delays.

**Commute widget:** the next departures between your Home and Work stops, in the direction
you're probably going. Compact by default (4×1):

```
╭──────────────────────────────────────────╮
│ [7] Market → Station  nearby           ⇄ │
│ 07:54 +2  5 min                08:02 13 min│
╰──────────────────────────────────────────╯
```

- **Picks the direction for you.** Near Home it shows departures to Work, and near Work
  departures to Home. Anywhere else it goes by time of day: to Work before 13:00 and to Home
  after.
- **Only direct connections:** departures that reach your other stop without changing, on
  any line or mode.
- **⇄** flips the direction for two hours. Tapping the widget opens the app with more departures.
- **Resize** it to two rows or more for a detailed layout with the reason, a refresh button and
  the last update time.

**Station board:** the next departures at any stop you choose, on every line and in every
direction. You pick the stop when adding the widget, and you can add as many as you like, one
per stop. It shows 2 to 7 departures depending on its height. Tap it to refresh; to change the
stop, long-press it → settings (✎).

Your stops are stored only on your phone. Nothing about them is in this repository.

## Install

### Download the APK

1. On your phone, open this repository's **Releases** page → latest release → tap
   **departures.apk** under *Assets*. Android asks you to allow installs from your browser.
2. Open **Departures** and choose your **Home** and **Work** stops. Optionally allow approximate
   location (see *First run*).
3. Long-press the home screen → **Widgets** → **Departures**, then add **Commute** and/or
   **Station board**.

### Updating

The bottom of the app shows the installed version, e.g. *Version 1.0.12 (build 12, a1b2c3d)*.
When you open the app, at most every 6 hours, it checks the latest release. If a newer build
exists it shows **Update**: tap it, and the app downloads the APK, verifies its checksum and
hands it to Android, which asks you to confirm. The first time, Android asks you to allow
*Install unknown apps* for Departures. **Check for updates** forces a check.

### Build it yourself

Requirements: JDK 17+ and the Android SDK (Android Studio installs both).

```bash
./gradlew :app:assembleDebug         # APK in app/build/outputs/apk/debug/
./gradlew :app:installDebug          # with the phone connected and USB debugging on
```

Local builds have no update source.

### First run

The app asks for **approximate** location. This is optional; without it the commute direction
follows the time of day. To let the widget switch direction without opening the app, tap
**Allow all the time** on the card that follows (Android 10+). Precise location is never
requested.

For a calm **whole-minute countdown**, tap **Allow** on the *Countdown in minutes* card and turn
on *Alarms & reminders*. Without it the widgets show a seconds countdown.

Requires Android 8.0 (API 26) or newer.

### Samsung Galaxy (One UI) notes

- **Installing the APK:** One UI asks to allow your browser (or *My Files*) to install unknown
  apps. **Auto Blocker** (Settings → Security and privacy) blocks sideloading while it's on;
  turn it off for the install.
- **Battery:** One UI can put apps it thinks are unused to sleep, which stops the widgets from
  updating. If they go stale, set Settings → Apps → Departures → Battery to **Unrestricted**
  and check it isn't in Settings → Battery → Background usage limits → Sleeping apps.
- **Colours:** on Android 12+ the widgets follow your wallpaper palette and the launcher's
  corner radius.

## Setting up your own copy (signing)

Android only installs an update if it is signed with the same key as the installed app. The key
lives in two repository secrets, never in the code:

1. On any computer with Java, create a key once and keep it somewhere safe, e.g. a password
   manager:
   ```bash
   keytool -genkeypair -keystore signing.jks -storetype PKCS12 -alias app \
     -keyalg EC -groupname secp256r1 -validity 36500 -dname "CN=Departures"
   base64 -w0 signing.jks > signing.jks.b64     # macOS: base64 -i signing.jks -o signing.jks.b64
   ```
2. In GitHub: **Settings → Secrets and variables → Actions → New repository secret**:
   - `SIGNING_KEYSTORE_BASE64`: the contents of `signing.jks.b64`
   - `SIGNING_KEYSTORE_PASSWORD`: the password you chose

CI reads the key alias from the keystore. It only publishes releases signed with this key;
without the secrets it still builds and tests, but skips the release. The app checks the
repository it was built in for updates, so the repository must be public.

## How it works

### Data source

[transport.opendata.ch](https://transport.opendata.ch): free, no API key, built on the Swiss
national timetable with real-time data. The app uses:

- `/v1/locations?type=station`, to search stops by name
- `/v1/stationboard?id=<stop>`, for the next departures at a stop

For the commute widget it keeps only journeys whose pass list reaches your other stop, so
short runs that end earlier are filtered out. The `api-smoke.yml` workflow checks the API's
shape weekly.

### Battery

The design goal is that the app costs nothing while you aren't looking at it.

- **Whole minutes, redrawn on the minute.** The widgets show `5 min` and redraw at the exact
  moment that becomes `4 min`. They use a non-waking alarm: while the phone sleeps nothing runs,
  and the alarm fires as soon as the screen comes back on. It needs *Alarms & reminders*.
- **Without that permission** the widgets fall back to a seconds countdown drawn by the
  launcher itself, so the time shown is still never stale.
- **The network is only used when needed.** Data is refetched when it is older than 5 minutes,
  for another stop or direction, or running out of departures. Automatic fetches never happen
  while the screen is off, and they back off for a minute after a failure.
- A 30-minute WorkManager job is a safety net. It follows the same rules.
- Location comes from the platform `LocationManager`. The app reuses a fix the phone already
  has, never uses GPS, and has no Google Play Services dependency.

### Privacy

- Timetable requests carry only a stop id; stop searches carry only the text you type.
- Your stops and the last coarse location fix are stored only on the phone, are excluded from
  backups, and the location is ignored after 30 minutes.
- Update checks fetch a small `version.json` from GitHub, only when you open the app, at most
  every 6 hours.
- No accounts, analytics, ads or third-party SDKs.

### Direction logic (commute widget)

From `core/.../DirectionResolver.kt`, highest priority first:

1. A manual choice (⇄ in the widget, or the switch in the app), valid for 2 hours.
2. A location fix less than 30 minutes old near one of your stops. "Near" means within 1.5 km,
   or 40% of the distance between your stops if they're closer together.
3. Time of day: 03:00–12:59 → Work, otherwise → Home. If your location is known but far from
   both stops, the widget says "away".

### Code layout

```
core/   Pure Kotlin, JVM-tested: API parsing, direction choice, refresh policy, formatting, codecs
app/    Android: widgets (RemoteViews), WorkManager job, location, updater, Compose screens
```

## Tests

```bash
./gradlew :core:test                 # pure logic, no Android SDK needed
./gradlew :app:testDebugUnitTest     # Robolectric: storage, refresh rules, widget rendering
./gradlew :app:lintDebug
```

The tests use fictional stops ("Townsville"). CI (`.github/workflows/build.yml`) runs all three
and builds the APKs on every push.
