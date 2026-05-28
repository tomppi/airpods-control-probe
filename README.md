# AirPods Control Probe v15

GitHub-runner-ready Android probe app for the AirPods Pro hearing-aid save investigation.

This repo is intentionally set up for GitHub Actions instead of requiring a local Android build environment. Push the repo to GitHub, open **Actions**, run **Build Android probe APK**, then download the uploaded `airpods-control-probe-v15-apks` artifact.

## What v15 changes from v14

v14 already proved that direct writes to `0x002A` are acknowledged/sent but readback remains the baseline value. It also mapped the nearby handles and showed:

- `0x002A` = read + write-no-response + notify, CCCD `0x002B`
- `0x002E`, `0x0031`, `0x0034`, `0x0037` = write + indicate command/status-style channels
- CCCDs for those indication channels are `0x002F`, `0x0032`, `0x0035`, `0x0038`

v15 keeps the v14 robust stale-packet filtering and adds the missing next things to try:

1. Tests the sibling Apple-service write handles `0x0021`, `0x0026`, and `0x0028` as possible input routes while still reading `0x002A` as the state/output value.
2. Enables all command indication descriptors, including the v14-discovered `0x0038` path that was not part of the earlier command-indication test set.
3. Runs each route in a fresh AACP + ATT session so one bad write cannot poison the following tests.
4. Uses a controlled header-preserved mutation: only byte `[4]` is changed to `0x01` when the baseline is long enough.

## Build on GitHub runners

The workflow is included at:

```text
.github/workflows/android.yml
```

It builds both debug and CI-signed release APKs on GitHub-hosted runners. It uses:

- Ubuntu GitHub-hosted runner
- JDK 17
- Android SDK setup action
- Gradle installed by the workflow
- `gradle :app:assembleDebug`

No local Gradle wrapper is required for GitHub Actions.

## Runtime notes

Install the debug APK on the Android device where the LibrePods Xposed module is already active in `com.android.bluetooth`.

The app itself does not install or replace the Xposed module. It only opens the same L2CAP/AACP/ATT paths used by the previous probe builds.
