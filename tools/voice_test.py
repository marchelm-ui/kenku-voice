"""
Build-time voice test, run on an ARM machine (similar to phone CPUs) with the same
sherpa-onnx version, models and settings the app uses.

Checks:
  1. All 54 built-in voices produce real (non-silent) speech.
  2. Voice cloning (Pocket TTS) works from a short example recording.
  3. A 50-word line, split and voiced the way the app does it, starts playing within
     a few seconds and is generated faster than it plays (so streaming never stalls).
Writes kenku-sample.wav (a full kenku line including a cloned voice) for listening.
"""
import os, random, sys, time, wave
import numpy as np
import sherpa_onnx

K, P, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
RATE = 24000
THREADS = 4


def kokoro():
    return sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(
            model=f"{K}/model.onnx", voices=f"{K}/voices.bin", tokens=f"{K}/tokens.txt",
            data_dir=f"{K}/espeak-ng-data", lexicon=f"{K}/lexicon-us-en.txt,{K}/lexicon-zh.txt"),
        num_threads=THREADS, provider="cpu")))


def pocket():
    return sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        pocket=sherpa_onnx.OfflineTtsPocketModelConfig(
            lm_flow=f"{P}/lm_flow.int8.onnx", lm_main=f"{P}/lm_main.int8.onnx", encoder=f"{P}/encoder.onnx",
            decoder=f"{P}/decoder.int8.onnx", text_conditioner=f"{P}/text_conditioner.onnx",
            vocab_json=f"{P}/vocab.json", token_scores_json=f"{P}/token_scores.json"),
        num_threads=THREADS, provider="cpu")))


def rms(x):
    return float(np.sqrt(np.mean(np.square(x)))) if len(x) else 0.0


def trim(x, thr=0.012):
    idx = np.where(np.abs(x) > thr)[0]
    if len(idx) == 0:
        return x
    pad = int(RATE * 0.018)
    return x[max(0, idx[0] - pad): min(len(x), idx[-1] + pad)]


def vrms(x):
    fr = sorted(float(np.sqrt(np.mean(x[i:i + 480] ** 2))) for i in range(0, len(x), 480) if len(x[i:i + 480]))
    top = fr[len(fr) // 3:]
    return float(np.mean(top)) if top else 0.0


def norm(x, t):
    r = vrms(x)
    return x * min(3.5, max(0.35, t / r)) if r > 1e-4 else x


def compress(x, thr=0.25, ratio=3.0):
    ga = np.exp(-1 / (0.003 * RATE)); gr = np.exp(-1 / (0.06 * RATE)); env = 0.0
    y = np.empty_like(x)
    for i, v in enumerate(x):
        a = abs(v)
        env = ga * env + (1 - ga) * a if a > env else gr * env + (1 - gr) * a
        y[i] = v * (thr * (env / thr) ** (1 / ratio) / env if env > thr else 1.0)
    return y


def match(x, target=0.105):
    """Mirror of AudioDsp.polish: 80 Hz high-pass, +2.5 dB presence at 3 kHz, compression, level."""
    from scipy.signal import lfilter
    w = 2 * np.pi * 80 / RATE; al = np.sin(w) / (2 * 0.707); c = np.cos(w); a0 = 1 + al
    x = lfilter([(1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0], [1, -2 * c / a0, (1 - al) / a0], x)
    A = 10 ** (2.5 / 40); w = 2 * np.pi * 3000 / RATE; al = np.sin(w) / (2 * 0.9); c = np.cos(w); a0 = 1 + al / A
    x = lfilter([(1 + al * A) / a0, -2 * c / a0, (1 - al * A) / a0], [1, -2 * c / a0, (1 - al / A) / a0], x)
    x = compress(norm(x.astype(np.float32), 0.09))
    x = norm(x, target)
    return np.clip(x, -0.98, 0.98).astype(np.float32)


t = time.time(); kt = kokoro(); print(f"kokoro load {time.time() - t:.1f}s, speakers {kt.num_speakers}")
assert kt.num_speakers >= 54

# 1. every voice speaks, and after the clarity chain lands in the table-loudness band.
bad, loudness_outliers, levels = [], [], []
for sid in range(54):
    a = np.array(kt.generate("Squawk! You owe us gold, friend.", sid=sid, speed=1.0).samples, dtype=np.float32)
    if len(a) / RATE < 0.8 or rms(a) < 0.01:
        bad.append(sid)
        continue
    v = vrms(match(trim(a)))
    levels.append(v)
    if not (0.075 <= v <= 0.135):
        loudness_outliers.append((sid, round(float(v), 3)))
print("voices checked: 54, broken:", bad)
print(f"loudness after polish: min {min(levels):.3f} max {max(levels):.3f} target 0.105, outliers: {loudness_outliers}")
assert not bad, f"broken voices {bad}"
assert not loudness_outliers, f"voices outside the loudness band {loudness_outliers}"

# 2. cloning
t = time.time(); pt = pocket(); print(f"pocket load {time.time() - t:.1f}s")
ref = np.array(kt.generate("Hello there, traveler. I have walked these roads for many years, and I have seen strange things in the hills.", sid=24).samples, dtype=np.float32)
g = sherpa_onnx.GenerationConfig(); g.reference_audio = ref.tolist(); g.reference_sample_rate = RATE; g.num_steps = 5
t = time.time()
c = np.array(pt.generate("Pay the toll, little bird.", g).samples, dtype=np.float32)
first_clone_gen = time.time() - t
print(f"clone sample {len(c) / RATE:.2f}s rms {rms(c):.3f}, first generation {first_clone_gen:.1f}s")
assert len(c) / RATE > 0.8 and rms(c) > 0.01, "cloning produced no speech"
# The app warms this path in the background; the budget keeps "a few seconds" honest.
assert first_clone_gen < 20, f"first clone generation too slow ({first_clone_gen:.1f}s)"

# 3. 50-word line, streamed like the app
fixture = [l.strip() for l in open(os.path.join(os.path.dirname(__file__), "benchmark_chunks.txt"))
           if l.strip() and not l.startswith("#")]
line_words = sum(len(c.rstrip('.,;:').split()) for c in fixture)
assert line_words <= 50
random.seed(7)
chunks = fixture
print("chunks:", chunks)
pieces, gen, audio, first = [], 0.0, 0.0, None
start = time.time()
for i, w in enumerate(chunks):
    t0 = time.time()
    if i % 5 == 2:
        a = pt.generate(w, g)
    else:
        a = kt.generate(w, sid=random.randrange(54), speed=random.choice([0.92, 1.0, 1.06]))
    x = match(trim(np.array(a.samples, dtype=np.float32)))
    gen += time.time() - t0
    audio += len(x) / RATE
    if first is None:
        first = time.time() - start
    pieces += [x, np.zeros(int(RATE * 0.03), dtype=np.float32)]
rtf = gen / audio
print(f"50-word line: {len(chunks)} snippets, {audio:.1f}s audio, generated in {gen:.1f}s (RTF {rtf:.2f}), first sound after {first:.2f}s")
# Streaming starts once the rest can be made before playback catches up.
wait = max(first, gen * 1.15 - audio + first)
print(f"estimated wait before speaking: {wait:.2f}s")
assert wait < 3.0, "too slow to reply within seconds"
# Speed budget: generation must stay comfortably faster than real time, or streaming stalls.
assert rtf < 0.85, f"generation too slow (RTF {rtf:.2f}, budget 0.85)"

# 4. spectral intelligibility: clear mode's worst pitch/speed corners must keep
# consonant-band (1-4 kHz) energy, or voices get loud but mushy - the one fault
# Marcus reported by ear, now partially measurable without ears. The pitch shift
# mirrors the app's resampling approach (model-side authority, like trim/match).
def band_fraction(x, lo, hi):
    spec = np.abs(np.fft.rfft(x.astype(np.float64))) ** 2
    freqs = np.fft.rfftfreq(len(x), 1.0 / RATE)
    total = spec.sum() + 1e-12
    return spec[(freqs >= lo) & (freqs < hi)].sum() / total

def pitch_mirror(x, f):
    import scipy.signal
    return scipy.signal.resample(x, max(1, int(len(x) / f))).astype(np.float32)

clarity_text = "The quick thief slipped past six sleeping guards with the silver coins."
base = trim(np.array(kt.generate(clarity_text, sid=0, speed=1.0).samples, dtype=np.float32))
f_base = band_fraction(base, 1000, 4000)
print(f"clarity baseline: {f_base:.3f} of energy in 1-4 kHz")
# Measured 0.026 on this voice (speech energy is mostly fundamentals under 1 kHz);
# the floor only guards against a near-zero band, the ratio gate below is the real guard.
assert f_base > 0.008, f"baseline speech has almost no consonant band ({f_base:.3f})"
# Clear-mode worst corners: (pitch, model speed = rate/pitch).
for pitch, rate_ in [(0.88, 1.12), (1.16, 0.86)]:
    a = trim(np.array(kt.generate(clarity_text, sid=0, speed=rate_ / pitch).samples, dtype=np.float32))
    shifted = pitch_mirror(a, pitch)
    f_shift = band_fraction(shifted, 1000, 4000)
    print(f"clarity at pitch {pitch} speed {rate_ / pitch:.2f}: {f_shift:.3f} (baseline {f_base:.3f})")
    # Measured corners: 0.85x baseline (pitch down), 1.46x (pitch up); 0.6x is the budget.
    assert f_shift > 0.6 * f_base, f"consonant band collapsed at pitch {pitch}: {f_shift:.3f} vs {f_base:.3f}"

# 5. table-talk canary: the messy text real users type must never crash the model,
# and anything with actual words in it must produce audible speech.
canary = [
    ("Roll 2d6 plus 3,", True),
    ("10 gold, 20 silver,", True),
    ("WAIT. STOP. NOW.", True),
    ("What?!?!", True),
    ("Wait… okay go!", True),
    ("'swordfish' (I think),", True),
    ("Supercalifragilisticexpialidocious,", True),
    ("🪙🔥,", False),            # emoji-only: silence is fine, crashing is not
    ("?!?!.", False),
]
for text, must_speak in canary:
    try:
        a = np.array(kt.generate(text, sid=0).samples, dtype=np.float32)
    except Exception as e:
        raise AssertionError(f"model crashed on {text!r}: {e}")
    if must_speak:
        assert len(a) / RATE > 0.15 and rms(trim(a)) > 0.01, f"no speech for {text!r}"
print(f"table-talk canary: {len(canary)} tricky inputs survived")

pcm = (np.clip(np.concatenate(pieces), -1, 1) * 32767).astype("<i2")
with wave.open(OUT, "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE); w.writeframes(pcm.tobytes())
print("wrote", OUT)
