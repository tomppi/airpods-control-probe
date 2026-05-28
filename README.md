# AirPods AACP v28 Control Probe

This Android probe is the requested pivot away from setter/canary guessing.

It relies on the existing LibrePods Xposed module already being active in `com.android.bluetooth`. It does **not** install, replace, or modify the Xposed module.

## Why v28-control exists

v24 through v27 showed that:

- ATT `0x002A` accepts writes, but read-back remains the original value even for a Q8-sized change.
- AACP `0x0054` full-payload canary writes were mostly silent.
- v27 produced one delayed AACP `0x0052` packet after the original-value `0x0054` restore path:

```text
0x0052 payload: 03 00 02 01 01
```

That means the next useful test is not another float mutation. The next useful test is to isolate whether `0x0052` is actually caused by `0x0054`, a refresh request, or just delayed background noise.

## What this probe does

v28-control uses the known-good v20/v22 capture path, then runs four isolated control blocks.

It sends **no canary**, **no semantic mutation**, **no ATT `0x002A` writes**, and no AACP `0x0052`, `0x0053`, or `0x0055` candidate frames.

For each guarded attempt, it:

1. Connects AACP PSM `4097`.
2. Sends the known init sequence:
   - handshake,
   - AACP `0x004D` feature flags `D7`,
   - AACP `0x000F FF FF FF FF` notification request.
3. Opens ATT PSM `31` read-only.
4. Reads ATT `0x0021`, `0x0024`, and `0x002A` as context only.
5. Enables the known trigger CCCD:
   - ATT handle `0x0022 = 01 00`.
6. Captures the current-session AACP `0x0053` payload.
7. Runs these isolated blocks:

### Block A: baseline wait

Sends nothing and drains AACP for a long window.

Purpose: detect spontaneous/background `0x0052`.

### Block B: benign refresh only

Sends only:

```text
04 00 04 00 0F 00 FF FF FF FF
```

Purpose: determine whether refresh alone causes `0x0052`, `0x0053`, or `0x0055`.

### Block C: original current-session `0x0053` via `0x0054`

Sends exactly one no-op full-payload `0x0054` using the captured current-session `0x0053` payload unchanged:

```text
04 00 04 00 54 00 <captured 0x0053 payload unchanged>
```

Purpose: determine whether an original/no-op `0x0054` is parsed/accepted and whether it causes the delayed `0x0052` clue.

### Block D: refresh after original `0x0054`

Sends the same benign refresh again:

```text
04 00 04 00 0F 00 FF FF FF FF
```

Purpose: determine whether `0x0052` or `0x0053` only appears after a refresh following `0x0054`.

## Important logging behavior

Every packet in Blocks A-D is logged with relative timing:

```text
(+1234 ms since block C original 0x0054)
```

The result matrix reports, per block:

- packet count,
- command counts,
- whether `0x0052` appeared,
- whether `0x0053` appeared,
- whether `0x0055` appeared,
- whether any replayed `0x0053` equals the selected current-session payload.

## How to read the result

| Result | Meaning |
|---|---|
| `0x0052` appears in Block A | not specific to `0x0054`; likely background/periodic |
| `0x0052` appears in Block B | refresh side effect |
| `0x0052` appears only in Block C | strong evidence original `0x0054` is parsed/accepted |
| `0x0052` appears only in Block D | delayed `0x0054` status or refresh-after-`0x0054` side effect |
| `0x0053` appears in Block C/D | replay path discovered; compare payload to selected `0x0053` |
| no `0x0052`/`0x0053`/`0x0055` in any block | v27's `0x0052` was likely noisy/delayed, or a different trigger is needed |

## Build

This repo is ready for GitHub-hosted runners. Push it to GitHub and run the included workflow, or build locally with Gradle/Android SDK:

```bash
gradle :app:assembleDebug
```

The debug APK will be under:

```text
app/build/outputs/apk/debug/
```

## Notes

- The app uses reflection to call Android L2CAP socket methods.
- It requires the AirPods to already be paired and reachable.
- It requires Bluetooth permissions on Android 12+.
- The only post-capture AACP write that is not part of the normal init is the original, unchanged captured `0x0053` payload wrapped in a `0x0054` frame.
