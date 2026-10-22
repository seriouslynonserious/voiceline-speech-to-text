"""Bounded, utterance-based incremental decoding of 16 kHz signed PCM.

Draft text is replaced on each decode, never concatenated. Confirmed text is
committed on pauses. Long utterances commit only complete timed segments,
retaining the undecided audio tail to avoid cutting the next word in half.
"""
from dataclasses import dataclass
import numpy as np

RATE = 16000
@dataclass(frozen=True)
class Segment:
    start: float
    end: float
    text: str

class StreamBuffer:
    def __init__(self):
        self.audio = np.empty(0, dtype=np.float32)
        self.total_samples = 0
        self.since_decode = 0
        self.silence_samples = 0
        self.confirmed = ""

    def append(self, pcm: bytes):
        if not pcm or len(pcm) % 2 or len(pcm) > 8192:
            raise ValueError("Expected 1–4096 signed 16-bit PCM samples")
        samples = np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768.0
        if self.total_samples + len(samples) > RATE * 600:
            raise ValueError("Recording limit reached (10 minutes)")
        if len(self.audio) + len(samples) > RATE * 25:
            raise ValueError("Audio processing is too slow; try a smaller model")
        self.audio = np.concatenate((self.audio, samples))
        self.total_samples += len(samples)
        self.since_decode += len(samples)
        # Energy only chooses when to finalize. Whisper's VAD filters speech.
        if np.sqrt(np.mean(samples * samples)) < 0.008:
            self.silence_samples += len(samples)
        else:
            self.silence_samples = 0

    def due(self):
        return self.since_decode >= RATE * 2 or (
            self.silence_samples >= RATE * 0.7 and self.since_decode >= RATE * 0.7
        )

    def finish_decode(self, segments: list[Segment], final=False):
        self.since_decode = 0
        duration = len(self.audio) / RATE
        boundary = final or self.silence_samples >= RATE * 0.7
        committed = []
        remaining = segments
        if boundary:
            committed, remaining = segments, []
            self.audio = np.empty(0, dtype=np.float32)
        elif duration >= 12:
            # Retain at least 1s of context; trim on an actual model timestamp.
            committed = [s for s in segments if s.end <= duration - 1]
            remaining = [s for s in segments if s.end > duration - 1]
            if committed:
                cut = min(len(self.audio), int(committed[-1].end * RATE))
                self.audio = self.audio[cut:]
        addition = " ".join(s.text.strip() for s in committed if s.text.strip())
        if addition:
            self.confirmed = (self.confirmed + " " + addition).strip()
        return {
            "type": "final" if final else "update",
            "confirmed": self.confirmed,
            "partial": " ".join(s.text.strip() for s in remaining if s.text.strip()),
            "durationMs": round(self.total_samples * 1000 / RATE),
        }
