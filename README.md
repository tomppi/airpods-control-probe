# AirPods Control Probe

A small diagnostic Android app for testing AirPods advanced-control channels on Android.

This is **not** LibrePods and does not replace LibrePods. It is a separate probe app designed to answer one question:

> Which AirPods control channels and safe read payloads actually work on this phone + AirPods firmware?

## Important

This app is designed to be used with the **existing LibrePods Xposed/Vector/LSPosed module already enabled** for `com.android.bluetooth`.

The probe app itself is not an Xposed module. The existing LibrePods module should still provide the Bluetooth stack hooks, including:

- VendorID/DID spoofing
- L2CAP channel-mode workaround

The app then tries to open and test the AirPods control sockets from userland.

## What it tests

The app tests these paths:

- AACP/main control channel PSM `4097` — connect-only probe
- ATT-like advanced-settings channel PSM `31`
- Safe ATT read probes:
  - handle `0x0018` — transparency/custom transparency config candidate
  - handle `0x001B` — loud sound reduction candidate
  - handle `0x002A` — hearing aid config candidate

It tries several socket constructors/strategies:

- hidden `createL2capSocket(psm)`
- hidden `createInsecureL2capSocket(psm)`
- public `createL2capChannel(psm)`
- public `createInsecureL2capChannel(psm)`

For ATT, it checks whether the AirPods respond with an ATT read response (`0x0B`) or error response (`0x01`). A timeout means the channel is not actually usable.

## Why this exists

In the tested Samsung setup, LibrePods successfully spoofed the phone DID VendorID to Apple `0x004C`, and the L2CAP hook fired, but the advanced ATT path still failed. This app isolates the ATT/AACP channel behavior from the rest of LibrePods UI/state logic.

## Build with GitHub Actions

1. Create a new GitHub repo.
2. Upload this repo's files.
3. Go to **Actions** → **Build AirPods Control Probe** → **Run workflow**.
4. Download the APK artifact.

## Usage

1. Keep the LibrePods Xposed module enabled for Bluetooth.
2. Reboot after changing Xposed scope.
3. Pair/connect your AirPods normally.
4. Open this app.
5. Select the AirPods device.
6. Run the probe.
7. Copy/share the log.

## Safety

The default probe only performs connection tests and ATT read requests. Avoid raw write payloads unless you know exactly what the bytes do.

## License

GPL-3.0-or-later. This project is intended to interoperate with LibrePods and to keep derivative diagnostic work compatible with LibrePods' license.

## v2 workflow note

This repo uses `android-actions/setup-android@v3` before calling `sdkmanager`. This is required on GitHub-hosted runners because `sdkmanager` may not be on PATH by default.


## v5 note

This version adds an important test: it keeps the working AACP PSM 4097 socket open while trying to connect ATT PSM 31. This distinguishes "PSM 31 is globally unreachable" from "PSM 31 only works after/while the AACP session is open".


## v5 update

Adds a LibrePods-style AACP init probe: the app opens AACP PSM 4097, sends the known startup packet sequence (handshake, set feature flags, request notifications), keeps AACP open, and then retries ATT PSM 31. This helps determine whether ATT 31 requires an AACP init sequence before it becomes reachable.

## v6 update

Adds UUID/SDP-resolved socket tests for the two AirPods custom BR/EDR UUIDs observed in dumps:

- `74ec2172-0bad-4d01-8f77-997b2be0722a`
- `4715650b-5e9d-4ac2-b898-a4fc0aa5df78`

The app now logs `BluetoothDevice.getUuids()` and tries RFCOMM and hidden `createSocket(..., ParcelUuid)` strategies so we can check whether Android can resolve a working channel by UUID instead of hardcoding PSM `31`.
