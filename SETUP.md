# Setup

Start in an Android emulator. The app and Ubuntu service can be developed together locally; QR-based SSH pairing is implemented; a tested physical r1 installation is still ahead.

## Build

You need Git, JDK 17 or 21 (21 is used in CI), and the Android SDK command-line tools or Android Studio.

Set `ANDROID_HOME` to your SDK directory. Add `$ANDROID_HOME/cmdline-tools/latest/bin` and `$ANDROID_HOME/platform-tools` to your `PATH` so the commands below can find `sdkmanager`, `avdmanager` and `adb`.

```sh
git clone https://github.com/philkoerber/unrabbit.git
cd unrabbit
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
```

```sh
cd android
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
cd ..
```

The APK is `android/app/build/outputs/apk/debug/app-debug.apk`. You can also download the `unrabbit-dev` artifact from a successful GitHub Actions run.

## Run in an emulator

The tested profile is AOSP Android 35, 480 × 640 pixels, density 240. On Apple Silicon:

```sh
sdkmanager 'emulator' 'system-images;android-35;default;arm64-v8a'
avdmanager create avd --name unrabbit-dev --package 'system-images;android-35;default;arm64-v8a'
"$ANDROID_HOME/emulator/emulator" -avd unrabbit-dev -port 5572 -skin 480x640 -dpi-device 240 -camera-back virtualscene
```

Choose `no` if asked for a custom hardware profile. On an x86-64 host, use the corresponding `x86_64` system image. In another terminal:

```sh
adb -s emulator-5572 install -t -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5572 shell am start -n dev.unrabbit.app/.MainActivity
```

The `-t` flag is required because this development APK is `testOnly`. Choose **VM verbinden** to scan your VM wizard’s QR. **Demo öffnen** remains available in the debug build for UI development. Speech input is still simulated.

| Input | Action |
| --- | --- |
| ↑ / ↓ | Scroll chat or choose a menu item |
| ← briefly | Leave the rest screen, open the chat menu or confirm a selection |
| Hold ←, then release | Simulate speech and submit the example message |
| ↑ / ↓ while holding ← | Cancel speech without submitting |
| Double-press ← within 300 ms | Open the photo camera from chat or rest |
| ← in the photo camera | Capture a local photo draft |
| Touch X in either camera | Close the camera |

Photos wait in chat until the next completed speech input. A paired VM takes precedence over the optional debug transport below. Release builds require QR pairing to send requests.

## Local Ubuntu service

With Docker running and the debug app installed/opened once:

```sh
python3 scripts/local_vm.py up
python3 scripts/local_vm.py attach
python3 scripts/check_local_vm.py
```

The first two commands start Ubuntu locally and connect the emulator over ADB. The third is an opt-in smoke test: it sends one simulated request (including waiting photos), restarts the app, and checks result recovery without clearing history. It refuses QR-paired devices and requires kiosk mode to be disabled so Android can actually stop the app. Everything uses the offline demo agent by default. See [vm/README.md](vm/README.md) for details, Codex integration and stopping/detaching the setup. The product's SSH QR fields remain separate from this debug-only connection.

## Terminal wizard

On your Ubuntu VM, run from the checkout as a dedicated account **without root or sudo rights**. Use Python 3.11+ with venv support. If Codex is already installed and `codex login status` succeeds under that account, use the existing installation and login. Otherwise install/authenticate it first; the wizard also offers an offline demo agent.

```sh
python3 -m venv .venv-setup
.venv-setup/bin/pip install -r scripts/requirements-setup.txt
.venv-setup/bin/python scripts/unrabbit.py setup
```

1. Choose the detected agent. Currently Codex and Demo have adapters.
2. Confirm the reachable VM address, Unrabbit SSH port (default **2222**) and workspace.
3. In the app, choose **VM verbinden** and scan the QR. Pairing and the connection check happen automatically. No address, port or key is entered on the device.

The wizard starts the real service before displaying the QR. It reports **Verbunden** only after the app authenticates with its device-owned key and verifies the service identity. Keep the foreground process running; Ctrl+C stops it. To restart an already paired service:

```sh
.venv-setup/bin/python scripts/unrabbit.py run
```

The current Codex adapter is read-only: command reads are limited to the selected workspace, current job files and minimal runtime paths. Service state and credential directories are denied, command networking is off, and no approval escalation is enabled. The tested Codex version is 0.165.0. Model/authentication traffic still reaches the hosted provider.

The SSH listener is separate from the VM’s administrative SSH service. The selected TCP port must be reachable through the VM/cloud firewall. The wizard does **not** edit firewall rules, system SSH configuration, users or autostart. If the administrator approves it, a UFW installation can expose the default port with `sudo ufw allow 2222/tcp comment 'Unrabbit'`; it can later be removed with `sudo ufw delete allow 2222/tcp`. Only the administrator needs elevated privileges; the Unrabbit service must remain unprivileged.

The address suggestion comes from the SSH session or a local routed address. The port belongs to Unrabbit, not the administrative SSH session. With NAT or multiple networks, set the reachable endpoint in the terminal. Containers do not advertise their internal IP automatically.

The QR contains a **single-use code valid for ten minutes**, the endpoint and a pinned host fingerprint. Treat it as a temporary credential, not a public image. It carries no private key, OS password or provider credentials. The device creates its key locally and encrypts it using Android Keystore. The wizard renews expired invitations and removes the QR image after verified pairing or clean shutdown. A copied image can no longer enroll another device once the code has been consumed.

Configuration lives in `~/.config/unrabbit/vm.json`; state, the dedicated SSH host key and registered public device keys live in `~/.local/state/unrabbit`. `--config /absolute/path/vm.json` selects a different config. For disposable development, place the checkout, venv and selected workspace in one directory and point the shell’s `XDG_CONFIG_HOME`, `XDG_STATE_HOME`, `XDG_CACHE_HOME`, `XDG_DATA_HOME` and `TMPDIR` inside it. Keep the account's normal `CODEX_HOME` to reuse its existing login; choose a separate Codex home only when you want a separate personal login. See [OpenAI's authentication documentation](https://learn.chatgpt.com/docs/auth). Stop the process before deleting its directory.

Old address-only QR codes are no longer accepted. Re-run the wizard to generate a real invitation. An unsuccessful scan/connection leaves an existing pairing intact; switching VMs is blocked while requests are outstanding. A rejected device key ends the request with a re-pairing message rather than retrying indefinitely. Existing local chat history is preserved and is not automatically uploaded.

Autostart installation, physical r1 verification and a UI for revoking individual devices remain future work. To revoke all device access during development, stop the service and remove its `devices.json` before restarting.

## Optional emulator kiosk

Use only a disposable development emulator with no existing device owner. With `adb` on your PATH:

```sh
python3 scripts/emulator_kiosk.py enable --serial emulator-5572
python3 scripts/emulator_kiosk.py status --serial emulator-5572
```

The helper refuses physical hardware and non-`testOnly` builds. It makes Unrabbit the emulator's launcher and enables managed lock task mode. Keep the generated `private/kiosk-emulator-5572.json` locally; it records the previous launcher.

To restore the emulator's launcher without deleting app data:

```sh
python3 scripts/emulator_kiosk.py disable --serial emulator-5572
```

This is not an r1 provisioning or firmware tool. Physical installation, recovery and hardware behavior remain on the [roadmap](ROADMAP.md).
