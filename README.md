# AirPods AACP v30 Hearing-Gate Probe

This Android probe tests the new hypothesis that hearing-aid settings may only be writable after the hearing-aid feature is enabled, and that the settings writer must use the LibrePods ATT `0x002A` write shape rather than the read-back shape observed in earlier probes.

It relies on the existing LibrePods Xposed module already being active in `com.android.bluetooth`. It does **not** install, replace, or modify the Xposed module.

## Why v30 exists

The earlier probes showed two important things:

- ATT handle `0x002A` accepts Write Requests, but read-back stayed byte-for-byte original when the probe wrote the same read-side `0x0060` payload shape.
- AACP `0x0054` with an exact captured `0x0053` payload sometimes produced a delayed `0x0052`, but semantic Q8 canaries did not produce clear evidence of a committed setting change.

LibrePods gives two new clues worth testing directly:

- The Android AACP control path has a `HEARING_AID` control command id `0x2C`, with boolean `true` encoded as data byte `0x01` and `false` as `0x02`.
- The Linux hearing-aid settings writer reads ATT handle `0x002A`, changes payload byte `[2]` to `0x64`, and then writes the settings back.

So v30 is built around the missing preconditions: **turn hearing aid on first**, then write the settings using the **LibrePods-style `byte[2]=0x64` marker**.

## What this probe does

v30 uses the known-good v20/v22/v28 capture path:

1. Connect AACP PSM `4097`.
2. Send the known init sequence:
   - handshake,
   - AACP `0x004D` feature flags `D7`,
   - AACP `0x000F FF FF FF FF` notification request.
3. Open ATT PSM `31`.
4. Read ATT `0x0021`, `0x0024`, and `0x002A` as context.
5. Enable the known trigger CCCD:
   - ATT handle `0x0022 = 01 00`.
6. Capture the current-session AACP stream, including `0x0053`, `0x0055`, `0x0052`, and `0x0009` status/control entries.
7. Log the observed hearing-related control values:
   - `0x2C` = `HEARING_AID`,
   - `0x33` = `HEARING_ASSIST_CONFIG`,
   - `0x37` = `PPE_TOGGLE_CONFIG`,
   - `0x38` = `PPE_CAP_LEVEL_CONFIG`.
8. Send AACP `0x0009` control command `0x2C 01 00 00 00` to enable hearing aid.
9. Read ATT `0x002A` again.
10. Build a guarded ATT `0x002A` settings canary:
    - requires 104-byte observed shape,
    - requires selector byte `[0] = 0x02`,
    - accepts read-shape `[2..3] = 0x0060` or write-shape `[2..3] = 0x0064`,
    - changes `[2..3]` to `0x0064`,
    - changes only the final float32-le value by one Q8 step (`1/256`).
11. Write the canary to ATT `0x002A`, drain AACP responses, and read `0x002A` back.
12. Restore the original semantic value using the same `byte[2]=0x64` write shape.
13. Optionally replay the exact captured `0x0053` payload as AACP `0x0054` after the hearing-aid enable step, to see whether the `0x0052` oracle behaves differently when the feature is on.
14. If the probe observed the prior `HEARING_AID` state as disabled (`0x02`), it sends a final OFF restore. Otherwise, it leaves the state alone for continuity.

The probe sends **no AACP `0x0052`, `0x0053`, or `0x0055` candidate frames**. Those commands are only observed as responses.

## How to read the result

| Result | Likely meaning |
|---|---|
| Post-canary ATT `0x002A` reflects the canary final float | strong win: earlier failures were probably caused by missing hearing-aid enable and/or missing `byte[2]=0x64` write marker |
| ATT read-back stays original, but AACP emits `0x0052` after the settings write | write was parsed/accepted somewhere, but ATT read-back may not be the committed store or another apply/refresh step is missing |
| ATT read-back stays original and no AACP acceptance signal appears | hearing-aid toggle alone is not enough; next target is commit/authorization or a different settings characteristic |
| HEARING_AID ON control itself produces useful `0x0052`/`0x0053`/`0x0055` | decode the toggle response first; settings may be gated on a state transition or delayed readiness window |
| Optional post-toggle `0x0054` exact-original starts producing `0x0052` more reliably | AACP setting path is gated by hearing-aid state, even if ATT read-back remains unchanged |

## Build

This repo is ready for GitHub-hosted runners. Push it to GitHub and run the included workflow, or build locally with Gradle/Android SDK:

```bash
gradle :app:assembleDebug
```

The debug APK will be under:

```text
app/build/outputs/apk/debug/
```
