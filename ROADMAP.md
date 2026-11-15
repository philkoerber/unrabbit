# Roadmap

Unrabbit starts with a small Android frontend and a locally testable Ubuntu service. QR pairing connects the app to its VM; hardware integration comes next.

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

- [ ] Unattended Ubuntu service installation and autostart.
- [ ] Real microphone input and transcription; optional read-aloud responses.
- [ ] Verify model-driven tool actions and define tool permissions/approval behavior.
- [ ] Semantic context compaction and routing to the appropriate tools and projects.
- [ ] Bounded device cache, server history synchronization and retention controls.

## On the r1

- [ ] Validate access, installation and a recovery procedure on actual hardware.
- [ ] Verify the wheel, side button, camera orientation, microphone and speaker.
- [ ] Measure battery use and tune brightness, display timeout and standby.

No release dates yet. The emulator prototype is available now; a tested r1 conversion is not.
