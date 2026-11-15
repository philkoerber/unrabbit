import base64
import http.client
import json
from pathlib import Path
import tempfile
import threading
import time
import tomllib
import unittest
import uuid

from unrabbit_vm.agents import Cancelled, CodexAgent, DemoAgent
from unrabbit_vm.server import Server, Worker
from unrabbit_vm.store import MAX_BODY_BYTES, Problem, Store


def request(text="Eine Idee", photos=None):
    return {"id": str(uuid.uuid4()), "text": text, "photos": photos or []}


def photo():
    return {"id": str(uuid.uuid4()), "width": 32, "height": 24,
            "jpeg": base64.b64encode(b"\xff\xd8synthetic-test-photo\xff\xd9").decode()}


class StoreTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / "state.sqlite3"
        self.store = Store(self.path)

    def tearDown(self):
        self.store.close(); self.tmp.cleanup()

    def test_duplicate_is_idempotent_but_changed_payload_conflicts(self):
        payload = request(photos=[photo()])
        self.store.submit(payload)
        self.store.claim(); self.store.finish(payload["id"], answer="Antwort")
        for _ in range(3):
            self.assertEqual("completed", self.store.submit(payload)["state"])
        self.assertEqual(1, self.store.db.execute("SELECT count(*) FROM jobs").fetchone()[0])
        self.assertEqual(1, self.store.db.execute("SELECT count(*) FROM photos").fetchone()[0])
        with self.assertRaises(Problem) as error:
            self.store.submit({**payload, "text": "Andere Aufgabe"})
        self.assertEqual(409, error.exception.status)

    def test_validation_has_no_partial_job_or_photo(self):
        first = photo()
        for photos in [[first, first], [first, {**photo(), "jpeg": "invalid"}], [{**first, "width": True}]]:
            with self.assertRaises(Problem):
                self.store.submit(request(photos=photos))
        with self.assertRaises(Problem):
            self.store.submit({**request(), "command": "ignored?"})
        self.assertEqual(0, self.store.db.execute("SELECT count(*) FROM jobs").fetchone()[0])

    def test_photo_bytes_and_order_survive_database_restart(self):
        payload = request(photos=[photo(), photo()])
        self.store.submit(payload)
        self.store.close(); self.store = Store(self.path)
        job = self.store.claim()
        self.assertEqual([p["id"] for p in payload["photos"]], [p["id"] for p in job["photos"]])
        self.assertEqual(base64.b64decode(payload["photos"][0]["jpeg"]), job["photos"][0]["data"])

    def test_cancel_before_submission_leaves_tombstone_and_late_results_cannot_win(self):
        payload = request()
        self.store.cancel(payload["id"])
        self.assertEqual("cancelled", self.store.submit(payload)["state"])
        self.assertIsNone(self.store.claim())
        other = request(); self.store.submit(other); self.store.claim()
        self.store.cancel(other["id"]); self.store.finish(other["id"], answer="Too late")
        self.assertEqual("cancelled", self.store.get(other["id"])["state"])
        self.assertIsNone(self.store.get(other["id"])["answer"])

    def test_recovery_keeps_completed_and_queued_but_never_replays_running(self):
        done, running, queued = request(), request(), request()
        self.store.submit(done); self.store.claim(); self.store.finish(done["id"], answer="Done")
        self.store.submit(running); self.store.claim(); self.store.submit(queued)
        self.store.close(); self.store = Store(self.path); self.store.recover()
        self.assertEqual("completed", self.store.get(done["id"])["state"])
        self.assertEqual("interrupted", self.store.get(running["id"])["state"])
        self.assertEqual(queued["id"], self.store.claim()["id"])

    def test_server_context_is_bounded_and_excludes_cancelled_and_future_jobs(self):
        for text in ["first", "second", "third"]:
            payload = request(text); self.store.submit(payload); self.store.claim()
            self.store.finish(payload["id"], answer="answer")
        pending = request("not yet"); self.store.submit(pending)
        current = self.store.claim()
        self.assertEqual(["second", "third"], [x["user"] for x in self.store.context(current["seq"], budget=23)])
        self.assertEqual(4, self.store.db.execute("SELECT count(*) FROM jobs").fetchone()[0])


class ServiceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = Store(Path(self.tmp.name) / "state.sqlite3")
        self.worker = Worker(self.store, DemoAgent(.15))
        self.worker.start()
        self.server = Server(("127.0.0.1", 0), self.store, self.worker, "test-token", "test-server")
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join()
        self.worker.close(); self.store.close(); self.tmp.cleanup()

    def call(self, method, path, body=None, **overrides):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=3)
        headers = {"Authorization": "Bearer test-token", "X-Unrabbit-Server": "test-server", "Content-Type": "application/json"}
        headers.update(overrides)
        connection.request(method, path, json.dumps(body) if body is not None else None, headers)
        response = connection.getresponse()
        result = response.status, json.loads(response.read())
        connection.close()
        return result

    def wait_for(self, job_id, state):
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            result = self.call("GET", f"/v1/jobs/{job_id}")[1]
            if result["state"] == state:
                return result
            time.sleep(.02)
        self.fail(f"Job did not reach {state}")

    def test_health_requires_auth_and_expected_server_and_rejects_browser_origin(self):
        self.assertEqual(401, self.call("GET", "/v1/health", Authorization="Bearer wrong")[0])
        self.assertEqual(409, self.call("GET", "/v1/health", **{"X-Unrabbit-Server": "other"})[0])
        self.assertEqual(400, self.call("GET", "/v1/health", Origin="http://example.net")[0])
        self.assertEqual("demo", self.call("GET", "/v1/health")[1]["agent"])

    def test_real_http_round_trip_returns_result_after_client_disconnect(self):
        payload = request(photos=[photo()])
        self.assertEqual(200, self.call("POST", "/v1/jobs", payload)[0])
        result = self.wait_for(payload["id"], "completed")
        self.assertIn("1 Bild", result["answer"])
        self.assertEqual(payload["photos"][0]["id"], result["photos"][0]["id"])
        self.assertEqual(result, self.call("POST", "/v1/jobs", payload)[1])

    def test_running_cancellation_is_terminal(self):
        self.worker.agent.delay = 2
        payload = request(); self.call("POST", "/v1/jobs", payload)
        self.wait_for(payload["id"], "running")
        self.assertEqual("cancelled", self.call("POST", f"/v1/jobs/{payload['id']}/cancel", {})[1]["state"])
        time.sleep(.2)
        self.assertEqual("cancelled", self.call("GET", f"/v1/jobs/{payload['id']}")[1]["state"])

    def test_bounded_body_and_unknown_routes(self):
        self.assertEqual(413, self.call("POST", "/v1/jobs", {}, **{"Content-Length": str(MAX_BODY_BYTES + 1)})[0])
        self.assertEqual(404, self.call("GET", "/arbitrary-command")[0])
        self.assertEqual(400, self.call("GET", "/v1/jobs/../../etc/passwd")[0])

    def test_agent_failure_becomes_durable_without_leaking_diagnostics(self):
        class Broken:
            name = "broken"
            def run(self, *_):
                raise RuntimeError("sensitive upstream diagnostic")
        self.worker.agent = Broken()
        payload = request(); self.call("POST", "/v1/jobs", payload)
        result = self.wait_for(payload["id"], "failed")
        self.assertNotIn("sensitive", result["error"])
        self.assertIsNone(result["answer"])


class CodexTests(unittest.TestCase):
    def test_cli_contract_uses_stdin_pinned_workspace_images_and_no_user_config(self):
        with tempfile.TemporaryDirectory() as tmp:
            fake = Path(tmp) / "fake-codex"
            fake.write_text("#!/usr/bin/env python3\n" + '''import json, pathlib, sys
args = sys.argv[1:]
assert args[:2] == ["exec", "--ignore-user-config"]
assert "--sandbox" not in args and "--strict-config" in args
pathlib.Path(__file__).with_name("argv.json").write_text(json.dumps(args))
assert "--ephemeral" in args and "--image" in args
assert args[-1] == "-"
assert '"previous_turns"' in sys.stdin.read()
pathlib.Path(args[args.index("--output-last-message") + 1]).write_text("Antwort vom Adapter")
''')
            fake.chmod(0o700)
            agent = CodexAgent(Path(tmp) / "workspace", str(fake), private_state=Path(tmp) / "state")
            job = {"text": "$(do not execute); hello", "photos": [{"data": b"synthetic"}]}
            self.assertEqual("Antwort vom Adapter", agent.run(job, [], lambda: False))
            args = json.loads((Path(tmp) / 'argv.json').read_text())
            config = tomllib.loads('\n'.join(args[i + 1] for i, arg in enumerate(args) if arg == '-c'))
            self.assertEqual('unrabbit', config['default_permissions'])
            rules = config['permissions']['unrabbit']['filesystem']
            self.assertEqual('deny', rules[':root'])
            self.assertEqual('read', rules[str((Path(tmp) / 'workspace').resolve())])
            self.assertEqual('deny', rules[str((Path(tmp) / 'state').resolve())])
            self.assertEqual('deny', rules[str((Path.home() / '.ssh').resolve())])
            self.assertFalse(config['permissions']['unrabbit']['network']['enabled'])
            self.assertEqual('never', config['approval_policy'])

    def test_cancellation_and_timeout_stop_the_cli(self):
        with tempfile.TemporaryDirectory() as tmp:
            fake = Path(tmp) / "fake-codex"
            fake.write_text("#!/usr/bin/env python3\nimport time\ntime.sleep(30)\n")
            fake.chmod(0o700)
            agent = CodexAgent(Path(tmp) / "workspace", str(fake), timeout=.2)
            with self.assertRaises(Cancelled):
                agent.run({"text": "cancel", "photos": []}, [], lambda: True)
            with self.assertRaises(RuntimeError):
                agent.run({"text": "timeout", "photos": []}, [], lambda: False)


if __name__ == "__main__":
    unittest.main()
