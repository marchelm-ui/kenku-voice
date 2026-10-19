"""
Build-time voice test: loads the same Kokoro model with the same settings the app uses
(via the sherpa-onnx Python package of the same version), speaks a sample in every
voice, checks none are silent, and writes a kenku-style sample line.
"""
import random
import sys
import wave

import numpy as np
import sherpa_onnx

D = sys.argv[1]  # folder with the app's kokoro assets
OUT = sys.argv[2]

config = sherpa_onnx.OfflineTtsConfig(
    model=sherpa_onnx.OfflineTtsModelConfig(
        kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(
            model=f"{D}/model.int8.onnx",
            voices=f"{D}/voices.bin",
            tokens=f"{D}/tokens.txt",
            data_dir=f"{D}/espeak-ng-data",
            lexicon=f"{D}/lexicon-us-en.txt,{D}/lexicon-zh.txt",
        ),
        num_threads=2,
        provider="cpu",
    ),
)
tts = sherpa_onnx.OfflineTts(config)
rate = tts.sample_rate
n = tts.num_speakers
print(f"sample rate {rate}, speakers {n}")
assert n >= 54, f"expected 54 speakers, got {n}"

bad = []
for sid in range(54):
    a = tts.generate("Squawk! You owe us gold, friend.", sid=sid, speed=1.0)
    s = np.array(a.samples, dtype=np.float32)
    rms = float(np.sqrt(np.mean(s ** 2))) if len(s) else 0.0
    dur = len(s) / rate
    print(f"sid {sid:2d}: {dur:4.2f}s rms {rms:.3f}")
    if dur < 0.8 or rms < 0.01:
        bad.append(sid)
assert not bad, f"silent or broken voices: {bad}"

# A kenku line built the way the app builds it.
def pitch_shift(x, f):
    if f == 1.0:
        return x
    idx = np.arange(0, len(x) - 1, f)
    return np.interp(idx, np.arange(len(x)), x).astype(np.float32)

line = "You owe us gold. The tavern keeper said so. Pay now, little bird."
words = line.split()
chunks, i = [], 0
while i < len(words):
    k = random.choice([2, 3])
    chunks.append(" ".join(words[i:i + k]))
    i += k
styles = [(1.0, 1.0), (0.78, 0.9), (1.28, 1.05), (0.86, 0.85), (1.1, 1.28)]
pieces = []
for c in chunks:
    sid = random.randrange(54)
    p, r = random.choice(styles)
    a = tts.generate(c, sid=sid, speed=max(0.5, min(2.0, r / p)))
    pieces.append(pitch_shift(np.array(a.samples, dtype=np.float32), p))
    pieces.append(np.zeros(int(rate * 0.06), dtype=np.float32))
    print(f"chunk '{c}' -> sid {sid}, pitch {p}, rate {r}")
audio = np.concatenate(pieces)
pcm = (np.clip(audio, -1, 1) * 32767).astype("<i2")
with wave.open(OUT, "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate); w.writeframes(pcm.tobytes())
print(f"wrote {OUT}: {len(audio) / rate:.1f}s")
