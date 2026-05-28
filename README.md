# AirPods Control Probe v18

Android probe app for GitHub-hosted runners.

This repo is set up to build on GitHub Actions using Ubuntu, JDK 17, Gradle 8.10.2, Android SDK 35, and a CI-generated release keystore.

## What v18 tests

v17 corrected the main attribution: AACP `0x0053` and `0x0055` are real packets, but they appear to be part of the AACP init/post-init stream rather than a persistent raw ATT write or `0x0052` commit path.

The observed `0x0053` shape was:

- payload length: 134 bytes
- first word: `0x0084`, matching the remaining 132 bytes
- prefix after length: `02 00 02 02`
- vector body: 32 little-endian float32 values, all `0.5`

The observed `0x0055` payload was `01 01 00 19`.

v18 therefore focuses on attribution and safe no-op verification:

1. Runs an AACP init matrix to determine which init/notification-request variant produces `0x0053`, `0x0055`, and `0x0017`.
2. Captures `0x0053`/`0x0055` from the init stream and sends only exact no-op echoes, reading `0x002A` after each.
3. Fully drains AACP before enabling ATT notify CCCDs, so stale post-init packets are not misattributed to `0x0022` or `0x002B`.
4. Decodes the current `0x002A` hearing config shape without attempting any new value mutation.

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

APKs are uploaded as the `airpods-control-probe-v18-apks` artifact.
