#!/usr/bin/env python3
"""Reversible device-owner provisioning for an Android emulator. Refuses real hardware."""
import argparse
import json
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("action", choices=["enable", "disable", "status"])
parser.add_argument("--serial", default="emulator-5572")
parser.add_argument("--adb", default="adb")
args = parser.parse_args()
adb = [args.adb, "-s", args.serial]
def run(*words):
    result = subprocess.run(adb + list(words), text=True, capture_output=True)
    if result.returncode:
        raise SystemExit(result.stderr.strip() or result.stdout.strip() or "ADB command failed")
    return result.stdout.strip()

if run("shell", "getprop", "ro.kernel.qemu") != "1":
    raise SystemExit("Nur für Emulatoren. Kein R1-Provisioning oder Werksreset durch dieses Werkzeug.")
package = "dev.unrabbit.app"
admin = package + "/.UnrabbitAdminReceiver"
home = package + "/.Home"
state_path = Path(__file__).resolve().parents[1] / "private" / ("kiosk-" + args.serial.replace("/", "_") + ".json")
owners = run("shell", "dpm", "list-owners")
if args.action == "status":
    print(owners)
    state = run("shell", "dumpsys", "activity", "activities")
    print("\n".join(line.strip() for line in state.splitlines() if "mLockTaskModeState" in line or "topResumedActivity" in line))
elif args.action == "enable":
    if "TEST_ONLY" not in run("shell", "dumpsys", "package", package):
        raise SystemExit("Zuerst die testOnly-Debug-APK mit adb install -t -r installieren. Sonst ist der Rückweg nicht gewährleistet.")
    if "no owners" not in owners.lower() and package not in owners:
        raise SystemExit("Ein anderer Device Owner ist eingerichtet; unverändert gelassen.")
    if "no owners" in owners.lower():
        original_home = run("shell", "cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME").splitlines()[-1]
        if "/" not in original_home or package in original_home:
            raise SystemExit("Ursprünglicher Launcher nicht eindeutig; keine Änderungen.")
        state_path.parent.mkdir(parents=True, exist_ok=True)
        fd = os.open(state_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as stream:
            json.dump({"home": original_home}, stream)
        print(run("shell", "dpm", "set-device-owner", admin))
    elif not state_path.exists():
        raise SystemExit("Device Owner bereits gesetzt, aber Launcher-Rückweg fehlt. Nicht weiter verändert.")
    run("shell", "input", "keyevent", "224")
    run("shell", "am", "force-stop", package)
    print(run("shell", "am", "start", "-W", "-n", package + "/.MainActivity"))
    print("Kiosk aktiviert. Rückweg: dieses Skript mit disable; ADB bleibt erhalten.")
else:
    if not state_path.exists():
        raise SystemExit("Kein gespeicherter Launcher-Rückweg. Keine Änderungen.")
    original_home = json.loads(state_path.read_text())["home"]
    if "no owners" not in owners.lower() and package not in owners:
        raise SystemExit("Fremder Device Owner; keine Änderungen.")
    if package in owners:
        print(run("shell", "dpm", "remove-active-admin", admin))
    run("shell", "am", "task", "lock", "stop")
    run("shell", "pm", "disable", home)
    run("shell", "cmd", "package", "set-home-activity", original_home)
    run("shell", "am", "force-stop", package)
    state_path.unlink()
    print("Kiosk entfernt, ursprünglicher Launcher wiederhergestellt. App-Daten bleiben erhalten.")
