# Setup

Start in an Android emulator. The app is a local development prototype; a tested r1 installation and the VM service are still planned.

## Build

You need Git, JDK 17 or 21 (21 is used in CI), and the Android SDK command-line tools or Android Studio.

```sh
git clone https://github.com/philkoerber/unrabbit.git
cd unrabbit
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
```

Set `ANDROID_HOME` to your SDK directory, or create an untracked `android/local.properties` containing `sdk.dir=/path/to/android-sdk`.

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

The `-t` flag is required because this development APK is `testOnly`. Choose **Demo öffnen** on first launch. No real microphone input or VM connection starts.

| Input | Action |
| --- | --- |
| ↑ / ↓ | Scroll chat or choose a menu item |
| ← briefly | Leave the rest screen, open the chat menu or confirm a selection |
| Hold ←, then release | Simulate speech and submit the example message |
| ↑ / ↓ while holding ← | Cancel speech without submitting |
| Double-press ← within 300 ms | Open the photo camera from chat or rest |
| ← in the photo camera | Capture a local photo draft |
| Touch X in either camera | Close the camera |
| ↑ / ↓ in the port wheel, then ← | Adjust the port, then accept it into the settings draft |

Photos wait in chat until the next completed speech input. The current demo passes them to a local simulated transport, not to a server.

## Connection QR

Create a QR on your setup computer. It contains public connection details together: host, port, user and an optional public host-key fingerprint.

```sh
python3 -m venv .venv-setup
.venv-setup/bin/pip install -r scripts/requirements-setup.txt
.venv-setup/bin/python scripts/setup_qr.py \
  --host vm.example.net --port 22 --user unrabbit \
  --output private/unrabbit-setup.png
```

Optionally add `--host-key-fingerprint` with a public `SHA256:...` fingerprint verified through your trusted administrative connection. Do not put private keys, passwords or tokens into the QR. The generator requires Python 3.9 or newer.

Open the PNG on the setup computer. In the app, choose **VM-Adresse** in settings and point the camera at it. **Benutzer** and **Host-Schlüssel** open the same scanner. A recognized QR fills all fields and closes the camera. The settings checkmark saves the draft; the cross discards it. The SSH port can also be adjusted with the number wheel. Slow turns change it by one; sustained fast turns accelerate.

Saving currently stores configuration only. It does not verify the host or establish SSH. The planned VM setup will generate the QR and provision a separate device-owned key.

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
