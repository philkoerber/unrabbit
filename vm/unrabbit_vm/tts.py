"""Bounded, ephemeral reply audio. No text/audio is persisted or sent to a provider."""
import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import threading
import unicodedata
import urllib.request

from .store import Problem

MANIFEST = json.loads(Path(__file__).with_name('voices.json').read_text())
MAX_TEXT = 240
MAX_AUDIO_BYTES = 256 * 1024
MAX_SECONDS = 30


def model_path():
    return Path(os.environ.get('UNRABBIT_TTS_MODELS',
        str(Path(os.environ.get('XDG_DATA_HOME', Path.home() / '.local/share')) / 'unrabbit/voices'))).expanduser().resolve()


def validate_text(body):
    if not isinstance(body, dict) or set(body) != {'text', 'language'}:
        raise Problem(400, 'Invalid synthesis request')
    text, language = body['text'], body['language']
    if language not in ('en', 'de') or not isinstance(text, str):
        raise Problem(400, 'Unsupported text or language')
    if not text.strip() or len(text) > MAX_TEXT:
        raise Problem(413, 'Speech text is empty or too long')
    if any(unicodedata.category(c).startswith('C') and c not in '\n\r\t' for c in text):
        raise Problem(400, 'Invalid speech text')
    return text.strip(), language


class PiperBackend:
    def __init__(self, directory):
        from piper import PiperVoice
        self.voices = {}
        for language, spec in MANIFEST['voices'].items():
            self.voices[language] = PiperVoice.load(directory / spec['name'] / (spec['name'] + '.onnx'))

    def synthesize(self, text, language):
        import av
        from piper import SynthesisConfig
        voice = self.voices[language]
        output = io.BytesIO()
        count = 0
        # AAC in a seekable MP4: Android decodes it directly from memory. One short chunk
        # stays comfortably inside the existing 512 KiB SSH response limit.
        with av.open(output, 'w', format='mp4') as container:
            stream = container.add_stream('aac', rate=voice.config.sample_rate)
            stream.layout = 'mono'
            stream.bit_rate = 48000
            for chunk in voice.synthesize(text, syn_config=SynthesisConfig(volume=0.9)):
                samples = chunk.audio_float_array
                count += len(samples)
                if count > MAX_SECONDS * voice.config.sample_rate:
                    raise Problem(413, 'Speech audio is too long')
                frame = av.AudioFrame.from_ndarray(samples.reshape(1, -1), format='flt', layout='mono')
                frame.sample_rate = voice.config.sample_rate
                for packet in stream.encode(frame):
                    container.mux(packet)
                if output.tell() > MAX_AUDIO_BYTES:
                    raise Problem(413, 'Speech audio is too large')
            for packet in stream.encode(None):
                container.mux(packet)
        return output.getvalue()


class TtsService:
    def __init__(self, backend=None):
        self.backend = backend
        self.lock = threading.Lock()

    @property
    def status(self):
        return {'ready': self.backend is not None, 'engine': 'piper',
                'voices': {lang: spec['name'] for lang, spec in MANIFEST['voices'].items()}}

    def synthesize(self, body):
        text, language = validate_text(body)
        if self.backend is None:
            raise Problem(503, 'VM reply audio is not installed; run tts-setup')
        if not self.lock.acquire(blocking=False):
            raise Problem(429, 'Speech synthesis is busy; try again')
        try:
            audio = self.backend.synthesize(text, language)
            if not isinstance(audio, bytes) or not 12 <= len(audio) <= MAX_AUDIO_BYTES or audio[4:8] != b'ftyp':
                raise Problem(500, 'Speech synthesis failed')
            return {'format': 'aac-mp4', 'language': language,
                    'audio': base64.b64encode(audio).decode('ascii')}
        except Problem:
            raise
        except Exception:
            raise Problem(500, 'Speech synthesis failed') from None
        finally:
            self.lock.release()


def verified(directory):
    for spec in MANIFEST['voices'].values():
        for entry in spec['files']:
            path = directory / spec['name'] / Path(entry['path']).name
            if not path.is_file() or path.stat().st_size != entry['size']:
                return False
            with path.open('rb') as source:
                if hashlib.file_digest(source, 'sha256').hexdigest() != entry['sha256']:
                    return False
    return True


def configured_tts():
    directory = model_path()
    try:
        if verified(directory):
            service = TtsService(PiperBackend(directory))
            print('Reply audio: Piper on VM, Amy medium/Ramona low.', flush=True)
            return service
    except Exception:
        pass
    print('Reply audio unavailable. Install speech requirements and run: unrabbit.py tts-setup', flush=True)
    return TtsService()


def setup(argv):
    parser = argparse.ArgumentParser(description='Install pinned English/German reply voices on this VM.')
    parser.parse_args(argv)
    if os.geteuid() == 0:
        parser.error('Run tts-setup as the unprivileged service account, never root.')
    os.umask(0o077)
    directory = model_path()
    for spec in MANIFEST['voices'].values():
        target = directory / spec['name']
        target.mkdir(parents=True, exist_ok=True)
        for entry in spec['files']:
            path = target / Path(entry['path']).name
            if path.is_file() and path.stat().st_size == entry['size']:
                with path.open('rb') as existing:
                    if hashlib.file_digest(existing, 'sha256').hexdigest() == entry['sha256']:
                        continue
            url = f"https://huggingface.co/{MANIFEST['repository']}/resolve/{MANIFEST['revision']}/{entry['path']}"
            partial = path.with_suffix(path.suffix + '.partial')
            try:
                digest = hashlib.sha256(); size = 0
                with urllib.request.urlopen(url, timeout=60) as response, partial.open('wb') as output:
                    while chunk := response.read(1024 * 1024):
                        size += len(chunk)
                        if size > entry['size']:
                            raise ValueError('Voice download exceeds expected size')
                        digest.update(chunk); output.write(chunk)
                if size != entry['size'] or digest.hexdigest() != entry['sha256']:
                    raise ValueError('Voice download failed verification')
                partial.replace(path)
            finally:
                partial.unlink(missing_ok=True)
    try:
        backend = PiperBackend(directory)
        for language, text in [('en', 'Ready.'), ('de', 'Bereit.')]:
            TtsService(backend).synthesize({'text': text, 'language': language})
    except ImportError:
        parser.error('Install vm/requirements-speech.txt in the service virtual environment first.')
    print('Piper voices verified. Restart the Unrabbit VM service to enable reply audio.', flush=True)
