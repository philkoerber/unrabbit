"""Ephemeral, authenticated speech recognition. Audio never enters the job store."""
import argparse
import base64
import binascii
import io
import os
from pathlib import Path
import threading

from .store import Problem

MODEL_REPO = 'Systran/faster-whisper-base'
MODEL_REVISION = 'ebe41f70d5b6dfa9166e2c581c45c9c0cfc57b66'
MODEL_FILES = ('config.json', 'model.bin', 'tokenizer.json', 'vocabulary.txt')
MAX_AUDIO_BYTES = 256 * 1024
MAX_SAMPLES = 30 * 16000


def model_path():
    return Path(os.environ.get('UNRABBIT_SPEECH_MODEL',
        str(Path(os.environ.get('XDG_DATA_HOME', Path.home() / '.local/share')) / 'unrabbit/speech/base'))).expanduser().resolve()


def validate_audio(body):
    if not isinstance(body, dict) or set(body) != {'format', 'language', 'audio'}:
        raise Problem(400, 'Invalid speech request')
    if body['format'] != 'aac-adts' or body['language'] not in ('en', 'de'):
        raise Problem(400, 'Unsupported speech format or language')
    encoded = body['audio']
    if not isinstance(encoded, str) or not 0 < len(encoded) <= 4 * ((MAX_AUDIO_BYTES + 2) // 3):
        raise Problem(413, 'Audio is empty or too large')
    try:
        audio = base64.b64decode(encoded, validate=True)
    except (ValueError, binascii.Error):
        raise Problem(400, 'Invalid audio encoding') from None
    # Only AAC-LC, 16 kHz mono, one raw block per frame, without CRC or trailing data.
    # Counting frames bounds decoder work before passing untrusted input to PyAV.
    offset = frames = 0
    while offset < len(audio):
        h = audio[offset:offset + 7]
        if (len(h) != 7 or h[0] != 255 or h[1] != 241 or h[2] >> 6 != 1 or
                (h[2] >> 2) & 15 != 8 or ((h[2] & 1) << 2 | h[3] >> 6) != 1 or h[6] & 3):
            raise Problem(400, 'Invalid audio frame')
        length = ((h[3] & 3) << 11) | (h[4] << 3) | (h[5] >> 5)
        if length <= 7 or offset + length > len(audio):
            raise Problem(400, 'Invalid audio frame')
        offset += length
        frames += 1
        if frames * 1024 > MAX_SAMPLES + 2048:
            raise Problem(413, 'Recording exceeds 30 seconds')
    if not frames or len(audio) > MAX_AUDIO_BYTES:
        raise Problem(413, 'Audio is empty or too large')
    return audio, body['language']


class FasterWhisperBackend:
    def __init__(self, directory):
        from faster_whisper import WhisperModel
        from faster_whisper.vad import get_vad_model
        self.model = WhisperModel(str(directory), device='cpu', compute_type='int8',
            cpu_threads=2, num_workers=1, local_files_only=True)
        get_vad_model()  # Bundled Silero; initialize before accepting requests.

    def transcribe(self, audio, language):
        import av
        import numpy as np
        from faster_whisper.vad import get_speech_timestamps, VadOptions
        chunks = []
        samples = None
        try:
            count = 0
            try:
                with av.open(io.BytesIO(audio), mode='r', format='aac') as container:
                    for frame in container.decode(audio=0):
                        if frame.sample_rate != 16000 or len(frame.layout.channels) != 1 or frame.format.name != 'fltp':
                            raise Problem(400, 'Invalid audio format')
                        chunk = frame.to_ndarray().reshape(-1).astype(np.float32)
                        count += len(chunk)
                        if count > MAX_SAMPLES + 2048:
                            raise Problem(413, 'Recording exceeds 30 seconds')
                        chunks.append(chunk)
            except av.error.FFmpegError:
                raise Problem(400, 'Invalid audio data') from None
            if not chunks:
                raise Problem(400, 'Empty audio')
            samples = np.concatenate(chunks)
            if not np.isfinite(samples).all():
                raise Problem(400, 'Invalid audio samples')
            # Reject silence, but retain the complete utterance: trimming damaged device clips.
            if not get_speech_timestamps(samples, VadOptions(min_speech_duration_ms=100)):
                return ''
            segments, _ = self.model.transcribe(samples, language=language, beam_size=1, best_of=1,
                temperature=0.0, condition_on_previous_text=False, vad_filter=False,
                without_timestamps=True, initial_prompt='Unrabbit, Codex, Notion, Solana.')
            return ' '.join(segment.text.strip() for segment in segments).strip()
        finally:
            if samples is not None:
                samples.fill(0)
            for chunk in chunks:
                chunk.fill(0)


class SpeechService:
    def __init__(self, backend=None):
        self.backend = backend
        self.lock = threading.Lock()

    @property
    def status(self):
        return {'ready': self.backend is not None, 'model': 'base', 'languages': ['en', 'de']}

    def transcribe(self, body):
        audio, language = validate_audio(body)
        if self.backend is None:
            raise Problem(503, 'VM speech is not installed; run speech-setup')
        if not self.lock.acquire(blocking=False):
            raise Problem(429, 'Speech recognition is busy; try again')
        try:
            text = self.backend.transcribe(audio, language)
            if not isinstance(text, str) or len(text) > 16000:
                raise Problem(500, 'Speech recognition failed')
            return {'text': text, 'language': language}
        except Problem:
            raise
        except Exception:
            raise Problem(500, 'Speech recognition failed') from None
        finally:
            self.lock.release()


def configured_speech():
    directory = model_path()
    if all((directory / name).is_file() for name in MODEL_FILES):
        try:
            service = SpeechService(FasterWhisperBackend(directory))
            print('Speech: Whisper base on VM, English/German.', flush=True)
            return service
        except Exception:
            pass  # Keep pairing/text service usable, without logging paths or upstream diagnostics.
    print('Speech unavailable. Install vm/requirements-speech.txt and run: unrabbit.py speech-setup', flush=True)
    return SpeechService()


def setup(argv):
    parser = argparse.ArgumentParser(description='Download and verify the VM speech model; no hosted inference.')
    parser.parse_args(argv)
    if os.geteuid() == 0:
        parser.error('Run speech-setup as the unprivileged service account, never root.')
    os.umask(0o077)
    try:
        from huggingface_hub import snapshot_download
        directory = model_path()
        snapshot_download(MODEL_REPO, revision=MODEL_REVISION, allow_patterns=list(MODEL_FILES) + ['README.md'],
            local_dir=str(directory), token=False)
        FasterWhisperBackend(directory)
    except ImportError:
        parser.error('Install vm/requirements-speech.txt in the service virtual environment first.')
    print('Whisper base ready. Restart the Unrabbit VM service to enable speech.', flush=True)
