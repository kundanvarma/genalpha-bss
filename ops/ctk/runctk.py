#!/usr/bin/env python3
"""Reusable TM Forum CTK runner: point a cloned CTK at a live component through
the gateway, run it with a modern newman, and print a real pass/fail summary.

Usage: runctk.py <CTK-dir> <base-url-ending-in-slash> <token> <StockVarName>
It normalises the CTK's broken URL objects into properly structured Postman
URLs (the collections pre-parse the whole template into the host array, which
modern newman cannot resolve), bakes the auth header in, and runs newman.
"""
import json, os, subprocess, sys
from urllib.parse import urlsplit, parse_qsl

ctk_dir, base, token, varname = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
ctkjs = os.path.join(ctk_dir, "ctk")

# 1. config.json: point at the live component, inject bearer auth.
cfg_path = os.path.join(ctk_dir, "config.json")
cfg = json.load(open(cfg_path))
cfg["url"] = base
cfg.setdefault("headers", {})["Authorization"] = "Bearer " + token
json.dump(cfg, open(cfg_path, "w"), indent=2)

# 2. Generate pmtest.json (bakes headers into every request). Ignore the
#    runner's own newman invocation failing on modern node.
subprocess.run(["node", "index.js"], cwd=ctkjs, capture_output=True)
pm_path = os.path.join(ctkjs, "pmtest.json")
coll = json.load(open(pm_path))


def structured(raw):
    raw = raw.replace("{{" + varname + "}}", base)
    sp = urlsplit(raw)
    return {
        "raw": raw,
        "protocol": sp.scheme or "http",
        "host": (sp.hostname or "localhost").split("."),
        "port": str(sp.port) if sp.port else "",
        "path": [p for p in sp.path.split("/") if p != ""],
        "query": [{"key": k, "value": v} for k, v in parse_qsl(sp.query, keep_blank_values=True)],
    }


def walk(items):
    for it in items:
        if "item" in it:
            walk(it["item"])
        if "request" in it:
            u = it["request"].get("url")
            raw = u.get("raw") if isinstance(u, dict) else u
            if raw:
                it["request"]["url"] = structured(raw)


if varname == "auto":
    import re
    from collections import Counter
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

walk(coll.get("item", []))
json.dump(coll, open(pm_path, "w"))

# 3. Run newman 6.
res_path = os.path.join(ctkjs, "result.json")
subprocess.run(["newman", "run", pm_path, "--reporter-json-export", res_path,
                "-r", "json", "--insecure"], cwd=ctkjs, capture_output=True)

# 4. Summarise.
r = json.load(open(res_path))
run = r["run"]; st = run["stats"]
name = os.path.basename(ctk_dir)
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
    print("   -", src, "::", msg[:95])
