# AirPods AACP v31 Exact LibrePods Hearing Probe

Android test app for probing AirPods hearing-aid settings writes while the existing LibrePods Xposed module is active in `com.android.bluetooth`.

## Why v31 exists

v30 showed that merely sending the LibrePods `HEARING_AID` AACP toggle and writing ATT `0x002A` with the `byte[2]=0x64` marker is not enough to prove a committed settings change:

- The observed `HEARING_AID` control status already looked enabled-ish (`0x2C = 01 02 00 00`).
- ATT write requests to `0x002A` returned a normal write response, but readback stayed byte-for-byte identical to the original read.
- The reversible canary did not persist.
- A delayed AACP `0x0052` can appear on a valid restore/original-looking write, so it is not a reliable standalone acceptance oracle.

The missing difference from LibrePods' Linux hearing-aid settings path is that LibrePods enables notifications for the **HEARING_AID ATT characteristic**, meaning CCCD handle `0x002B = 0x002A + 1`, before reading/writing `0x002A`.

v31 therefore follows the exact LibrePods ATT sequence more closely:

1. Open AACP PSM `4097` and perform the known-good init/request-notifications sequence.
2. Open ATT PSM `31`.
3. Keep the old `0x0021` / `0x0022` trigger path only for current-session AACP context.
4. Enable the actual LibrePods hearing-aid CCCD: ATT handle `0x002B = 01 00`.
5. Read ATT `0x002A`.
6. Send `HEARING_AID ON` over AACP control id `0x2C`.
7. Write a LibrePods-style ATT `0x002A` settings payload with `byte[2]=0x64`.
8. Instead of poking the final/own-voice float, change a real LibrePods field: `left_eq[0]` at offset `4` by `+1.0 dBHL`.
9. Read back and restore the original value using the same `byte[2]=0x64` write shape.

## Safety

The canary is intentionally small and reversible. It changes only `left_eq[0]` by `+1.0 dBHL`, then restores the exact original semantic value. Do not treat this as a medical or hearing-calibration tool.

## Build

This repo includes a GitHub Actions workflow using a GitHub-hosted runner. Push the repo to GitHub, open **Actions**, run the Android build workflow, and download the debug APK artifact.

## Requirements

- Android device where the LibrePods Xposed/LSPosed module is already active in `com.android.bluetooth`.
- AirPods already paired.
- Bluetooth permissions granted.
- If hearing-aid writes still do not persist, verify LibrePods' “Act as Apple device” / DeviceID spoofing path and re-pair after changing it.
