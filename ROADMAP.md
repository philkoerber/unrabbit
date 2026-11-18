# Roadmap

Unrabbit starts with a small Android frontend and a locally testable Ubuntu service. The immediate scope is a reliable connection between the r1 and Codex on the user's Ubuntu VM. Speech recognition uses Whisper `base` on the VM; broader voice and physical-device validation follow.

## Working in the emulator

- [x] One continuous chat, with no conversation management or typing.
- [x] Wheel/button navigation, a logo-free black rest screen and live device status.
- [x] Local photo drafts that join the next spoken request.
- [x] QR-only setup with automatic SSH enrollment and verification.
- [x] Reversible emulator kiosk setup, persistent local data and automated tests.
- [x] Local Ubuntu container, authenticated debug transport and reproducible emulator smoke test.
- [x] Durable jobs and photo transfer; client reconnect, cancellation and result recovery after app restart.
- [x] Server-owned bounded recent context and a Codex CLI adapter with verified remote text and image replies.
- [x] Minimal Linux terminal wizard: CLI detection, saved configuration, expiring pairing QR and foreground service start.
- [x] Optional local Codex image, separate device login and a verified live text response through the worker.
- [x] Device-owned SSH keys, pinned host-key verification and a restricted SSH endpoint.

## Next

- [ ] Complete physical r1 checks of the Codex request/reply loop, including sleep, reconnect and restart without a setup computer.
- [x] Integrate microphone input, compressed audio upload and VM Whisper `base`, with silence rejection and cancellation.
- [x] Spoken language: English or German during setup and editable later in settings.
- [x] VM-local Piper reply audio with English/German female voices, bounded prefetch and interruption.
- [x] Read fresh replies aloud by default with a persistent on/off control in settings; no replay after sleep/restart.
- [ ] Optional hosted reply voices and private VM-side API-key entry.
- [ ] Tune end-to-end recognition latency and validate broader accents, background noise, longer utterances and mobile connectivity.

## Later

- [ ] VM-side MCP integrations for email, Notion and research. Keep their configuration and credentials on the VM.
- [ ] Verify model-driven tool actions and define tool permissions/approval behavior.
- [ ] Automatic Ubuntu service installation; a manual systemd setup is verified and documented.
- [ ] Semantic context compaction and routing to the appropriate tools and projects.
- [ ] Bounded device cache, server history synchronization and retention controls.

## On the r1

- [x] Install Android, Unrabbit and managed Wi-Fi on an r1; pair the VM and receive a real reply.
- [x] Benchmark native CPU transcription, including English and German recordings captured on the r1. This is separate from app integration.
- [x] Verify the earlier on-device Whisper integration and a live microphone → transcript → VM reply test; this measured prototype was subsequently replaced by VM recognition.
- [x] Verify r1 AAC → paired SSH → VM `base` with both microphone clips, silence, bounded duration and cancellation; measure complete transcription latency.
- [x] Confirm a live side-button recording → VM transcription → chat reply on the r1 with the device owner.
- [ ] Validate stock recovery and package a repeatable hardware installer.
- [ ] Complete wheel/button, camera orientation and speaker checks; broaden the successful live microphone test.
- [ ] Measure battery use and tune brightness, display timeout and standby.

No release dates yet. The emulator prototype and a manual guide for the tested r1 configuration are available; complete hardware/recovery validation remains open.
