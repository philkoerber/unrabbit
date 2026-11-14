#!/usr/bin/env python3
"""Create a public Unrabbit setup QR. No SSH connection, private keys or tokens."""
import argparse
import json
from pathlib import Path
import re

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--host", required=True)
parser.add_argument("--port", type=int, default=22)
parser.add_argument("--user", default="unrabbit")
parser.add_argument("--host-key-fingerprint", default="")
parser.add_argument("--output", type=Path, default=Path("unrabbit-setup.png"))
args = parser.parse_args()
if len(args.host) > 253 or not re.fullmatch(r"[A-Za-z0-9._:%\[\]-]+", args.host):
    parser.error("Host muss ein DNS-Name oder eine IP-Adresse ohne URL/Benutzer sein.")
if not 1 <= args.port <= 65535:
    parser.error("Port muss zwischen 1 und 65535 liegen.")
if args.user == "root" or not re.fullmatch(r"[a-z_][a-z0-9_-]{0,31}", args.user):
    parser.error("Einen gültigen, unprivilegierten Linux-Benutzernamen angeben.")
if args.host_key_fingerprint and not re.fullmatch(r"SHA256:[A-Za-z0-9+/]{43}", args.host_key_fingerprint):
    parser.error("Nur den öffentlichen SHA256-Fingerabdruck angeben, keinen privaten Schlüssel.")
try:
    import qrcode
except ImportError:
    raise SystemExit("Abhängigkeit fehlt: pip install -r scripts/requirements-setup.txt")
payload = {"type": "unrabbit.setup", "version": 1, "host": args.host, "port": args.port,
           "user": args.user, "hostKeyFingerprint": args.host_key_fingerprint}
qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=12, border=4)
qr.add_data(json.dumps(payload, separators=(",", ":")))
qr.make(fit=True)
args.output.parent.mkdir(parents=True, exist_ok=True)
qr.make_image(fill_color="black", back_color="white").save(args.output)
print(args.output.resolve())
