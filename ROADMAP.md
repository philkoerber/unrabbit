# Roadmap

Unrabbit starts with a small Android frontend and a locally testable Ubuntu service. The immediate scope is a reliable connection between the r1 and Codex on the user's Ubuntu VM. A real reply already works on the r1; speech and full physical-device validation follow.

## Working in the emulator

- [x] One continuous chat, with no conversation management or typing.
- [x] Wheel/button navigation, a morphing logo and live device status.
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
- [ ] Real microphone input and transcription; the speech provider has not been selected.
- [ ] Read replies aloud by default, with a persistent on/off control in settings.

## Later

- [ ] VM-side MCP integrations for email, Notion and research. Keep their configuration and credentials on the VM.
- [ ] Verify model-driven tool actions and define tool permissions/approval behavior.
- [ ] Automatic Ubuntu service installation; a manual systemd setup is verified and documented.
- [ ] Semantic context compaction and routing to the appropriate tools and projects.
- [ ] Bounded device cache, server history synchronization and retention controls.

## On the r1

- [x] Install Android, Unrabbit and managed Wi-Fi on an r1; pair the VM and receive a real reply.
- [ ] Validate stock recovery and package a repeatable hardware installer.
- [ ] Verify the wheel, side button, camera orientation, microphone and speaker.
- [ ] Measure battery use and tune brightness, display timeout and standby.

No release dates yet. The emulator prototype and a manual guide for the tested r1 configuration are available; complete hardware/recovery validation remains open.
