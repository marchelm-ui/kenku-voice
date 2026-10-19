import sys, time, os, glob, numpy as np, sherpa_onnx
K="kok"; P=glob.glob("sherpa-onnx-pocket-tts-int8-*")[0]
print("pocket files:", sorted(os.listdir(P)))
for f in sorted(os.listdir(P)): print(f, os.path.getsize(os.path.join(P,f)) if os.path.isfile(os.path.join(P,f)) else 'dir')
g=sherpa_onnx.GenerationConfig(); print("GenerationConfig attrs:", [a for a in dir(g) if not a.startswith('_')])
kt=sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(
    model=f"{K}/model.int8.onnx", voices=f"{K}/voices.bin", tokens=f"{K}/tokens.txt", data_dir=f"{K}/espeak-ng-data",
    lexicon=f"{K}/lexicon-us-en.txt,{K}/lexicon-zh.txt"), num_threads=4)))
ref_text="Hello there traveler. I have seen many strange things on the road, and I will tell you about all of them."
t=time.time(); ref=kt.generate(ref_text, sid=24, speed=1.0); print("kokoro ref", len(ref.samples)/ref.sample_rate, "s in", round(time.time()-t,2))
def f(n): return os.path.join(P,n)
files=os.listdir(P)
def pick(k): return f([x for x in files if k in x][0])
cfg=sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(pocket=sherpa_onnx.OfflineTtsPocketModelConfig(
    lm_flow=pick("lm_flow"), lm_main=pick("lm_main"), encoder=pick("encoder"), decoder=pick("decoder"),
    text_conditioner=pick("text_conditioner"), vocab_json=pick("vocab"), token_scores_json=pick("token_scores")), num_threads=4))
t=time.time(); pt=sherpa_onnx.OfflineTts(cfg); print("pocket load", round(time.time()-t,2), "rate", pt.sample_rate)
for txt in ["You owe us gold.", "The tavern keeper said so, pay now little bird."]:
    g=sherpa_onnx.GenerationConfig()
    g.reference_audio=list(ref.samples); g.reference_sample_rate=ref.sample_rate
    for a,v in [("speed",1.0),("num_steps",5)]:
        if hasattr(g,a): setattr(g,a,v)
    for trial in range(2):
        t=time.time(); a=pt.generate(txt, g); dt=time.time()-t
        s=np.array(a.samples); print(f"pocket '{txt}': {len(s)/a.sample_rate:.2f}s audio in {dt:.2f}s rms {float(np.sqrt(np.mean(s**2))):.3f}")
for txt in ["You owe us gold.", "The tavern keeper said so, pay now little bird."]:
    t=time.time(); a=kt.generate(txt, sid=3, speed=1.0); print(f"kokoro '{txt}': {len(a.samples)/a.sample_rate:.2f}s in {time.time()-t:.2f}s")
import wave
pcm=(np.clip(np.array(a.samples),-1,1)*32767).astype('<i2')
