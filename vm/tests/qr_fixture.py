"""Deterministic, non-authorizing invitations for the actual Android QR decoder."""
import base64
import io
import random
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from unrabbit_vm.setup_qr import make_qr

rng = random.Random(7216)
for index in range(128):
    payload = {
        'type': 'unrabbit.setup', 'version': 2,
        'host': ('vm.example.net', '203.0.113.10', 'my-unrabbit-vm.example.org', '[2001:db8::1234]')[index % 4],
        'port': 2222 + index, 'user': 'device',
        'hostKeyFingerprint': 'SHA256:' + ''.join(rng.choices('abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+/', k=43)),
        'serverId': '12345678-1234-1234-1234-123456789abc',
        'pairingToken': ''.join(rng.choices('abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_', k=43)),
        'expiresAt': 1900000000,
    }
    png = io.BytesIO()
    make_qr(payload).make_image().save(png, format='PNG')
    print(base64.b64encode(png.getvalue()).decode(), flush=True)
