#!/usr/bin/env python3
"""EVERY FIXTURE A SUITE ASSERTS ON MUST BE CREATABLE BY THE SEED PATH.

A suite that asserts on a product, plan, place or label which no seed in
ops/seed/manifest.txt creates cannot pass on a fleet seeded from scratch. It
can still pass on a machine where somebody once ran the right script by hand,
which is exactly how the laptop got into a state nobody could reproduce and the
committed proof run sat at 122 of 258 suites since 24 September (#261).

HOW IT DECIDES. A "fixture" here is a quoted string that a suite mentions and
some seed script also contains. That is deliberately narrow: it cannot see a
fixture nothing creates (that is a broken suite, not a seed gap), and it does
not try to parse the seeds. What it catches is the real failure mode — a
fixture a seed DOES create, from a script the seed path never runs.

Generic vocabulary is filtered out, because "Individual", "Overview" and
"ProductOrder" appear in both suites and seeds without being fixtures: a
candidate must carry a space or a digit, and must not be spread across more
than MAX_OWNERS seed files. Without that filter the first version of this
reported 157 "gaps", nearly all of them TMF type names.

Run: python3 ops/arch/seed_coverage_check.py [--verbose]
"""

import collections
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "ops/seed/manifest.txt"
SEEDS = ROOT / "ops/seed"
SUITES = ROOT / "ops/e2e"
ALLOW = ROOT / "ops/arch/seed-coverage-allow.txt"

MAX_OWNERS = 5          # a string in more seeds than this is vocabulary
LITERAL = re.compile(r"""['"]([A-Z][A-Za-z0-9][^'"]{6,48})['"]""")


def manifest_scripts():
    names = []
    if not MANIFEST.exists():
        return names
    for raw in MANIFEST.read_text().splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) >= 2:
            names.append(parts[1])
    return names


def allowed():
    if not ALLOW.exists():
        return set()
    out = set()
    for raw in ALLOW.read_text().splitlines():
        line = raw.split("#", 1)[0].strip()
        if line:
            out.add(line)
    return out


def main():
    verbose = "--verbose" in sys.argv
    seeded = manifest_scripts()
    if not seeded:
        print("seed-coverage: FAIL — ops/seed/manifest.txt names no scripts")
        return 1

    seeds = {f.stem: f.read_text(errors="ignore") for f in SEEDS.glob("*.py")}
    skip = allowed()

    gaps = collections.defaultdict(set)
    covered = 0
    for suite in sorted(SUITES.glob("*_test.js")):
        text = suite.read_text(errors="ignore")
        for lit in set(LITERAL.findall(text)):
            if lit.startswith(("http", "/", "Bearer")) or lit in skip:
                continue
            if not re.search(r"[a-z]", lit):
                continue
            if " " not in lit and not re.search(r"\d", lit):
                continue
            owners = [s for s, t in seeds.items() if lit in t]
            if not owners or len(owners) > MAX_OWNERS:
                continue
            if any(o in seeded for o in owners):
                covered += 1
            else:
                for o in owners:
                    gaps[o].add((suite.stem, lit))

    if not gaps:
        print(f"seed-coverage: clean — {covered} suite fixture(s) all reachable "
              f"from the {len(seeded)} script(s) in the seed path")
        return 0

    total = len({f for v in gaps.values() for f in v})
    print(f"seed-coverage: FAIL — {total} fixture(s) that suites assert on are created "
          f"only by scripts the seed path never runs")
    for script, items in sorted(gaps.items(), key=lambda kv: -len(kv[1])):
        suites = sorted({s for s, _ in items})
        print(f"  {script}  — {len(items)} fixture(s) across {len(suites)} suite(s)")
        for s, lit in sorted(items)[: (99 if verbose else 3)]:
            print(f"      {s}: {lit!r}")
    print("\nAdd the script to ops/seed/manifest.txt in the right place, or — if the")
    print("match is vocabulary rather than a fixture — to ops/arch/seed-coverage-allow.txt")
    print("with a reason. A suite asserting on a fixture no seed path creates cannot")
    print("pass on a fleet seeded from scratch.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
