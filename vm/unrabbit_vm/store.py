"""Durable, idempotent jobs. The server, not the device, owns conversation context."""
import base64
import hashlib
import json
import sqlite3
import threading
import time
import uuid
from pathlib import Path

MAX_PHOTOS = 4
MAX_PHOTO_BYTES = 4 * 1024 * 1024
MAX_BODY_BYTES = 24 * 1024 * 1024
TERMINAL = {"completed", "failed", "cancelled", "interrupted"}


class Problem(Exception):
    def __init__(self, status, message):
        self.status, self.message = status, message
        super().__init__(message)


def identifier(value):
    if not isinstance(value, str):
        raise Problem(400, "Expected a UUID")
    try:
        if str(uuid.UUID(value)) == value:
            return value
    except ValueError:
        pass
    raise Problem(400, "Expected a canonical UUID")


def validate(payload):
    if not isinstance(payload, dict) or set(payload) != {"id", "text", "photos"}:
        raise Problem(400, "Expected id, text and photos only")
    identifier(payload["id"])
    text = payload["text"]
    if not isinstance(text, str) or not 1 <= len(text.strip()) <= 16000:
        raise Problem(400, "Text must contain 1–16000 characters")
    if not isinstance(payload["photos"], list) or len(payload["photos"]) > MAX_PHOTOS:
        raise Problem(400, "At most four photos per request")
    photos, seen = [], set()
    for photo in payload["photos"]:
        if not isinstance(photo, dict) or set(photo) != {"id", "width", "height", "jpeg"}:
            raise Problem(400, "Invalid photo fields")
        identifier(photo["id"])
        if photo["id"] in seen:
            raise Problem(400, "Duplicate photo ID")
        seen.add(photo["id"])
        if any(type(photo[k]) is not int or not 1 <= photo[k] <= 2048 for k in ("width", "height")):
            raise Problem(400, "Invalid photo dimensions")
        try:
            if not isinstance(photo["jpeg"], str) or len(photo["jpeg"]) > 4 * ((MAX_PHOTO_BYTES + 2) // 3):
                raise ValueError()
            data = base64.b64decode(photo["jpeg"], validate=True)
        except (ValueError, TypeError):
            raise Problem(400, "Invalid photo encoding") from None
        if not 4 <= len(data) <= MAX_PHOTO_BYTES or not data.startswith(b"\xff\xd8") or not data.endswith(b"\xff\xd9"):
            raise Problem(400, "Expected a bounded JPEG")
        photos.append({"id": photo["id"], "width": photo["width"], "height": photo["height"],
                       "sha256": hashlib.sha256(data).hexdigest(), "data": data})
    normalized = {"text": text.strip(), "photos": [{k: v for k, v in p.items() if k != "data"} for p in photos]}
    digest = hashlib.sha256(json.dumps(normalized, sort_keys=True).encode()).hexdigest()
    return text.strip(), photos, digest


class Store:
    def __init__(self, path):
        self.lock = threading.RLock()
        self.db = sqlite3.connect(str(path), check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.executescript("""
            PRAGMA journal_mode=WAL;
            PRAGMA foreign_keys=ON;
            CREATE TABLE IF NOT EXISTS jobs (
                seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL,
                digest TEXT, text TEXT NOT NULL, state TEXT NOT NULL,
                answer TEXT, error TEXT, created REAL NOT NULL
            );
            CREATE TABLE IF NOT EXISTS photos (
                job TEXT NOT NULL REFERENCES jobs(id), position INTEGER NOT NULL,
                id TEXT NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL,
                sha256 TEXT NOT NULL, data BLOB NOT NULL, PRIMARY KEY(job, id)
            );
        """)

    def recover(self):
        # Never replay potentially side-effectful work after an uncertain process exit.
        with self.lock, self.db:
            self.db.execute("UPDATE jobs SET state='interrupted', error=? WHERE state='running'",
                            ("Dienst neu gestartet. Laufender Auftrag wurde nicht wiederholt.",))

    def submit(self, payload):
        text, photos, digest = validate(payload)
        with self.lock, self.db:
            previous = self.db.execute("SELECT * FROM jobs WHERE id=?", (payload["id"],)).fetchone()
            if previous:
                if previous["digest"] is not None and previous["digest"] != digest:
                    raise Problem(409, "Request ID already belongs to different content")
            else:
                pending = self.db.execute("SELECT count(*) FROM jobs WHERE state IN ('queued','running')").fetchone()[0]
                if pending >= 32:
                    raise Problem(429, "Work queue is full")
                self.db.execute("INSERT INTO jobs(id,digest,text,state,created) VALUES(?,?,?,'queued',?)",
                                (payload["id"], digest, text, time.time()))
                for index, photo in enumerate(photos):
                    self.db.execute("INSERT INTO photos VALUES(?,?,?,?,?,?,?)",
                                    (payload["id"], index, photo["id"], photo["width"], photo["height"],
                                     photo["sha256"], photo["data"]))
            return self.get(payload["id"])

    def get(self, job_id):
        identifier(job_id)
        with self.lock:
            row = self.db.execute("SELECT id,state,answer,error FROM jobs WHERE id=?", (job_id,)).fetchone()
            if row is None:
                raise Problem(404, "Unknown request")
            photos = self.db.execute("SELECT id,width,height,sha256 FROM photos WHERE job=? ORDER BY position", (job_id,))
            return {**dict(row), "photos": [dict(p) for p in photos]}

    def cancel(self, job_id):
        identifier(job_id)
        with self.lock, self.db:
            # A tombstone also covers cancellation racing a delayed first submission.
            self.db.execute("INSERT OR IGNORE INTO jobs(id,text,state,created) VALUES(?,'','cancelled',?)", (job_id, time.time()))
            self.db.execute("UPDATE jobs SET state='cancelled' WHERE id=? AND state IN ('queued','running')", (job_id,))
            return self.get(job_id)

    def claim(self):
        with self.lock, self.db:
            row = self.db.execute("SELECT id,text,seq FROM jobs WHERE state='queued' ORDER BY seq LIMIT 1").fetchone()
            if row is None:
                return None
            self.db.execute("UPDATE jobs SET state='running' WHERE id=?", (row["id"],))
            photos = [dict(p) for p in self.db.execute("SELECT * FROM photos WHERE job=? ORDER BY position", (row["id"],))]
            return {**dict(row), "photos": photos}

    def finish(self, job_id, answer=None, error=None, interrupted=False):
        with self.lock, self.db:
            state = "interrupted" if interrupted else "failed" if error else "completed"
            self.db.execute("UPDATE jobs SET state=?, answer=?, error=? WHERE id=? AND state='running'",
                            (state, answer, error, job_id))

    def context(self, before_seq, budget=24000):
        """Bounded recent completed turns, oldest first. Originals remain in SQLite.

        This is a context window, not semantic summarization or project routing.
        """
        with self.lock:
            rows = self.db.execute("SELECT text,answer FROM jobs WHERE seq<? AND state='completed' ORDER BY seq DESC LIMIT 20",
                                   (before_seq,)).fetchall()
        result = []
        for row in rows:
            cost = len(row["text"]) + len(row["answer"])
            if cost > budget:
                break
            result.append({"user": row["text"], "assistant": row["answer"]})
            budget -= cost
        return result[::-1]

    def close(self):
        self.db.close()
