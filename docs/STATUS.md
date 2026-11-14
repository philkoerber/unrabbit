# Prototype status

Current app: **0.8.0-dev**. Verified on an AOSP Android 35 arm64 emulator at 480 × 640 pixels, density 240.

## Automated checks

The repository's Gradle command runs 20 core tests and 30 Android/Robolectric tests, builds the development APK and runs Android Lint. The verified local run has no failures or lint errors. Three lint warnings report newer CameraX versions; the tested 1.4.2 dependency set remains pinned.

Coverage includes button timing and cancellation, independent chat requests, persisted photo drafts, migrations, QR validation, settings save/cancel, port bounds, animation lifecycle and device status.

GitHub Actions runs the same Gradle tasks and generates an example setup QR. Its status is available in the repository's Actions tab.

## Checked in the emulator

- Wheel/button substitutes, rest screen, chat, menus and settings.
- Full-screen photo capture, private JPEG storage and photo drafts across restarts.
- Generated setup QR decoded by the virtual camera into a draft; no implicit save.
- Port wheel, acceleration, cancellation and explicit settings confirmation.
- Camera release on close and display-off, and preview restoration on wake.
- Reversible kiosk provisioning; no app-data reset is required by the helper.

Screenshots show the native prototype, with the UI currently in German. Camera tests use the emulator's virtual scene, not physical r1 hardware.

## Not implemented or verified

Actual r1 installation and recovery, hardware input mapping, camera rotation, measured battery life, real audio input, SSH pairing, a VM service, image analysis and server-side context routing.

The app has no microphone or internet permission. Responses and speech input are simulated. Its rest screen is an app view, not an authentication boundary.
