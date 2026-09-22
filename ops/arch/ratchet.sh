#!/bin/bash
# The architecture ratchet: a check the agent can run. Two counts that may only fall.
#   1. public methods returning Map<String, Object> per service (typed core, open edge)
#   2. lines per front-end source file over the size rule
#   3. PATCH/PUT handlers that accept a raw Map<String, Object> body (mass-assignment surface; a record cannot carry a field it does not declare)
# Usage: ops/arch/ratchet.sh            compare against ops/arch/baseline.json, exit 2 on regression
#        ops/arch/ratchet.sh --baseline  rewrite the baseline from the current tree (deliberate, reviewed)
#        ops/arch/ratchet.sh --quick <f> check only the service/app that owns file <f> (edit hook)
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"; cd "$REPO"
BASE=ops/arch/baseline.json
MAX_FE_LINES=300

count_maps() { grep -rhE 'public .*Map<String, ?Object> [a-zA-Z_]+\(' "$1" 2>/dev/null | wc -l | tr -d ' '; }

current() {
  python3 - "$MAX_FE_LINES" <<'PY'
import json, os, re, subprocess, sys
mx = int(sys.argv[1])
out = {"mapReturns": {}, "frontendFiles": {}, "rawMapWrites": {}}
pat = re.compile(r'public .*Map<String, ?Object> [a-zA-Z_]+\(')
write = re.compile(r'@(Patch|Put|Post)Mapping[^\n]*\n(?:[^\n]*\n){0,3}?[^\n]*@RequestBody\s+(?:final\s+)?Map<String, ?Object>')
for svc in sorted(os.listdir('services')):
    root = os.path.join('services', svc, 'src', 'main', 'java')
    if not os.path.isdir(root): continue
    n = 0; w = 0
    for dp, _, fs in os.walk(root):
        for f in fs:
            if f.endswith('.java'):
                with open(os.path.join(dp, f), errors='ignore') as fh: src = fh.read()
                n += len(pat.findall(src)); w += len(write.findall(src))
    if n: out["mapReturns"][svc] = n
    if w: out["rawMapWrites"][svc] = w
for app in sorted(os.listdir('apps')):
    for sub in ('site', 'src'):
        root = os.path.join('apps', app, sub)
        if not os.path.isdir(root): continue
        for dp, dn, fs in os.walk(root):
            dn[:] = [d for d in dn if d not in ('node_modules', 'dist', 'build')]
            for f in fs:
                if f.endswith(('.js', '.jsx', '.ts', '.tsx')) and not f.endswith('.min.js'):
                    p = os.path.join(dp, f)
                    with open(p, errors='ignore') as fh: n = sum(1 for _ in fh)
                    if n > mx: out["frontendFiles"][p] = n
print(json.dumps(out, indent=1, sort_keys=True))
PY
}

if [ "${1:-}" = "--baseline" ]; then current > "$BASE"; echo "ratchet: baseline written to $BASE"; exit 0; fi
[ -f "$BASE" ] || { echo "ratchet: no baseline — run ops/arch/ratchet.sh --baseline"; exit 1; }

NOW=$(current)
python3 - "$BASE" "$NOW" "${1:-}" "${2:-}" <<'PY'
import json, sys
base = json.load(open(sys.argv[1])); now = json.loads(sys.argv[2]); quick = sys.argv[3] == '--quick'; f = sys.argv[4]
bad = []
for svc, n in now["mapReturns"].items():
    if quick and f and f'services/{svc}/' not in f: continue
    b = base["mapReturns"].get(svc, 0)
    if n > b: bad.append(f"services/{svc}: {n} public Map<String,Object> returns, baseline {b} — type the new one (record + mapper), see docs/engineering-conventions.md §1")
for svc, n in now.get("rawMapWrites", {}).items():
    if quick and f and f'services/{svc}/' not in f: continue
    b = base.get("rawMapWrites", {}).get(svc, 0)
    if n > b: bad.append(f"services/{svc}: {n} PATCH/PUT/POST handlers take a raw Map body, baseline {b} — declare a record for the body (mass-assignment surface), see docs/engineering-conventions.md §9")
for p, n in now["frontendFiles"].items():
    if quick and f and not f.endswith(p): continue
    b = base["frontendFiles"].get(p, 0)
    if n > b: bad.append(f"{p}: {n} lines, baseline {b} — split before adding, see docs/engineering-conventions.md §2")
for p in base["frontendFiles"]:
    pass
if bad:
    print("ARCHITECTURE RATCHET — regression:"); [print("  - " + m) for m in bad]; sys.exit(2)
tm = sum(now["mapReturns"].values()); tb = sum(base["mapReturns"].values())
tw = sum(now.get("rawMapWrites", {}).values()); twb = sum(base.get("rawMapWrites", {}).values())
if not quick: print(f"ratchet ok: untyped public returns {tm} (baseline {tb}); raw-map write handlers {tw} (baseline {twb}); oversized front-end files {len(now['frontendFiles'])} (baseline {len(base['frontendFiles'])})")
PY
