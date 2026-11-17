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
    raise SystemExit("For emulators only. This tool does not provision an r1 or perform factory resets.")
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
        raise SystemExit("First install the testOnly debug APK with adb install -t -r so kiosk mode can be reversed.")
    if "no owners" not in owners.lower() and package not in owners:
        raise SystemExit("Another device owner is configured; left unchanged.")
    if "no owners" in owners.lower():
        original_home = run("shell", "cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME").splitlines()[-1]
        if "/" not in original_home or package in original_home:
            raise SystemExit("Original launcher is ambiguous; no changes made.")
        state_path.parent.mkdir(parents=True, exist_ok=True)
        fd = os.open(state_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as stream:
            json.dump({"home": original_home}, stream)
        print(run("shell", "dpm", "set-device-owner", admin))
    elif not state_path.exists():
        raise SystemExit("Device owner is already set, but launcher recovery is missing. No further changes made.")
    run("shell", "input", "keyevent", "224")
    run("shell", "am", "force-stop", package)
    print(run("shell", "am", "start", "-W", "-n", package + "/.MainActivity"))
    print("Kiosk enabled. To reverse, run this script with disable; ADB remains available.")
else:
    if not state_path.exists():
        raise SystemExit("No saved launcher recovery. No changes made.")
    original_home = json.loads(state_path.read_text())["home"]
    if "no owners" not in owners.lower() and package not in owners:
        raise SystemExit("Another device owner is set; no changes made.")
    if package in owners:
        print(run("shell", "dpm", "remove-active-admin", admin))
    run("shell", "am", "task", "lock", "stop")
    run("shell", "pm", "disable", home)
    run("shell", "cmd", "package", "set-home-activity", original_home)
    run("shell", "am", "force-stop", package)
    state_path.unlink()
    print("Kiosk removed and original launcher restored. App data is preserved.")
