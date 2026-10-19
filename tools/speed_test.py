import time, os, glob, sys, numpy as np, sherpa_onnx
def kokoro(d, model, threads):
    return sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(
        model=f"{d}/{model}", voices=f"{d}/voices.bin", tokens=f"{d}/tokens.txt", data_dir=f"{d}/espeak-ng-data",
        lexicon=f"{d}/lexicon-us-en.txt,{d}/lexicon-zh.txt"), num_threads=threads)))
chunks=["You owe us,","gold, the","tavern keeper","said so.","Pay now,","little bird,","or the guards","hear everything."]
def bench(name, tts, gen):
    gen(tts,"Warm up please.")
    t=time.time(); total=0
    for c in chunks:
        a=gen(tts,c); total+=len(a.samples)/a.sample_rate
    dt=time.time()-t
    print(f"{name}: {total:.2f}s audio in {dt:.2f}s  RTF {dt/total:.2f}  per-chunk {dt/len(chunks):.2f}s", flush=True)
print("cpu", os.cpu_count(), os.uname().machine)
for model in ["model.int8.onnx","model.onnx"]:
    d="kok_int8" if "int8" in model else "kok_fp32"
    if not os.path.exists(f"{d}/{model}"): continue
    for th in [2,4]:
        t=time.time(); k=kokoro(d, model, th); print(f"load {model} t{th}: {time.time()-t:.2f}s")
        bench(f"kokoro {model} threads={th}", k, lambda tts,c: tts.generate(c, sid=3, speed=1.0))
P=glob.glob("sherpa-onnx-pocket-tts-int8-*")[0]; files=os.listdir(P)
def pick(k): return os.path.join(P,[x for x in files if k in x][0])
pt=sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(pocket=sherpa_onnx.OfflineTtsPocketModelConfig(
    lm_flow=pick("lm_flow"), lm_main=pick("lm_main"), encoder=pick("encoder"), decoder=pick("decoder"),
    text_conditioner=pick("text_conditioner"), vocab_json=pick("vocab"), token_scores_json=pick("token_scores")), num_threads=4)))
k=kokoro("kok_int8","model.int8.onnx",4)
ref=k.generate("Hello there traveler. I have seen many strange things on the road, and I will tell you about all of them.", sid=24)
g=sherpa_onnx.GenerationConfig(); g.reference_audio=list(ref.samples); g.reference_sample_rate=ref.sample_rate
for steps in [3,5]:
    g.num_steps=steps
    bench(f"pocket clone steps={steps}", pt, lambda tts,c: tts.generate(c, g))
