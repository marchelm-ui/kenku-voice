import json, re, os, sys, time, urllib.request, subprocess, zipfile, io
UA={'User-Agent':'Mozilla/5.0 kenku-probe'}
def get(url, binary=False, timeout=60):
    req=urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        d=r.read()
    return d if binary else d.decode('utf-8','replace')

print("== tts-models assets with pocket/zipvoice")
page=1
names=[]
rel=json.loads(get("https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/tts-models"))
rid=rel['id']
while True:
    a=json.loads(get(f"https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/{rid}/assets?per_page=100&page={page}"))
    if not a: break
    names+= [(x['name'],x['size']) for x in a]
    page+=1
for n,s in names:
    if re.search('pocket|zipvoice|clone|supertonic', n, re.I): print(n, s)

print("== sherpa python api")
import sherpa_onnx
for k in dir(sherpa_onnx):
    if re.search('Pocket|ZipVoice|Generation', k): print(k)
for k in ['OfflineTtsPocketModelConfig','GenerationConfig','OfflineTtsZipVoiceModelConfig']:
    if hasattr(sherpa_onnx,k):
        print('---',k); print(getattr(sherpa_onnx,k).__init__.__doc__)
print('--- OfflineTts methods', [m for m in dir(sherpa_onnx.OfflineTts) if not m.startswith('_')])
print(sherpa_onnx.OfflineTts.generate.__doc__)
