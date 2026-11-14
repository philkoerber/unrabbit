# Android app

Native Kotlin, Android Views and Canvas. No Compose or WebView. Start with the repository's [setup guide](../SETUP.md).

## Build

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

| Tool | Pinned version |
| --- | --- |
| Gradle Wrapper | 8.11.1 |
| Android Gradle Plugin | 8.9.2 |
| Kotlin | 2.1.20 |
| Compile / target SDK | 35 |
| Minimum SDK | 28 |
| JDK | 17 or 21; CI uses 21 |

The minimum SDK is an app requirement, not a statement about supported r1 firmware. The development build is `0.8.0-dev`, marked `testOnly`, and uses a local debug signing key.

## Code map

- `core/`: one-stream chat state, request IDs, photo drafts, button gestures and connection validation. No Android dependency.
- `app/`: native views, input adapters, local storage, camera handling and device status.
- `ChatTransport` / `DemoTransport`: the boundary for a future VM implementation. The current transport only returns canned replies.
- `PhotoCameraView` / `PhotoStore`: full-screen CameraX capture, normalized private JPEGs and explicit camera lifecycle.
- `SetupScannerView` / `SetupQr`: local QR recognition and strict connection-data validation.
- `SettingsView` / `PortWheelView`: QR configuration and numeric port input, without editable text widgets.
- `LogoView` / `LogoMotion`: compatible paths, continuous morphing and lifecycle-aware frame scheduling.

## Data and device boundaries

History is stored in `no_backup/demo-stream.json` (schema 3). Older schemas migrate without replaying work. JPEGs live in `no_backup/photos/`, with a maximum edge of 2048 pixels and camera metadata removed. Both are excluded from Android backup. Connection preferences currently hold public configuration, not login credentials.

The app requests camera access and network/Wi-Fi status read access. It has no internet or microphone permission. Speech input, responses and transport are simulated; photo capture and QR decoding use Android's camera APIs.

Power is not intercepted. The app does not hold a wake lock or keep the display awake. Actual r1 input codes, camera rotation, installation and power behavior need hardware testing.

See [architecture](../docs/ARCHITECTURE.md), [design](../docs/DESIGN.md), [verified status](../docs/STATUS.md) and [third-party notices](../THIRD_PARTY_NOTICES.md).
