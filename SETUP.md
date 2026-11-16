# Unrabbit on r1 + Ubuntu

The computer is needed for installation; the r1 talks directly to your VM afterwards. No always-on Mac or PC is required.

**Prototype:** Android, in-app Wi-Fi and VM pairing run on our test r1. Speech uses Whisper `base` on your VM; reply audio, full hardware and stock-restore checks are unfinished. [Verified status](docs/STATUS.md).

## 1. Prepare the r1

Follow the [manual r1 installation](docs/R1-INSTALL.md): back up the device, enable Rabbit Developer Mode, unlock, install the tested Android 14 image and the small hardware module. This wipes user data; Rabbit requires a warranty waiver. A factory reset alone does not restore rabbitOS.

Already on the tested Android build with the hardware module? Continue below. For an emulator instead, use [development setup](docs/DEVELOPMENT.md).

## 2. Install Unrabbit over USB

Clone this repository on your computer. With JDK 21 and Android SDK 35 configured:

```sh
git clone https://github.com/philkoerber/unrabbit.git
cd unrabbit
cd android
./gradlew :app:assembleDebug
cd ..
adb devices
```

The APK has no bundled speech model. Recording uses Android's microphone and AAC encoder; recognition runs on your VM without a speech API key.

Set `R1_SERIAL` to your r1's **ADB** serial, then install:

```sh
R1_SERIAL='YOUR_R1_ADB_SERIAL'
adb -s "$R1_SERIAL" install -t -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s "$R1_SERIAL" shell wm density 240
adb -s "$R1_SERIAL" shell dpm set-device-owner dev.unrabbit.app/.UnrabbitAdminReceiver
adb -s "$R1_SERIAL" shell am start -n dev.unrabbit.app/.MainActivity
```

The owner command is a **one-time step on a fresh device without accounts or another owner**. It makes Unrabbit the launcher and enables in-app Wi-Fi setup. On updates, only install the APK again. If Android refuses ownership, resolve that setup issue rather than resetting an already-used device. Build prerequisites and ordinary non-owner installs are covered in [development setup](docs/DEVELOPMENT.md#build).

## 3. Prepare your Ubuntu VM

Ubuntu 24.04 was tested. As its administrator, install dependencies and create a separate service account:

```sh
sudo apt-get update
sudo apt-get install -y git python3-venv
sudo adduser --disabled-password --gecos '' unrabbit
sudo -iu unrabbit
```

Keep this account out of `sudo`. As **unrabbit**, in its home directory:

```sh
git clone https://github.com/philkoerber/unrabbit.git
cd unrabbit
python3 -m venv .venv-setup
.venv-setup/bin/pip install -r scripts/requirements-setup.txt
.venv-setup/bin/pip install -r vm/requirements-speech.txt
.venv-setup/bin/python scripts/unrabbit.py speech-setup
```

Speech setup downloads the pinned multilingual `base` model once. Use the same account and environment when starting the service; it keeps the model loaded for requests. Allow roughly 1 GB of free disk space for the speech packages/model and headroom. The tested CPU VM has two vCPUs and 4 GB RAM. Existing installations need the new source, these speech setup commands and a service restart; pairing is preserved.

For real replies, install a compatible Codex CLI and complete its login **under this account**; the tested version is 0.165.0. Otherwise choose the offline Demo agent. [Agent setup and limits](docs/DEVELOPMENT.md#terminal-wizard).

## Terminal wizard

Still in the VM checkout as the service account:

```sh
.venv-setup/bin/python scripts/unrabbit.py setup
```

Choose the agent, an address reachable from the r1, the Unrabbit SSH port (default **2222**) and a workspace. These are configurable; administrative SSH uses its own login/port. The administrator must allow the selected TCP port through the VM/cloud firewall. The wizard does not alter firewall or system SSH settings.

## 4. Connect the r1

1. Open **WLAN & SIM**, connect Wi-Fi, then **Zurück**. A SIM is optional.
2. Open **Sprache**, choose **English** or **Deutsch** for spoken input. You can change this in settings later.
3. Tap **VM verbinden** once: the QR scanner opens directly. Scan the wizard's **current** QR.
4. Wait for **Verbunden** and the checked VM icon. The wizard confirms the enrolled device and removes the QR.

The code expires after ten minutes and can enroll one device. Scan the live terminal/image; old screenshots do not refresh. The device creates its own private key; no SSH private key goes into the QR or repository.

Hold the side button, speak after the listening animation starts, then release. The r1 sends compressed audio over its paired SSH connection; Whisper `base` on the VM returns text for the chat request. Allow microphone access if Android asks, then hold again. Turn the wheel while holding to discard the recording; a short press or Back discards a pending transcript. Silence creates no chat request, and recordings over 30 seconds are discarded. Audio is not saved on either side. Recognition requires the VM connection; after an interrupted upload, speak again. Reply audio is still planned.

## 5. Keep the VM running

The wizard stays in the foreground; Ctrl+C stops the service. After pairing, restart it with:

```sh
.venv-setup/bin/python scripts/unrabbit.py run
```

For operation after logout and reboot, follow the [short systemd recipe](docs/VM-SERVICE.md). Setup is complete when the r1 reconnects after a service/device restart and receives a VM reply. No automatic installer or stock-restore test is implied by this guide.

If something fails: [USB/boot troubleshooting](docs/R1-INSTALL.md#common-snags), [QR and network details](docs/DEVELOPMENT.md#terminal-wizard).
