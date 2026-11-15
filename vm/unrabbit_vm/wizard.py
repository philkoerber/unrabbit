"""Small Linux terminal setup. No package installs, SSH edits or cloud calls."""
import argparse
from dataclasses import dataclass
import os
from pathlib import Path
import pwd
import shutil
import subprocess
import sys
from typing import Optional

from .config import default_config_path, default_state_path, load_config, save_config
from .network import detect_connection
from .setup_qr import connection_payload, terminal_lines


@dataclass(frozen=True)
class AgentOption:
    name: str
    label: str
    executable: Optional[str]
    supported: bool


def discover_agents():
    result = []
    for name, label, supported in (("codex", "Codex", True), ("claude", "Claude Code", False),
                                   ("gemini", "Gemini CLI", False), ("opencode", "OpenCode", False)):
        executable = shutil.which(name)
        if executable:
            result.append(AgentOption(name, label, os.path.abspath(executable), supported))
    return result


def check_codex(executable):
    """Probe only the CLI contract and local login status, not a model response."""
    try:
        help_result = subprocess.run([executable, "exec", "--help"], stdin=subprocess.DEVNULL,
                                     capture_output=True, text=True, timeout=10)
        required = ("--ignore-user-config", "--ephemeral", "--strict-config", "--output-last-message", "--image")
        if help_result.returncode or not all(flag in help_result.stdout for flag in required):
            return "Diese Codex-Version passt noch nicht zum Adapter. CLI aktualisieren."
        status = subprocess.run([executable, "login", "status"], stdin=subprocess.DEVNULL,
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
        if status.returncode:
            return "Codex ist nicht angemeldet. In einem zweiten Terminal als dieses Konto: codex login"
    except (OSError, subprocess.TimeoutExpired):
        return "Codex ließ sich nicht prüfen. Installation und PATH prüfen."
    return None


class CancelSetup(Exception):
    pass


class Terminal:
    def __init__(self, source=None, output=None):
        self.source, self.output = source or sys.stdin, output or sys.stdout
        self.tty = self.output.isatty() and os.environ.get("TERM") != "dumb"
        self.color = self.tty and "NO_COLOR" not in os.environ

    def line(self, text=""):
        print(text, file=self.output, flush=True)

    def step(self, number, title):
        text = f"{number}/3  {title}"
        self.line("\n" + (f"\033[38;5;208m{text}\033[0m" if self.color else text))

    def ask(self, label, default="", validate=None):
        while True:
            suffix = f" [{default}]" if str(default) else ""
            print(f"  {label}{suffix}: ", end="", file=self.output, flush=True)
            answer = self.source.readline()
            if not answer or answer.strip().lower() == "q":
                raise CancelSetup()
            value = answer.strip() or str(default)
            if any(ord(c) < 32 or ord(c) == 127 for c in value):
                self.line("  Bitte ohne Steuerzeichen eingeben.")
                continue
            try:
                return validate(value) if validate else value
            except ValueError as error:
                self.line(f"  {error}")

    def qr(self, qr, png, interactive=True):
        if not self.tty or "UTF" not in (self.output.encoding or "").upper().replace("-", ""):
            self.line(f"  QR als Bild öffnen: {png}")
            return
        width = len(qr.get_matrix())
        height = (width + 1) // 2 + 6
        while True:
            size = shutil.get_terminal_size()
            if size.columns >= width and size.lines >= height:
                break
            self.line(f"  Für den QR braucht das Terminal {width} Spalten × {height} Zeilen.")
            if not interactive:
                return False
            if self.ask("Vergrößern + Enter, oder p für PNG", validate=one_of("", "p")) == "p":
                self.line(f"  QR als Bild öffnen: {png}")
                return
        for line in terminal_lines(qr):
            self.line(line)
        return True

    async def live_qr(self, qr, png):
        import asyncio
        while self.qr(qr, png, interactive=False) is False:
            self.line("  Vergrößern + Enter · p öffnet PNG · q beendet")
            loop = asyncio.get_running_loop()
            answer = loop.create_future()
            descriptor = self.source.fileno()
            def read():
                if not answer.done():
                    answer.set_result(self.source.readline())
            loop.add_reader(descriptor, read)
            try:
                text = await answer
            finally:
                loop.remove_reader(descriptor)
            if not text or text.strip().lower() == "q":
                raise CancelSetup()
            if text.strip().lower() == "p":
                self.line(f"  QR als Bild öffnen: {png}")
                return


def one_of(*choices):
    def validate(value):
        if value not in choices:
            raise ValueError("Bitte eine der angezeigten Optionen wählen.")
        return value
    return validate


def port_number(value):
    try:
        number = int(value)
    except ValueError:
        raise ValueError("Port muss eine Zahl sein.") from None
    if not 1 <= number <= 65535:
        raise ValueError("Port muss zwischen 1 und 65535 liegen.")
    return number


def workspace_path(value):
    path = Path(value).expanduser()
    if not path.is_absolute() or (path.exists() and not path.is_dir()):
        raise ValueError("Einen absoluten Ordnerpfad angeben (~/workspace geht auch).")
    return str(path.resolve())


def run_wizard(config_path, terminal, user, service_port=None):
    existing = load_config(config_path) if config_path.exists() else None
    try:
        import qrcode  # noqa: F401
        from PIL import Image  # noqa: F401
    except ImportError:
        raise ValueError("QR-Abhängigkeit fehlt. Im Setup-venv: pip install -r scripts/requirements-setup.txt") from None
    connection_payload("vm.example.net", 22, user)
    terminal.line("\nunrabbit / setup")
    terminal.line("Enter übernimmt den Vorschlag · q beendet")
    terminal.step(1, "Agent")
    detected = discover_agents()
    options = [item for item in detected if item.supported]
    options.append(AgentOption("demo", "Demo · ohne Modell", None, True))
    for index, item in enumerate(options, 1):
        terminal.line(f"  {index}  {item.label}" + (" · installiert" if item.executable else ""))
    unsupported = [item.label for item in detected if not item.supported]
    if unsupported:
        terminal.line("  Ebenfalls gefunden, noch ohne Adapter: " + ", ".join(unsupported))
    if existing and existing["agent"] not in [item.name for item in options]:
        terminal.line("  Der gespeicherte Agent wurde im PATH nicht gefunden. Bitte neu wählen.")
    default = next((str(index) for index, item in enumerate(options, 1)
                    if existing and item.name == existing["agent"]), "1")
    while True:
        chosen = terminal.ask("Auswahl", default, one_of(*(str(i) for i in range(1, len(options) + 1))))
        agent = options[int(chosen) - 1]
        problem = check_codex(agent.executable) if agent.name == "codex" else None
        if not problem:
            break
        terminal.line("  " + problem)
    if agent.name == "codex":
        terminal.line("  Aktuell: lesender CLI-Adapter. Eigene MCP-Konfiguration wird noch nicht geladen.")

    terminal.step(2, "VM")
    terminal.line(f"  Konto: {user} · eigener Unrabbit-SSH-Zugang")
    old = existing["connection"] if existing else {}
    hint = detect_connection() if not old else None
    if hint:
        terminal.line(f"  Adresse erkannt: {hint.source}")
    host = terminal.ask("VM-Adresse", old.get("host", hint.host if hint else ""),
                        lambda text: connection_payload(text, 22, user)["host"])
    # This endpoint belongs to Unrabbit, not the administrator's system sshd.
    default_port = old.get("port", 2222) if existing and existing["version"] == 2 else 2222
    def unprivileged_port(text):
        port = port_number(text)
        if port < 1024:
            raise ValueError("Unrabbit verwendet einen eigenen Port ab 1024 (Standard: 2222).")
        return port
    ssh_port = terminal.ask("Unrabbit-SSH-Port", default_port, unprivileged_port)
    workspace = terminal.ask("Arbeitsordner", existing["workspace"] if existing else "~/workspace", workspace_path)
    terminal.line(f"  {agent.label} · {user}@{host}:{ssh_port}")
    terminal.ask("Enter zum Starten und Koppeln", validate=one_of(""))
    from .ssh_service import Registry
    state = existing["state"] if existing else str(default_state_path().resolve())
    registry = Registry(state)
    connection = {**connection_payload(host, ssh_port, user, registry.host_key.get_fingerprint()),
                  "version": 2, "serverId": registry.server_id}
    config = {"version": 2, "agent": agent.name, "executable": agent.executable,
              "workspace": workspace, "state": state, "port": service_port or 8787, "connection": connection}
    save_config(config_path, config)
    return config


def main(argv=None):
    parser = argparse.ArgumentParser(description="Unrabbit: minimaler Linux-Setup-Wizard")
    parser.add_argument("--config", type=Path, default=default_config_path())
    parser.add_argument("--port", type=int, help="Interner Loopback-Port (Standard: 8787), nicht der SSH-Port")
    args = parser.parse_args(argv)
    if sys.platform != "linux":
        parser.error("Auf der Linux-VM als Dienstkonto ausführen. Lokal: in einem Ubuntu-Container testen.")
    if os.geteuid() == 0:
        parser.error("Als eigenes Konto ohne root/sudo ausführen.")
    if args.port is not None and not 1024 <= args.port <= 65535:
        parser.error("Interner Port muss zwischen 1024 und 65535 liegen.")
    config_path = args.config.expanduser().absolute()
    terminal = Terminal()
    try:
        config = run_wizard(config_path, terminal, pwd.getpwuid(os.geteuid()).pw_name, args.port)
        from .ssh_service import run
        return run(config, terminal, config_path)
    except (CancelSetup, KeyboardInterrupt):
        terminal.line("\n  Beendet. Ein bereits gespeichertes Setup bleibt erhalten.")
        return 0
    except (ValueError, OSError) as error:
        parser.exit(1, f"Setup: {error}\n")
