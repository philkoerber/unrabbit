# Prototype status

Current app: **0.16.0-dev**, latest UI/device update check on **2026-11-18**. Android UI verification uses an AOSP Android 35 arm64 emulator at 480 × 640 pixels, density 240. A physical r1 runs Android 14 with managed kiosk mode, in-app WPA2 connection, QR pairing and real Codex VM replies. Speech recognition now runs on the Ubuntu VM with faster-whisper `base`; the r1 records and compresses audio. The device owner confirmed the live side-button recording → VM transcription → chat reply flow in 0.14.0-dev and reported that it feels faster. This user feedback is separate from the measured fixture timings below. Full hardware checks and stock recovery remain unfinished.

## Automated checks

**79 Android/JVM tests and 22 core tests** pass for the simplified 0.16.0-dev revision, including the real SSH and QR integration checks. The debug APK builds and lint has no errors. These Android checks were verified again on 2026-11-18. The 57 VM/setup tests, 6 local-helper checks and release build passed before this device update; they were not repeated during it. The VM suite has also passed under the Ubuntu service account. Navigation tests cover direct welcome-to-scanner, both cancel/back paths, camera permission retry and preservation of history on errors. Speech coverage checks selected language, denied microphone permission, single submission, silence/errors, the recording limit, cancellation and rejection of stale results after a new turn or activity shutdown. It also verifies the authenticated SSH/HTTP speech routes, format/size/duration validation before decoding, one-at-a-time inference, missing/busy service handling and the absence of chat jobs after audio-only requests. Reply-audio coverage checks authenticated synthesis, bounded text/audio, missing/busy engines, next-section prefetch, cancellation during an in-flight response, stale callbacks, persistent default-on settings and silence after recovery/restart. Audio failure preserves the chat answer. Connection-status tests cover server identity, offline/reconnect transitions and rejection of late results after pause. A separate instrumentation probe on the Android 14 r1 also accepts both a synthetic invitation and the current VM invitation, and rejects an expired invitation. The Android SSH integration test uses a real AsyncSSH listener, not a mocked transport, when `UNRABBIT_TEST_PYTHON` points to the setup venv. CI enables protocol tests without speech weights or model credentials.

SSH coverage includes full enrollment, pinned host verification, key authentication, photo upload, result recovery, reconnect and idempotence using the actual Android JSch client. Server tests reject expired/consumed codes, a second enrollment from an already authenticated bootstrap session, unknown keys, wrong server identity, shell/PTY requests and forwarding. They also cover persistent identity, private file permissions and clean wizard shutdown. The actual camera decoder reads 128 Python-generated invitation images with varied patterns and rejects ambiguous frames containing two codes. Strict invitation parsing and the absence of manual connection controls also have Android coverage.

Rejected device keys and invalid protocol replies end a pending request instead of trapping the app in endless retries. A subprocess test sends SIGTERM to the SSH service while an agent is running: the agent process stops and the job remains interrupted, without replaying it. Shutdown also completes with an authenticated SSH client still connected. The local recovery helper refuses to claim a restart if Android leaves the process running in kiosk mode.

## Checked in the app

- Wheel/button substitutes and live device status. The simplified English UI uses pure black, a centered lock-screen clock and three status icons without a background logo. The layout was inspected in the 480 × 640 emulator and on the physical r1. Debug/release builds, Android lint, 101 Android/core tests, 57 VM tests and 6 setup-helper tests pass for this revision. VM tests use local fixtures; they do not verify a deployed VM or live speech models.
- Full-screen photo capture, persistent photo drafts and delayed submission with the next utterance.
- Camera release on pause/display-off, native scanner and reversible emulator kiosk mode.
- One continuous chat with preserved history and stable request IDs.
- Settings and network pages share borderless rows and the chat's status rail. An authenticated health check drives the VM icon; SIM controls stay hidden without a card. Network setup returns to its originating menu, without a second VM action.
- The previous debug HTTP path has verified photo transfer, cancellation, sleep/restart recovery and history preservation with a real Ubuntu worker.
- Default-on reply audio using VM-local Piper, with Amy medium/Ramona low voices, a persistent settings switch, interruption and no automatic playback of old answers.
- Foreground push-to-talk, persistent English/German selection and AAC-LC compression on the r1. The paired Ubuntu VM performs Silero speech detection and `base` INT8 transcription. Broader accents, noise and longer spoken utterances still need testing.

The 2026-11-18 in-place APK update verified launch in managed kiosk mode, the rest/chat/settings/network layouts, menu/back navigation with ADB-injected keys and authenticated VM health after display sleep/wake. The installed APK hash matched the local build; hashes of the pairing, encrypted device identity and chat-history files remained unchanged. This was a UI/update check, not a fresh microphone → model reply → speaker test or a physical-wheel feel test. The live speech and audio evidence below comes from the earlier checks.

The actual r1 AAC encoder and paired SSH connection were exercised under the ordinary, non-root app UID with the earlier English/German microphone clips. The VM returned both expected sentences, rejected digital silence and accepted the maximum-length encoder fixture. Cancelled results were discarded and the recognizer could be reused. The app contains no speech models/native recognition code. The Ubuntu service loads the pinned `base` model once, runs under its existing unprivileged account and creates no agent jobs for these speech probes.

With the final encoder, three alternating runs per microphone clip measured the complete encode → SSH upload → VM transcription → text-return path. English (9.34 seconds of audio) took **4.77 seconds median**, range 4.21–5.21; German (6.78 seconds of audio) took **4.53 seconds median**, range 3.75–4.75. All six transcripts matched the expected sentences. These measurements exclude recording and Codex response time; the VM model was already loaded. An earlier final-encoder pass included a 9.88-second English outlier, so this is not a guaranteed response-time bound.

The AAC payloads were 38,399 and 27,922 bytes respectively, before base64 and SSH overhead. At this bitrate, a minute of speech is roughly 0.33 MB of base64 audio plus connection overhead; this is an estimate from these clips, not a measured cellular bill. Digital silence returned no text in 1.09 seconds, and a 30-second silence fixture passed format/duration checks and returned no text in 3.89 seconds. Cancellation after encoding disconnected the speech request; a subsequent recognition succeeded. Private audio fixtures and the instrumentation APK were removed afterwards. Pairing and previous chat history were retained.

A full native-camera scan → Android Keystore → direct remote SSH → reply test passes in the Android emulator against an Ubuntu VM with its application SSH port reachable. The emulator scans the wizard's actual QR through its virtual camera; no connection data is injected into the app. The app generates and encrypts its device key, verifies the pinned host, enrolls, and displays “Connected”. The wizard confirms possession of the enrolled key and removes its QR image. Both the offline agent and real Codex responses have been checked; all pre-existing chat messages are preserved.

## Reply audio on the r1

Piper 1.8.0 runs in the same unprivileged VM service with the pinned **Amy medium** English and **Ramona low** German voices. Initial physical-device checks used the earlier Cori medium/Kerstin medium selection. That probe used the ordinary app UID, its existing pinned SSH pairing and Android's native AAC/MP4 decoder. Both short test sentences played to completion; cancellation discarded late results and a subsequent reply played. The final probe started English audio after 1.44 seconds and German after 1.64 seconds; a later short English reply started after 0.97 seconds. Cancellation returned in 0.23 seconds including instrumentation scheduling. These are individual short-fixture runs, excluding Codex response time, not latency guarantees. These probes create no chat jobs. The device key, pairing and all existing chat history remained unchanged; the temporary instrumentation APK was removed and the managed home app restored.

Separate CPU measurements generated the English and German clips on the VM with three runs each. A machine transcription of the generated samples recovered the English content and most German content; it misspelled German “E-Mails”. This is a small diagnostic, not a listening-quality score. After hearing the English/German test sentences on the r1, the device owner confirmed that the voices are easy to understand and asked to keep them. The owner subsequently preferred VM-generated Amy medium/Ramona low samples for a brighter, softer, friendlier voice. Those are now the selected defaults, at their original pitch and pace. The sample preference is separate from device listening feedback, machine transcription and timing measurements. A live spoken request all the way through Codex and the new read-aloud feature still needs user confirmation.

The Amy/Ramona selection was subsequently installed with revision/SHA-256 checks and both setup synthesis checks passed. A fresh physical-r1 probe over the existing pinned SSH pairing played both voices to completion, discarded late audio after cancellation and played the next reply successfully. Pairing, device identity and chat-history file hashes matched before and after the probe. The temporary instrumentation APK was removed and the normal app restored. These are playback/function checks; the owner's voice preference came from the separate listening samples.

## Checked on Ubuntu

The actual source runs under an existing unprivileged account in a dedicated development directory on Ubuntu 24.04. System SSH configuration and existing services remain unchanged. The wizard detects Codex, suggests the real session’s server address, creates its own SSH host identity, starts its restricted listener and generates a private, expiring QR. The application SSH port is separate from administrative SSH.

The normal wizard was completed with Codex and the account's existing login. The application port must be opened by the administrator; setup does not change the firewall. The CLI runs in the foreground, and restarting an already paired service does not require another QR. The development VM now uses a manually configured systemd service; a [portable example](VM-SERVICE.md) documents the pattern. Automatic service installation is not implemented.

## Checked with real Codex over remote SSH

- Codex CLI 0.165.0 runs under the unprivileged service account with the read-only adapter.
- A named permission profile confines command reads to the selected workspace, job temporary files and minimal runtime paths. Harmless probes on the VM confirm allowed workspace reads, unavailable outside files and blocked writes, including through a real Codex invocation. Service state, SSH files and the Codex home are explicitly denied.
- A text request from the emulator receives a real model response.
- A native camera capture remains a local draft until the next simulated utterance. Codex then correctly describes the emulator's synthetic room image.
- The app was restarted with that image request pending. It recovered the result without resending the job, losing earlier messages or requiring another QR.

These are live model calls, separate from the offline automated suite. Model requests use the hosted provider even though the worker runs on the user's VM. Reports and credentials remain outside the public repository.

## Photos and seven-day retention

The earlier photo-follow-up gap is fixed: subsequent requests include recent, unexpired photos alongside text history, within a four-image total including current attachments. The adapter maps each image to its original turn. A live diagnostic against the updated Ubuntu adapter reused an existing r1 photo in its text-only follow-up and returned an image-related answer. That check did not add, replace or replay a stored chat job; it does not independently verify the answer's factual identification.

Photos expire after seven days on both device and VM. Current uploads carry capture time; future device timestamps cannot extend VM retention. Legacy device photos keep their original file age, and legacy VM photos keep original job receipt time. Text, request IDs and image references remain; expired images appear as placeholders. Viewing/referencing a photo and retrying a request never renew expiry or restore deleted bytes. Old drafts do not attach to new requests; queued requests whose required images expire fail explicitly.

Regression tests cover follow-up image bytes and turn mapping, bounded context, the exact seven-day boundary, legacy database migration/restart, expired uploads and queued jobs, idempotent retries after deletion, SQLite/WAL cleanup, local file/camera-cache cleanup and persisted offline scheduling. On the physical r1, forcing the real background job removed a synthetic eight-day-old image while preserving a fresh image and the chat-history file hash. Both test files were removed afterwards. The updated app and VM service start successfully.

Deletion is automatic, not a promise of an exact wall-clock alarm: the VM prunes on startup and between jobs (including idle polling); Android schedules hourly maintenance and also cleans on app resume. Standby/offline scheduling or shutdown may delay physical cleanup, which catches up when execution resumes. Expired images are excluded from display, new uploads and model context. This retention applies to Unrabbit's device/VM storage, not externally exported files or a model provider's own retention.

Agent stdout/stderr are discarded and tool traces are not retained, so a claim of having searched cannot currently be audited from worker records alone.

## Previously checked with real Codex locally

- The optional Ubuntu image installs Codex CLI 0.165.0 and uses a separate personal login completed by the user.
- The read-only sandbox allowed workspace reads and blocked writes.
- A live text request completed through the worker and appeared in the emulator while preserving all 29 previous messages.

Those earlier checks used the local Ubuntu/debug transport. The newer remote checks above use the product's SSH transport.

## Still ahead

Physical r1 recovery, remaining hardware input checks and camera rotation; faster recognition, broader speech quality and battery measurements; broader listening evaluation and optional hosted reply voices with private VM-side key setup; model-driven tool actions and approval behavior; semantic compaction/project routing; bounded device cache, history synchronization and device-revocation UI. VM-side MCP integrations for email, Notion and research come later.

Release builds include microphone capture, AAC compression and VM speech over SSH, but exclude the debug HTTP adapter and do not offer the standalone demo action. Speech requires a working VM connection; failed audio uploads are not queued for later delivery. The Docker runtime image was previously rebuilt successfully with the pinned recognition model and the then-selected Cori/Kerstin reply voices; its setup steps loaded the models and synthesized English/German checks. The subsequent Amy/Ramona selection was installed and checked on the Ubuntu VM; the Docker image has not been rebuilt for this voice change. This does not revalidate the optional Codex container or a complete new-VM onboarding. The rest screen is an app view, not an authentication boundary. This remains a development prototype, not a production-ready r1 replacement.

## Isolated speech evaluation

On 2026-11-15, faster-whisper and Piper were tested separately on an Ubuntu VM with two vCPUs and about 4 GB RAM, using CPU inference. The existing Unrabbit service was left running. This is a prototype experiment, not an r1 microphone test or an integrated speech feature.

The speech-recognition fixtures include English and German human recordings from [MINDS-14](https://huggingface.co/datasets/PolyAI/minds14), synthetic short commands, added noise and silence. Warm-model timings exclude downloading, model startup, the device network hop and Codex. `tiny` makes substantial German and proper-name errors. `base` is a possible English prototype option with a known language and vocabulary hints, but German recognition still needs improvement; larger-model quality comes with appreciably more CPU latency. The additional run changed both the language hint and vocabulary prompt, so it does not isolate the performance effect of selecting a language. No general accuracy percentage is claimed from this small fixture set.

The tested faster-whisper version is 1.2.1, with CTranslate2 4.9.0 and PyAV pinned to 16.0.1: the unconstrained installation selected PyAV 19.0.2, whose changed `av.open` interface failed on first transcription. Piper 1.8.0 generated English and German voice samples on the same VM. These were isolated evaluations. The product now uses VM `base` with an explicit language; the integrated reply feature now uses Piper on the VM with Amy medium/Ramona low. See [speech architecture](ARCHITECTURE.md#speech).

## On-device speech evaluation

On 2026-11-16, [whisper.cpp 1.9.5](https://github.com/ggml-org/whisper.cpp/tree/d1be6fde11ac6e0407606b4e42fe72d34add8037) was cross-compiled with Android NDK 27.2 for the r1's ARM64 CPU and run through ADB. English and German clips were recorded using the device's existing Recorder app; the setup computer converted them to 16 kHz mono PCM, transferred files and collected results. All transcription ran on the r1, without VM or hosted speech inference. This is a native benchmark, not the Unrabbit microphone pipeline.

For the two microphone clips, with Q5_1 models, eight CPU threads, the full audio context and identical vocabulary hints:

| Model | English, selected language | German, selected language | English, auto | German, auto | Peak process RAM |
| --- | ---: | ---: | ---: | ---: | ---: |
| tiny | 2.8 s | 3.0 s | 5.2 s | 5.4 s | 109 MiB |
| base | 6.0 s | 6.3 s | 11.4 s | 11.7 s | 158 MiB |

Selected-language figures are medians of two runs per clip; automatic language has one run per clip. Models were already loaded; recording, transfer, model startup and Codex are excluded. `base` matched both requested microphone sentences in these trials. `tiny` matched the English sentence but corrupted the German one. The wider fixture set still showed German recognition errors even with `base`; these two successes do not establish general accuracy. Aggressively shortening the audio context was faster but produced repeated text and was rejected.

Those timings exclude voice-activity detection. Without it, `base` produced text from silence. A local Silero v6.2.0 check that rejects clips without speech while preserving the full recording kept both microphone sentences correct: about 7.1–7.2 seconds including transcription, one run per clip. Two silence checks returned no text in 0.5–0.8 seconds. Automatically cutting the recording to detected speech segments altered the German result, so that variant was rejected. This limited check still needs broader microphone and noise coverage.

Continuous eight-thread testing reached a reported skin-sensor temperature of 45 °C and was stopped by the benchmark's own guard. The later quality pass with ten-second pauses completed at a maximum reported skin temperature of 41 °C. Android thermal status remained zero; this does not prove the absence of all CPU frequency changes. The device was charging over USB, so no battery-life claim is made. Recordings and raw test output stay outside the public repository.

The historical 0.13.0-dev app then integrated this on-device engine. The two packaged-model clips matched under the ordinary app UID in 9.0 seconds on first use (including preparation/loading) and 7.7 seconds for German, one run each. The device owner confirmed live side-button speech and a VM reply, but found it slow. The 0.14.0-dev product moves recognition to the VM; the native library and bundled models were removed from the Android build. These earlier measurements remain historical evidence, not a description of the current execution location.
