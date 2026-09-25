#!/bin/bash
# The architecture ratchet: a check the agent can run. Two counts that may only fall.
#   1. public methods returning Map<String, Object> per service (typed core, open edge)
#   2. lines per front-end source file over the size rule
#   3. PATCH/PUT handlers that accept a raw Map<String, Object> body (mass-assignment surface; a record cannot carry a field it does not declare)
#   4. String.valueOf(x.get("...id...")) on an identifier — a missing value becomes the text "null",
#      which as a write mints a fake reference and as a lookup or delete matches IS NULL (six real bugs)
#   5. unescaped interpolations into innerHTML in a front end — operator or customer text
#      reaching the DOM as markup is cross-site scripting with a care agent's session
# Usage: ops/arch/ratchet.sh            compare against ops/arch/baseline.json, exit 2 on regression
#        ops/arch/ratchet.sh --baseline  rewrite the baseline from the current tree (deliberate, reviewed)
#        ops/arch/ratchet.sh --quick <f> check only the service/app that owns file <f> (edit hook)
#        ops/arch/ratchet.sh --live      also read the LIVE metric categoryFallbacks from ops/arch/cfs_check.py
#                                        (services the category table fulfilled because their spec named no
#                                        CFS — step 3's counted debt; may only fall). Needs a fleet; without
#                                        --live, or when the gate is unreachable, the metric is skipped, never
#                                        red — the pre-commit hook runs without a fleet. Pin it with
#                                        --baseline --live.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"; cd "$REPO"
BASE=ops/arch/baseline.json
MAX_FE_LINES=300

count_maps() { grep -rhE 'public .*Map<String, ?Object> [a-zA-Z_]+\(' "$1" 2>/dev/null | wc -l | tr -d ' '; }

current() {
  python3 - "$MAX_FE_LINES" <<'PY'
import json, os, re, subprocess, sys
mx = int(sys.argv[1])
out = {"mapReturns": {}, "frontendFiles": {}, "rawMapWrites": {}, "nullIdGuards": {},
       "htmlInterpolations": {}, "undeclaredJsx": {}}

# --- rule 6: a JSX component that nothing declares or imports --------------------
# Vite bundles it as a global and the page throws "X is not defined" the first time
# that branch renders — a ReferenceError the build never sees. Found live on
# 25 Sep 2026: Services.jsx used <RouterPanel> without importing it, for months.
JSX_TAG = re.compile(r'<([A-Z][A-Za-z0-9_]*)')
# indented too: a component declared inside another component's body (const Err = ... ) is a declaration
DECLARED = re.compile(r'^\s*(?:export\s+)?(?:default\s+)?(?:async\s+)?(?:function|class)\s+([A-Za-z_]\w*)'
                      r'|^\s*(?:export\s+)?(?:const|let|var)\s+([A-Za-z_]\w*)', re.M)
IMPORTED = re.compile(r'^import\s+([^;]+?)\s+from\s+', re.M)
BUILTIN_TAGS = {'Fragment', 'Suspense', 'StrictMode', 'Profiler'}
def undeclared_jsx(src):
    if '<' not in src: return 0
    names = set()
    for a, b in DECLARED.findall(src): names.add(a or b)
    for spec in IMPORTED.findall(src):
        spec = spec.replace('{', ',').replace('}', ',')
        for part in spec.split(','):
            part = part.strip()
            if not part or part == 'type': continue
            names.add(part.split(' as ')[-1].strip().lstrip('* ').strip())
    body = re.sub(r'^import[^\n]*$', '', src, flags=re.M)
    used = set(JSX_TAG.findall(body))
    # a dotted tag (<Tab.Screen>) is declared by its head; a destructured default (const {X} = ...) by DECLARED
    return sum(1 for u in used if u not in names and u not in BUILTIN_TAGS)
pat = re.compile(r'public .*Map<String, ?Object> [a-zA-Z_]+\(')
write = re.compile(r'@(Patch|Put|Post)Mapping[^\n]*\n(?:[^\n]*\n){0,3}?[^\n]*@RequestBody\s+(?:final\s+)?Map<String, ?Object>')
# an identifier KEY: exactly id/ref/…, or ending in Id/Ref, or a known owner-ish name.
# Deliberately not "any key containing the letters id" — that matches provider, valid, considered.
nullid = re.compile(r'String\.valueOf\(\s*[A-Za-z_][A-Za-z0-9_]*\.get\(\s*"'
                    r'(?:id|ref|uuid|key|[A-Za-z]+(?:Id|Ref|Uuid)'
                    r'|party|tenant|owner|subject|customer|account|msisdn|iccid|imei)"\s*\)')

# --- rule 5: a value reaching innerHTML as markup instead of as text -----------
# Counted: every dynamic piece of a string that carries an HTML tag, in a file that
# uses innerHTML. Not counted (the code produced the value itself): a number it
# formatted, a .length it counted, a variable already named …Html, a pick between
# two literals, and anything already passed through esc(). Known blind spot: a
# .jsx file that starts using innerHTML — an apostrophe in JSX text desynchronises
# the scanner, so React files are gated out by the innerHTML check above.
TAG = re.compile(r'<[a-zA-Z/!][^<>]*>')
SAFEV = re.compile(r'^(?:[\d.]+|(?:Math|Number|JSON)[.(].*|[\w.?\[\]]+\.length'
                   r'|[A-Za-z_$][\w$]*(?:[Hh]tml|HTML))$')
PICK = re.compile(r'^[^?@]*\?\s*@[SH]@\s*:\s*@[SH]@$')
REGEX_OK = set('(,=:[!&|?{};+-*%~^<>') | {''}

def blank(src):
    """Blank comments and regex literals in place (offsets kept), so a /[<>"]/
    cannot desynchronise the quote and backtick scanners below."""
    out_ = list(src); n = len(src); i = 0; prev = ''
    def wipe(a, b):
        for k in range(a, min(b, n)):
            if src[k] != '\n': out_[k] = ' '
    while i < n:
        c = src[i]
        if c == '/' and src.startswith('//', i):
            j = src.find('\n', i); j = n if j < 0 else j
            wipe(i, j); i = j; prev = ''; continue
        if c == '/' and src.startswith('/*', i):
            j = src.find('*/', i); j = n if j < 0 else j + 2
            wipe(i, j); i = j; prev = ''; continue
        if c == '/' and prev in REGEX_OK:
            j = i + 1; cls = False; ok = False
            while j < n:
                d = src[j]
                if d == '\\': j += 2; continue
                if d == '\n': break
                if d == '[': cls = True
                elif d == ']': cls = False
                elif d == '/' and not cls: ok = True; break
                j += 1
            if ok:
                wipe(i, j + 1); i = j + 1; prev = 'x'; continue
        if c in '\'"':
            q = c; j = i + 1
            while j < n:
                if src[j] == '\\': j += 2; continue
                if src[j] == q: break
                j += 1
            i = j + 1; prev = q; continue
        if c == '`':
            j = i + 1
            while j < n and src[j] != '`':
                if src[j] == '\\': j += 2; continue
                if src.startswith('${', j):            # a placeholder is code too
                    d = 0; k = j + 1
                    while k < n:
                        if src[k] == '{': d += 1
                        elif src[k] == '}':
                            d -= 1
                            if d == 0: break
                        k += 1
                    out_[j + 2:k] = list(blank(''.join(out_[j + 2:k])))
                    j = k + 1; continue
                j += 1
            i = j + 1; prev = '`'; continue
        if not c.isspace(): prev = c
        i += 1
    return ''.join(out_)

def templates(src):
    """Every template literal as (static text, [placeholder expressions])."""
    res = []; i = 0; n = len(src)
    while i < n:
        c = src[i]
        if c in '\'"':
            q = c; i += 1
            while i < n and src[i] != q: i += 2 if src[i] == '\\' else 1
            i += 1; continue
        if c == '`':
            i += 1; static = []; holes = []
            while i < n and src[i] != '`':
                if src[i] == '\\': i += 2; continue
                if src.startswith('${', i):
                    d = 0; j = i + 1
                    while j < n:
                        if src[j] == '{': d += 1
                        elif src[j] == '}':
                            d -= 1
                            if d == 0: break
                        j += 1
                    holes.append(src[i + 2:j]); res.extend(templates(src[i + 2:j]))
                    i = j + 1; continue
                static.append(src[i]); i += 1
            i += 1; res.append((''.join(static), holes)); continue
        i += 1
    return res

def mask(expr):
    """Every string literal becomes @S@, or @H@ when it carries a tag."""
    res = []; i = 0; n = len(expr)
    while i < n:
        c = expr[i]
        if c in '\'"`':
            q = c; i += 1; body = []
            while i < n and expr[i] != q:
                if expr[i] == '\\': body.append(expr[i:i + 2]); i += 2; continue
                if q == '`' and expr.startswith('${', i):
                    d = 0; j = i + 1
                    while j < n:
                        if expr[j] == '{': d += 1
                        elif expr[j] == '}':
                            d -= 1
                            if d == 0: break
                        j += 1
                    i = j + 1; continue
                body.append(expr[i]); i += 1
            i += 1; res.append('@H@' if TAG.search(''.join(body)) else '@S@'); continue
        res.append(c); i += 1
    return ''.join(res)

def statement(src, i):
    """The one expression assigned to innerHTML, from i to its ; or line end."""
    depth = 0; n = len(src); res = []
    while i < n:
        c = src[i]
        if c in '([{': depth += 1
        elif c in ')]}':
            if depth == 0: break
            depth -= 1
        elif c in '\'"`':
            q = c; res.append(c); i += 1
            while i < n:
                if src[i] == '\\': res.append(src[i:i + 2]); i += 2; continue
                if q == '`' and src.startswith('${', i):
                    d = 0; j = i + 1
                    while j < n:
                        if src[j] == '{': d += 1
                        elif src[j] == '}':
                            d -= 1
                            if d == 0: j += 1; break
                        j += 1
                    res.append(src[i:j]); i = j; continue
                res.append(src[i])
                if src[i] == q: i += 1; break
                i += 1
            continue
        elif c == ';' and depth == 0: break
        elif c == '\n' and depth == 0:
            if not ''.join(res).rstrip().endswith(('+', '(', ',', '?', ':', '=', '&&', '||')): break
        res.append(c); i += 1
    return ''.join(res)

def risky(exprs):
    bad = []
    for e in exprs:
        s = ' '.join(e.split())
        if not s or 'esc(' in s or 'escapeHtml(' in s or 'map(esc)' in s: continue
        if SAFEV.match(s) or PICK.match(' '.join(mask(s).split())): continue
        bad.append(s)
    return bad

def html_holes(src):
    src = blank(src); bad = []
    for static, holes in templates(src):
        if TAG.search(static): bad += risky(holes)
    for m in re.finditer(r'\.innerHTML\s*\+?=\s*', src):
        st = mask(statement(src, m.end()))
        if '@H@' not in st: continue
        parts = []; depth = 0; cur = ''
        for ch in st:
            if ch in '([{': depth += 1
            elif ch in ')]}': depth -= 1
            if ch == '+' and depth == 0: parts.append(cur); cur = ''; continue
            cur += ch
        parts.append(cur)
        ops = [o.strip().strip('()').strip() for o in parts]
        bad += risky([o for o in ops if o and '@S@' not in o and '@H@' not in o])
    return len(bad)

for svc in sorted(os.listdir('services')):
    root = os.path.join('services', svc, 'src', 'main', 'java')
    if not os.path.isdir(root): continue
    n = 0; w = 0; g = 0
    for dp, _, fs in os.walk(root):
        for f in fs:
            if f.endswith('.java'):
                with open(os.path.join(dp, f), errors='ignore') as fh: src = fh.read()
                n += len(pat.findall(src)); w += len(write.findall(src)); g += len(nullid.findall(src))
    if n: out["mapReturns"][svc] = n
    if w: out["rawMapWrites"][svc] = w
    if g: out["nullIdGuards"][svc] = g
for app in sorted(os.listdir('apps')):
    for sub in ('site', 'src'):
        root = os.path.join('apps', app, sub)
        if not os.path.isdir(root): continue
        for dp, dn, fs in os.walk(root):
            dn[:] = [d for d in dn if d not in ('node_modules', 'dist', 'build')]
            for f in fs:
                if f.endswith(('.js', '.jsx', '.ts', '.tsx')) and not f.endswith('.min.js'):
                    p = os.path.join(dp, f)
                    with open(p, errors='ignore') as fh: src = fh.read()
                    n = src.count('\n') + (0 if src.endswith('\n') or not src else 1)
                    if n > mx: out["frontendFiles"][p] = n
                    if 'innerHTML' in src:
                        h = html_holes(src)
                        if h: out["htmlInterpolations"][p] = h
                    if f.endswith(('.jsx', '.tsx')):
                        u = undeclared_jsx(src)
                        if u: out["undeclaredJsx"][p] = u
print(json.dumps(out, indent=1, sort_keys=True))
PY
}

# the live metric: the gate prints "metric categoryFallbacks=N"; empty when no fleet or not asked
live_fallbacks() {
  case " $* " in *" --live "*) ;; *) return 0 ;; esac
  python3 ops/arch/cfs_check.py 2>/dev/null | grep -oE '^metric categoryFallbacks=[0-9]+' | grep -oE '[0-9]+$' || true
}
LIVE=$(live_fallbacks "$@")

if [ "${1:-}" = "--baseline" ]; then
  current > "$BASE"
  if [ -n "$LIVE" ]; then
    python3 - "$BASE" "$LIVE" <<'PY'
import json, sys
b = json.load(open(sys.argv[1])); b["categoryFallbacks"] = int(sys.argv[2])
json.dump(b, open(sys.argv[1], "w"), indent=1, sort_keys=True); open(sys.argv[1], "a").write("\n")
PY
  fi
  echo "ratchet: baseline written to $BASE${LIVE:+ (categoryFallbacks pinned at $LIVE)}"; exit 0
fi
[ -f "$BASE" ] || { echo "ratchet: no baseline — run ops/arch/ratchet.sh --baseline"; exit 1; }

NOW=$(current)
python3 - "$BASE" "$NOW" "${1:-}" "${2:-}" "$LIVE" <<'PY'
import json, sys
base = json.load(open(sys.argv[1])); now = json.loads(sys.argv[2]); quick = sys.argv[3] == '--quick'; f = sys.argv[4]
live = sys.argv[5]
bad = []
if live != "" and "categoryFallbacks" in base:
    n, b = int(live), int(base["categoryFallbacks"])
    if n > b: bad.append(f"live: {n} service(s) fulfilled by the category fallback, baseline {b} — a product spec names no CFS; stamp it (seed_service_specifications.py or the Fulfilment picker) rather than let the category table decide")
for svc, n in now["mapReturns"].items():
    if quick and f and f'services/{svc}/' not in f: continue
    b = base["mapReturns"].get(svc, 0)
    if n > b: bad.append(f"services/{svc}: {n} public Map<String,Object> returns, baseline {b} — type the new one (record + mapper), see docs/engineering-conventions.md §1")
for svc, n in now.get("rawMapWrites", {}).items():
    if quick and f and f'services/{svc}/' not in f: continue
    b = base.get("rawMapWrites", {}).get(svc, 0)
    if n > b: bad.append(f"services/{svc}: {n} PATCH/PUT/POST handlers take a raw Map body, baseline {b} — declare a record for the body (mass-assignment surface), see docs/engineering-conventions.md §9")
for svc, n in now.get("nullIdGuards", {}).items():
    if quick and f and f'services/{svc}/' not in f: continue
    b = base.get("nullIdGuards", {}).get(svc, 0)
    if n > b: bad.append(f"services/{svc}: {n} String.valueOf(x.get(\"…id…\")) guards, baseline {b} — a missing value becomes the text \"null\"; refuse it or read it null-safely, see docs/engineering-conventions.md §9")
for p, n in now["frontendFiles"].items():
    if quick and f and not f.endswith(p): continue
    b = base["frontendFiles"].get(p, 0)
    if n > b: bad.append(f"{p}: {n} lines, baseline {b} — split before adding, see docs/engineering-conventions.md §2")
for p, n in now.get("htmlInterpolations", {}).items():
    if quick and f and not f.endswith(p): continue
    b = base.get("htmlInterpolations", {}).get(p, 0)
    if n > b: bad.append(f"{p}: {n} unescaped interpolations into innerHTML, baseline {b} — a name or a message off the wire reaches the DOM as markup; wrap it in esc(…), or build the node with createElement + textContent, see docs/engineering-conventions.md §4")
for p, n in now.get("undeclaredJsx", {}).items():
    if quick and f and not f.endswith(p): continue
    b = base.get("undeclaredJsx", {}).get(p, 0)
    if n > b: bad.append(f"{p}: {n} JSX component(s) used but never declared or imported, baseline {b} — the page throws 'X is not defined' the first time that branch renders; import it")
if bad:
    print("ARCHITECTURE RATCHET — regression:"); [print("  - " + m) for m in bad]; sys.exit(2)
tm = sum(now["mapReturns"].values()); tb = sum(base["mapReturns"].values())
tw = sum(now.get("rawMapWrites", {}).values()); twb = sum(base.get("rawMapWrites", {}).values())
tg = sum(now.get("nullIdGuards", {}).values()); tgb = sum(base.get("nullIdGuards", {}).values())
th = sum(now.get("htmlInterpolations", {}).values()); thb = sum(base.get("htmlInterpolations", {}).values())
fb = f"; category fallbacks {live} (baseline {base.get('categoryFallbacks', '?')})" if live != "" else ""
if not quick: print(f"ratchet ok: untyped public returns {tm} (baseline {tb}); raw-map write handlers {tw} (baseline {twb}); null-id guards {tg} (baseline {tgb}); unescaped innerHTML interpolations {th} (baseline {thb}); undeclared JSX components {sum(now.get("undeclaredJsx", {}).values())} (baseline {sum(base.get("undeclaredJsx", {}).values())}); oversized front-end files {len(now['frontendFiles'])} (baseline {len(base['frontendFiles'])}){fb}")
PY
