import json, sys, urllib.parse, urllib.request
IDX = "https://api.tangled.org/xrpc"
CORE = "did:plc:j5hmlfdrwkvtxm7cjmu7j2is"
def get(url):
    r = urllib.request.Request(url, headers={"User-Agent": "Forgeline specs (+https://github.com/RoiArthurB/forgeline)"})
    try:
        with urllib.request.urlopen(r, timeout=60) as resp: return resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e: return f"HTTP {e.code}: {e.read()[:2000]!r}"
def x(method, **params): return get(f"{IDX}/{method}?" + urllib.parse.urlencode(params, doseq=True))
def tree(path): return json.loads(x("org.tangled.temp.git.getTree", repo=CORE, ref="master", path=path)).get("files") or []
def blob(path): return x("org.tangled.temp.git.getBlob", repo=CORE, ref="master", path=path)
def show(title, text): print(f"\n##### {title}\n{text}\n##### END {title}", flush=True)
def walk(path, depth):
    out = []
    for e in tree(path):
        child = f"{path}/{e['name']}"
        isdir = int(str(e.get("mode", "0")), 8) & 0o170000 == 0o040000
        out.append(child + ("/" if isdir else ""))
        if isdir and depth > 0: out += walk(child, depth - 1)
    return out
for line in sys.argv[1:]:
    kind, _, arg = line.partition(" ")
    if kind == "tree": show("tree " + arg, "\n".join(walk(arg, 6)))
    elif kind == "file": show(arg, blob(arg))
    elif kind == "xrpc":
        method, _, q = arg.partition("?")
        show(arg, get(f"{IDX}/{method}?{q}"))
    elif kind == "url": show(arg, get(arg))
