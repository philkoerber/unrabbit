"""Project-owned SSH endpoint. No shell, forwarding, system keys or sshd edits."""
import asyncio
import fcntl
import hashlib
import hmac
import io
import json
import os
from pathlib import Path
import secrets
import signal
import time
import uuid

import asyncssh

from .agents import CodexAgent, DemoAgent
from .config import write_private
from .server import Worker
from .setup_qr import make_qr
from .store import MAX_BODY_BYTES, Problem, Store, identifier
from .speech import SpeechService, configured_speech
from .tts import TtsService, configured_tts


def unique_json(raw):
    def unique(pairs):
        value = {}
        for key, item in pairs:
            if key in value:
                raise ValueError('Duplicate field')
            value[key] = item
        return value
    return json.loads(raw, object_pairs_hook=unique)


class Registry:
    def __init__(self, state):
        self.state = Path(state)
        self.state.mkdir(parents=True, exist_ok=True, mode=0o700)
        key_file = self.state / 'ssh-host-key'
        if not key_file.exists():
            key = asyncssh.generate_private_key('ecdsa-sha2-nistp256')
            write_private(key_file, key.export_private_key())
        self.host_key = asyncssh.read_private_key(key_file)
        identity = self.state / 'client.json'
        if not identity.exists():
            write_private(identity, json.dumps({'version': 1, 'serverId': str(uuid.uuid4()), 'token': secrets.token_urlsafe(32)}).encode())
        self.server_id = json.loads(identity.read_text())['serverId']
        self.keys_file = self.state / 'devices.json'
        self.keys = json.loads(self.keys_file.read_text()) if self.keys_file.exists() else {}
        self.invite_hash = None
        self.expires_at = 0
        self.connected = asyncio.Event()

    def invite(self, connection, ttl=600):
        token = secrets.token_urlsafe(32)
        self.invite_hash = hashlib.sha256(token.encode()).digest()
        self.expires_at = int(time.time()) + ttl
        self.connected.clear()
        return {**connection, 'pairingToken': token, 'expiresAt': self.expires_at}

    def valid_invite(self, digest):
        return bool(self.invite_hash and time.time() < self.expires_at and
                    hmac.compare_digest(self.invite_hash, digest))

    def enroll(self, digest, public):
        if not self.valid_invite(digest):
            raise Problem(403, 'Pairing code expired or already used')
        if not isinstance(public, str) or len(public) > 2048:
            raise Problem(400, 'Invalid public key')
        try:
            key = asyncssh.import_public_key(public)
            if key.get_algorithm() not in ('ecdsa-sha2-nistp256', 'ssh-ed25519', 'ssh-rsa'):
                raise ValueError()
        except (ValueError, asyncssh.KeyImportError):
            raise Problem(400, 'Invalid public key') from None
        normalized = ' '.join(key.export_public_key().decode().split()[:2])
        updated = {**self.keys, key.get_fingerprint(): normalized}
        write_private(self.keys_file, json.dumps(updated).encode())
        self.keys = updated
        self.invite_hash = None  # One enrollment, including already authenticated bootstrap sessions.
        return {'serverId': self.server_id}

    def known(self, key):
        saved = self.keys.get(key.get_fingerprint(), '')
        return ' '.join(saved.split()[:2]) == ' '.join(key.export_public_key().decode().split()[:2])


def dispatch(store, worker, server_id, request, speech=None, tts=None):
    if not isinstance(request, dict) or set(request) != {'serverId', 'method', 'path', 'body'}:
        raise Problem(400, 'Invalid request')
    if request['serverId'] != server_id:
        raise Problem(409, 'Wrong server identity')
    method, path, body = request['method'], request['path'], request['body']
    if method not in ('GET', 'POST') or not isinstance(path, str):
        raise Problem(400, 'Invalid request')
    if method == 'GET' and body is not None:
        raise Problem(400, 'Unexpected body')
    if method == 'GET' and path == '/v1/health':
        return {'version': 1, 'agent': worker.agent.name, 'serverId': server_id,
                'speech': (speech or SpeechService()).status, 'tts': (tts or TtsService()).status}
    if method == 'POST' and path == '/v1/speech/transcribe':
        return (speech or SpeechService()).transcribe(body)
    if method == 'POST' and path == '/v1/speech/synthesize':
        return (tts or TtsService()).synthesize(body)
    if method == 'POST' and path == '/v1/jobs':
        result = store.submit(body)
        worker.wake.set()
        return result
    if path.startswith('/v1/jobs/'):
        parts = path.removeprefix('/v1/jobs/').split('/')
        job_id = identifier(parts[0])
        if len(parts) == 1 and method == 'GET':
            return store.get(job_id)
        if len(parts) == 2 and parts[1] == 'cancel' and method == 'POST' and body == {}:
            return store.cancel(job_id)
    raise Problem(404, 'Unknown endpoint')


class Gateway:
    def __init__(self, registry, user, store, worker, speech=None, tts=None):
        self.registry, self.user, self.store, self.worker = registry, user, store, worker
        self.speech = speech or SpeechService()
        self.tts = tts or TtsService()
        self.connections = set()
        self.requests = asyncio.Semaphore(8)

    def server(self):
        gateway = self
        class Authentication(asyncssh.SSHServer):
            def connection_made(self, connection):
                self.connection = connection
                gateway.connections.add(connection)
                if len(gateway.connections) > 16:
                    connection.abort()

            def connection_lost(self, exc):
                gateway.connections.discard(self.connection)

            def begin_auth(self, username):
                return True

            def password_auth_supported(self):
                return True

            def public_key_auth_supported(self):
                return True

            def validate_password(self, username, password):
                self.connection.set_extra_info(device=False)
                digest = hashlib.sha256(password.encode()).digest()
                valid = username == gateway.user and gateway.registry.valid_invite(digest)
                if valid:
                    self.connection.set_extra_info(bootstrap=digest)
                return valid

            def validate_public_key(self, username, key):
                valid = username == gateway.user and gateway.registry.known(key)
                if valid:
                    self.connection.set_extra_info(device=True)
                return valid
        return Authentication()

    async def process(self, process):
        try:
            digest = process.get_extra_info('bootstrap')
            device = bool(process.get_extra_info('device'))
            allowed = ((process.command == 'unrabbit-pair' and digest and self.registry.valid_invite(digest)) or
                       (process.command == 'unrabbit-api' and device))
            limit = MAX_BODY_BYTES + 4096 if allowed and device else 4096
            async with self.requests:
                async with asyncio.timeout(30):
                    raw = bytearray()
                    while True:
                        chunk = await process.stdin.read(min(65536, limit + 1 - len(raw)))
                        if not chunk:
                            break
                        raw.extend(chunk)
                        if len(raw) > limit:
                            raise Problem(413, 'Request too large')
                if not allowed:
                    raise Problem(403, 'Device permission required; no shell or forwarding')
                request = unique_json(bytes(raw))
                if process.command == 'unrabbit-pair':
                    if not isinstance(request, dict) or set(request) != {'publicKey', 'serverId'} or request['serverId'] != self.registry.server_id:
                        raise Problem(400, 'Invalid enrollment')
                    result = self.registry.enroll(digest, request['publicKey'])
                else:
                    result = await asyncio.to_thread(dispatch, self.store, self.worker, self.registry.server_id, request, self.speech, self.tts)
                    if request['path'] == '/v1/health':
                        self.registry.connected.set()
            status = 200
        except Problem as error:
            status, result = error.status, {'error': error.message}
        except (ValueError, UnicodeError, TimeoutError):
            status, result = 400, {'error': 'Invalid request'}
        except (asyncio.CancelledError, asyncssh.ConnectionLost):
            process.close()
            return
        except Exception:
            status, result = 500, {'error': 'Service error'}
        process.stdout.write(json.dumps({'status': status, 'body': result}, ensure_ascii=False).encode())
        process.exit(0)

    async def listen(self, host, port):
        return await asyncssh.listen(host, port, server_factory=self.server,
            server_host_keys=[self.registry.host_key], process_factory=self.process,
            encoding=None, login_timeout=15, config=None, authorized_client_keys=None,
            kbdint_auth=False, gss_auth=False, gss_host=None,
            allow_pty=False, allow_scp=False, x11_forwarding=False, agent_forwarding=False)

    async def close(self):
        connections = list(self.connections)
        for connection in connections:
            connection.close()
        await asyncio.gather(*(connection.wait_closed() for connection in connections), return_exceptions=True)


async def serve(config, terminal=None, config_path=None):
    state = Path(config['state'])
    state.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (state / 'service.lock').open('w') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError('Service is already running. Stop it before running setup again.') from None
        registry = Registry(state)
        connection = config['connection']
        if registry.server_id != connection['serverId'] or registry.host_key.get_fingerprint() != connection['hostKeyFingerprint']:
            raise ValueError('Server identity does not match. Run setup again.')
        store = Store(state / 'unrabbit.sqlite3')
        agent = DemoAgent() if config['agent'] == 'demo' else CodexAgent(Path(config['workspace']), config['executable'], private_state=state)
        worker = Worker(store, agent)
        gateway = Gateway(registry, connection['user'], store, worker, configured_speech(), configured_tts())
        listener = None
        started = False
        png_path = Path(config_path).with_suffix('.qr.png') if config_path else None
        try:
            listener = await gateway.listen('', connection['port'])
            worker.start(); started = True
            if terminal:
                terminal.step(3, 'Scan QR')
                terminal.line('  App: Connect VM → scan QR. The QR is valid for ten minutes.')
                terminal.line('  The QR is an access code. Do not share it.')
                while True:
                    payload = registry.invite(connection)
                    qr = make_qr(payload)
                    png = io.BytesIO()
                    qr.make_image(fill_color='black', back_color='white').save(png, format='PNG')
                    write_private(png_path, png.getvalue())
                    # Cancellable terminal input; never block SSH while waiting for resize.
                    await terminal.live_qr(qr, png_path)
                    terminal.line('  Waiting for device …')
                    try:
                        await asyncio.wait_for(registry.connected.wait(), timeout=max(1, registry.expires_at - time.time()))
                        break
                    except asyncio.TimeoutError:
                        terminal.line('  New pairing QR:')
                registry.invite_hash = None
                png_path.unlink(missing_ok=True)
                if terminal.tty:
                    terminal.line('\033[2J\033[H')
                terminal.line('  Connected. Unrabbit is running · Ctrl+C stops it.')
            else:
                print(f"Unrabbit SSH: Port {connection['port']} · Ctrl+C stops it.", flush=True)
            await asyncio.Future()
        finally:
            registry.invite_hash = None
            if png_path:
                png_path.unlink(missing_ok=True)
            if listener:
                listener.close()
            # asyncio's server can wait for accepted connections too. Close them
            # first, or an idle, still-connected device can deadlock shutdown.
            await gateway.close()
            if listener:
                await listener.wait_closed()
            if started:
                worker.close()
            store.close()


def run(config, terminal=None, config_path=None):
    os.umask(0o077)
    async def lifecycle():
        loop = asyncio.get_running_loop()
        task = asyncio.current_task()
        previous = signal.getsignal(signal.SIGTERM)
        loop.add_signal_handler(signal.SIGTERM, task.cancel)
        try:
            await serve(config, terminal, config_path)
        finally:
            loop.remove_signal_handler(signal.SIGTERM)
            signal.signal(signal.SIGTERM, previous)
    try:
        asyncio.run(lifecycle())
    except (KeyboardInterrupt, asyncio.CancelledError):
        return 0
