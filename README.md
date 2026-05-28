# AirPods AACP v26 Probe

This Android probe is the next step after the v25 run.

It relies on the existing LibrePods Xposed module already being active in `com.android.bluetooth`. It does **not** install, replace, or modify the Xposed module.

## Why v26 exists

v22/v23 proved the clean current-session AACP `0x0053` capture path is reliable, but AACP `0x0054` full-payload and body-only no-op echoes were silent and did not change ATT `0x002A`.

v24 tested ATT handle `0x002A` directly and v25 then tried a one-ULP reversible canary. The important v25 result was:

```text
ATT Write Request to 0x002A with one-ULP final-float canary -> ATT 0x13 success
post-canary 0x002A read-back -> unchanged / original
post-canary AACP 0x0055 / 0x0053 / 0x0052 -> none
restore-original write -> ATT 0x13 success and final read-back original
```

That means ATT `0x002A` writes are accepted at the ATT layer, but a one-ULP change was either ignored, quantized away, or hidden behind another trigger. v26 therefore makes the next-smallest practical quantization test: one Q8-sized step.

## What v26 does

For up to two guarded attempts, v26:

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
8. Requires the conservative observed `0x002A` shape:
   - exactly 104 bytes,
   - byte `0` is `0x02`,
   - little-endian word at `[2..3]` is `96`,
   - final float32-le value is finite and can be moved by `1/256` while staying inside `[0, 1]`.
9. Builds a canary by changing **only** the final float32-le value by one Q8-sized step (`1/256`).
   - In the observed `0.5` case, this changes final bytes from `00 00 00 3F` to `00 00 01 3F` (`0.50390625`).
10. Sends one ATT Write Request to handle `0x002A` with the canary value.
11. Reads ATT `0x002A` back and checks whether it equals the canary, the original, or something else.
12. Immediately restores the exact original `0x002A` bytes with another ATT Write Request.
13. Reads ATT `0x002A` again to verify the final value equals the original.

v26 does **not** send AACP `0x0052`, `0x0053`, `0x0054`, or `0x0055` candidates.

If ATT `0x002A` cannot be read, if it changes between the guarded pre/post-CCCD reads, or if the value does not match the conservative observed shape, v26 aborts without sending the canary write.

## Success signals

The strongest signal is:

```text
post-canary ATT 0x002A equals canary: true
final restored ATT 0x002A equals original: true
```

That would prove ATT `0x002A` is byte-level semantically writable and restorable.

Other useful signals:

- canary write accepted, but read-back stays original: the write is probably ignored or needs a commit/refresh/alternate gate; a simple Q8 quantization explanation becomes less likely;
- canary write accepted, but read-back is neither original nor canary: inspect the exact byte delta before any next step;
- direct post-canary AACP `0x0055`, `0x0053`, or `0x0052`: this would be a new setter/commit clue;
- restore write fails or final read-back is not original: stop testing and inspect the log.

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
4. Launch **AACP v26 Probe**.
5. Enter or confirm the AirPods MAC address.
6. Tap **Run v26 probe**.
7. Use **Copy log** when the probe finishes.

## Notes

- This is a reverse-engineering probe. It may fail on firmware or Android Bluetooth stack variants.
- The app uses reflection to call hidden `BluetoothDevice.createL2capSocket(int)` / `createInsecureL2capSocket(int)` because the previous probes used those exact paths.
- The only non-current value written by v26 is a Q8-step change to the final float32-le value of ATT `0x002A`, guarded by stable reads and followed by an immediate exact-original restore.
