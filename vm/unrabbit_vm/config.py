"""Versioned, private VM configuration. Never part of the public setup QR."""
import json
import os
from pathlib import Path
import tempfile

from .setup_qr import connection_payload


def default_config_path():
    return Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "unrabbit/vm.json"


def default_state_path():
    return Path(os.environ.get("XDG_STATE_HOME", Path.home() / ".local/state")) / "unrabbit"


def validate_config(value):
    fields = {"version", "agent", "executable", "workspace", "state", "port", "connection"}
    if not isinstance(value, dict) or set(value) != fields or type(value["version"]) is not int or value["version"] not in (1, 2):
        raise ValueError("Unknown VM configuration format.")
    if value["agent"] not in ("demo", "codex"):
        raise ValueError("This agent does not have an adapter yet.")
    for key in ("workspace", "state"):
        if not isinstance(value[key], str) or not Path(value[key]).is_absolute() or any(ord(c) < 32 for c in value[key]):
            raise ValueError(f"{key}: enter an absolute path.")
    executable = value["executable"]
    if value["agent"] == "demo":
        if executable is not None:
            raise ValueError("Demo does not need a CLI.")
    elif not isinstance(executable, str) or not Path(executable).is_absolute() or any(ord(c) < 32 for c in executable):
        raise ValueError("Codex needs an absolute CLI path.")
    if type(value["port"]) is not int or not 1024 <= value["port"] <= 65535:
        raise ValueError("Internal service port must be between 1024 and 65535.")
    public = value["connection"]
    keys = {"type", "version", "host", "port", "user", "hostKeyFingerprint"}
    if value["version"] == 2:
        keys.add("serverId")
    if not isinstance(public, dict) or set(public) != keys:
        raise ValueError("Invalid connection settings.")
    expected = connection_payload(public["host"], public["port"], public["user"], public["hostKeyFingerprint"])
    if value["version"] == 2:
        import uuid
        if not public["hostKeyFingerprint"] or public["port"] < 1024 or str(uuid.UUID(public["serverId"])) != public["serverId"]:
            raise ValueError("Invalid SSH identity.")
        expected.update(version=2, serverId=public["serverId"])
    if type(public["version"]) is not int or public != expected:
        raise ValueError("Unknown QR format.")
    return value


def load_config(path):
    def unique(pairs):
        value = {}
        for key, item in pairs:
            if key in value:
                raise ValueError("Duplicate configuration field.")
            value[key] = item
        return value
    if Path(path).stat().st_size > 16384:
        raise ValueError("VM configuration is too large.")
    return validate_config(json.loads(Path(path).read_text(), object_pairs_hook=unique))


def write_private(path, data):
    """Atomic replacement, mode 0600; never follows a destination symlink."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    descriptor, temporary = tempfile.mkstemp(prefix=".unrabbit-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def save_config(path, value):
    write_private(path, (json.dumps(validate_config(value), indent=2) + "\n").encode())
