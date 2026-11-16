import asyncio
import json
from pathlib import Path
import stat
import tempfile
import time
import unittest
import uuid

import asyncssh

from unrabbit_vm.agents import DemoAgent
from unrabbit_vm.ssh_service import Gateway, Registry
from unrabbit_vm.server import Worker
from unrabbit_vm.store import Store
from unrabbit_vm.speech import SpeechService
from test_speech import Backend, audio_request


class SshTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.registry = Registry(self.root)
        self.store = Store(self.root / 'jobs.sqlite3')
        self.worker = Worker(self.store, DemoAgent(.01))
        self.worker.start()
        self.gateway = Gateway(self.registry, 'device', self.store, self.worker)
        self.listener = await self.gateway.listen('127.0.0.1', 0)
        self.port = self.listener.get_port()
        self.device = asyncssh.generate_private_key('ecdsa-sha2-nistp256')
        self.device.set_comment('unrabbit-device')
        self.invite = self.registry.invite({'serverId': self.registry.server_id})

    async def asyncTearDown(self):
        self.listener.close(); await self.listener.wait_closed()
        await self.gateway.close()
        self.worker.close(); self.store.close(); self.tmp.cleanup()

    async def connect(self, password=None, key=None, host_key=None):
        return await asyncssh.connect('127.0.0.1', self.port, username='device',
            known_hosts=([host_key or self.registry.host_key.convert_to_public()], [], []),
            client_keys=[key] if key else [], password=password, agent_path=None, config=None)

    async def rpc(self, client, command, body):
        reply = await client.run(command, input=json.dumps(body), check=True, timeout=5)
        return json.loads(reply.stdout)

    async def denied(self, client, command):
        reply = await client.run(command, input='{}', check=True, timeout=5)
        self.assertEqual(403, json.loads(reply.stdout)['status'])

    def request(self, method='GET', path='/v1/health', body=None):
        return dict(serverId=self.registry.server_id, method=method, path=path, body=body)

    async def enroll(self):
        async with await self.connect(password=self.invite['pairingToken']) as client:
            result = await self.rpc(client, 'unrabbit-pair', {'serverId': self.registry.server_id,
                'publicKey': self.device.export_public_key().decode()})
            self.assertEqual(200, result['status'])

    async def test_pair_then_key_auth_job_and_recovery(self):
        await self.enroll()
        self.assertFalse(self.registry.connected.is_set())
        async with await self.connect(key=self.device) as client:
            health = await self.rpc(client, 'unrabbit-api', self.request())
            self.assertEqual(self.registry.server_id, health['body']['serverId'])
            self.assertTrue(self.registry.connected.is_set())
            job = {'id': str(uuid.uuid4()), 'text': 'hello', 'photos': []}
            response = await self.rpc(client, 'unrabbit-api', self.request('POST', '/v1/jobs', job))
            self.assertEqual(200, response['status'])
        async with await self.connect(key=self.device) as client:
            for _ in range(50):
                result = await self.rpc(client, 'unrabbit-api', self.request(path='/v1/jobs/' + job['id']))
                if result['body']['state'] == 'completed':
                    break
                await asyncio.sleep(.02)
            self.assertEqual('completed', result['body']['state'])
            duplicate = await self.rpc(client, 'unrabbit-api', self.request('POST', '/v1/jobs', job))
            self.assertEqual(result['body']['answer'], duplicate['body']['answer'])
        restored = Registry(self.root)
        self.assertTrue(restored.known(self.device))
        self.assertEqual(self.registry.server_id, restored.server_id)
        self.assertEqual(self.registry.host_key.get_fingerprint(), restored.host_key.get_fingerprint())
        self.assertIsNone(restored.invite_hash)

    async def test_expired_wrong_and_consumed_codes_are_rejected(self):
        for password in ('wrong',):
            with self.assertRaises(asyncssh.PermissionDenied):
                await self.connect(password=password)
        self.registry.expires_at = time.time() - 1
        with self.assertRaises(asyncssh.PermissionDenied):
            await self.connect(password=self.invite['pairingToken'])
        self.invite = self.registry.invite({'serverId': self.registry.server_id})
        await self.enroll()
        with self.assertRaises(asyncssh.PermissionDenied):
            await self.connect(password=self.invite['pairingToken'])
        with self.assertRaises(asyncssh.PermissionDenied):
            await self.connect(key=asyncssh.generate_private_key('ecdsa-sha2-nistp256'))

    async def test_bootstrap_is_not_a_shell_api_or_forwarding_credential(self):
        async with await self.connect(password=self.invite['pairingToken']) as client:
            await self.denied(client, 'unrabbit-api')
            await self.denied(client, 'touch /tmp/never-unrabbit')
            with self.assertRaises(asyncssh.ChannelOpenError):
                await client.open_connection('127.0.0.1', 22)
            with self.assertRaises(asyncssh.ChannelOpenError):
                await client.create_process(term_type='xterm')

    async def test_already_authenticated_bootstrap_cannot_enroll_second_key(self):
        async with await self.connect(password=self.invite['pairingToken']) as second:
            await self.enroll()
            await self.denied(second, 'unrabbit-pair')
            self.assertEqual(1, len(self.registry.keys))

    async def test_changed_host_key_fails_before_enrollment(self):
        other = asyncssh.generate_private_key('ecdsa-sha2-nistp256').convert_to_public()
        with self.assertRaises(asyncssh.HostKeyNotVerifiable):
            await self.connect(password=self.invite['pairingToken'], host_key=other)
        self.assertEqual({}, self.registry.keys)

    async def test_registered_device_still_has_no_shell_and_wrong_server_is_rejected(self):
        await self.enroll()
        async with await self.connect(key=self.device) as client:
            await self.denied(client, 'uname -a')
            wrong = {**self.request(), 'serverId': str(uuid.uuid4())}
            self.assertEqual(409, (await self.rpc(client, 'unrabbit-api', wrong))['status'])
            result = await client.run('unrabbit-api', input='{"method":"GET","method":"POST"}', check=True)
            self.assertEqual(400, json.loads(result.stdout)['status'])
            with self.assertRaises(asyncssh.ChannelOpenError):
                await client.open_connection('127.0.0.1', 22)

    async def test_private_files_and_new_invitation_invalidate_previous_code(self):
        old = self.invite
        self.invite = self.registry.invite({'serverId': self.registry.server_id})
        with self.assertRaises(asyncssh.PermissionDenied):
            await self.connect(password=old['pairingToken'])
        await self.enroll()
        for name in ('ssh-host-key', 'client.json', 'devices.json'):
            self.assertEqual(0o600, stat.S_IMODE((self.root / name).stat().st_mode))
        self.assertNotIn(self.invite['pairingToken'], (self.root / 'devices.json').read_text())

    async def test_speech_requires_device_key_and_returns_text_without_creating_job(self):
        engine = Backend()
        self.gateway.speech = SpeechService(engine)
        payload = self.request('POST', '/v1/speech/transcribe', audio_request(language='de'))
        async with await self.connect(password=self.invite['pairingToken']) as client:
            denied = await self.rpc(client, 'unrabbit-api', payload)
            self.assertEqual(403, denied['status'])
        self.assertEqual([], engine.calls)
        await self.enroll()
        async with await self.connect(key=self.device) as client:
            result = await self.rpc(client, 'unrabbit-api', payload)
            self.assertEqual(200, result['status'])
            self.assertEqual({'text': 'Recognized words', 'language': 'de'}, result['body'])
            health = await self.rpc(client, 'unrabbit-api', self.request())
            self.assertTrue(health['body']['speech']['ready'])
        self.assertEqual(0, self.store.db.execute('SELECT count(*) FROM jobs').fetchone()[0])


class RuntimeTests(unittest.IsolatedAsyncioTestCase):
    async def test_wizard_runtime_is_live_before_qr_and_cleans_up_on_stop(self):
        import socket
        from unrabbit_vm.ssh_service import serve
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            registry = Registry(root / 'state')
            with socket.socket() as probe:
                probe.bind(('127.0.0.1', 0))
                port = probe.getsockname()[1]
            connection = dict(type='unrabbit.setup', version=2, host='127.0.0.1', port=port,
                user='device', hostKeyFingerprint=registry.host_key.get_fingerprint(), serverId=registry.server_id)
            config = dict(agent='demo', state=str(root / 'state'), workspace=str(root / 'workspace'), connection=connection)
            class Terminal:
                tty = False
                ready = asyncio.Event()
                paired = asyncio.Event()
                invitation = None
                def step(self, *_): pass
                def line(self, text):
                    if 'Verbunden.' in text: self.paired.set()
                async def live_qr(self, qr, png):
                    self.invitation = json.loads(b''.join(part.data for part in qr.data_list))
                    self.ready.set()
            terminal = Terminal()
            task = asyncio.create_task(serve(config, terminal, root / 'config/vm.json'))
            key = asyncssh.generate_private_key('ecdsa-sha2-nistp256')
            key.set_comment('phone')
            options = dict(username='device', known_hosts=([registry.host_key.convert_to_public()], [], []),
                           config=None, agent_path=None)
            try:
                await asyncio.wait_for(terminal.ready.wait(), 5)
                png = root / 'config/vm.qr.png'
                self.assertTrue(png.exists())
                async with await asyncssh.connect('127.0.0.1', port, client_keys=[], password=terminal.invitation['pairingToken'], **options) as client:
                    result = await client.run('unrabbit-pair', input=json.dumps({'serverId':registry.server_id,'publicKey':key.export_public_key().decode()}), timeout=5)
                    self.assertEqual(200, json.loads(result.stdout)['status'])
                async with await asyncssh.connect('127.0.0.1', port, client_keys=[key], **options) as client:
                    result = await client.run('unrabbit-api', input=json.dumps(dict(serverId=registry.server_id, method='GET',path='/v1/health',body=None)), timeout=5)
                    self.assertEqual(200, json.loads(result.stdout)['status'])
                    await asyncio.wait_for(terminal.paired.wait(), 5)
                    self.assertFalse(png.exists())
                    # Keep the device authenticated and idle while shutting down.
                    task.cancel()
                    done, _ = await asyncio.wait({task}, timeout=3)
                    self.assertIn(task, done, 'Shutdown hung on an accepted SSH connection')
                    with self.assertRaises(asyncio.CancelledError): await task
            finally:
                task.cancel()
                with self.assertRaises(asyncio.CancelledError): await task
            self.assertFalse((root / 'config/vm.qr.png').exists())

    async def test_resizing_prompt_is_cancellable_without_a_blocked_reader_thread(self):
        import io
        import os
        from unittest.mock import patch
        from unrabbit_vm.wizard import Terminal
        from unrabbit_vm.setup_qr import make_qr
        class Utf8Output(io.StringIO):
            encoding = 'utf-8'
        read_fd, write_fd = os.pipe()
        try:
            with os.fdopen(read_fd) as source:
                terminal = Terminal(source, Utf8Output())
                terminal.tty = True
                with patch('shutil.get_terminal_size', return_value=os.terminal_size((10, 10))):
                    task = asyncio.create_task(terminal.live_qr(make_qr({'fixture':True}), Path('test.png')))
                    await asyncio.sleep(0)
                    self.assertFalse(task.done())
                    task.cancel()
                    with self.assertRaises(asyncio.CancelledError): await task
        finally:
            os.close(write_fd)


class ShutdownTests(unittest.TestCase):
    def test_sigterm_stops_agent_process_and_preserves_interrupted_job(self):
        import os
        import socket
        import subprocess
        import sys
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            registry = Registry(root / 'state')
            store = Store(root / 'state/unrabbit.sqlite3')
            job_id = str(uuid.uuid4())
            store.submit(dict(id=job_id, text='shutdown test', photos=[]))
            store.close()
            fake = root / 'codex-fixture'
            marker = root / 'agent.pid'
            fake.write_text(f'#!/usr/bin/env python3\nimport os,pathlib,time\npathlib.Path({str(marker)!r}).write_text(str(os.getpid()))\ntime.sleep(60)\n')
            fake.chmod(0o700)
            with socket.socket() as probe:
                probe.bind(('127.0.0.1', 0))
                port = probe.getsockname()[1]
            config = dict(agent='codex', executable=str(fake), state=str(root / 'state'),
                          workspace=str(root / 'workspace'), connection=dict(user='device', port=port,
                          serverId=registry.server_id, hostKeyFingerprint=registry.host_key.get_fingerprint()))
            code = 'import json,sys; from unrabbit_vm.ssh_service import run; run(json.loads(sys.argv[1]))'
            process = subprocess.Popen([sys.executable, '-c', code, json.dumps(config)],
                env={**os.environ, 'PYTHONPATH':str(Path(__file__).resolve().parents[1])},
                stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            try:
                deadline = time.monotonic() + 10
                while not marker.exists() and process.poll() is None and time.monotonic() < deadline:
                    time.sleep(.02)
                self.assertTrue(marker.exists(), 'Agent never started')
                agent_pid = int(marker.read_text())
                process.terminate()
                _, errors = process.communicate(timeout=10)
                self.assertEqual(0, process.returncode, errors.decode())
                with self.assertRaises(ProcessLookupError): os.kill(agent_pid, 0)
                restored = Store(root / 'state/unrabbit.sqlite3')
                try: self.assertEqual('interrupted', restored.get(job_id)['state'])
                finally: restored.close()
            finally:
                if process.poll() is None: process.kill()
                process.communicate(timeout=5)


if __name__ == '__main__':
    unittest.main()
