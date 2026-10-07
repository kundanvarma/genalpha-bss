#!/usr/bin/env python3
"""Every component validates its own tokens. There are 39 copies of
SecurityConfig to make that true, and 39 copies is 39 chances to drift.

CLAUDE.md has carried "a drift check across the copies is a follow-up" since
the security-hardening arc. This is it. Two things are checked:

1. INVARIANTS that must hold in every copy, because a copy that loses one is
   open in a way no test would notice: the terminal rule is authenticated (not
   permitAll), the component validates tokens itself, sessions are stateless,
   and the issuer resolver rejects an issuer the tenant registry does not know.

2. The ANONYMOUS SURFACE, declared in public-paths.txt. Each permitAll matcher
   in every copy must appear there. This does not claim the declared set is
   right -- several entries are deliberate and documented (a guest cart's
   random id is its bearer secret; the public catalog is readable by design).
   It claims the set cannot GROW without someone editing a file that says, in
   one place, everything the fleet exposes without a token.

Run: python3 ops/security/security_config_drift.py
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
DECLARED = pathlib.Path(__file__).parent / "public-paths.txt"


def strip_comments(src):
    """Scanned, not regexed. Every path here ends in "/**", so a regex for
    /* ... */ starts a "comment" inside a string literal and eats the rest of
    the file -- which is how the first version of this script reported four
    services with an anonymous surface instead of thirty-nine."""
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c == '"':                                  # string literal, verbatim
            out.append(c)
            i += 1
            while i < n:
                out.append(src[i])
                if src[i] == "\\":
                    i += 1
                    if i < n:
                        out.append(src[i])
                        i += 1
                    continue
                if src[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        if c == "'":                                  # char literal
            out.append(c)
            i += 1
            while i < n:
                out.append(src[i])
                if src[i] == "\\":
                    i += 1
                    if i < n:
                        out.append(src[i])
                        i += 1
                    continue
                if src[i] == "'":
                    i += 1
                    break
                i += 1
            continue
        if src.startswith("//", i):
            while i < n and src[i] != "\n":
                i += 1
            continue
        if src.startswith("/*", i):
            end = src.find("*/", i + 2)
            i = n if end < 0 else end + 2
            out.append(" ")
            continue
        out.append(c)
        i += 1
    return "".join(out)


def balanced(src, start):
    """Index just past the ( ... ) that starts at src[start] == '('."""
    depth, i, in_str = 0, start, False
    while i < len(src):
        c = src[i]
        if in_str:
            if c == "\\":
                i += 2
                continue
            if c == '"':
                in_str = False
        elif c == '"':
            in_str = True
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return -1


def permit_all_matchers(src):
    """Every argument of a requestMatchers(...) chained straight to permitAll()."""
    out = []
    for m in re.finditer(r"\.requestMatchers\s*\(", src):
        open_paren = src.index("(", m.start())
        end = balanced(src, open_paren)
        if end < 0:
            continue
        args = src[open_paren + 1:end - 1]
        tail = src[end:end + 40]
        if not re.match(r"\s*\.permitAll\s*\(\s*\)", tail):
            continue
        # one argument per top-level comma
        depth, cur, parts = 0, "", []
        in_str = False
        for ch in args:
            if in_str:
                cur += ch
                if ch == '"':
                    in_str = False
                continue
            if ch == '"':
                in_str = True
                cur += ch
            elif ch == "(":
                depth += 1
                cur += ch
            elif ch == ")":
                depth -= 1
                cur += ch
            elif ch == "," and depth == 0:
                parts.append(cur)
                cur = ""
            else:
                cur += ch
        parts.append(cur)
        for p in parts:
            token = re.sub(r"\s+", "", p)
            if not token or token.startswith("HttpMethod."):
                continue
            out.append(token)
    return out


def main():
    configs = sorted(ROOT.glob("services/*/src/main/java/com/bss/*/security/SecurityConfig.java"))
    if len(configs) < 30:
        print(f"drift: FAIL — found only {len(configs)} SecurityConfig copies; the glob is wrong")
        return 1

    declared = {}
    for line in DECLARED.read_text().splitlines():
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        svc, _, path = line.partition(" ")
        declared.setdefault(svc, set()).add(path.strip())

    problems = []
    for cfg in configs:
        svc = cfg.relative_to(ROOT).parts[1]
        src = strip_comments(cfg.read_text())
        flat = re.sub(r"\s+", " ", src)

        # --- invariants -------------------------------------------------
        terminal = re.findall(r"\.anyRequest\s*\(\s*\)\s*\.(\w+)", flat)
        if not terminal:
            problems.append((svc, "no anyRequest() terminal rule — anything unmatched is open"))
        elif any(t == "permitAll" for t in terminal):
            problems.append((svc, "anyRequest().permitAll() — the whole component is open"))

        if "oauth2ResourceServer" not in flat:
            problems.append((svc, "no oauth2ResourceServer — this component does not validate tokens"))
        if "SessionCreationPolicy.STATELESS" not in flat:
            problems.append((svc, "sessions are not STATELESS"))
        if "JwtIssuerAuthenticationManagerResolver" not in flat:
            problems.append((svc, "no multi-issuer resolver — one tenant's issuer would be trusted for all"))
        if "byIssuer" not in flat:
            problems.append((svc, "the resolver does not consult the tenant registry by issuer"))

        # --- the anonymous surface --------------------------------------
        found = set(permit_all_matchers(flat))
        known = declared.get(svc, set())
        for extra in sorted(found - known):
            problems.append((svc, f"opens {extra} without a token, and public-paths.txt does not say so"))
        for gone in sorted(known - found):
            problems.append((svc, f"public-paths.txt still declares {gone}, which the config no longer opens"))

    if problems:
        print(f"drift: FAIL — {len(problems)} problem(s) across {len(configs)} SecurityConfig copies")
        for svc, why in problems:
            print(f"  {svc}: {why}")
        print("\nA new anonymous path is a deliberate act: add it to")
        print("ops/security/public-paths.txt with a reason, in the same commit.")
        return 1

    total = sum(len(v) for v in declared.values())
    print(f"drift: clean — {len(configs)} SecurityConfig copies hold every invariant; "
          f"{total} declared anonymous paths, no more")
    return 0


if __name__ == "__main__":
    sys.exit(main())
