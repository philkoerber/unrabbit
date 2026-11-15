#!/usr/bin/env python3
"""Opt-in emulator smoke test: appends a demo request and restarts the app, never clears data."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import time
import urllib.error
import urllib.request
from local_vm import require_unpaired

ROOT = Path(__file__).resolve().parents[1]


def stop_app(prefix, parser):
    subprocess.run(prefix + ["shell", "am", "force-stop", "dev.unrabbit.app"], check=True, capture_output=True)
    result = subprocess.run(prefix + ["shell", "pidof", "dev.unrabbit.app"], capture_output=True)
    if result.returncode != 1:
        parser.error("Android did not stop the app. Disable emulator kiosk before this recovery test; a running process cannot verify restart recovery.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5572")
    args = parser.parse_args()
    adb = shutil.which("adb")
    if not adb:
        parser.error("Add adb to PATH first")
    prefix = [adb, "-s", args.serial]
    def device(*argv):
        return subprocess.check_output(prefix + list(argv))
    assert device("shell", "getprop", "ro.kernel.qemu").strip() == b"1", "Emulator only"
    require_unpaired(prefix, parser)
    def app_file(path):
        return device("exec-out", "run-as", "dev.unrabbit.app", "cat", path)
    config = json.loads(app_file("no_backup/unrabbit-local.json"))
    def api(path):
        request = urllib.request.Request(config["endpoint"] + path, headers={
            "Authorization": "Bearer " + config["token"], "X-Unrabbit-Server": config["serverId"]})
        with urllib.request.urlopen(request, timeout=3) as response:
            return json.load(response)
    assert api("/v1/health")["agent"] == "demo", "Smoke test must not call a real model"
    def stream():
        return json.loads(app_file("no_backup/demo-stream.json"))
    before = stream()
    assert not before.get("pendingRequestIds"), "Wait for existing requests to finish first"
    assert b'name="demoAcknowledged" value="true"' in app_file("shared_prefs/unrabbit-development.xml"), "Open Demo once first"
    backup = ROOT / "private/local-vm-checks" / str(time.time_ns())
    backup.mkdir(parents=True, mode=0o700)
    (backup / "before.json").write_text(json.dumps(before))
    previous_ids = {m["id"] for m in before["messages"]}
    stop_app(prefix, parser)
    device("shell", "am", "start", "-n", "dev.unrabbit.app/.MainActivity")
    time.sleep(1)
    device("shell", "input", "keyevent", "--duration", "650", "KEYCODE_DPAD_LEFT")
    deadline = time.monotonic() + 10
    request_id = None
    while time.monotonic() < deadline:
        sent = stream()
        fresh = [m for m in sent["messages"] if m["id"] not in previous_ids and m["role"] == "USER" and m["text"]]
        if fresh:
            request_id = fresh[-1]["requestId"]
            try:
                api("/v1/jobs/" + request_id)
                break
            except urllib.error.HTTPError as error:
                if error.code != 404:
                    raise
        time.sleep(.1)
    assert request_id and request_id in sent["pendingRequestIds"], "No pending request; increase UNRABBIT_DEMO_DELAY for slow emulators"
    stop_app(prefix, parser)
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        job = api("/v1/jobs/" + request_id)
        if job["state"] == "completed":
            break
        time.sleep(.2)
    assert job["state"] == "completed", job["state"]
    assert request_id in stream()["pendingRequestIds"], "App must still have the uncollected outbox entry"
    device("shell", "am", "start", "-n", "dev.unrabbit.app/.MainActivity")
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        after = stream()
        if request_id not in after["pendingRequestIds"]:
            break
        time.sleep(.2)
    replies = [m for m in after["messages"] if m["role"] == "ASSISTANT" and m.get("requestId") == request_id]
    assert len(replies) == 1 and replies[0]["text"] == job["answer"], "Result must be collected exactly once"
    retained = {m["id"]: m for m in after["messages"]}
    for message in before["messages"]:
        for field in ("role", "text", "photos"):
            assert retained[message["id"]].get(field) == message.get(field), "Existing history changed"
    for photo in job["photos"]:
        data = app_file("no_backup/photos/" + photo["id"] + ".jpg")
        assert hashlib.sha256(data).hexdigest() == photo["sha256"], "Photo bytes changed in transit"
    report = {"passed": True, "requestId": request_id, "photosVerified": len(job["photos"]),
              "priorMessagesRetained": len(before["messages"]), "singleReplyAfterRestart": True}
    (backup / "result.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Passed: request persisted, service completed while app stopped, result recovered once; existing history retained.")
    print(f"Verified {len(job['photos'])} photo(s) byte-for-byte. Private report: {backup.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
