#!/usr/bin/env python3
"""Connect the debug Android emulator to the local Ubuntu development service."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "dev.unrabbit.app"


def run(argv, **kwargs):
    return subprocess.run(argv, cwd=ROOT, check=True, **kwargs)


def compose_command(agent):
    command = ["docker", "compose", "-f", "compose.yaml"]
    if agent == "codex":
        command += ["-f", "compose.codex.yaml"]
    return command


def require_idle(compose, env, parser):
    running = run(compose + ["ps", "--status", "running", "--quiet", "vm"], env=env, capture_output=True).stdout.strip()
    if not running:
        return
    check = ("import sqlite3; db=sqlite3.connect('file:/data/unrabbit.sqlite3?mode=ro', uri=True); "
             "print(db.execute(\"SELECT count(*) FROM jobs WHERE state IN ('queued','running')\").fetchone()[0])")
    pending = run(compose + ["exec", "-T", "vm", "python3", "-c", check], env=env, capture_output=True).stdout.strip()
    if pending != b"0":
        parser.error("Finish or cancel pending requests before restarting/changing agents. Nothing changed.")


def require_unpaired(prefix, parser):
    # A QR pairing wins in the app. Never claim to switch a real VM to offline Docker.
    for name in ("paired-vm.json", "paired-vm.json.bak"):
        found = subprocess.run(prefix + ["shell", "run-as", PACKAGE, "test", "-e", "no_backup/" + name],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if found.returncode == 0:
            parser.error("The app is QR-paired to a VM. This debug helper will not replace or detach that pairing. Nothing changed.")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["up", "attach", "detach", "status", "down", "login", "wizard"])
    parser.add_argument("--agent", choices=["demo", "codex"], help="up/wizard default to demo; login uses Codex")
    parser.add_argument("--serial", default="emulator-5572")
    parser.add_argument("--port", type=int, default=int(os.environ.get("UNRABBIT_DEV_PORT", "8787")))
    args = parser.parse_args(argv)
    if not 1 <= args.port <= 65535:
        parser.error("Invalid port")
    env = {**os.environ, "UNRABBIT_DEV_PORT": str(args.port)}
    agent = args.agent or ("codex" if args.action == "login" else "demo")
    if args.action == "login" and agent != "codex":
        parser.error("The demo agent needs no login. Use login without --agent.")
    compose = compose_command(agent)
    if args.action in ("login", "wizard"):
        run(compose + ["build", "setup"], env=env)
        if args.action == "login":
            # Device code goes only to the user's interactive terminal. No host auth import,
            # no API key in argv/env, no credentials or login transcript in the repository.
            run(compose + ["run", "--rm", "--no-deps", "--entrypoint", "codex", "setup",
                           "-c", 'cli_auth_credentials_store="file"', "login", "--device-auth"], env=env)
        else:
            run(compose + ["run", "--rm", "--no-deps", "setup"], env=env)
        return
    if args.action == "down":
        run(compose + ["down"], env=env)
        print("Service stopped. Local database and photos retained.")
        return
    if args.action == "up":
        require_idle(compose, env, parser)
        run(compose + ["build", "vm"], env=env)
        if agent == "codex":
            status = subprocess.run(compose + ["run", "--rm", "--no-deps", "-T", "--entrypoint", "codex",
                                                "vm", "login", "status"],
                                    cwd=ROOT, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if status.returncode:
                parser.error("Codex in the container needs login first: python3 scripts/local_vm.py login. Existing service unchanged.")
        run(compose + ["up", "--no-build", "-d", "vm"], env=env)
    if args.action in ("up", "status", "attach"):
        deadline = time.monotonic() + (30 if args.action == "up" else 3)
        while True:
            try:
                raw = run(compose + ["exec", "-T", "vm", "cat", "/data/client.json"],
                          env=env, capture_output=True).stdout
                config = json.loads(raw)
                config["endpoint"] = f"http://127.0.0.1:{args.port}"
                request = urllib.request.Request(config["endpoint"] + "/v1/health", headers={
                    "Authorization": "Bearer " + config["token"], "X-Unrabbit-Server": config["serverId"]})
                with urllib.request.urlopen(request, timeout=2) as response:
                    health = json.load(response)
                break
            except (OSError, ValueError, subprocess.CalledProcessError):
                if time.monotonic() >= deadline:
                    parser.error("Local VM not ready. Run up, or inspect docker compose logs vm.")
                time.sleep(.2)
        if args.action != "attach":
            print(f"Local Ubuntu service ready on loopback:{args.port}; agent={health['agent']}.")
            if args.action == "up":
                print("Next: python3 scripts/local_vm.py attach")
            return
    adb = shutil.which("adb")
    if not adb:
        sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
        adb = str(sdk / "platform-tools/adb")
    prefix = [adb, "-s", args.serial]
    emulator = run(prefix + ["shell", "getprop", "ro.kernel.qemu"], capture_output=True, text=True).stdout.strip()
    if emulator != "1":
        parser.error("This helper only accepts an emulator, not a physical device.")
    # run-as fails for release builds. Do not install, reset, root, or modify provisioning here.
    run(prefix + ["shell", "run-as", PACKAGE, "id"], capture_output=True)
    require_unpaired(prefix, parser)
    saved = subprocess.run(prefix + ["exec-out", "run-as", PACKAGE, "cat", "no_backup/demo-stream.json"],
                           capture_output=True, cwd=ROOT)
    if saved.returncode == 0:
        snapshot = json.loads(saved.stdout)
        if snapshot.get("pendingRequestIds") and (
                args.action == "detach" or snapshot.get("backendId") != config["serverId"]):
            parser.error("Finish or cancel pending requests before detaching/changing the backend. Nothing changed.")
    run(prefix + ["shell", "am", "force-stop", PACKAGE], capture_output=True)
    if args.action == "attach":
        run(prefix + ["reverse", f"tcp:{args.port}", f"tcp:{args.port}"], capture_output=True)
        run(prefix + ["shell", "run-as", PACKAGE, "sh", "-c",
                      "'umask 077; mkdir -p no_backup; cat > no_backup/unrabbit-local.json'"],
            input=json.dumps(config).encode(), capture_output=True)
        print("Debug app connected via ADB. Credential transferred privately; chat data retained.")
    else:
        run(prefix + ["shell", "run-as", PACKAGE, "rm", "-f", "no_backup/unrabbit-local.json"], capture_output=True)
        run(prefix + ["reverse", "--remove", f"tcp:{args.port}"], capture_output=True)
        print("Debug app returned to offline demo. Chat data retained.")
    run(prefix + ["shell", "am", "start", "-n", PACKAGE + "/.MainActivity"], capture_output=True)


if __name__ == "__main__":
    main()
