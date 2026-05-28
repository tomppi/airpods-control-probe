# AirPods AACP v20 Probe

This Android probe is a follow-up to v20.

It relies on the existing LibrePods Xposed module already being active in `com.android.bluetooth`. It does **not** install, replace, or modify the Xposed module.

## Why v20 exists

v20 showed that repeatedly sending AACP `0x004D` feature flags by itself only produced the normal `0x0000`, `0x002B`, and `0x002E` packets. It did **not** produce AACP `0x0053` or `0x0055`.

That means the missing gate is probably one of these:

- the AACP `0x0F` notification-request mask,
- opening the ATT L2CAP socket,
- reading ATT `0x002A`, or
- enabling ATT CCCDs `0x0022` / `0x002B`.

## What v20 tests

v20 is a capture/attribution-only matrix. It does not send AACP `0x0052`, `0x0053`, `0x0054`, or `0x0055` setter/echo candidates.

The matrix includes:

1. AACP default init with no ATT socket.
2. AACP default init plus ATT open only.
3. AACP default init plus ATT `0x002A` read.
4. AACP default init plus CCCD `0x0022` only.
5. AACP default init plus CCCD `0x002B` only.
6. AACP default init plus `0x0022` then `0x002B`.
7. AACP default init plus `0x002B` then `0x0022`.
8. AACP `0x004D/D7` without `0x0F`, then CCCD pair.
9. AACP `0x0F` masks `00000000`, `01000000`, `02000000`, and `FFFF0000`, each with the CCCD pair.

For every variant it logs:

- all drained AACP packets,
- command histograms,
- whether `0x0053`, `0x0055`, `0x0052`, or `0x0017` appeared,
- detailed payload decode for the first interesting packet.

## Build locally

This repo intentionally does not include a Gradle wrapper jar. Use an installed Gradle plus Android SDK:

```bash
gradle :app:assembleDebug
```

The APK will be at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Build on GitHub Actions

The included workflow installs Java 17, Android SDK components, and Gradle, then builds the debug APK and uploads it as an artifact.

## Running

1. Pair the AirPods with the Android device.
2. Make sure the LibrePods Xposed module is active in `com.android.bluetooth`.
3. Install the debug APK.
4. Launch **AACP v20 Probe**.
5. Enter or confirm the AirPods MAC address.
6. Tap **Run v20 probe**.
7. Use **Copy log** when the probe finishes.

## Notes

- This is a reverse-engineering probe. It may fail on firmware or Android Bluetooth stack variants.
- The app uses reflection to call hidden `BluetoothDevice.createL2capSocket(int)` / `createInsecureL2capSocket(int)` because the previous probes used those exact paths.
- The app performs ATT CCCD writes to `0x0022` and/or `0x002B` in some variants, but does not write a new value to ATT `0x002A` and does not send AACP setter/echo candidates.
