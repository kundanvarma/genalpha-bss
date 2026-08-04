#!/usr/bin/env python3
"""The proof-run receipt: results.tsv -> docs/proof-run.html.
One row per suite, honest verdicts, total wall clock. Regenerate after a
run with: python3 docs/build-proof-run-html.py [run-date-label]"""
import sys
from datetime import date
from pathlib import Path

root = Path(__file__).resolve().parent.parent
results = root / 'ops/e2e/.proof-run/results.tsv'
label = sys.argv[1] if len(sys.argv) > 1 else str(date.today())

best = {}
order = []
wall = 0
for line in results.read_text().splitlines():
    parts = line.split('\t')
    name, verdict, seconds = parts[0], parts[1], int(parts[2])
    attempt = int(parts[4]) if len(parts) > 4 else 1
    wall += seconds
    if name not in best:
        order.append(name)
        best[name] = (verdict, seconds, attempt)
    elif verdict == 'pass' and best[name][0] != 'pass':
        best[name] = (verdict, seconds, attempt)
rows = [(n, best[n][0], best[n][1], best[n][2]) for n in order]

npass = sum(1 for r in rows if r[1] == 'pass')
total = len(rows)
badge = {'pass': ('✅', '#2c8a4b'), 'fail': ('❌', '#c0392b'), 'timeout': ('⏱', '#c07a2b')}

body = []
for name, verdict, seconds, attempt in rows:
    icon, color = badge.get(verdict, ('?', '#666'))
    mins = f"{seconds // 60}m {seconds % 60:02d}s" if seconds >= 60 else f"{seconds}s"
    note = ' · 2nd attempt' if attempt > 1 else ''
    body.append(f'<tr><td>{name}</td>'
                f'<td style="color:{color};font-weight:600">{icon} {verdict}{note}</td>'
                f'<td class="num">{mins}</td></tr>')

html = f"""<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>genalpha-bss — the proof run</title>
<style>
  :root {{ --teal:#147673; --ink:#20262b; --dim:#6b7680; --line:#e3e8ea; }}
  body {{ margin:0; font:15px/1.6 Georgia, serif; color:var(--ink); }}
  .page {{ max-width:46rem; margin:0 auto; padding:2rem 1.4rem 5rem; }}
  h1 {{ font-size:1.9rem; margin:.2rem 0 .3rem; }}
  .kicker {{ font-family:-apple-system,sans-serif; font-size:.72rem; letter-spacing:.16em;
             color:var(--teal); text-transform:uppercase; }}
  .stat {{ font-family:-apple-system,sans-serif; font-size:1.05rem; margin:.8rem 0 1.4rem; }}
  .stat b {{ font-size:1.5rem; }}
  table {{ border-collapse:collapse; width:100%; font-size:.92em; }}
  td {{ padding:.34rem .6rem; border-bottom:1px solid var(--line);
       font-family:ui-monospace, Menlo, monospace; font-size:.86em; }}
  td.num {{ text-align:right; color:var(--dim); font-variant-numeric:tabular-nums; }}
  footer {{ margin-top:2rem; color:var(--dim); font-size:.85rem; }}
  a {{ color:var(--teal); }}
</style></head><body><div class="page">
<div class="kicker">Receipt · genalpha-bss</div>
<h1>The proof run</h1>
<p>Every suite, serially, one command (<code style="font-family:ui-monospace,Menlo,monospace">ops/run-all-suites.sh</code>) —
the claim "every feature is verified end-to-end" as a single reproducible fact.</p>
<div class="stat"><b style="color:#2c8a4b">{npass}</b> / {total} suites passed ·
total wall clock {wall // 3600}h {(wall % 3600) // 60}m · run of {label}</div>
<table>{''.join(body)}</table>
<footer>Generated from ops/e2e/.proof-run/results.tsv ·
<a href="capability-map.html">the capability map</a> · <a href="index.html">← genalpha-bss</a></footer>
</div></body></html>"""

out = root / 'docs/proof-run.html'
out.write_text(html)
print(f"proof-run.html: {npass}/{total} passed, wall {wall // 3600}h {(wall % 3600) // 60}m")
