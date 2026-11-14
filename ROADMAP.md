# Roadmap

Unrabbit starts with a small Android frontend. The VM and hardware integration come next.

## Working in the emulator

- [x] One continuous chat, with no conversation management or typing.
- [x] Wheel/button navigation, a morphing logo and live device status.
- [x] Local photo drafts that join the next spoken request.
- [x] Connection setup by QR and a numeric SSH-port wheel.
- [x] Reversible emulator kiosk setup, persistent local data and automated tests.

## Next

- [ ] A small Ubuntu service and a setup flow that generates the connection QR.
- [ ] Device-owned SSH keys, host-key verification and a restricted server entry point.
- [ ] Real microphone input and transcription; optional read-aloud responses.
- [ ] Durable VM jobs, photo upload, reconnect and result recovery after sleep.
- [ ] VM-side context management and routing to the appropriate tools and projects.

## On the r1

- [ ] Validate access, installation and a recovery procedure on actual hardware.
- [ ] Verify the wheel, side button, camera orientation, microphone and speaker.
- [ ] Measure battery use and tune brightness, display timeout and standby.

No release dates yet. The emulator prototype is available now; a tested r1 conversion is not.
