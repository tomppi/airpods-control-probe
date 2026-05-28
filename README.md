# AirPods Control Probe v16

Android probe app for GitHub-hosted runners.

This repo is set up to build on GitHub Actions using Ubuntu, JDK 17, Gradle 8.10.2, Android SDK 35, and a CI-generated release keystore.

## What v16 tests

v15 showed that raw ATT writes to the hearing-aid region did not persist:

- direct `0x002A` write-command
- sibling `0x0026` write-command
- sibling `0x0021` write-command
- sibling `0x0028` write-request
- notification/indication combinations around `0x002B`, `0x002F`, `0x0032`, `0x0035`, and `0x0038`

v16 therefore focuses on the only new clue from v15: an AACP packet with command/message `0x0052` observed after the full indication setup.

The probe:

1. Attributes which CCCD enable causes AACP `0x0052`, including the previously untested `0x0022` notify path for `0x0021`.
2. Sends small AACP `0x0052` status/query variants and reads `0x002A` after each.
3. Stages ATT writes, then sends AACP `0x0052` variants as possible commit/status triggers.
4. Sends ATT Handle Value Confirmation `0x1E` whenever an ATT indication is observed.

The app still relies on the existing LibrePods Xposed module being active in `com.android.bluetooth`. It does not install or replace the Xposed module.

## Build on GitHub

Push this repo to GitHub and open the **Build AirPods Control Probe** workflow, or push to `main`/`master`.

The workflow builds:

```bash
gradle --no-daemon :app:assembleDebug :app:assembleRelease \
  -PAIRPODS_PROBE_KEYSTORE="$GITHUB_WORKSPACE/ci-release-key.jks" \
  -PAIRPODS_PROBE_KEYSTORE_PASSWORD=android \
  -PAIRPODS_PROBE_KEY_ALIAS=airpodsprobe \
  -PAIRPODS_PROBE_KEY_PASSWORD=android
```

APKs are uploaded as the `airpods-control-probe-v16-apks` artifact.
