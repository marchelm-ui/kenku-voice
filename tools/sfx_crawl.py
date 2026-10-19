import json, re, os, sys, urllib.request, urllib.parse, zipfile, io, subprocess, hashlib
UA={'User-Agent':'Mozilla/5.0 kenku-probe'}
def get(url, binary=False, timeout=90):
    req=urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        d=r.read()
    return d if binary else d.decode('utf-8','replace')
OUT='sfx_raw'; os.makedirs(OUT, exist_ok=True)
pages=set(sys.argv[1:])
kw=['door creak','wooden door','coins','coin','sword','metal clang','footsteps','horse','tavern','crowd','fire crackle','rain','thunder','wind','bell','blacksmith','anvil','chicken','crow','raven','wolf','dog bark','cat meow','sheep','cow','pig','magic spell','chest open','book page','bottle','glass','drinking','eating','arrow bow','armor','chain','lock key','squeak','creak','hammer','saw wood','water splash','river','birds','owl','dragon','monster growl','goblin','scream','laugh','cough','snore','knock','bag inventory']
for k in kw:
    try:
        u="https://opengameart.org/art-search-advanced?keys="+urllib.parse.quote(k)+"&field_art_type_tid%5B%5D=13&sort_by=count&sort_order=DESC"
        h=get(u)
        for m in re.findall(r'href="(/content/[a-z0-9\-]+)"', h)[:6]:
            pages.add("https://opengameart.org"+m)
    except Exception as e: print("search fail",k,e)
print("pages",len(pages))
index=[]
for p in sorted(pages):
    try:
        h=get(p)
    except Exception as e:
        print("page fail",p,e); continue
    i=h.find('field-name-field-art-licenses')
    region=h[i:i+3000] if i>=0 else ''
    j=region.find('field-name-',40)
    if j>0: region=region[:j]
    lics=sorted(set(re.findall(r'(CC0|CC-BY-SA \d\.\d|CC-BY \d\.\d|GPL \d\.\d|LGPL [^<"]*|OGA-BY \d\.\d)', region)))
    if p.endswith('50-rpg-sound-effects'): print("REGION SAMPLE", region[:800].replace('\n',' '))
    if lics!=['CC0']:
        print("skip",p,lics); continue
    title=re.search(r'<title>([^<|]+)', h); title=title.group(1).strip() if title else p
    files=re.findall(r'href="(https://opengameart.org/sites/default/files/[^"]+\.(?:zip|ogg|wav|mp3|flac))"', h)
    slug=p.rsplit('/',1)[1]
    total=0
    for f in dict.fromkeys(files):
        try:
            d=get(f,binary=True)
        except Exception as e:
            print("dl fail",f,e); continue
        if len(d)>60_000_000: print("too big",f,len(d)); continue
        name=urllib.parse.unquote(f.rsplit('/',1)[1])
        dest=os.path.join(OUT,slug); os.makedirs(dest,exist_ok=True)
        if name.lower().endswith('.zip'):
            try:
                z=zipfile.ZipFile(io.BytesIO(d))
                for zi in z.infolist():
                    if re.search(r'\.(ogg|wav|mp3|flac)$', zi.filename, re.I) and not zi.filename.startswith('__MACOSX'):
                        tgt=os.path.join(dest, re.sub(r'[^A-Za-z0-9._-]+','_', zi.filename))
                        open(tgt,'wb').write(z.read(zi)); total+=1
            except Exception as e: print("zip fail",f,e)
        else:
            open(os.path.join(dest,re.sub(r'[^A-Za-z0-9._-]+','_',name)),'wb').write(d); total+=1
    index.append({'page':p,'title':title,'files':total})
    print("ok",p,title,total)
json.dump(index,open(os.path.join(OUT,'index.json'),'w'),indent=1)
