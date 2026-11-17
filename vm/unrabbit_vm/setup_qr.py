"""Public connection details shared by the terminal wizard and PNG helper."""
import json
import re


def connection_payload(host, port, user, fingerprint=""):
    if not isinstance(host, str) or len(host) > 253 or host.startswith("-") or not re.fullmatch(r"[A-Za-z0-9._:%\[\]-]+", host):
        raise ValueError("Enter a VM DNS name or IP address without a URL scheme or username.")
    if type(port) is not int or not 1 <= port <= 65535:
        raise ValueError("Port must be between 1 and 65535.")
    if not isinstance(user, str) or user == "root" or not re.fullmatch(r"[a-z_][a-z0-9_-]{0,31}", user):
        raise ValueError("Use a valid, unprivileged Linux username.")
    if not isinstance(fingerprint, str) or (fingerprint and not re.fullmatch(r"SHA256:[A-Za-z0-9+/]{43}", fingerprint)):
        raise ValueError("Enter only a public SHA256 fingerprint.")
    return {"type": "unrabbit.setup", "version": 1, "host": host, "port": port,
            "user": user, "hostKeyFingerprint": fingerprint}


def make_qr(payload):
    import qrcode  # Loaded only while issuing a setup invitation.
    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=12, border=4)
    qr.add_data(json.dumps(payload, separators=(",", ":")))
    qr.make(fit=True)
    return qr


def terminal_lines(qr):
    """One module per column, two per row; explicit white quiet zone on dark terminals."""
    matrix = qr.get_matrix()
    white = [False] * len(matrix)
    glyphs = {(False, False): " ", (True, False): "▀", (False, True): "▄", (True, True): "█"}
    for row in range(0, len(matrix), 2):
        lower = matrix[row + 1] if row + 1 < len(matrix) else white
        yield "\033[30;107m" + "".join(glyphs[bool(a), bool(b)] for a, b in zip(matrix[row], lower)) + "\033[0m"
