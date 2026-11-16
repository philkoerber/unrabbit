"""Loopback fixture for the Android SSH integration test; never calls a model."""
import asyncio
import json
from pathlib import Path
import sys
import tempfile
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from unrabbit_vm.agents import DemoAgent
from unrabbit_vm.server import Worker
from unrabbit_vm.ssh_service import Gateway, Registry
from unrabbit_vm.store import Store
from unrabbit_vm.speech import SpeechService

class FixtureSpeech:
    def transcribe(self, audio, language):
        return 'SSH speech fixture'

async def main():
    with tempfile.TemporaryDirectory() as tmp:
        registry = Registry(tmp)
        store = Store(Path(tmp) / 'jobs.sqlite3')
        worker = Worker(store, DemoAgent(.01))
        gateway = Gateway(registry, 'device', store, worker, SpeechService(FixtureSpeech()))
        listener = await gateway.listen('127.0.0.1', 0)
        worker.start()
        try:
            # Read by the parent test only; never included in test reports.
            print(json.dumps(registry.invite({'type':'unrabbit.setup','version':2,
                'host':'127.0.0.1','port':listener.get_port(),'user':'device',
                'hostKeyFingerprint':registry.host_key.get_fingerprint(),'serverId':registry.server_id})), flush=True)
            await asyncio.to_thread(sys.stdin.buffer.read, 1)
        finally:
            listener.close(); await listener.wait_closed()
            await gateway.close()
            worker.close(); store.close()

asyncio.run(main())
