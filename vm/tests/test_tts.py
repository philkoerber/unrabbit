import base64
import threading
import unittest

from unrabbit_vm.tts import TtsService, validate_text
from unrabbit_vm.store import Problem


class TtsBackend:
    def __init__(self):
        self.calls = []
        self.audio = b'\x00\x00\x00\x18ftypM4A ' + b'\x00' * 24

    def synthesize(self, text, language):
        self.calls.append((text, language))
        return self.audio


class TtsTests(unittest.TestCase):
    def test_bounded_audio_language_and_missing_installation(self):
        backend = TtsBackend(); service = TtsService(backend)
        result = service.synthesize({'text': ' Guten Morgen! ', 'language': 'de'})
        self.assertEqual([('Guten Morgen!', 'de')], backend.calls)
        self.assertEqual(backend.audio, base64.b64decode(result['audio']))
        self.assertEqual('aac-mp4', result['format'])
        self.assertEqual('de', result['language'])
        self.assertTrue(service.status['ready'])
        self.assertFalse(TtsService().status['ready'])
        with self.assertRaises(Problem) as missing:
            TtsService().synthesize({'text': 'Hello.', 'language': 'en'})
        self.assertEqual(503, missing.exception.status)

    def test_invalid_requests_never_reach_engine(self):
        backend = TtsBackend(); service = TtsService(backend)
        body = {'text': 'Hello.', 'language': 'en'}
        for invalid in [None, [], {}, {**body, 'path': '/etc/passwd'}, {**body, 'language': 'auto'},
                        {**body, 'language': []}, {**body, 'text': 123}, {**body, 'text': ' '},
                        {**body, 'text': 'a' * 241}, {**body, 'text': '\x00private'},
                        {**body, 'text': '\ud800'}]:
            with self.subTest(value=repr(invalid)), self.assertRaises(Problem):
                service.synthesize(invalid)
        self.assertEqual([], backend.calls)
        self.assertEqual(('Hello.\nNext.', 'en'), validate_text({**body, 'text': 'Hello.\nNext.'}))

    def test_failure_is_sanitized_and_releases_synthesis_slot(self):
        entered = threading.Event(); release = threading.Event(); errors = []
        class Blocking:
            def synthesize(self, *_):
                entered.set(); release.wait(3)
                raise RuntimeError('private text or diagnostic')
        service = TtsService(Blocking()); body = {'text': 'Hello.', 'language': 'en'}
        def run():
            try: service.synthesize(body)
            except Problem as error: errors.append(error)
        thread = threading.Thread(target=run); thread.start()
        try:
            self.assertTrue(entered.wait(1))
            with self.assertRaises(Problem) as busy: service.synthesize(body)
            self.assertEqual(429, busy.exception.status)
        finally: release.set(); thread.join(3)
        self.assertEqual('Speech synthesis failed', errors[0].message)
        service.backend = TtsBackend()
        self.assertEqual('en', service.synthesize(body)['language'])
        for audio in [b'', b'private error text', b'\x00\x00\x00\x18ftyp' + b'x' * 262144, 'wrong type']:
            service.backend.audio = audio
            with self.assertRaises(Problem): service.synthesize(body)
