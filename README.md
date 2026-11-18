<p align="center">
  <img src="docs/assets/mark.svg" width="84" alt="Unrabbit">
</p>

<h1 align="center">Unrabbit</h1>

<p align="center">A minimal Android frontend for the rabbit r1.<br>Designed to work with your own Ubuntu VM.</p>

<p align="center"><a href="SETUP.md">Setup</a> · <a href="ROADMAP.md">Roadmap</a> · <a href="docs/ARCHITECTURE.md">Architecture</a> · <a href="https://github.com/philkoerber/unrabbit/actions/workflows/android.yml">Build</a></p>

<p align="center">
  <img src="docs/assets/rest.png" width="220" alt="Unrabbit black lock screen with a centered clock and three status icons">
  <img src="docs/assets/settings.png" width="220" alt="Unrabbit settings with QR configuration and orange wheel selection">
</p>

**Early prototype.** The native Android app connects to an Ubuntu VM through QR-based SSH pairing. Android 14, kiosk mode, in-app Wi-Fi, pairing and real VM replies have been checked on an r1; broader transport/photo coverage also runs in an emulator. Speech recognition uses Whisper `base` on your VM, with a live microphone → transcription → chat reply test confirmed on the r1. Reply audio now uses Piper on the VM; the device owner confirmed that the English/German test voices are easy to understand. Broader hardware checks and stock recovery remain in progress. The interface is in English; speech input and reply audio support English and German. The screenshots show the simplified layout in the 480 × 640 emulator. An in-place r1 update on 2026-11-18 also verified the logo-free layout, menu navigation and VM health after sleep/wake, with existing pairing and chat history preserved. See the [scope of the hardware check](docs/STATUS.md#checked-in-the-app).

## The idea

One continuous chat, a physical wheel and simple menus on a black background. Hold the side button to speak, then release to send compressed audio to your VM. The VM transcribes it and runs the agent on the recognized text. Replies are read aloud by default, with an on/off setting. The device stays small.

The immediate focus is a reliable r1 → Codex on Ubuntu → reply loop. Email, Notion and research through VM-side MCP servers come later; those integrations are not implemented yet.

Recent photos remain available for follow-up questions, with at most four images per model request. **Photos expire after seven days** on the r1 and VM; asking about them does not extend their lifetime. Text history remains, with a placeholder for expired photos. Automatic cleanup runs in the background and catches up after downtime. See [photo context and retention](docs/STATUS.md#photos-and-seven-day-retention).

Speech recognition uses faster-whisper `base` with INT8 CPU inference and a Silero silence check on your VM. Choose **English** or **German** during setup or in settings; English is the default. Audio travels over the paired SSH connection and is held in memory for recognition, without being saved or sent to a hosted speech provider. Only the recognized text joins the Codex request. See the [verified speech status](docs/STATUS.md).

Reply audio uses VM-local Piper: **Amy medium** for English and **Ramona low** for German. Voices are currently fixed per language; an in-app voice picker is not implemented yet. The app plays short compressed sections while the next one is generated. Pressing the side button interrupts playback immediately; old replies are not replayed after sleep or restart. **Settings → Read aloud** turns it off. Text remains available if audio fails. Optional hosted voices and API-key setup are planned.

The prototype already has:

- Wheel/button navigation, dark mode and a shared battery, clock, Wi-Fi and verified VM status rail across chat and settings.
- Animated wheel scrolling in chat, with up to 3× travel when turning quickly, precise slow ticks, immediate direction changes and touch takeover. Automated checks cover acceleration and these transitions; physical-wheel feel still needs confirmation.
- A black lock screen with a centered clock, three status icons and quiet transitions; no background logo.
- Local photo drafts that accompany the next spoken request.
- Push-to-talk, English/German selection, compressed audio upload, VM transcription and cancellation without submitting a chat request.
- VM-generated reply audio with default-on playback, interruption and a persistent settings toggle.
- Scan-to-connect setup: pinned SSH, a device-owned key and no on-device connection fields.
- Network onboarding: direct Wi-Fi selection on a managed device, optional SIM status and Android dialogs for additional network settings.
- Persistent local history and a reversible emulator kiosk mode.
- A local Ubuntu worker with durable jobs, photo transfer, cancellation and result recovery after app restart.
- A three-step Linux terminal wizard with agent/address detection, saved VM settings and a terminal QR.
- An optional Codex CLI adapter, verified with real text and camera-image replies over remote SSH.

## Try it

For the device, follow the short [r1 + Ubuntu setup guide](SETUP.md). It links the manual firmware steps and optional VM autostart; no always-on setup computer is needed.

With JDK 21 and Android SDK 35 installed:

```sh
cd android
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

The app uses Android's microphone and AAC encoder; it bundles no speech models or native recognition library. Follow [VM speech setup](SETUP.md#3-prepare-your-ubuntu-vm) to install the model on your own server.

For app development, use the [emulator setup](docs/DEVELOPMENT.md). Use **↑/↓ for the wheel** and **← for the side button**. Double-press ← to open the photo camera.

To develop both sides locally, with Docker running and the debug app installed:

```sh
python3 scripts/local_vm.py up
python3 scripts/local_vm.py attach
```

See the [local VM guide](vm/README.md) for tests, shutdown and the optional Codex adapter. The default demo uses VM-local speech recognition and makes no hosted model/API calls.

For real Codex responses, an [optional Ubuntu image](vm/README.md#real-codex-locally) installs the CLI and uses a separate personal login in a private Docker volume. Live text responses have been verified locally; model requests still go to OpenAI.

Connect your own VM with the [terminal wizard](SETUP.md#terminal-wizard), then scan its QR in the app.

On your Linux VM, use `scripts/unrabbit.py setup` with the [setup dependencies](SETUP.md#terminal-wizard). Codex and an offline demo are selectable; other detected CLIs are marked as not yet supported. The short-lived QR enrolls the device and checks the real SSH connection. No private key is transferred.

SSH enrollment, host verification and chat requests have automated integration coverage. A native camera scan, device-key creation and replies over remote SSH have also been checked in the emulator. The Unrabbit SSH port must be reachable; the wizard leaves existing system and firewall configuration untouched. See [verified status](docs/STATUS.md) for the device/network checks.

The [status notes](docs/STATUS.md) separate working features from planned integrations. The [design notes](docs/DESIGN.md) describe the interface.

## Contribute

Small, focused contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) and the [roadmap](ROADMAP.md).

[MIT](LICENSE) for original project code. Fonts and dependencies retain their [own licenses](THIRD_PARTY_NOTICES.md).

An independent project, not affiliated with Rabbit Inc.
