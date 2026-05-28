# AirPods AACP v23 Probe

This Android probe is the next step after the v22 run.

It relies on the existing LibrePods Xposed module already being active in `com.android.bluetooth`. It does **not** install, replace, or modify the Xposed module.

## Why v23 exists

v22 successfully captured the current-session AACP `0x0053` vector on the clean v20 winning path:

```text
CCCD 0x0022 only -> current-session 0x0053 -> 0x0055
```

The captured `0x0053` looked internally consistent:

```text
84 00 02 00 02 02 <32 little-endian float32 values, all 0.5>
```

v22 then sent a full-payload `0x0054` no-op echo:

```text
04 00 04 00 54 00 <captured 0x0053 payload including 84 00>
```

That produced no direct `0x0055`, `0x0053`, or `0x0052` response, and ATT `0x002A` did not change. The next safest hypothesis is that `0x0054` may want the `0x0053` body **without** the leading 2-byte reported length word.

## What v23 does

For up to two safe capture attempts, v23:

1. Runs the normal AACP init:
   - handshake,
   - AACP `0x004D` feature flags `D7`,
   - AACP `0x000F` notification request `FF FF FF FF`.
2. Opens ATT PSM `31`.
3. Performs pre-CCCD ATT reads of `0x0021`, `0x0024`, and `0x002A`.
4. Enables only the v20/v22 winning CCCD:
   - ATT handle `0x0022 = 01 00`.
5. Drains AACP directly after the CCCD write.
6. Performs the v20-style post-CCCD ATT reads:
   - `0x0021`, `0x0024`, and `0x002A` again.
7. Performs a longer final AACP capture drain.
8. If a current-session `0x0053` payload is captured and its leading length word equals `payloadLen - 2`, derives a body-only no-op payload by stripping those first two bytes.
9. Sends exactly one setter-like AACP frame:

```text
04 00 04 00 54 00 <captured 0x0053 payload without the first two length bytes>
```

10. Watches for direct post-`0x0054` AACP `0x0055`, `0x0053`, `0x0052`, and ATT `0x002A` changes.
11. Sends one benign post-`0x0054` notification refresh request, the same `0x000F FF FF FF FF` used during init, and logs that stream separately. This refresh is **not** counted as a direct `0x0054` ack.

It does **not** send AACP `0x0052`, `0x0053`, or `0x0055` candidates.

If no current-session `0x0053` appears after the fuller replay, v23 aborts without sending `0x0054`.

## Success signals

The strongest signal is a direct post-`0x0054` AACP `0x0055` response.

The app also logs:

- whether a direct post-`0x0054` `0x0053` refresh appeared,
- whether a direct post-`0x0054` `0x0052` status appeared,
- whether ATT `0x002A` changed after the body-only no-op,
- whether the post-refresh `0x0053` equals the selected captured `0x0053`,
- decoded payloads for first relevant packets.

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
4. Launch **AACP v23 Probe**.
5. Enter or confirm the AirPods MAC address.
6. Tap **Run v23 probe**.
7. Use **Copy log** when the probe finishes.

## Notes

- This is a reverse-engineering probe. It may fail on firmware or Android Bluetooth stack variants.
- The app uses reflection to call hidden `BluetoothDevice.createL2capSocket(int)` / `createInsecureL2capSocket(int)` because the previous probes used those exact paths.
- The only setter-like AACP frame sent by v23 is one `0x0054` body-only no-op using a current-session captured `0x0053` payload.
