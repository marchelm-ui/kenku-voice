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
import random, sys, time, wave
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


# ---- the app's chunker (same rules as Chunker.kt) ----
LEANERS = set("""a an the my your his her its our their this that these those of to in on at for with from by into onto
upon about over under and or but nor so if than as is are was were be been am will would can could shall should must
may might do does did not no very too i we you he she they it i'm you're we're they're it's don't can't won't some any
every each all one two three who what where when why how which whose has have had just before after behind through
across against between without within around toward towards near like since until till while because via per really
quite more most less such""".split())


def split(text):
    words = text.split()
    if len(words) <= 3:
        return [words]
    cost = []
    for w in words:
        bare = w.lower().strip('"\'()*').rstrip('.,!?;:')
        end = w.rstrip('"\')*')[-1:]
        c = -4 if end in ".!?" else -3 if end in ",;:" else 3 if bare in LEANERS else 0
        cost.append(c + random.uniform(-0.6, 0.6))
    n = len(words)
    best = [float("inf")] * (n + 1); frm = [-1] * (n + 1); best[0] = 0
    for e in range(1, n + 1):
        for size in (2, 3, 1, 4):
            s = e - size
            if s < 0 or best[s] == float("inf"):
                continue
            c = best[s] + (0 if e == n else cost[e - 1]) + {1: 6, 4: 2.2}.get(size, 0)
            if c < best[e]:
                best[e], frm[e] = c, s
    b, e = [], n
    while e > 0:
        b.append(e); e = frm[e]
    b.reverse()
    out, s = [], 0
    for x in b:
        out.append(words[s:x]); s = x
    return out


def spoken(words, last):
    t = " ".join(words)
    if t[-1] in ".!?":
        return t
    if t[-1] in ",;:":
        return t.rstrip(",;:") + ","
    return t + ("." if last else ",")


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

# 1. every voice
bad = []
for sid in range(54):
    a = np.array(kt.generate("Squawk! You owe us gold, friend.", sid=sid, speed=1.0).samples, dtype=np.float32)
    if len(a) / RATE < 0.8 or rms(a) < 0.01:
        bad.append(sid)
print("voices checked: 54, broken:", bad)
assert not bad, f"broken voices {bad}"

# 2. cloning
t = time.time(); pt = pocket(); print(f"pocket load {time.time() - t:.1f}s")
ref = np.array(kt.generate("Hello there, traveler. I have walked these roads for many years, and I have seen strange things in the hills.", sid=24).samples, dtype=np.float32)
g = sherpa_onnx.GenerationConfig(); g.reference_audio = ref.tolist(); g.reference_sample_rate = RATE; g.num_steps = 5
c = np.array(pt.generate("Pay the toll, little bird.", g).samples, dtype=np.float32)
print(f"clone sample {len(c) / RATE:.2f}s rms {rms(c):.3f}")
assert len(c) / RATE > 0.8 and rms(c) > 0.01, "cloning produced no speech"

# 3. 50-word line, streamed like the app
line = ("You owe us gold, the tavern keeper said so. Pay now, little bird, or the guards hear everything. "
        "We saw the captain at the docks tonight, and he swore he would find the thief before the morning bells. "
        "Run quickly through the market and hide the map.")
assert len(line.split()) <= 50
random.seed(7)
chunks = split(line)
print("chunks:", [" ".join(w) for w in chunks])
pieces, gen, audio, first = [], 0.0, 0.0, None
start = time.time()
for i, w in enumerate(chunks):
    t0 = time.time()
    if i % 5 == 2:
        a = pt.generate(spoken(w, i == len(chunks) - 1), g)
    else:
        a = kt.generate(spoken(w, i == len(chunks) - 1), sid=random.randrange(54), speed=random.choice([0.92, 1.0, 1.06]))
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
assert wait < 6.0, "too slow to reply within seconds"

pcm = (np.clip(np.concatenate(pieces), -1, 1) * 32767).astype("<i2")
with wave.open(OUT, "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE); w.writeframes(pcm.tobytes())
print("wrote", OUT)
