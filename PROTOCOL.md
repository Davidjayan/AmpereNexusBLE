# Ampere Connect — reverse-engineered BLE protocol (Nexus)

App analysed: `com.greavesmobility.app` (Greaves/Ampere "Ampere Connect"), React Native + Hermes,
BLE via `react-native-ble-plx` (Polidea). No login needed for BLE — the login/server issue only
blocks the cloud account screen; the scooter link itself is **plain GATT with no pairing, no
bonding, no crypto handshake** (`connectToDevice(id, {autoConnect:true, requestMTU:255})` →
`discoverAllServicesAndCharacteristics()` → subscribe). This is why an offline app can work.

## GATT layout (per app config map: primus / nexus / nexusService2)
Nexus scooter uses two custom services:

| Role                 | Service UUID                             | Char UUID (notify/write)                 |
|----------------------|------------------------------------------|------------------------------------------|
| Telemetry (notify)   | `20032202-1234-1234-1234-220201042403`   | notify `20012202-1234-1234-1234-220201042403`, write `20032202-…` |
| Cluster writes (2nd) | `20022202-1234-1234-1234-220201042403`   | write/notify `20022202-1234-1234-1234-220201042403` |

(Primus/older uses Revos-style `0000a002` svc, notify `0000c306`, write `0000c304`.)

## Scan / identify
App does `startDeviceScan(null,null,cb)` (no UUID filter) and matches by advertised **name**:
Nexus → name contains `nex` (case-insensitive); Ampere/Tarang → `ampere`/`tarang`; Magnus → `mag`.
Scan window 10 s.

## Frames (notifications)
Char value is base64 → hex **uppercase** string. `b = hex.match(/../g)` = byte-pair array (0-based).
Frame type = first 2 bytes (`b[0]b[1]`, i.e. hex substring 0..4).

### `0150` — dashboard (parseDecodedAmpereValues)
- `battery_perc` = int(b[2])                     ← **charge %**
- `odo`          = int(LE b[4..7]) / 10  (km)     ← total odometer
- `trip_two`     = int(LE b[9..10]) / 10 (km)     ← **last / current trip distance**
- `dte`          = int(b[12])                     ← distance-to-empty / range (km)
- `speed`        = int(b[14]) (km/h)
- `top_speed`    = int(b[16]) (km/h)
- `avg_speed`    = int(b[18]) (km/h)

### `0254` — status
- `mode` = b[2]: 1=ECO, 2=City, 3=Park, else Power
- `limphome` = b[4]
- `time_to_charge` = int(LE b[6..7]) / 10 (hrs)
- `thermal60`=b[9], `thermal70`=b[11]
- `charge_complete`=b[13], `reverse`=b[15], `ready`=b[17], `side_stand`=b[19]
- `day_night` = nibble0 of b[19], `service_dueAlert` = nibble1 of b[19]

### `0147` — call/live-location; `0551` — TPMS (front/rear tyre id+value). Not needed for core UI.

## Writes (hex → bytes → base64 → write char)
Command families (Nexus, header echoed in response):
- **Set clock / cluster tick** (`writeTimetoCluster`, nexus): 
  `0102` + `getTimeHexNXGT()` + `40FF4100000000420000000043FF02FF…4D034E` + <missedCalls> + `45`
  where `getTimeHexNXGT()` = `Math.floor(Date.now()/1000).toString(16)` (Unix epoch seconds, hex).
  Primus clock: `659B65000000` + `getHexTime()` + `000000`, `getHexTime()`=`HHmm00` (hex of decimal H/M).
- VIN read `01102E0110…`, profile name `01122E0112…`, family DOB `01132E0113…`, cricket `01142F0114…`,
  song/album framed with `084A00004B00004C`. These push text to the scooter's cluster display.

Telemetry (`0150`/`0254`) is **pushed automatically by the scooter once you subscribe** — no request
write required to read charge/trip/range.

## Turn-by-turn navigation (ST vs EX) — feasibility analysis
- The app chooses the nav encoder by **model family only**: `['nexus','magnus','magnus_grand']`
  → `getDirectionNXGT`, else `getDirection`. **There is no ST-vs-EX branch** — both are "nexus",
  so the app builds the identical nav payload for either.
- Maneuver → direction code (`getDirectionNXGT`): `01`=right, `02`=left, `03`=slight-right/merge,
  `04`=slight-left, `05`=other; plus `leftDistance` (m to next turn). Stored as `currentTBTStep`.
- **BUT the rich ST map/TBT is rendered on the ST's smart touchscreen cluster over WiFi, not BLE:**
  the phone raises a **personal hotspot**, shares SSID/PWD with the cluster (`hotspotSSID/PWD`,
  `getHotspotCredentials`), and serves the map + TBT via a **local web server (lighttpd) + WebSocket**
  (`startServer`/`stopServer`, `addWebSocketHandler`, `getNavDevice`, `127.0.0.1`). Mappls SDK draws
  the map. The cluster sends taps back (`mapViewControl`, `isLiveLocationPressed` in the 0147/0150 RX).
- BLE only carries the lightweight cluster frame (time, missed calls, and TLV slots incl. a
  direction/distance field 0x44/0x49/0x4F in the `0102`-style write).
- **Conclusion:** the full ST navigation experience depends on the smart cluster's WiFi + graphical
  map engine, which the EX (non-touch basic display, BLE-only) does not have — so it can't be
  "unlocked" by software alone. The only EX possibility is a **minimal arrow+distance** IF the EX
  firmware/display renders the BLE direction field — unknown from the app, empirically testable by
  writing the direction byte to the EX cluster and observing the display. Phone-screen TBT (arrows on
  the phone) is always possible independent of the scooter.
