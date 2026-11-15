# Prototype status

Current app: **0.10.0-dev**, verified on **2026-11-15**. Android UI verification uses an AOSP Android 35 arm64 emulator at 480 × 640 pixels, density 240. Physical r1 installation and hardware behavior are not verified.

## Automated checks

**21 core tests, 40 Android/Robolectric tests, 40 VM/setup tests and 6 local-helper tests** pass. Debug and release APKs build; lint has no errors. The Android SSH integration test uses a real AsyncSSH listener, not a mocked transport, when `UNRABBIT_TEST_PYTHON` points to the setup venv. CI enables this check without model credentials.

SSH coverage includes full enrollment, pinned host verification, key authentication, photo upload, result recovery, reconnect and idempotence using the actual Android JSch client. Server tests reject expired/consumed codes, a second enrollment from an already authenticated bootstrap session, unknown keys, wrong server identity, shell/PTY requests and forwarding. They also cover persistent identity, private file permissions and clean wizard shutdown. The actual camera decoder reads 128 Python-generated invitation images with varied patterns and rejects ambiguous frames containing two codes. Strict invitation parsing and the absence of manual connection controls also have Android coverage.

Rejected device keys and invalid protocol replies end a pending request instead of trapping the app in endless retries. A subprocess test sends SIGTERM to the SSH service while an agent is running: the agent process stops and the job remains interrupted, without replaying it. Shutdown also completes with an authenticated SSH client still connected. The local recovery helper refuses to claim a restart if Android leaves the process running in kiosk mode.

## Checked in the app

- Wheel/button substitutes, dark mode, morphing logo and live device status.
- Full-screen photo capture, persistent photo drafts and delayed submission with the next utterance.
- Camera release on pause/display-off, native scanner and reversible emulator kiosk mode.
- One continuous chat with preserved history and stable request IDs.
- Minimal connection screen: VM status, one scan action and close; no port wheel or editable connection fields.
- The previous debug HTTP path has verified photo transfer, cancellation, sleep/restart recovery and history preservation with a real Ubuntu worker.

A full native-camera scan → Android Keystore → direct remote SSH → reply test passes against an Ubuntu VM with its application SSH port reachable. The emulator scans the wizard's actual QR through its virtual camera; no connection data is injected into the app. The app generates and encrypts its device key, verifies the pinned host, enrolls, and displays “Verbunden”. The wizard confirms possession of the enrolled key and removes its QR image. Both the offline agent and real Codex responses have been checked; all pre-existing chat messages are preserved.

## Checked on Ubuntu

The actual source runs under an existing unprivileged account in a dedicated development directory on Ubuntu 24.04. System SSH configuration and existing services remain unchanged. The wizard detects Codex, suggests the real session’s server address, creates its own SSH host identity, starts its restricted listener and generates a private, expiring QR. The application SSH port is separate from administrative SSH.

The normal wizard was completed with Codex and the account's existing login. The application port must be opened by the administrator; setup does not change the firewall. The CLI runs in the foreground, and restarting an already paired service does not require another QR. Unattended installation and autostart are not implemented.

## Checked with real Codex over remote SSH

- Codex CLI 0.165.0 runs under the unprivileged service account with the read-only adapter.
- A named permission profile confines command reads to the selected workspace, job temporary files and minimal runtime paths. Harmless probes on the VM confirm allowed workspace reads, unavailable outside files and blocked writes, including through a real Codex invocation. Service state, SSH files and the Codex home are explicitly denied.
- A text request from the emulator receives a real model response.
- A native camera capture remains a local draft until the next simulated utterance. Codex then correctly describes the emulator's synthetic room image.
- The app was restarted with that image request pending. It recovered the result without resending the job, losing earlier messages or requiring another QR.

These are live model calls, separate from the offline automated suite. Model requests use the hosted provider even though the worker runs on the user's VM. Reports and credentials remain outside the public repository.

## Previously checked with real Codex locally

- The optional Ubuntu image installs Codex CLI 0.165.0 and uses a separate personal login completed by the user.
- The read-only sandbox allowed workspace reads and blocked writes.
- A live text request completed through the worker and appeared in the emulator while preserving all 29 previous messages.

Those earlier checks used the local Ubuntu/debug transport. The newer remote checks above use the product's SSH transport.

## Still ahead

Physical r1 installation/recovery, hardware input mapping and camera rotation; real microphone input/transcription and optional read-aloud; battery measurements; model-driven tool actions and approval behavior; semantic compaction/project routing; bounded device cache, history synchronization and device-revocation UI.

Speech remains simulated. Release builds include internet permission and the SSH transport, but exclude the debug HTTP adapter and do not offer the standalone demo action. No microphone permission is requested. The rest screen is an app view, not an authentication boundary. This is ready for manual emulator testing, not a production-ready r1 replacement.
