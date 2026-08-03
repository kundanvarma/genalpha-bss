#!/usr/bin/env python3
"""The executive face of the capability map — GENERATED from
capability-map.md so the two views can never drift (rerun after editing
the md). Output: docs/capability-map.html (GitHub Pages)."""
import re, html, pathlib

md = pathlib.Path(__file__).with_name('capability-map.md').read_text()
domains = []
for m in re.finditer(r'^## (\d+\. .+?)$\n\n\|.*?\n\|[-| ]+\|\n((?:\|.*\n)+)', md, re.M):
    rows = []
    for line in m.group(2).strip().split('\n'):
        cells = [c.strip() for c in line.strip('|').split('|')]
        if len(cells) >= 3:
            status_cell = cells[-1]
            s = 'proven' if status_cell.startswith('✅') else ('partial' if status_cell.startswith('◐') else 'gap')
            rows.append({'cap': cells[0], 'tech': cells[1],
                         'comp': cells[2] if len(cells) > 3 else '', 'note': status_cell, 's': s})
    domains.append({'name': m.group(1), 'rows': rows})

counts = {'proven': 0, 'partial': 0, 'gap': 0}
for d in domains:
    for r in d['rows']:
        counts[r['s']] += 1

def esc(x):
    x = html.escape(x).replace('`', '')
    return re.sub(r'\*\*(.+?)\*\*', r'<b>\1</b>', x)

rows_html = ''
for d in domains:
    rows_html += f'<section><h2>{esc(d["name"])}</h2>'
    for r in d['rows']:
        rows_html += (f'<div class="row {r["s"]}" data-s="{r["s"]}">'
                      f'<span class="dot"></span><div class="body">'
                      f'<b>{esc(r["cap"])}</b>'
                      f'<span class="tech">{esc(r["tech"])}'
                      + (f' · <code>{esc(r["comp"])}</code>' if r['comp'] and r['comp'] != '—' else '')
                      + f'</span><span class="note">{esc(r["note"])}</span></div></div>')
    rows_html += '</section>'

page = f"""<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>genalpha-bss — the capability map</title>
<style>
:root{{--teal:#147673;--ink:#20262b;--dim:#6b7680;--line:#e3e8ea;--bg:#fff;--card:#f7f9f9;
--ok:#1e8e5a;--part:#b07d16;--gap:#b0433f}}
@media (prefers-color-scheme: dark){{:root{{--ink:#e8edee;--dim:#9aa6ad;--line:#2a3238;
--bg:#14181b;--card:#1b2125;--teal:#3aa39f;--ok:#4cc38a;--part:#d9a736;--gap:#e5726d}}}}
:root[data-theme="dark"]{{--ink:#e8edee;--dim:#9aa6ad;--line:#2a3238;--bg:#14181b;--card:#1b2125;--teal:#3aa39f;--ok:#4cc38a;--part:#d9a736;--gap:#e5726d}}
:root[data-theme="light"]{{--ink:#20262b;--dim:#6b7680;--line:#e3e8ea;--bg:#fff;--card:#f7f9f9;--teal:#147673;--ok:#1e8e5a;--part:#b07d16;--gap:#b0433f}}
*{{box-sizing:border-box}}body{{margin:0;color:var(--ink);background:var(--bg);
font:16px/1.55 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif}}
.wrap{{max-width:56rem;margin:0 auto;padding:2.5rem 1.3rem 4rem}}
h1{{font-size:1.9rem;margin:.2rem 0 .4rem;letter-spacing:-.02em}}
.lede{{color:var(--dim);max-width:44rem}}
.stats{{display:flex;flex-wrap:wrap;gap:.6rem;margin:1.4rem 0}}
.stat{{flex:1 1 8rem;background:var(--card);border:1px solid var(--line);border-radius:10px;
padding:.7rem 1rem}}.stat b{{display:block;font-size:1.5rem;font-variant-numeric:tabular-nums}}
.stat span{{font-size:.78rem;color:var(--dim)}}
.filters{{display:flex;gap:.5rem;flex-wrap:wrap;margin:0 0 1.4rem}}
.chip{{border:1px solid var(--line);background:var(--card);color:var(--ink);border-radius:999px;
padding:.35rem .9rem;font-size:.85rem;cursor:pointer}}
.chip.on{{background:var(--teal);border-color:var(--teal);color:#fff}}
h2{{font-size:1.05rem;color:var(--teal);margin:1.6rem 0 .5rem}}
.row{{display:flex;gap:.7rem;padding:.55rem .8rem;border:1px solid var(--line);
border-radius:9px;margin-bottom:.45rem;background:var(--card)}}
.row.hidden{{display:none}}
.dot{{flex:0 0 .65rem;height:.65rem;border-radius:50%;margin-top:.4rem}}
.proven .dot{{background:var(--ok)}}.partial .dot{{background:var(--part)}}.gap .dot{{background:var(--gap)}}
.body{{display:flex;flex-direction:column;gap:.1rem}}
.tech{{font-size:.8rem;color:var(--dim)}}
.note{{font-size:.82rem;color:var(--dim)}}
.proven .note{{color:var(--ok)}}.partial .note{{color:var(--part)}}.gap .note{{color:var(--gap)}}
code{{font-family:ui-monospace,Menlo,monospace;font-size:.85em}}
a{{color:var(--teal)}}footer{{margin-top:2.5rem;color:var(--dim);font-size:.85rem}}
</style></head><body><div class="wrap">
<h1>The capability map</h1>
<p class="lede">TM Forum business capabilities → the component that carries each — with a proof
suite behind every green dot, a caveat on every amber one, and the gaps in red, named out loud.
An integration plan, not a wall.</p>
<div class="stats">
<div class="stat"><b style="color:var(--ok)">{counts['proven']}</b><span>proven (suite-backed)</span></div>
<div class="stat"><b style="color:var(--part)">{counts['partial']}</b><span>partial — caveat attached</span></div>
<div class="stat"><b style="color:var(--gap)">{counts['gap']}</b><span>gaps — bring from elsewhere</span></div>
<div class="stat"><b>75</b><span>proof suites · 13 CTKs at zero</span></div>
</div>
<div class="filters">
<button class="chip on" data-f="all">All</button>
<button class="chip" data-f="proven">✅ Proven</button>
<button class="chip" data-f="partial">◐ Partial</button>
<button class="chip" data-f="gap">❌ Gaps</button>
</div>
{rows_html}
<footer>Generated from <a href="capability-map.md">capability-map.md</a> — the two views cannot
drift · <a href="index.html">← genalpha-bss</a></footer>
</div><script>
document.querySelectorAll('.chip').forEach(c=>c.addEventListener('click',()=>{{
document.querySelectorAll('.chip').forEach(x=>x.classList.remove('on'));c.classList.add('on');
const f=c.dataset.f;document.querySelectorAll('.row').forEach(r=>
r.classList.toggle('hidden',f!=='all'&&r.dataset.s!==f));}}));
</script></body></html>"""
pathlib.Path(__file__).with_name('capability-map.html').write_text(page)
print(f"capability-map.html: {sum(len(d['rows']) for d in domains)} rows, "
      f"{counts['proven']}✅ {counts['partial']}◐ {counts['gap']}❌")
