# AirPods AACP v24 Probe

This Android probe is the next step after the v23 run.

It relies on the existing LibrePods Xposed module already being active in `com.android.bluetooth`. It does **not** install, replace, or modify the Xposed module.

## Why v24 exists

v22 successfully captured the clean current-session AACP `0x0053` vector on the v20 winning path:

```text
CCCD 0x0022 only -> current-session 0x0053 -> 0x0055
```

v22 then sent a full-payload AACP `0x0054` no-op echo. v23 sent the next plausible body-only AACP `0x0054` no-op shape. Both were silent:

- no direct post-`0x0054` AACP `0x0055`, `0x0053`, or `0x0052`,
- no ATT `0x002A` change,
- no profile refresh after the benign notification refresh request.

That makes the next safest hypothesis: the actual writable no-op path may be the ATT characteristic itself, handle `0x002A`, rather than AACP `0x0054`.

## What v24 does

For up to two guarded attempts, v24:

1. Runs the normal AACP init:
   - handshake,
   - AACP `0x004D` feature flags `D7`,
   - AACP `0x000F` notification request `FF FF FF FF`.
2. Opens ATT PSM `31`.
3. Reads ATT `0x0021`, `0x0024`, and `0x002A`.
4. Enables only the v20/v22 winning CCCD:
   - ATT handle `0x0022 = 01 00`.
5. Drains the AACP stream and logs the usual `0x0053` / `0x0055` context if present.
6. Performs the v20-style post-CCCD reads of `0x0021`, `0x0024`, and `0x002A`.
7. Requires the pre-CCCD and post-CCCD ATT `0x002A` values to be byte-for-byte identical before writing anything.
8. Sends exactly one setter-like operation:

```text
ATT Write Request to handle 0x002A with the exact current 0x002A bytes just read back
```

9. Watches for:
   - the ATT write response (`0x13` success or matching ATT error),
   - direct post-write AACP `0x0055`, `0x0053`, or `0x0052`,
   - post-write ATT `0x002A` read-back changes.

v24 does **not** send AACP `0x0052`, `0x0053`, `0x0054`, or `0x0055` candidates.

If ATT `0x002A` cannot be read, or if it changes between the guarded pre/post-CCCD reads, v24 aborts without sending the ATT write.

## Success signals

The strongest signal is:

```text
ATT write to 0x002A accepted -> direct post-write AACP 0x0055 appears
```

Useful secondary signals:

- ATT write accepted, but no AACP response;
- ATT write rejected with a specific ATT error;
- ATT `0x002A` read-back stays identical after the exact-current write;
- ATT `0x002A` read-back changes after the exact-current write, which would be surprising and important.

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
4. Launch **AACP v24 Probe**.
5. Enter or confirm the AirPods MAC address.
6. Tap **Run v24 probe**.
7. Use **Copy log** when the probe finishes.

## Notes

- This is a reverse-engineering probe. It may fail on firmware or Android Bluetooth stack variants.
- The app uses reflection to call hidden `BluetoothDevice.createL2capSocket(int)` / `createInsecureL2capSocket(int)` because the previous probes used those exact paths.
- The only setter-like operation sent by v24 is one exact-current ATT `0x002A` write-back, guarded by byte-for-byte stable reads.
