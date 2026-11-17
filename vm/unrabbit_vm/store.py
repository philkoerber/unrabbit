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
PHOTO_RETENTION_SECONDS = 7 * 24 * 60 * 60
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
        if not isinstance(photo, dict) or set(photo) not in ({"id", "width", "height", "jpeg"},
                                                            {"id", "width", "height", "jpeg", "capturedAt"}):
            raise Problem(400, "Invalid photo fields")
        if "capturedAt" in photo and (type(photo["capturedAt"]) is not int or not 0 < photo["capturedAt"] < 2**63):
            raise Problem(400, "Invalid photo capture time")
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
        if "capturedAt" in photo:
            photos[-1]["capturedAt"] = photo["capturedAt"]
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
            PRAGMA secure_delete=ON;
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
        # Existing photos age from their original job, never from the migration/restart.
        with self.db:
            columns = {r[1] for r in self.db.execute("PRAGMA table_info(photos)")}
            if "expires" not in columns:
                self.db.execute("ALTER TABLE photos ADD COLUMN expires REAL NOT NULL DEFAULT 0")
                self.db.execute("UPDATE photos SET expires=(SELECT created FROM jobs WHERE jobs.id=photos.job)+?",
                                (PHOTO_RETENTION_SECONDS,))
            self.db.execute("CREATE INDEX IF NOT EXISTS photos_expiry ON photos(expires)")
        self.prune_photos()

    def prune_photos(self):
        """Remove bytes, retaining references and job digests for history/idempotent retries."""
        with self.lock:
            now = time.time()
            with self.db:
                removed = self.db.execute("UPDATE photos SET data=X'' WHERE expires<=? AND length(data)>0", (now,)).rowcount
                self.db.execute("""UPDATE jobs SET state='failed', error='Photo deleted after seven days. Take a new photo.'
                    WHERE state='queued' AND id IN (SELECT job FROM photos WHERE expires<=?)""", (now,))
            if removed:
                # secure_delete clears SQLite pages; checkpoint also removes old JPEGs from the WAL.
                self.db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            return removed

    def recover(self):
        # Never replay potentially side-effectful work after an uncertain process exit.
        with self.lock, self.db:
            self.db.execute("UPDATE jobs SET state='interrupted', error=? WHERE state='running'",
                            ("Service restarted. The active request was not retried.",))

    def submit(self, payload):
        text, photos, digest = validate(payload)
        with self.lock, self.db:
            previous = self.db.execute("SELECT * FROM jobs WHERE id=?", (payload["id"],)).fetchone()
            if previous:
                if previous["digest"] is not None and previous["digest"] != digest:
                    raise Problem(409, "Request ID already belongs to different content")
            else:
                now = time.time()
                for photo in photos:
                    captured = min(photo.get("capturedAt", now * 1000) / 1000, now)
                    photo["expires"] = captured + PHOTO_RETENTION_SECONDS
                    if photo["expires"] <= now:
                        raise Problem(410, "Photo deleted after seven days. Take a new photo.")
                pending = self.db.execute("SELECT count(*) FROM jobs WHERE state IN ('queued','running')").fetchone()[0]
                if pending >= 32:
                    raise Problem(429, "Work queue is full")
                self.db.execute("INSERT INTO jobs(id,digest,text,state,created) VALUES(?,?,?,'queued',?)",
                                (payload["id"], digest, text, now))
                for index, photo in enumerate(photos):
                    self.db.execute("INSERT INTO photos(job,position,id,width,height,sha256,data,expires) VALUES(?,?,?,?,?,?,?,?)",
                                    (payload["id"], index, photo["id"], photo["width"], photo["height"],
                                     photo["sha256"], photo["data"], photo["expires"]))
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
        self.prune_photos() # The worker calls this even when idle; restart also prunes immediately.
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

    def context(self, before_seq, budget=24000, photo_limit=MAX_PHOTOS):
        """Bounded recent completed turns, oldest first. Originals remain in SQLite.

        This is a context window, not semantic summarization or project routing.
        """
        self.prune_photos()
        with self.lock:
            rows = self.db.execute("SELECT id,text,answer FROM jobs WHERE seq<? AND state='completed' ORDER BY seq DESC LIMIT 20",
                                   (before_seq,)).fetchall()
            result = []
            remaining = min(MAX_PHOTOS, max(0, photo_limit))
            for row in rows:
                cost = len(row["text"]) + len(row["answer"])
                if cost > budget:
                    break
                turn = {"user": row["text"], "assistant": row["answer"]}
                count = self.db.execute("SELECT count(*) FROM photos WHERE job=?", (row["id"],)).fetchone()[0]
                photos = [dict(p) for p in self.db.execute("""SELECT id,data,expires FROM photos
                    WHERE job=? AND expires>? AND length(data)>0 ORDER BY position LIMIT ?""",
                    (row["id"], time.time(), remaining))]
                if count:
                    turn.update(photos=photos, unavailable_photos=count - len(photos))
                remaining -= len(photos)
                result.append(turn)
                budget -= cost
        return result[::-1]

    def close(self):
        self.db.close()
