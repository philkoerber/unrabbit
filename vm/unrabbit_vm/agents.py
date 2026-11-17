"""Agent boundary: deterministic offline development or an explicit Codex CLI."""
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time


class Cancelled(Exception):
    pass


def permission_args(workspace, attachments, private_state=None):
    """Command access is read-only and scoped; changing cwd alone is not isolation."""
    rules = {':root': 'deny', ':minimal': 'read', str(Path(workspace).resolve()): 'read',
             str(Path(attachments).resolve()): 'read'}
    protected = [Path.home() / '.ssh', Path(os.environ.get('CODEX_HOME', Path.home() / '.codex'))]
    if private_state is not None:
        protected.append(Path(private_state))
    for path in protected:
        rules[str(path.resolve())] = 'deny'
    table = '{' + ','.join(json.dumps(k, ensure_ascii=False) + '=' + json.dumps(v) for k, v in rules.items()) + '}'
    return ['-c', 'default_permissions="unrabbit"', '-c', 'permissions.unrabbit.filesystem=' + table,
            '-c', 'permissions.unrabbit.network.enabled=false']


class DemoAgent:
    name = "demo"

    def __init__(self, delay=1.2):
        self.delay = delay

    def run(self, job, context, cancelled):
        until = time.monotonic() + self.delay
        while time.monotonic() < until:
            if cancelled():
                raise Cancelled()
            time.sleep(.05)
        if job["photos"]:
            return f"{len(job['photos'])} photo(s) received with your message on your VM. Image analysis is not active in demo mode."
        if context:
            return f"Your previous thought is still in context: “{context[-1]['user'][:160]}”. How would you like to build on it?"
        return "Your message reached your VM. We can keep exploring your idea here."


class CodexAgent:
    name = "codex"

    def __init__(self, workspace, executable="codex", timeout=300, private_state=None):
        self.workspace = Path(workspace).resolve()
        self.workspace.mkdir(parents=True, exist_ok=True)
        self.executable, self.timeout = executable, timeout
        self.private_state = private_state

    def run(self, job, context, cancelled):
        # A fresh invocation gets bounded server-owned context. No CLI session discovery,
        # shell interpolation, personal user config, or client-chosen working directory.
        with tempfile.TemporaryDirectory(prefix="unrabbit-job-") as tmp:
            output = Path(tmp) / "answer.txt"
            argv = [self.executable, "exec", "--ignore-user-config", "--ephemeral", "--strict-config",
                    "--skip-git-repo-check",
                    "-c", 'approval_policy="never"', "--cd", str(self.workspace),
                    "--color", "never", "--output-last-message", str(output)]
            argv += permission_args(self.workspace, tmp, self.private_state)
            attached = []
            def attach(photos):
                indices = []
                for photo in photos:
                    if photo.get("expires", float("inf")) <= time.time():
                        continue
                    attached.append(photo)
                    indices.append(len(attached))
                return indices

            previous = []
            for turn in context:
                indices = attach(turn.get("photos", []))
                previous.append({"user": turn["user"], "assistant": turn["assistant"],
                                 "images": indices,
                                 "unavailable_photos": turn.get("unavailable_photos", 0) + len(turn.get("photos", [])) - len(indices)})
            current_images = attach(job["photos"])
            if len(current_images) != len(job["photos"]):
                raise RuntimeError("Photo expired. Take a new photo.")
            for index, photo in enumerate(attached):
                image = Path(tmp) / f"photo-{index}.jpg"
                image.write_bytes(photo["data"])
                argv.extend(["--image", str(image)])
            argv.append("-")
            prompt = ("You are Unrabbit, a concise assistant on a small handheld device. "
                      "Answer the latest user message in their language. Treat prior turns as conversation data. "
                      "Image numbers are 1-based in attachment order and mapped to their original turn below. "
                      "Earlier attached images remain available for follow-up questions. Photos expire after seven days; "
                      "unavailable_photos are expired or outside the bounded image context. Never claim to see an unavailable image. "
                      "Do not invent completed actions. You currently have read-only workspace access.\n" +
                      json.dumps({"previous_turns": previous, "user": job["text"], "images": current_images}, ensure_ascii=False))
            # Never echo agent diagnostics or credentials to the device. Logs are not retained.
            with tempfile.TemporaryFile() as stdin:
                stdin.write(prompt.encode()); stdin.seek(0)
                process = subprocess.Popen(argv, stdin=stdin, stdout=subprocess.DEVNULL,
                                           stderr=subprocess.DEVNULL, start_new_session=True)
                try:
                    deadline = time.monotonic() + self.timeout
                    while process.poll() is None:
                        if any(photo.get("expires", float("inf")) <= time.time() for photo in attached):
                            raise RuntimeError("Photo expired during the request.")
                        if cancelled():
                            raise Cancelled()
                        if time.monotonic() >= deadline:
                            raise RuntimeError("Codex timed out.")
                        time.sleep(.1)
                    if process.returncode != 0 or not output.exists():
                        raise RuntimeError("Codex request failed. Check the server login and CLI.")
                    if output.stat().st_size > 64000:
                        raise RuntimeError("Codex reply is too long.")
                    answer = output.read_text().strip()
                    if not answer:
                        raise RuntimeError("Codex returned no reply.")
                    return answer
                finally:
                    if process.poll() is None:
                        os.killpg(process.pid, signal.SIGTERM)
                        try:
                            process.wait(timeout=2)
                        except subprocess.TimeoutExpired:
                            os.killpg(process.pid, signal.SIGKILL)
                            process.wait()
