"""Authenticated local HTTP boundary; use loopback or the development container only."""
import hmac
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading
from socketserver import TCPServer

from .agents import Cancelled
from .store import MAX_BODY_BYTES, Problem, identifier
from .speech import SpeechService
from .tts import TtsService


class Worker:
    def __init__(self, store, agent):
        self.store, self.agent = store, agent
        self.stop = threading.Event()
        self.wake = threading.Event()
        self.thread = threading.Thread(target=self.run, name="unrabbit-worker", daemon=True)

    def start(self):
        self.store.recover()
        self.thread.start()

    def run(self):
        while not self.stop.is_set():
            self.wake.clear()
            job = self.store.claim()
            if job is None:
                self.wake.wait(5)
                continue
            try:
                cancelled = lambda: self.stop.is_set() or self.store.get(job["id"])["state"] == "cancelled"
                if cancelled():
                    raise Cancelled()
                answer = self.agent.run(job, self.store.context(job["seq"], photo_limit=4 - len(job["photos"])), cancelled)
                self.store.finish(job["id"], answer=answer)
            except Cancelled:
                self.store.finish(job["id"], error="Service stopped. The request was not retried.", interrupted=True)
            except Exception:
                self.store.finish(job["id"], error="Agent request failed. Check the server configuration.")

    def close(self):
        self.stop.set(); self.wake.set()
        self.thread.join(timeout=10)
        if self.thread.is_alive():
            raise RuntimeError("Worker did not stop")


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def server_bind(self):
        # This API never uses a reverse-DNS server name. Binding must not wait for DNS.
        TCPServer.server_bind(self)
        self.server_name, self.server_port = self.server_address[:2]

    def __init__(self, address, store, worker, token, server_id, speech=None, tts=None):
        self.store, self.worker, self.token, self.server_id = store, worker, token, server_id
        self.speech = speech or SpeechService()
        self.tts = tts or TtsService()
        super().__init__(address, Handler)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass  # No request content, credentials or photo bytes in access logs.

    def setup(self):
        super().setup()
        self.connection.settimeout(15)

    def do_GET(self):
        self.dispatch()

    def do_POST(self):
        self.dispatch()

    def dispatch(self):
        try:
            if self.headers.get("Origin") or self.headers.get("Transfer-Encoding"):
                raise Problem(400, "Unsupported request")
            supplied = self.headers.get("Authorization", "")
            if not hmac.compare_digest(supplied, "Bearer " + self.server.token):
                raise Problem(401, "Authentication required")
            if self.headers.get("X-Unrabbit-Server") != self.server.server_id:
                raise Problem(409, "Wrong server identity")
            if self.command == "GET" and self.path == "/v1/health":
                result = {"version": 1, "agent": self.server.worker.agent.name, "serverId": self.server.server_id,
                          "speech": self.server.speech.status, "tts": self.server.tts.status}
            elif self.command == "POST" and self.path == "/v1/speech/transcribe":
                result = self.server.speech.transcribe(self.body())
            elif self.command == "POST" and self.path == "/v1/speech/synthesize":
                result = self.server.tts.synthesize(self.body())
            elif self.command == "POST" and self.path == "/v1/jobs":
                result = self.server.store.submit(self.body())
                self.server.worker.wake.set()
            elif self.path.startswith("/v1/jobs/"):
                parts = self.path.removeprefix("/v1/jobs/").split("/")
                job_id = identifier(parts[0])
                if len(parts) == 1 and self.command == "GET":
                    result = self.server.store.get(job_id)
                elif len(parts) == 2 and parts[1] == "cancel" and self.command == "POST":
                    if self.body() != {}:
                        raise Problem(400, "Cancellation body must be empty")
                    result = self.server.store.cancel(job_id)
                else:
                    raise Problem(404, "Unknown endpoint")
            else:
                raise Problem(404, "Unknown endpoint")
            self.respond(200, result)
        except Problem as error:
            self.respond(error.status, {"error": error.message})
        except (ValueError, UnicodeError, TimeoutError):
            self.respond(400, {"error": "Invalid request"})
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception:
            self.respond(500, {"error": "Internal service error"})

    def body(self):
        if self.headers.get_content_type() != "application/json":
            raise Problem(415, "Expected application/json")
        length = int(self.headers.get("Content-Length", "0"))
        if not 0 < length <= MAX_BODY_BYTES:
            raise Problem(413, "Request body is empty or too large")
        raw = self.rfile.read(length)
        if len(raw) != length:
            raise Problem(400, "Incomplete body")
        def unique(pairs):
            result = {}
            for key, value in pairs:
                if key in result:
                    raise ValueError("Duplicate field")
                result[key] = value
            return result
        return json.loads(raw, object_pairs_hook=unique)

    def respond(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)
