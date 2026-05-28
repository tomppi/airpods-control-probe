# AirPods AACP v19 Probe

This Android probe is a follow-up to v18.

It relies on the existing LibrePods Xposed module already being active in `com.android.bluetooth`. It does **not** install, replace, or modify the Xposed module.

## What v19 tests

v18 showed that:

- AACP `0x0053` is emitted by the AACP init / feature-flags path, not by ATT CCCD enablement.
- Exact `0x0053` echo did not mutate ATT handle `0x002A`.
- `0x0053` has the observed shape:

```text
cmd 0x0053
payload: 84 00 | 02 00 02 02 | 32 × float32-le
observed vector: all 0.5
```

v19 therefore does three things:

1. Sweeps AACP `0x004D` feature values:

   ```text
   00
   01 02 04 08 10 20 40 80
   D7 D6 D5 D3 C7 97 57
   ```

   For each value, it records whether the stream produces `0x0053`, `0x0055`, `0x0052`, or `0x0017`.

2. Selects the best capture:

   - preferred: a session containing both `0x0053` and `0x0055`
   - fallback: the first session containing `0x0053`

3. Opens an adjacent no-op setter test session:

   - reads baseline ATT `0x002A`
   - sends AACP `0x0054` with the exact captured `0x0053` payload
   - optionally echoes captured `0x0055` if available
   - reads `0x002A` after each candidate

`0x0052` retest is present but disabled by default in `AacpV19Probe.java`:

```java
private static final boolean ENABLE_RISKIER_0052_RETEST = false;
```

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
4. Launch **AACP v19 Probe**.
5. Enter or confirm the AirPods MAC address.
6. Tap **Run v19 probe**.
7. Use **Copy log** when the probe finishes.

## Notes

- This is a reverse-engineering probe. It may fail on firmware or Android Bluetooth stack variants.
- The app uses reflection to call hidden `BluetoothDevice.createL2capSocket(int)` / `createInsecureL2capSocket(int)` because the previous probes used those exact paths.
- The app performs no intentional ATT value mutation except reading `0x002A`; the AACP `0x0054` candidate uses the exact current/default `0x0053` payload as a no-op candidate.
