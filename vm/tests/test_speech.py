import base64
import threading
import unittest

from unrabbit_vm.speech import SpeechService, validate_audio
from unrabbit_vm.store import Problem


def audio_request(frames=1, language='en'):
    # Structurally valid ADTS framing with a dummy payload; no codec/model needed for protocol tests.
    frame = bytes.fromhex('fff16040011ffc00')
    return {'format': 'aac-adts', 'language': language, 'audio': base64.b64encode(frame * frames).decode()}


class Backend:
    def __init__(self):
        self.calls = []
        self.answer = 'Recognized words'
    def transcribe(self, audio, language):
        self.calls.append((audio, language))
        return self.answer


class SpeechTests(unittest.TestCase):
    def test_language_and_silence_result_without_agent_job(self):
        engine = Backend(); service = SpeechService(engine)
        self.assertEqual({'text': 'Recognized words', 'language': 'de'}, service.transcribe(audio_request(language='de')))
        self.assertEqual('de', engine.calls[0][1])
        engine.answer = ''
        self.assertEqual('', service.transcribe(audio_request())['text'])
        self.assertTrue(service.status['ready'])

    def test_rejects_malformed_oversized_and_long_audio_before_decoder(self):
        engine = Backend(); service = SpeechService(engine)
        valid = audio_request()
        invalid = [None, [], {}, {**valid, 'path': '/etc/passwd'}, {**valid, 'language': 'auto'},
            {**valid, 'format': 'wav'}, {**valid, 'audio': 123}, {**valid, 'audio': '!!!'},
            {**valid, 'audio': ''}, {**valid, 'audio': 'A' * 400000},
            {**valid, 'audio': base64.b64encode(b'not audio').decode()}, audio_request(frames=472)]
        for body in invalid:
            with self.subTest(body_type=type(body).__name__), self.assertRaises(Problem):
                service.transcribe(body)
        frame = bytearray(base64.b64decode(valid['audio']))
        for position, value in [(1, 0xf9), (2, 0x50), (3, 0x80), (6, 0xfd), (4, 0xff)]:
            bad = frame.copy(); bad[position] = value
            with self.assertRaises(Problem):
                validate_audio({**valid, 'audio': base64.b64encode(bad).decode()})
        self.assertEqual([], engine.calls)

    def test_missing_backend_is_explicit_and_does_not_download(self):
        service = SpeechService()
        self.assertFalse(service.status['ready'])
        with self.assertRaises(Problem) as result:
            service.transcribe(audio_request())
        self.assertEqual(503, result.exception.status)

    def test_only_one_recognition_runs_and_failure_releases_slot_without_leaking_details(self):
        started = threading.Event(); release = threading.Event()
        class Blocking:
            def transcribe(self, *_):
                started.set(); release.wait(3)
                raise RuntimeError('private diagnostic and audio')
        service = SpeechService(Blocking())
        failures = []
        def run():
            try: service.transcribe(audio_request())
            except Problem as error: failures.append(error)
        thread = threading.Thread(target=run); thread.start()
        try:
            self.assertTrue(started.wait(1))
            with self.assertRaises(Problem) as busy:
                service.transcribe(audio_request())
            self.assertEqual(429, busy.exception.status)
        finally: release.set(); thread.join(3)
        self.assertFalse(thread.is_alive())
        self.assertEqual('Speech recognition failed', failures[0].message)
        service.backend = Backend()
        self.assertEqual('Recognized words', service.transcribe(audio_request())['text'])
