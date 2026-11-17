import base64
import json
from pathlib import Path
import sqlite3
import tempfile
import time
import unittest
from unittest.mock import patch

from unrabbit_vm.agents import CodexAgent
from unrabbit_vm.store import PHOTO_RETENTION_SECONDS, Problem, Store
from test_service import photo, request


class PhotoRetentionTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.store = Store(self.root / 'jobs.sqlite3')
        self.now = time.time()

    def tearDown(self):
        self.store.close()
        self.tmp.cleanup()

    def completed(self, photos, text='What is in this photo?'):
        payload = request(text, photos)
        with patch('unrabbit_vm.store.time.time', return_value=self.now):
            self.store.submit(payload)
            job = self.store.claim()
            self.store.finish(job['id'], answer='A painted character.')
        return payload, job

    def test_follow_up_really_attaches_previous_photo_and_maps_it_to_its_turn(self):
        first, original = self.completed([photo()])
        self.store.submit(request('Who is that character?'))
        follow_up = self.store.claim()
        context = self.store.context(follow_up['seq'])
        self.assertEqual([], follow_up['photos'])
        self.assertEqual(original['photos'][0]['data'], context[-1]['photos'][0]['data'])
        fake = self.root / 'codex'
        fake.write_text('''#!/usr/bin/env python3
import json, pathlib, sys
args = sys.argv[1:]
raw = sys.stdin.read()
prompt = json.loads(raw[raw.index('{'):])
assert prompt['previous_turns'][-1]['images'] == [1]
assert prompt['images'] == []
images = [pathlib.Path(args[i+1]) for i, arg in enumerate(args) if arg == '--image']
assert len(images) == 1
assert images[0].read_bytes() == b'\\xff\\xd8synthetic-test-photo\\xff\\xd9'
pathlib.Path(__file__).with_name('temporary-image.txt').write_text(str(images[0]))
pathlib.Path(args[args.index('--output-last-message')+1]).write_text('The previous image is available.')
''')
        fake.chmod(0o700)
        agent = CodexAgent(self.root / 'workspace', str(fake))
        self.assertEqual('The previous image is available.', agent.run(follow_up, context, lambda: False))
        self.assertFalse(Path((self.root / 'temporary-image.txt').read_text()).exists())

    def test_expiry_clears_bytes_and_wal_but_preserves_text_and_retry_identity(self):
        payload, job = self.completed([photo()])
        with patch('unrabbit_vm.store.time.time', return_value=self.now + PHOTO_RETENTION_SECONDS - .01):
            self.assertEqual(0, self.store.prune_photos())
            self.assertEqual(1, len(self.store.context(job['seq'] + 1)[0]['photos']))
        with patch('unrabbit_vm.store.time.time', return_value=self.now + PHOTO_RETENTION_SECONDS):
            self.assertEqual(1, self.store.prune_photos())
            context = self.store.context(job['seq'] + 1)[0]
            self.assertEqual([], context['photos'])
            self.assertEqual(1, context['unavailable_photos'])
            self.assertEqual(payload['text'], context['user'])
            self.assertEqual('completed', self.store.submit(payload)['state'])
            self.assertEqual(0, self.store.db.execute('SELECT length(data) FROM photos').fetchone()[0])
            self.assertEqual(0, self.store.prune_photos())
        for path in self.root.glob('jobs.sqlite3*'):
            self.assertNotIn(b'synthetic-test-photo', path.read_bytes())

    def test_capture_age_is_not_reset_by_upload_and_future_clock_cannot_extend_retention(self):
        captured = self.now - PHOTO_RETENTION_SECONDS + 60
        _, job = self.completed([{**photo(), 'capturedAt': int(captured * 1000)}])
        self.assertAlmostEqual(captured + PHOTO_RETENTION_SECONDS, job['photos'][0]['expires'], places=2)
        _, future = self.completed([{**photo(), 'capturedAt': int((self.now + 86400) * 1000)}])
        self.assertEqual(self.now + PHOTO_RETENTION_SECONDS, future['photos'][0]['expires'])
        with patch('unrabbit_vm.store.time.time', return_value=self.now):
            for stamp in (int((self.now - PHOTO_RETENTION_SECONDS - 1) * 1000), True, -1):
                with self.assertRaises(Problem):
                    self.store.submit(request(photos=[{**photo(), 'capturedAt': stamp}]))
        self.assertEqual(2, self.store.db.execute('SELECT count(*) FROM jobs').fetchone()[0])

    def test_queued_image_expiry_fails_the_job_without_silently_removing_its_attachment(self):
        with patch('unrabbit_vm.store.time.time', return_value=self.now):
            payload = request(photos=[photo()])
            self.store.submit(payload)
        with patch('unrabbit_vm.store.time.time', return_value=self.now + PHOTO_RETENTION_SECONDS):
            self.assertIsNone(self.store.claim())
            self.assertEqual('failed', self.store.get(payload['id'])['state'])
            self.assertIn('seven days', self.store.get(payload['id'])['error'])

    def test_context_prioritizes_recent_photos_and_reserves_room_for_current_attachments(self):
        _, first = self.completed([photo(), photo(), photo()])
        _, second = self.completed([photo(), photo(), photo()])
        context = self.store.context(second['seq'] + 1)
        self.assertEqual([1, 3], [len(turn['photos']) for turn in context])
        self.assertEqual(second['photos'][0]['id'], context[-1]['photos'][0]['id'])
        context = self.store.context(second['seq'] + 1, photo_limit=1)
        self.assertEqual([0, 1], [len(turn['photos']) for turn in context])
        self.assertEqual([3, 2], [turn['unavailable_photos'] for turn in context])
        self.assertEqual([], self.store.context(second['seq'] + 1, budget=1))

    def test_legacy_database_migration_uses_original_job_age_and_is_repeatable(self):
        path = self.root / 'legacy.sqlite3'
        db = sqlite3.connect(path)
        db.executescript('''CREATE TABLE jobs (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL,
            digest TEXT, text TEXT NOT NULL, state TEXT NOT NULL, answer TEXT, error TEXT, created REAL NOT NULL);
            CREATE TABLE photos (job TEXT NOT NULL REFERENCES jobs(id), position INTEGER NOT NULL, id TEXT NOT NULL,
            width INTEGER NOT NULL, height INTEGER NOT NULL, sha256 TEXT NOT NULL, data BLOB NOT NULL, PRIMARY KEY(job,id));''')
        for index, age in enumerate((PHOTO_RETENTION_SECONDS + 1, 60)):
            payload = request(photos=[photo()])
            db.execute("INSERT INTO jobs(id,text,state,answer,created) VALUES(?,?,'completed','description',?)",
                       (payload['id'], payload['text'], self.now - age))
            p = payload['photos'][0]
            db.execute('INSERT INTO photos VALUES(?,?,?,?,?,?,?)',
                       (payload['id'], 0, p['id'], 32, 24, 'hash', base64.b64decode(p['jpeg'])))
        db.commit(); db.close()
        for _ in range(2):
            migrated = Store(path)
            try:
                sizes = [row[0] for row in migrated.db.execute('SELECT length(data) FROM photos ORDER BY rowid')]
                self.assertEqual(0, sizes[0]); self.assertGreater(sizes[1], 0)
                self.assertEqual(2, migrated.db.execute('SELECT count(*) FROM jobs').fetchone()[0])
            finally:
                migrated.close()


if __name__ == '__main__':
    unittest.main()
