#!/usr/bin/env python3
"""THE PROOF RUN HAS TO PROVE SOMETHING.

The claims gate has always checked that the number of suites in the README
matches the number of files in ops/e2e. It never checked that any of them
PASS. So the committed proof run sat at 122 of 258 suites with 30 failures
from 24 September 2026 while every document said "258 browser suites prove
the behaviour end to end" -- the exact shape of claim this repository has a
gate for, in the one artifact that is supposed to be the evidence.

Two things are checked here, both against ops/arch/proof-baseline.txt:

  covered  how many suites the committed run actually ran
  green    how many of them passed

Both are RATCHETS: they may rise and never fall. That is deliberate. A hard
"every suite must be green" gate would block every unrelated pull request on
the day one suite goes flaky, and a gate people route around is worse than no
gate. A ratchet makes the number visible on every build, lets it only improve,
and makes a regression a failed build rather than a discovery.

The run's own age is the third thing worth knowing and the artifact cannot
tell us: results.tsv has no timestamp, and docs/build-proof-run-html.py takes
the date as a command-line argument. So the age below comes from git, and it
is REPORTED, not enforced -- an age failure would fire on a quiet week and
teach people to ignore the gate.

Run: python3 ops/arch/proof_run_check.py
"""

import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
RESULTS = ROOT / "ops/e2e/.proof-run/results.tsv"
BASELINE = ROOT / "ops/arch/proof-baseline.txt"


def read_baseline():
    values = {}
    if not BASELINE.exists():
        return values
    for line in BASELINE.read_text().splitlines():
        line = line.split("#", 1)[0].strip()
        if not line or "=" not in line:
            continue
        k, _, v = line.partition("=")
        try:
            values[k.strip()] = int(v.strip())
        except ValueError:
            pass
    return values


def age_days():
    """From git, because the artifact does not date itself."""
    try:
        out = subprocess.run(
            ["git", "log", "-1", "--format=%ct", "--", str(RESULTS.relative_to(ROOT))],
            cwd=ROOT, capture_output=True, text=True, timeout=20)
        stamp = out.stdout.strip()
        if not stamp:
            return None
        import time
        return int((time.time() - int(stamp)) / 86400)
    except Exception:
        return None


def main():
    suites = len(list((ROOT / "ops/e2e").glob("*_test.js")))

    if not RESULTS.exists() or not RESULTS.read_text().strip():
        print("proof-run: FAIL — no committed proof run at ops/e2e/.proof-run/results.tsv")
        print("           every document claiming suites prove the behaviour is unsupported")
        return 1

    verdicts = {}
    for line in RESULTS.read_text().splitlines():
        parts = line.split("\t")
        if len(parts) < 2 or not parts[0].strip():
            continue
        # one row per suite; the runner's retry pass can append a second row,
        # so the LAST verdict for a suite is the one that counts
        verdicts[parts[0].strip()] = parts[1].strip()

    covered = len(verdicts)
    green = sum(1 for v in verdicts.values() if v == "pass")
    red = sorted(n for n, v in verdicts.items() if v == "fail")
    notready = sorted(n for n, v in verdicts.items() if v == "notready")

    base = read_baseline()
    want_covered = base.get("covered", 0)
    want_green = base.get("green", 0)

    days = age_days()
    age = "unknown age" if days is None else f"last committed {days} day(s) ago"
    print(f"proof-run: {green} green of {covered} run, {suites} suites on disk ({age})")
    if red:
        print(f"           red: {', '.join(red[:8])}{' …' if len(red) > 8 else ''}")
    if notready:
        print(f"           notready: {', '.join(notready[:6])}{' …' if len(notready) > 6 else ''}")
    if covered < suites:
        print(f"           {suites - covered} suite(s) on disk were never run")

    problems = []
    if covered < want_covered:
        problems.append(f"coverage fell: {covered} suites run, baseline {want_covered}")
    if green < want_green:
        problems.append(f"green fell: {green} suites pass, baseline {want_green}")

    if problems:
        print("proof-run: FAIL")
        for p in problems:
            print(f"           {p}")
        print("\nThe proof run is the evidence behind every 'suites prove it' sentence.")
        print("Re-run it (bash ops/run-all-suites.sh), fix what broke, and commit")
        print("results.tsv with the baseline raised — never lowered.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
