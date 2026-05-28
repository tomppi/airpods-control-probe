# AirPods AACP v27 Probe

This Android probe is the next step after the v26 run.

It relies on the existing LibrePods Xposed module already being active in `com.android.bluetooth`. It does **not** install, replace, or modify the Xposed module.

## Why v27 exists

v24, v25, and v26 showed that ATT handle `0x002A` accepts Write Requests at the ATT layer, but even a one-Q8-step semantic canary does not read back and does not produce direct AACP `0x0055` / `0x0053` / `0x0052` evidence.

The important v26 result was:

```text
ATT Write Request to 0x002A with Q8-step final-float canary -> ATT 0x13 success
post-canary 0x002A read-back -> unchanged / original
post-canary AACP 0x0055 / 0x0053 / 0x0052 -> none
restore-original write -> ATT 0x13 success and final read-back original
```

That makes a simple “too small / quantized away” explanation less likely for ATT `0x002A`. v27 therefore returns to AACP `0x0054`, but avoids the earlier no-op limitation: instead of echoing the current `0x0053` unchanged, it sends a reversible one-Q8-step canary in the captured `0x0053` vector and then restores the original vector.

## What v27 does

For up to two guarded attempts, v27:

1. Runs the normal AACP init:
   - handshake,
   - AACP `0x004D` feature flags `D7`,
   - AACP `0x000F` notification request `FF FF FF FF`.
2. Opens ATT PSM `31` for read-only context.
3. Reads ATT `0x0021`, `0x0024`, and `0x002A`.
4. Enables only the v20/v22 winning CCCD:
   - ATT handle `0x0022 = 01 00`.
5. Drains the AACP stream and captures the current-session `0x0053` vector plus any `0x0055` context.
6. Performs the v20-style post-CCCD ATT reads for context.
7. Selects the best current-session `0x0053` payload.
8. Requires the conservative observed `0x0053` shape:
   - payload length word equals `payloadLen - 2`,
   - prefix bytes `[2..5]` are `02 00 02 02`,
   - remaining bytes are complete finite float32-le values in `[0,1]`.
9. Builds a canary by changing **only** the final float32-le value in the `0x0053` payload by one Q8-sized step (`1/256`).
   - In the observed `0.5` case, this changes final bytes from `00 00 00 3F` to `00 00 01 3F` (`0.50390625`).
10. Sends one AACP `0x0054` frame using the **full captured-payload shape**:

```text
04 00 04 00 54 00 <captured 0x0053 payload with only final float changed>
```

11. Drains AACP and ATT, then sends one benign notification refresh request (`0x000F FF FF FF FF`) to see whether `0x0053` refreshes to the canary or original.
12. Immediately restores with one AACP `0x0054` full-payload frame containing the exact original captured `0x0053` payload.
13. Drains again and sends one post-restore refresh request to check whether `0x0053` refreshes back to original.

v27 does **not** send AACP `0x0052`, `0x0053`, or `0x0055` candidates. It only sends `0x0054` canary/restore frames and the same `0x000F` notification refresh request used during init.

If no current-session `0x0053` is captured, or the selected `0x0053` payload does not match the guarded shape, v27 aborts without sending the canary.

## Success signals

The strongest signal is either:

```text
direct post-canary first 0x0053 equals canary 0x0053 payload: true
```

or:

```text
refresh post-canary first 0x0053 equals canary 0x0053 payload: true
```

Then the important restore signal is:

```text
refresh post-restore first 0x0053 equals original 0x0053 payload: true
```

Other useful signals:

- post-canary AACP `0x0055`: `0x0054` may be accepted but status-coded; inspect the bytes;
- post-canary `0x0053` equals original: full-payload `0x0054` likely needs a different shape, a commit trigger, or a different selector;
- no post-canary `0x0055`/`0x0053` at all: full-payload canary is still silent, so body-only canary or another command becomes the next shape hypothesis;
- post-restore does not show original: stop testing and inspect the exact log before continuing.

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

The included workflow runs on GitHub-hosted `ubuntu-latest`, installs Java 17, Android SDK components, and Gradle, then builds the debug APK and uploads it as an artifact.

## Running

1. Pair the AirPods with the Android device.
2. Make sure the LibrePods Xposed module is active in `com.android.bluetooth`.
3. Install the debug APK.
4. Launch **AACP v27 Probe**.
5. Enter or confirm the AirPods MAC address.
6. Tap **Run v27 probe**.
7. Use **Copy log** when the probe finishes.

## Notes

- This is a reverse-engineering probe. It may fail on firmware or Android Bluetooth stack variants.
- The app uses reflection to call hidden `BluetoothDevice.createL2capSocket(int)` / `createInsecureL2capSocket(int)` because the previous probes used those exact paths.
- The only non-current value written by v27 is a Q8-step change to the final float32-le value of the captured AACP `0x0053` payload, sent via AACP `0x0054`, guarded by current-session capture and followed by an immediate exact-original `0x0054` restore.
