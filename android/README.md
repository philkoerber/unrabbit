# Android app

Native Kotlin, Android Views and Canvas. No Compose or WebView. Start with the [development guide](../docs/DEVELOPMENT.md).

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

The minimum SDK is an app requirement, not a statement about supported r1 firmware. The development build is `0.17.0-dev`, marked `testOnly`, and uses a local debug signing key. No app-owned native speech library, NDK or CMake is required.

The app contains no speech model. It records through Android's microphone API, compresses with the platform AAC encoder and asks the paired VM to transcribe. The VM needs the separate [speech setup](../SETUP.md#3-prepare-your-ubuntu-vm).

## Code map

- `core/`: one-stream chat state, request IDs, photo drafts, button gestures and connection validation. No Android dependency.
- `app/`: native views, input adapters, local storage, camera handling and device status.
- `ChatTransport` / `DemoTransport`: lifecycle-aware transport boundary and standalone simulation.
- `src/debug/.../VmHttpTransport`: authenticated ADB loopback transport, bounded uploads, backoff and result recovery. See the [local VM guide](../vm/README.md).
- `PhotoCameraView` / `PhotoStore`: full-screen CameraX capture, normalized private JPEGs and explicit camera lifecycle.
- `SetupScannerView` / `SetupQr`: local QR recognition and strict connection-data validation.
- `SettingsView` / `NetworkSetupView`: consistent menu actions, network setup and the shared status rail.
- `PairingView`: direct scan, connection progress and retry, returning to its caller on cancellation; no manual connection fields.
- `PairingStore` / `SshApi` / `VmTransport`: encrypted device identity, pinned SSH enrollment and the persistent request outbox.
- `StatusRailView`: centered lock-screen clock and battery, Wi-Fi and VM icons that compact into the chat margin.
- `ChatMarkdown`: CommonMark parsing to native text spans, with label-only links and unchanged persisted messages.
- `ReadyTone`: a 70 ms media cue after verified microphone startup, suppressed for silent/vibrate/muted media; its startup and a 150 ms lead-in are discarded from captured samples. Capture end/cancellation releases the tone.
- `SpeechInput` / `MicrophoneCapture`: foreground push-to-talk, bounded PCM capture and cancellation-safe turn delivery.
- `SpeechAudioEncoder` / `VmSpeechRecognizer`: in-memory AAC-LC compression and an ephemeral authenticated VM speech request.
- `SpeechPreferences` / `SpeechLanguageView`: persistent English/German selection and read-aloud preference.
- `VmReplySpeech` / `SpokenText`: bounded prose sections, one-section prefetch, ephemeral SSH synthesis and cancellation.
- `MemoryReplyPlayer`: in-memory AAC/MP4 playback with audio focus and foreground lifecycle handling.

## Data and device boundaries

History is stored in `no_backup/demo-stream.json` (schema 4), including pending request IDs and backend identity. Older schemas migrate without replaying work. Network requests recover only for the same backend; standalone demo requests are not replayed. JPEGs live in `no_backup/photos/`, with a maximum edge of 2048 pixels and camera metadata removed. Both are excluded from Android backup. Paired endpoint metadata and the encrypted device key live in `no_backup/paired-vm.json` and `device-key.enc`; Android Keystore holds the wrapping key. The temporary QR code is not saved. The private local development credential is installed separately into `no_backup/unrabbit-local.json` by the emulator helper.

The app requests camera, microphone and network/Wi-Fi status access. Both builds have internet permission for SSH. Only debug builds include the optional loopback HTTP transport and its credential loader; release builds require pairing. Only the optional demo agent produces deterministic replies.

The microphone records 16 kHz mono PCM in memory for at most 30 seconds. AAC-LC at 32 kbit/s is sent to `/v1/speech/transcribe` through the pinned SSH connection, or the existing loopback API in a configured debug build. Audio is never saved on the device or VM. The VM applies Silero and Whisper `base`; the transcript becomes ordinary chat text and follows the existing durable transport. Leaving the foreground, turning the wheel while recording or cancelling recognition invalidates the turn; late VM results cannot submit. Audio uploads are not automatically retried. Standalone UI demo mode has no recognizer; connect a VM for speech. Reply audio uses VM Piper over `/v1/speech/synthesize`, with native in-memory playback, default-on settings and immediate interruption on a new press. Only the newest request submitted in the current foreground session is eligible; pause, navigation, disable and new input invalidate that eligibility. Recovered replies are never automatically spoken.

Power is not intercepted. The app does not hold a wake lock or keep the display awake. Installation, network setup, VM pairing and a reply are checked on the r1; complete physical input, camera rotation and power checks remain pending.

See [architecture](../docs/ARCHITECTURE.md), [design](../docs/DESIGN.md), [verified status](../docs/STATUS.md) and [third-party notices](../THIRD_PARTY_NOTICES.md).
