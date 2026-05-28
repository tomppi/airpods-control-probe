# AirPods AACP v29 Matrix Probe

This Android probe implements the post-v28 pivot: use delayed AACP `0x0052` as an acceptance oracle for AACP `0x0054` payload shapes.

It relies on the existing LibrePods Xposed module already being active in `com.android.bluetooth`. It does **not** install, replace, or modify the Xposed module.

## Why v29 exists

v28-control gave a clean discriminator:

- baseline wait: no `0x0052`
- refresh-only `0x000F FF FF FF FF`: no `0x0052`
- exact current-session `0x0053` payload replayed as full-payload `0x0054`: delayed `0x0052`
- refresh after `0x0054`: no `0x0052`

That strongly suggests `0x0054` is parsed/accepted, and that `0x0052` is the useful acceptance/status clue.

v29 stops probing ATT writes and instead maps which `0x0054` payload shapes are accepted.

## What this probe does

v29 uses the known-good v20/v22/v28 capture path:

1. Connect AACP PSM `4097`.
2. Send the known init sequence:
   - handshake,
   - AACP `0x004D` feature flags `D7`,
   - AACP `0x000F FF FF FF FF` notification request.
3. Open ATT PSM `31` read-only.
4. Read ATT `0x0021`, `0x0024`, and `0x002A` as context only.
5. Enable the known trigger CCCD:
   - ATT handle `0x0022 = 01 00`.
6. Capture the current-session AACP `0x0053` vector.
7. Validate the observed shape:
   - payload length word equals `payloadLen - 2`,
   - prefix bytes `[2..5] = 02 00 02 02`,
   - exactly 32 finite float32-le values in `[0,1]`.
8. Run the `0x0054` validation matrix.

The probe sends **no ATT `0x002A` writes**. It sends **no AACP `0x0052`, `0x0053`, or `0x0055` candidate frames**. Those commands are only observed as responses.

## Matrix blocks

Each block sends one full-payload AACP `0x0054` frame and then waits for a delayed response window. Every observed packet is logged with relative timing.

The sequence is:

1. **Positive control:** exact original captured `0x0053` payload via `0x0054`.
2. **Test 1:** change only `float[0]` by one Q8 step (`1/256`).
3. **Restore 1:** exact original payload.
4. **Test 2:** change only `float[15]` by one Q8 step.
5. **Restore 2:** exact original payload.
6. **Test 3:** change only `float[31]` by one Q8 step.
7. **Restore 3:** exact original payload.
8. **Test 4:** change all 32 floats by the same Q8 step, preserving uniformity.
9. **Final restore:** exact original payload.

For each block, the result matrix reports:

- packet count,
- command counts,
- whether `0x0052` appeared,
- whether `0x0053` appeared,
- whether `0x0055` appeared,
- first `0x0052` payload bytes,
- whether the first `0x0052` equals the positive-control `0x0052`.

## How to read the result

| Result | Likely meaning |
|---|---|
| Positive original does not produce `0x0052` | run is inconclusive; rerun before changing hypotheses |
| Positive original and restores produce `0x0052`, but all mutations are silent | strict validation, missing authorization/commit context, or integrity-checked setter |
| Single-float mutation produces `0x0052` | that slot class is likely writable/accepted |
| Single-float mutations are silent, but all-32 uniform mutation produces `0x0052` | vector-level constraint such as uniformity/shape consistency |
| Restores stop producing `0x0052` | oracle is timing-sensitive, stateful, or rate-limited; repeat with longer waits |

## Build

This repo is ready for GitHub-hosted runners. Push it to GitHub and run the included workflow, or build locally with Gradle/Android SDK:

```bash
gradle :app:assembleDebug
```

The debug APK will be under:

```text
app/build/outputs/apk/debug/
```
