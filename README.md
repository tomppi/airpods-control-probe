# AirPods AACP v21 Probe

This Android probe tests the next step after the v20 trigger-attribution run.

It relies on the existing LibrePods Xposed module already being active in `com.android.bluetooth`. It does **not** install, replace, or modify the Xposed module.

## Why v21 exists

v20 found that the clean missing gate for the AACP `0x0053` vector stream was enabling ATT CCCD handle `0x0022` with `01 00`.

The first clean winning path was:

```text
AACP default init
→ AACP 0x004D feature flags D7
→ AACP 0x000F notification request FF FF FF FF
→ open ATT PSM 31
→ read ATT 0x002A
→ write ATT CCCD 0x0022 = 01 00
→ AACP emits 0x0052, 0x0053, and 0x0055
```

The selected v20 `0x0053` payload decoded as:

```text
84 00              declared/body-ish length = 132
02 00 02 02        prefix / selector / mode bytes
00 00 00 3F × 32   thirty-two float32-le values, all 0.5
```

## What v21 does

v21 is intentionally a **single-candidate** setter probe.

It:

1. Runs the v20 winning trigger path only.
2. Captures the current-session AACP `0x0053` payload.
3. Sends exactly one AACP `0x0054` frame using that captured `0x0053` payload unchanged.
4. Watches for post-`0x0054` AACP `0x0055`, `0x0053`, `0x0052`, and ATT `0x002A` before/after changes.

It does **not** send AACP `0x0052`, `0x0053`, or `0x0055` candidates.

The intended no-op echo frame shape is:

```text
04 00 04 00 54 00 <captured 0x0053 payload>
```

v21 prefers a `0x0053` captured after the winning CCCD `0x0022` write. If no post-CCCD `0x0053` arrives but an earlier current-session `0x0053` was observed, it logs a warning and uses that earlier current-session payload. If no current-session `0x0053` is captured, it aborts without sending `0x0054`.

## Success signals

The strongest success signal is a post-`0x0054` AACP `0x0055` response.

The app also logs:

- whether a post-`0x0054` `0x0053` refresh appeared,
- whether a post-`0x0054` `0x0052` status appeared,
- whether ATT `0x002A` changed after the no-op echo,
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
4. Launch **AACP v21 Probe**.
5. Enter or confirm the AirPods MAC address.
6. Tap **Run v21 probe**.
7. Use **Copy log** when the probe finishes.

## Notes

- This is a reverse-engineering probe. It may fail on firmware or Android Bluetooth stack variants.
- The app uses reflection to call hidden `BluetoothDevice.createL2capSocket(int)` / `createInsecureL2capSocket(int)` because the previous probes used those exact paths.
- The only setter-like AACP frame sent by v21 is one `0x0054` no-op echo using a current-session captured `0x0053` payload.
