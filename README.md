# AirPods AACP v22 Probe

This Android probe is the next step after the v21 run.

It relies on the existing LibrePods Xposed module already being active in `com.android.bluetooth`. It does **not** install, replace, or modify the Xposed module.

## Why v22 exists

v21 behaved safely: it did **not** send AACP `0x0054`, because it did not capture a current-session `0x0053` payload.

The important detail is timing. In the v20 `CCCD 0x0022 only` run, the first `0x0053`/`0x0055` pair appeared after the post-CCCD final ATT reads and final deep AACP drain. v21 stopped at the earlier post-CCCD drain when no `0x0053` had appeared yet.

v22 keeps the same no-stale safety rule, but replays the fuller v20 timing before deciding there is no current-session vector.

## What v22 does

For up to two safe attempts, v22:

1. Runs the normal AACP init:
   - handshake,
   - AACP `0x004D` feature flags `D7`,
   - AACP `0x000F` notification request `FF FF FF FF`.
2. Opens ATT PSM `31`.
3. Performs pre-CCCD ATT reads of `0x0021`, `0x0024`, and `0x002A`.
4. Enables only the v20 winning CCCD:
   - ATT handle `0x0022 = 01 00`.
5. Drains AACP directly after the CCCD write.
6. Continues past the v21 abort point by doing the v20-style post-CCCD ATT reads:
   - `0x0021`, `0x0024`, and `0x002A` again.
7. Performs a longer final AACP capture drain.
8. Sends exactly one AACP `0x0054` no-op echo **only if** a current-session `0x0053` payload was captured.
9. Watches for post-`0x0054` AACP `0x0055`, `0x0053`, `0x0052`, and ATT `0x002A` changes.

It does **not** send AACP `0x0052`, `0x0053`, or `0x0055` candidates.

The no-op echo frame shape remains:

```text
04 00 04 00 54 00 <captured current-session 0x0053 payload>
```

If no current-session `0x0053` appears after the fuller replay, v22 aborts without sending `0x0054`.

## Success signals

The strongest signal is a post-`0x0054` AACP `0x0055` response.

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
4. Launch **AACP v22 Probe**.
5. Enter or confirm the AirPods MAC address.
6. Tap **Run v22 probe**.
7. Use **Copy log** when the probe finishes.

## Notes

- This is a reverse-engineering probe. It may fail on firmware or Android Bluetooth stack variants.
- The app uses reflection to call hidden `BluetoothDevice.createL2capSocket(int)` / `createInsecureL2capSocket(int)` because the previous probes used those exact paths.
- The only setter-like AACP frame sent by v22 is one `0x0054` no-op echo using a current-session captured `0x0053` payload.
