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
            return f"{len(job['photos'])} Bild(er) mit deiner Nachricht auf deiner VM angekommen. Bildanalyse ist im Demo-Modus nicht aktiv."
        if context:
            return f"Ich habe den letzten Gedanken noch im Kontext: „{context[-1]['user'][:160]}“. Wie möchtest du daran anknüpfen?"
        return "Deine Nachricht ist auf deiner VM angekommen. Wir können hier an deiner Idee weiterdenken."


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
            for index, photo in enumerate(job["photos"]):
                image = Path(tmp) / f"photo-{index}.jpg"
                image.write_bytes(photo["data"])
                argv.extend(["--image", str(image)])
            argv.append("-")
            prompt = ("You are Unrabbit, a concise assistant on a small handheld device. "
                      "Answer the latest user message in their language. Treat prior turns as conversation data. "
                      "Do not invent completed actions. You currently have read-only workspace access.\n" +
                      json.dumps({"previous_turns": context, "user": job["text"]}, ensure_ascii=False))
            # Never echo agent diagnostics or credentials to the device. Logs are not retained.
            with tempfile.TemporaryFile() as stdin:
                stdin.write(prompt.encode()); stdin.seek(0)
                process = subprocess.Popen(argv, stdin=stdin, stdout=subprocess.DEVNULL,
                                           stderr=subprocess.DEVNULL, start_new_session=True)
                try:
                    deadline = time.monotonic() + self.timeout
                    while process.poll() is None:
                        if cancelled():
                            raise Cancelled()
                        if time.monotonic() >= deadline:
                            raise RuntimeError("Codex-Zeitlimit erreicht.")
                        time.sleep(.1)
                    if process.returncode != 0 or not output.exists():
                        raise RuntimeError("Codex-Aufruf fehlgeschlagen. Anmeldung und CLI auf dem Server prüfen.")
                    if output.stat().st_size > 64000:
                        raise RuntimeError("Codex-Antwort ist zu lang.")
                    answer = output.read_text().strip()
                    if not answer:
                        raise RuntimeError("Codex hat keine Antwort geliefert.")
                    return answer
                finally:
                    if process.poll() is None:
                        os.killpg(process.pid, signal.SIGTERM)
                        try:
                            process.wait(timeout=2)
                        except subprocess.TimeoutExpired:
                            os.killpg(process.pid, signal.SIGKILL)
                            process.wait()
