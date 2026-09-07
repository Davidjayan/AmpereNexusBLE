# Ampere Nexus BLE

A standalone Android app that connects to an **Ampere Nexus** electric scooter over Bluetooth LE
and shows **charge, last trip distance, range, odometer, speed, riding mode, charging status** and
the **scooter clock** — with **automatic reconnection** whenever Bluetooth is on and the scooter is
in range. It works fully offline: unlike the official *Ampere Connect* app, it needs no login or
server, because the scooter's BLE link is plain GATT with no pairing or encryption.

Built by reverse-engineering the official app's BLE protocol (see `PROTOCOL.md`).

## Features
- Auto-connect (OS-level `autoConnect`) + reconnect on Bluetooth-on and after reboot.
- Live dashboard: charge %, last trip, range, odometer, speed, mode.
- Syncs the scooter's real-time clock to your phone time on connect.
- Raw BLE frame log for debugging / protocol verification.

## Build
See **CLAUDE.md** for the full build/install/handoff guide. Quick version:
```
export ANDROID_HOME=$HOME/Android/Sdk
gradle :app:assembleDebug        # Gradle 8.14, JDK 17, AGP 8.13, Kotlin 2.1
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
minSdk 26 · targetSdk 35.

## Status
Working; live decode offsets pending final validation against a real scooter frame — details and
next steps in `CLAUDE.md`.

> Reverse-engineering notes are for interoperability with a scooter the owner possesses.
> No affiliation with Ampere / Greaves Electric Mobility.
