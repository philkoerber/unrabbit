# Android app

Native Kotlin, Android Views and Canvas. No Compose or WebView. Start with the repository's [setup guide](../SETUP.md).

## Build

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleRelease
```

| Tool | Pinned version |
| --- | --- |
| Gradle Wrapper | 8.11.1 |
| Android Gradle Plugin | 8.9.2 |
| Kotlin | 2.1.20 |
| Compile / target SDK | 35 |
| Minimum SDK | 28 |
| JDK | 17 or 21; CI uses 21 |

The minimum SDK is an app requirement, not a statement about supported r1 firmware. The development build is `0.10.0-dev`, marked `testOnly`, and uses a local debug signing key.

## Code map

- `core/`: one-stream chat state, request IDs, photo drafts, button gestures and connection validation. No Android dependency.
- `app/`: native views, input adapters, local storage, camera handling and device status.
- `ChatTransport` / `DemoTransport`: lifecycle-aware transport boundary and standalone simulation.
- `src/debug/.../VmHttpTransport`: authenticated ADB loopback transport, bounded uploads, backoff and result recovery. See the [local VM guide](../vm/README.md).
- `PhotoCameraView` / `PhotoStore`: full-screen CameraX capture, normalized private JPEGs and explicit camera lifecycle.
- `SetupScannerView` / `SetupQr`: local QR recognition and strict connection-data validation.
- `SettingsView`: one scan action; no manual address, port, user or key fields.
- `PairingStore` / `SshApi` / `VmTransport`: encrypted device identity, pinned SSH enrollment and the persistent request outbox.
- `LogoView` / `LogoMotion`: compatible paths, continuous morphing and lifecycle-aware frame scheduling.

## Data and device boundaries

History is stored in `no_backup/demo-stream.json` (schema 4), including pending request IDs and backend identity. Older schemas migrate without replaying work. Network requests recover only for the same backend; standalone demo requests are not replayed. JPEGs live in `no_backup/photos/`, with a maximum edge of 2048 pixels and camera metadata removed. Both are excluded from Android backup. Paired endpoint metadata and the encrypted device key live in `no_backup/paired-vm.json` and `device-key.enc`; Android Keystore holds the wrapping key. The temporary QR code is not saved. The private local development credential is installed separately into `no_backup/unrabbit-local.json` by the emulator helper.

The app requests camera access and network/Wi-Fi status read access. Both builds have internet permission for SSH. Only debug builds include the optional loopback HTTP transport and its credential loader; release builds require pairing. There is no microphone permission: speech remains simulated. Photo capture, QR decoding and the local Ubuntu transport are real; the default service agent uses deterministic replies.

Power is not intercepted. The app does not hold a wake lock or keep the display awake. Actual r1 input codes, camera rotation, installation and power behavior need hardware testing.

See [architecture](../docs/ARCHITECTURE.md), [design](../docs/DESIGN.md), [verified status](../docs/STATUS.md) and [third-party notices](../THIRD_PARTY_NOTICES.md).
