import argparse
import fcntl
import json
import os
from pathlib import Path
import secrets
import signal
import sys
import threading
import uuid

from .agents import CodexAgent, DemoAgent
from .config import default_config_path, load_config
from .server import Server, Worker
from .store import Store


def service_arguments(argv):
    parser = argparse.ArgumentParser(description="Unrabbit VM service (setup: unrabbit.py setup; start: unrabbit.py run)")
    configured_run = bool(argv and argv[0] == "run")
    parser.add_argument("--config", type=Path, default=default_config_path() if configured_run else None)
    parser.add_argument("--state", type=Path)
    parser.add_argument("--listen", choices=["127.0.0.1", "0.0.0.0"])
    parser.add_argument("--port", type=int)
    parser.add_argument("--agent", choices=["demo", "codex"])
    parser.add_argument("--workspace", type=Path)
    parser.add_argument("--demo-delay", type=float, default=1.2)
    args = parser.parse_args(argv[1:] if configured_run else argv)
    args.executable = "codex"
    args.saved = None
    if args.config:
        if any(getattr(args, field) is not None for field in ("state", "listen", "port", "agent", "workspace")):
            parser.error("Use --config on its own; it pins the workspace, state and loopback endpoint.")
        try:
            saved = load_config(args.config.expanduser())
        except (ValueError, OSError) as error:
            parser.error(f"Cannot load VM config: {error}. Run setup first.")
        args.saved = saved
        args.state, args.workspace = Path(saved["state"]), Path(saved["workspace"])
        args.port, args.agent, args.executable = saved["port"], saved["agent"], saved["executable"]
    if not args.state:
        parser.error("Use setup, run --config, or --state.")
    args.listen = args.listen or "127.0.0.1"
    args.port = args.port if args.port is not None else 8787
    args.agent = args.agent or "demo"
    if not 1 <= args.port <= 65535 or not 0 <= args.demo_delay <= 60:
        parser.error("Invalid port or demo delay")
    return parser, args


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv and argv[0] == "speech-setup":
        from .speech import setup
        return setup(argv[1:])
    if argv and argv[0] == "setup":
        from .wizard import main as setup
        return setup(argv[1:])
    parser, args = service_arguments(argv)
    if os.geteuid() == 0:
        parser.error("Run under a dedicated unprivileged user, never root.")
    if args.saved and args.saved["version"] == 2:
        from .ssh_service import run
        try:
            return run(args.saved)
        except (ValueError, OSError) as error:
            parser.error(str(error))
    if args.agent == "codex" and not os.access(args.executable, os.X_OK) and args.executable != "codex":
        parser.error("Configured Codex executable is unavailable. Run setup again.")
    os.umask(0o077)
    args.state.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (args.state / "service.lock").open("w") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            parser.error("This state directory already has a worker.")
        config_file = args.state / "client.json"
        if not config_file.exists():
            config = {"version": 1, "serverId": str(uuid.uuid4()), "token": secrets.token_urlsafe(32)}
            with config_file.open("x") as output:
                json.dump(config, output)
        config = json.loads(config_file.read_text())
        store = Store(args.state / "unrabbit.sqlite3")
        agent = DemoAgent(args.demo_delay) if args.agent == "demo" else CodexAgent(args.workspace or args.state / "workspace", args.executable, private_state=args.state)
        worker = Worker(store, agent)
        try:
            from .speech import configured_speech
            server = Server((args.listen, args.port), store, worker, config["token"], config["serverId"], configured_speech())
        except OSError:
            store.close()
            parser.error(f"Cannot bind {args.listen}:{args.port}. Check whether another service is running.")
        def shutdown(*_):
            threading.Thread(target=server.shutdown, daemon=True).start()
        signal.signal(signal.SIGTERM, shutdown)
        signal.signal(signal.SIGINT, shutdown)
        worker.start()
        print(f"Unrabbit VM: {agent.name}, port {args.port}. Client config saved privately.", flush=True)
        try:
            server.serve_forever()
        finally:
            server.server_close(); worker.close(); store.close()


if __name__ == "__main__":
    main()
