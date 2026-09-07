# AmpereNexusBLE — agent handoff / project memory

A standalone Android app (Kotlin, native BLE) that talks to an **Ampere Nexus** electric
scooter over Bluetooth LE — independent of the official "Ampere Connect" app and its broken
login/cloud backend. Built by reverse-engineering the official app's BLE protocol.

**Read this file first when resuming. It is the single source of truth for where we left off.**

## Goal (verbatim from the user)
Create an Android app that connects to the Ampere Nexus scooter over Bluetooth, displays charge,
last trip distance, etc., shows the scooter's current time, and **auto-connects whenever
Bluetooth is on and both devices are in range**. The official app "pauses at login" due to a
server issue — but the BLE link needs no login, so this app works offline.

## Current state (works / pending)
- ✅ Reverse-engineered the official app protocol → see `PROTOCOL.md`.
- ✅ App builds, installs, runs. UI: charge %, last trip, range, odometer, speed, mode,
  live scooter clock, and a raw-BLE-frame log. Dark theme, edge-to-edge insets handled.
- ✅ Auto-connect: OS-level `autoConnect=true` + Bluetooth-state receiver + boot receiver +
  foreground service. The target scooter MAC is remembered in prefs.
- ✅ Clock sync: on connect the app writes phone time to the scooter RTC (`0102`+epoch frame).
- ✅ **Trip history in SQLite** (`nexus_trips.db`, framework `SQLiteOpenHelper`, no external deps).
  Trips are derived from the live feed — the scooter does NOT send ride history over BLE (the
  official app got that from its cloud). `TripRecorder`: start on first `speed>0`, accumulate
  odo/battery/max-speed, finalize on 2-min idle / side-stand / disconnect; trips <50 m discarded.
  Verified end-to-end (insert → query → render). "View trip history" button → `TripHistoryActivity`.
- ✅ **LIVE-VALIDATED (2026-09-07)** against the real scooter. Confirmed frames:
  `RX 0150 01502A51C63F000052B5185525570058005900FF` → battery 42%, odo 1632.6 km,
  trip_two 632.5 km, range 37, speed 0. `RX 0254 025400600061E703620063006400650066006701`
  → mode Power, side_stand 1, time_to_charge raw 0x03E7=999 (the "not charging" sentinel).
  The `0150`/`0254` offsets from the bytecode are correct — they land exactly on the data bytes
  between the frame's marker bytes (0150 markers 51/52/55/57/58/59/FF; 0254 markers 60..67).
- ✅ **Real GATT layout discovered live** (this was the big bug). Each service holds ONE
  characteristic whose UUID == the service UUID; they are NOT nested under one service:
  `20012202 [R/N]` = telemetry notify · `20032202 [W/N]` = write (clock) ·
  `20022202 [R/W/N]` · `20042202 [R/W/N]`. The old code looked for notify char `20012202`
  *inside* service `20032202` → "Notify characteristic not found" → nothing worked, and the
  clock never synced (sync is gated on the CCCD-write callback). Fixed with `findCharAnywhere()`
  / `findCharByProperty()` in `NexusBleService.kt` — search the whole tree by UUID then by property.
- ✅ **Scooter clock fix:** the cluster RTC has no timezone; it renders the raw epoch as UTC.
  The official app (and our old code) sent raw UTC epoch → scooter read 5:30 behind (IST).
  `NexusProtocol.localClockSeconds()` now shifts by the local UTC offset so the cluster shows
  correct local wall-clock. Verified: old→15:59, new→21:29 for a 21:29 phone.
- ✅ **Charging sentinel:** `time_to_charge` raw 999 (0x03E7) means "not plugged in"; decode now
  treats only 1..998 as actually charging (killed the false "⚡ 99.9 h to full").
- ℹ️ **"LAST TRIP" tile = `trip_two`** = the scooter's resettable trip meter (Trip B), byte-faithful
  to the official app — NOT a single most-recent-ride distance. True per-ride distances are what the
  app's own `TripRecorder`/SQLite derives. 632.5 km here is the accumulated trip meter, not a bug.

## The scooter (this user's unit)
- BLE name: `NEX_Bca22`  ·  MAC: `48:23:35:99:CA:22`  (name match keyword: contains "nex")
- These are user-specific. Other Nexus units differ by MAC/suffix; the name-scan handles that.
  The MAC is seeded into app prefs on this device but a fresh install re-discovers by name scan.

## Protocol (summary — full detail in PROTOCOL.md)
Plain GATT. **No pairing, no bonding, no crypto handshake.** Connect → discover → subscribe.
- **Real layout (live-verified):** four vendor services, each with ONE char of the same UUID:
  - `20012202-…` **[R/N]** — notify char (scooter pushes 0150/0254 frames here after subscribe)
  - `20032202-…` **[W/N]** — write char (clock / cluster writes)
  - `20022202-…` **[R/W/N]** — 2nd cluster (name/DOB writes; not needed for core features)
  - `20042202-…` **[R/W/N]** — unused so far
  The chars are in their OWN services, not nested — always resolve by searching the whole tree
  (`findCharAnywhere`), never `service.getCharacteristic()` under one assumed service.
- Notification value = raw bytes → uppercase hex → `b = split into byte pairs`. Frame id = `b[0]b[1]`.
  - `0150` dashboard: charge%=`b[2]`; odo=LE(`b[4..7]`)/10; **last trip=LE(`b[9..10]`)/10**;
    range=`b[12]`; speed=`b[14]`; top_speed=`b[16]`; avg_speed=`b[18]`.
  - `0254` status: mode=`b[2]`(1 ECO,2 City,3 Park,else Power); time_to_charge=LE(`b[6..7]`)/10;
    charge_complete=`b[13]`; reverse=`b[15]`; ready=`b[17]`; side_stand=`b[19]`.
  - `0147` call/live-loc, `0551` TPMS — ignored by this app.
- Set scooter clock: write (to char `20032202`) hex `0102` + `localEpoch.toString(16)` + fixed
  cluster block + missedCalls(`00`) + `45`, where `localEpoch = (nowMs + tzOffsetMs)/1000` — the
  cluster RTC has no timezone and renders the number as UTC, so we pre-shift to local wall-clock.
  (The official app sends raw UTC here, which is why its clock reads 5:30 behind in IST.)
- Older Primus/Revos scooters use service `0000a002` / notify `0000c306` / write `0000c304`
  (constants present in `NexusProtocol` for completeness).

## Architecture
- `NexusProtocol.kt` — UUIDs, hex helpers, `decode()` (the 0150/0254 parser), `buildSetTimeCommand()`.
- `NexusRepository.kt` — singleton state + listener bridge between service and UI.
- `NexusBleService.kt` — foreground service: scan (name contains nex/ampere) → connect
  (`autoConnect`, MTU 255) → discover → subscribe CCCD → decode → clock-sync; reconnect on BT
  on / disconnect; actions RESCAN / SYNC_TIME / FORGET.
- `MainActivity.kt` — permissions, starts service, renders state, 1s clock ticker.
- `BootReceiver.kt` — restarts service after reboot / app update.
- `TripDb.kt` — `SQLiteOpenHelper` for `nexus_trips.db`, table `trips`, `TripRecord`, insert/all/totals.
- `TripRecorder.kt` — turns the telemetry stream into discrete saved trips (see heuristic above).
  Fed from `handleValue()`; finalized on GATT disconnect.
- `TripHistoryActivity.kt` + `activity_trips.xml`/`item_trip.xml` — trip list + totals screen.

## Build & install (no Android Studio needed)
Requires Android SDK + a Gradle 8.14 distribution + JDK 17. On the original dev machine:
```
export ANDROID_HOME=$HOME/Android/Sdk
GRADLE=path/to/gradle-8.14/bin/gradle   # or use ./gradlew if a wrapper is added
$GRADLE :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.ampere.nexusble/.MainActivity
# grant runtime perms if needed:
adb shell pm grant com.ampere.nexusble android.permission.BLUETOOTH_SCAN
adb shell pm grant com.ampere.nexusble android.permission.BLUETOOTH_CONNECT
adb shell pm grant com.ampere.nexusble android.permission.POST_NOTIFICATIONS
# watch BLE: adb logcat -s NexusBLE:D
```
Toolchain: AGP 8.13.0, Kotlin 2.1.0, compileSdk/targetSdk 35, minSdk 26.

## How the RE was done (to reproduce / go deeper)
Official app = `com.greavesmobility.app`, React Native + **Hermes** bytecode, `react-native-ble-plx`.
1. `adb pull` the base.apk; `unzip` `assets/index.android.bundle` (Hermes, magic `c6 1f bc 03`, v98).
2. Decompile with `hermes-dec` (P1sec) → readable pseudo-JS; disassemble for exact byte offsets.
3. Key functions: `parseDecodedAmpereValues` (Nexus parser), `parseDecodedAmpereValuesPrimus`,
   `writeTimetoCluster`, `getTimeHexNXGT`, the UUID config map (`primus`/`nexus`/`nexusService2`),
   and the scan name-match (`['nexus','nex']`).

## Next steps
1. Validate `0150`/`0254` offsets against a live frame; fix any that are off.
2. (Optional) Add a device picker UI for scanning when no MAC is saved.
3. (Optional) Decode TPMS (`0551`) and expose tyre pressure.
4. (Optional) Add lock/unlock or other writes if the user wants control features (verify safety).
