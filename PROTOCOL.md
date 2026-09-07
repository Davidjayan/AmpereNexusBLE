# Ampere Connect — reverse-engineered BLE protocol (Nexus)

App analysed: `com.greavesmobility.app` (Greaves/Ampere "Ampere Connect"), React Native + Hermes,
BLE via `react-native-ble-plx` (Polidea). No login needed for BLE — the login/server issue only
blocks the cloud account screen; the scooter link itself is **plain GATT with no pairing, no
bonding, no crypto handshake** (`connectToDevice(id, {autoConnect:true, requestMTU:255})` →
`discoverAllServicesAndCharacteristics()` → subscribe). This is why an offline app can work.

## GATT layout — LIVE-VERIFIED on the real scooter (2026-09-07)
The app's config map implied one service holding both notify+write chars. **That is wrong.** On the
actual scooter there are **four vendor services, each holding exactly ONE characteristic whose UUID
equals the service UUID:**

| Service = Char UUID                       | Props     | Role                                   |
|-------------------------------------------|-----------|----------------------------------------|
| `20012202-1234-1234-1234-220201042403`    | **R/N**   | Telemetry notify (0150/0254 frames)    |
| `20032202-1234-1234-1234-220201042403`    | **W/N**   | Write — clock / cluster writes         |
| `20022202-1234-1234-1234-220201042403`    | R/W/N     | 2nd cluster (name/DOB writes)          |
| `20042202-1234-1234-1234-220201042403`    | R/W/N     | Unused so far                          |

Plus standard GAP/GATT/DeviceInfo (`1800`/`1801`/`180a`).

⚠️ **The chars are in their OWN services, not nested together.** Resolve them by scanning the whole
GATT tree by UUID (`findCharAnywhere`) — do NOT call `service.getCharacteristic()` under one assumed
service. The original code looked for notify `20012202` inside service `20032202`, failed with
"Notify characteristic not found", and that also blocked the clock sync (gated on the CCCD write).

(Primus/older uses Revos-style `0000a002` svc, notify `0000c306`, write `0000c304`.)

## Scan / identify
App does `startDeviceScan(null,null,cb)` (no UUID filter) and matches by advertised **name**:
Nexus → name contains `nex` (case-insensitive); Ampere/Tarang → `ampere`/`tarang`; Magnus → `mag`.
Scan window 10 s.

## Frames (notifications)
Char value is base64 → hex **uppercase** string. `b = hex.match(/../g)` = byte-pair array (0-based).
Frame type = first 2 bytes (`b[0]b[1]`, i.e. hex substring 0..4).

Frames carry **marker bytes between data fields** (0150: `51 52 55 57 58 59 FF`; 0254: `60..67`);
the offsets below land on the data bytes and are all **live-confirmed**.
Sample live frames: `0150 01502A51C63F000052B5185525570058005900FF`,
`0254 025400600061E703620063006400650066006701`.

### `0150` — dashboard (parseDecodedAmpereValues) — LIVE-VERIFIED
- `battery_perc` = int(b[2])                     ← **charge %**              (0x2A = 42%)
- `odo`          = int(LE b[4..7]) / 10  (km)     ← total odometer            (1632.6)
- `trip_two`     = int(LE b[9..10]) / 10 (km)     ← **scooter TRIP METER (Trip B)** (632.5)
  - ⚠️ This is the resettable trip meter, **not** a single most-recent-ride distance. The app
    labels the tile "LAST TRIP" but the value is byte-faithful to the official app. True per-ride
    distances are derived by our own `TripRecorder` → SQLite, since the scooter sends no ride history.
- `dte`          = int(b[12])                     ← distance-to-empty / range (km)  (37)
- `speed`        = int(b[14]) (km/h)
- `top_speed`    = int(b[16]) (km/h)
- `avg_speed`    = int(b[18]) (km/h)

### `0254` — status — LIVE-VERIFIED
- `mode` = b[2]: 1=ECO, 2=City, 3=Park, else Power
- `limphome` = b[4]
- `time_to_charge` = int(LE b[6..7]) / 10 (hrs)
  - ⚠️ **raw 999 (0x03E7) = "not plugged in / not charging" sentinel.** Only treat raw 1..998 as
    actually charging, else the UI shows a false "⚡ 99.9 h to full".
- `thermal60`=b[9], `thermal70`=b[11]
- `charge_complete`=b[13], `reverse`=b[15], `ready`=b[17], `side_stand`=b[19]
- `day_night` = nibble0 of b[19], `service_dueAlert` = nibble1 of b[19]

### `0147` — call/live-location; `0551` — TPMS (front/rear tyre id+value). Not needed for core UI.

## Writes (hex → bytes → base64 → write char)
Command families (Nexus, header echoed in response):
- **Set clock / cluster tick** (`writeTimetoCluster`, nexus) — written to char `20032202`:
  `0102` + `<epoch, 4 bytes LITTLE-ENDIAN, hex>` + `40FF4100000000420000000043FF02FF…4D034E`
  + <missedCalls `00`> + `45`. Two things that MUST be right (both live-verified 2026-09-07):
  1. **Endianness — little-endian.** The official app's `getTimeHexNXGT()` = `floor(Date.now()/1000)`
     then `.toString(16)` **byte-swapped to little-endian** (its char-index reassembly order is
     6,7,4,5,2,3,0,1). The scooter reads the 4 time bytes little-endian (same as its LE telemetry).
     Sending big-endian `toString(16)` makes the fast low byte land in the scooter's high byte, so
     the clock **jumps by hours on each power cycle** (observed 16:57→1:59am). Emit LE (`epochHexLE`).
  2. **Timezone — send RAW UTC, no shift.** The cluster firmware adds **India +5:30 itself** before
     display (Ampere is India-only). Send plain `System.currentTimeMillis()/1000`; the scooter adds
     5:30 and shows correct IST. (A local-shifted epoch showed +5:30 too far ahead: 21:47→03:17.)
  - Net: send LE bytes of raw UTC epoch → scooter reconstructs UTC, adds 5:30, displays local IST.
    Verified: phone 21:49 → scooter 21:49. Value is stable and persists across power cycles.
  Primus clock: `659B65000000` + `getHexTime()` + `000000`, `getHexTime()`=`HHmm00` (hex of decimal H/M).
- VIN read `01102E0110…`, profile name `01122E0112…`, family DOB `01132E0113…`, cricket `01142F0114…`,
  song/album framed with `084A00004B00004C`. These push text to the scooter's cluster display.

Telemetry (`0150`/`0254`) is **pushed automatically by the scooter once you subscribe** — no request
write required to read charge/trip/range.

## Turn-by-turn navigation (ST vs EX) — feasibility analysis (UPDATED: frame builder decompiled)
- The app chooses the nav encoder by **model family only** (`['nexus','magnus','magnus_grand']`
  → `getDirectionNXGT`); **no ST-vs-EX branch**. Direction codes (`getDirectionNXGT`): `01`=U-turn,
  `02`=slight-left, `03`=slight-right, `04`=left, `05`=right, `06/07`=roundabout; plus
  `distanceToNextAdvise` and road name.
- **What actually goes over BLE — the exact cluster-frame TLV tags** (from the frame builder, the same
  `0102` family as the clock write): `40/41`=clock, `42`=distance-to-next-turn (4-byte LE), `43`=road
  name (ASCII), `44`=**caller name**, `4C`=volume, `4B`=album, `46`=song action, `4D`=call status,
  `4E`=missed calls, `49/4F`=GPS status, `45`=terminator.
- ⚠️ **The turn-direction code is NOT in the BLE frame.** `getDirectionNXGT`'s result is assembled into
  the nav object used for the **WiFi/Mappls map render on the ST touchscreen**, not written to any BLE
  TLV. Over BLE the scooter receives only *distance number* + *road-name text* — never a turn arrow.
  (The `44` tag that earlier looked like "direction" is caller name.)
- The rich ST map/TBT is served to the ST smart cluster over **WiFi**: phone hotspot →
  `getHotspotCredentials` → local **lighttpd + WebSocket** (`startServer`, `addWebSocketHandler`,
  `getNavDevice`, `127.0.0.1`) → Mappls SDK draws the map; the cluster sends taps back
  (`mapViewControl`, `isLiveLocationPressed`).
- **Conclusion (firmed up):** a turn-by-turn **arrow on the EX cannot be done over BLE at all** — it's
  not a firmware-unlock question, the app simply never transmits the direction over BLE. The EX has no
  WiFi/map engine either, so the ST map is out too. The realistic solution for an EX owner is
  **phone-screen navigation in the app** (independent of the scooter). Sending road-name/distance text
  to the EX cluster *might* surface something if the EX has a nav text field, but that's marginal and
  not the arrow experience.
