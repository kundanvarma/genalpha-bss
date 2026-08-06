#!/usr/bin/env python3
"""Runner for the R18-era CTK generation: a raw Postman collection + an
environment file, no config.json/index.js payload step. Usage:

  runctk-r18.py <collection.json> <base-url-no-trailing-slash> <token> [VarName]

Auto-detects the collection's base-URL variable when VarName is omitted,
rewrites every request URL into a structured Postman URL pointing at the
live component, bakes in the bearer token, runs modern newman, and prints
the same honest summary as runctk.py. Chained env captures
(pm.environment.set between requests) still work — newman keeps its own
in-memory environment during the run.
"""
import json, os, re, subprocess, sys, tempfile
from collections import Counter
from urllib.parse import urlsplit, parse_qsl

coll_path = os.path.abspath(sys.argv[1])
base = sys.argv[2].rstrip("/")
token = sys.argv[3]
varname = sys.argv[4] if len(sys.argv) > 4 else "auto"

coll = json.load(open(coll_path))

if varname == "auto":
    counts = Counter()

    def scan(items):
        for it in items:
            if "item" in it:
                scan(it["item"])
            if "request" in it:
                u = it["request"].get("url")
                raw = u.get("raw") if isinstance(u, dict) else u
                m = re.match(r"\{\{(\w+)\}\}", raw or "")
                if m:
                    counts[m.group(1)] += 1
    scan(coll.get("item", []))
    varname = counts.most_common(1)[0][0] if counts else "BASEURL"


def structured(raw):
    raw = raw.replace("{{" + varname + "}}", base)
    # secondary host-vars ({{Service_Ordering}}/tmf-api/...): the var is a
    # bare origin — point it at ours so the leg reaches the live gateway
    sp0 = urlsplit(base)
    raw = re.sub(r"\{\{\w+\}\}(?=/tmf-api/)", f"{sp0.scheme}://{sp0.netloc}", raw)
    sp = urlsplit(raw)
    return {
        "raw": raw,
        "protocol": sp.scheme or "http",
        "host": (sp.hostname or "localhost").split("."),
        "port": str(sp.port) if sp.port else "",
        "path": [p for p in sp.path.split("/") if p != ""],
        "query": [{"key": k, "value": v} for k, v in parse_qsl(sp.query, keep_blank_values=True)],
    }


AUTH = {"key": "Authorization", "value": "Bearer " + token}
CTYPE = {"key": "Content-Type", "value": "application/json"}


def walk(items):
    for it in items:
        if "item" in it:
            walk(it["item"])
        if "request" in it:
            req = it["request"]
            headers = [h for h in (req.get("header") or [])
                       if h.get("key", "").lower() not in ("authorization",)]
            headers.append(AUTH)
            if req.get("method") in ("POST", "PATCH", "PUT") \
                    and not any(h.get("key", "").lower() == "content-type" for h in headers):
                headers.append(CTYPE)
            req["header"] = headers
            u = req.get("url")
            raw = u.get("raw") if isinstance(u, dict) else u
            if raw:
                req["url"] = structured(raw)


walk(coll.get("item", []))

tmp = tempfile.mkdtemp(prefix="ctk-r18-")
pm_path = os.path.join(tmp, "pmtest.json")
res_path = os.path.join(tmp, "result.json")
json.dump(coll, open(pm_path, "w"))

subprocess.run(["newman", "run", pm_path, "--reporter-json-export", res_path,
                "-r", "json", "--insecure", "--timeout-script", "120000"], capture_output=True)

r = json.load(open(res_path))
run = r["run"]; st = run["stats"]
name = os.path.basename(coll_path)
rq, asrt = st["requests"], st["assertions"]
print(f"{name}: requests {rq['total']-rq['failed']}/{rq['total']} ok | "
      f"assertions {asrt['total']-asrt['failed']}/{asrt['total']} ok | "
      f"{len(run.get('failures',[]))} failures")
seen = set()
for fl in run.get("failures", []):
    src = fl["source"]["name"]; msg = fl["error"]["message"]
    k = (src, msg[:55])
    if k in seen:
        continue
    seen.add(k)
    print("   -", src, "::", msg[:100])
print("details:", res_path)
