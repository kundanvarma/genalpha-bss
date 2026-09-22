'use strict';

// ---- the Suggestions panel: what the desk learned this week, with evidence,
// one-click actions, and the anonymised report for the vendor's backlog
async function renderDeskLearning() {
  const host = el('listing-body');
  host.replaceChildren();
  const panel = document.createElement('div');
  panel.className = 'desk-learning';
  panel.style.cssText = 'display:grid;gap:1rem;max-width:64rem';
  const intro = document.createElement('p'); intro.className = 'dim';
  intro.textContent = 'The desk learns from how it is used — actions, never screens; staff as a hash; no customer data. '
    + 'Suggestions with a button are safe to accept; the rest are for the people who build the product.';
  panel.append(intro);
  let sugg = [], friction = null;
  try {
    const [a, b] = await Promise.all([authFetch('/insight/v1/desk/suggestions'), authFetch('/insight/v1/desk/friction?days=7')]);
    sugg = a.ok ? await a.json() : []; friction = b.ok ? await b.json() : null;
  } catch { /* shown below */ }
  if (friction && friction.enabled === false) {
    const off = document.createElement('p'); off.textContent = 'Desk learning is switched off for this tenant (desk-learning in the tenant configuration).'; panel.append(off);
    host.append(panel); return;
  }
  const live = sugg.filter((x) => !x.quiet);
  const h = document.createElement('h3'); h.textContent = live.length ? `${live.length} suggestion${live.length === 1 ? '' : 's'} this week` : 'No suggestions yet — the desk needs a week of use to notice patterns.';
  panel.append(h);
  for (const sg of live) {
    const card = document.createElement('div');
    card.style.cssText = 'border:1px solid var(--line,#e5e5ea);border-radius:8px;padding:.8rem 1rem;display:grid;gap:.4rem;background:#fff';
    // the server speaks in keys (page paths, field names); this desk knows the words people see
    const pageOf = (path) => RESOURCES.find((r) => r.path === path);
    const pageName = (path) => (pageOf(path) || {}).title || path;
    const fieldName = (path, name) => { const f = ((pageOf(path) || {}).fields || []).find((x) => x.name === name); return f ? f.label.replace(/\s*\(.*$/, '') : name; };
    let title = sg.title, evidence = sg.evidence;
    if (sg.form && sg.kind === 'preset') {
      title = `Save a preset for the ${pageName(sg.form)} form`;
      evidence = `${sg.count} submissions in 7 days shared the same ${(sg.fields || []).map((f) => fieldName(sg.form, f)).join(', ')} — one click would prefill them.`;
    } else if (sg.form && sg.kind === 'abandon') {
      title = `People start the ${pageName(sg.form)} form and leave`;
      evidence = `${sg.count} abandoned starts; most stop at "${fieldName(sg.form, sg.stopField)}". A default, a hint or a preset there would help.`;
    } else if (sg.form && sg.kind === 'rewrite') {
      title = `Copilot drafts for ${pageName(sg.form)} are rewritten before use`;
    }
    const t = document.createElement('strong'); t.textContent = title; card.append(t);
    const ev = document.createElement('div'); ev.className = 'dim'; ev.textContent = evidence; card.append(ev);
    if (Array.isArray(sg.features) && sg.features.length) {
      // the server knows pages by their keys; this desk knows their names — say the names,
      // and let one click open the page (its goal line and ? drawer say what it is for)
      const named = sg.features.map((p) => RESOURCES.find((r) => r.path === p)).filter(Boolean);
      const shown = named.slice(0, 8);
      ev.textContent = `In ${sg.actions} desk actions by ${sg.people === 1 ? 'one person' : sg.people + ' people'} this week, ${sg.opened} pages were used and these ${sg.features.length} never. `
        + 'Open one to see what it is for — every page has a goal line and a ? help drawer.';
      const links = document.createElement('div'); links.style.cssText = 'display:flex;flex-wrap:wrap;gap:.3rem';
      for (const r of shown) {
        const b = document.createElement('button'); b.className = 'ghost small'; b.textContent = r.title; b.title = `Open ${r.title}`;
        b.addEventListener('click', () => { active = r; offset = 0; listFilter = ''; listSortCol = null; stopEditing(); sessionStorage.setItem('bss.console.tab', r.path); renderTabs(); loadList(); });
        links.append(b);
      }
      if (named.length > shown.length) { const more = document.createElement('span'); more.className = 'dim'; more.style.alignSelf = 'center'; more.textContent = `and ${named.length - shown.length} more`; links.append(more); }
      card.append(links);
    }
    const who = document.createElement('span'); who.className = 'pill'; who.textContent = sg.audience === 'vendor' ? 'for the product team' : 'for this desk'; who.style.cssText = 'font-size:.75rem;justify-self:start'; card.append(who);
    const row = document.createElement('div'); row.style.cssText = 'display:flex;gap:.5rem';
    if (sg.action) {
      const ok = document.createElement('button'); ok.className = 'primary small'; ok.textContent = sg.action.kind === 'preset' ? 'Save preset' : 'Apply';
      ok.addEventListener('click', async () => {
        ok.disabled = true;
        try {
          const r = await authFetch(`/insight/v1/desk/suggestions/${sg.id}/accept`, { method: 'POST' });
          const res = r.ok ? await r.json() : {};
          if (res.action && res.action.path) {
            // the desk runs the fix with the signed-in user's own rights — insight holds no credential for it
            const done = await authFetch(res.action.path, { method: res.action.method || 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(res.action.body || {}) });
            ev.textContent = done.ok ? 'Applied.' : `The desk could not apply it (${done.status}).`;
          } else if (res.preset) {
            ev.textContent = `Saved as "${res.preset.name}" — it appears above the ${res.preset.form} form.`;
          } else { ev.textContent = 'Accepted.'; }
          desk('suggestion.accept', sg.kind, { id: sg.id });
        } catch (e) { ev.textContent = 'Could not apply: ' + e.message; }
      });
      row.append(ok);
    }
    const no = document.createElement('button'); no.className = 'ghost small'; no.textContent = 'Dismiss';
    no.addEventListener('click', async () => { await authFetch(`/insight/v1/desk/suggestions/${sg.id}/dismiss`, { method: 'POST' }).catch(() => {}); desk('suggestion.dismiss', sg.kind, { id: sg.id }); card.remove(); });
    row.append(no); card.append(row); panel.append(card);
  }
  if (friction) {
    const fh = document.createElement('h3'); fh.textContent = 'Friction report, last 7 days'; panel.append(fh);
    const grid = document.createElement('div'); grid.style.cssText = 'display:grid;grid-template-columns:repeat(auto-fit,minmax(14rem,1fr));gap:.6rem';
    const stat = (label, value) => { const d = document.createElement('div'); d.style.cssText = 'border:1px solid var(--line,#e5e5ea);border-radius:8px;padding:.6rem .8rem;background:#fafafa'; const v = document.createElement('div'); v.style.cssText = 'font-size:1.4rem;font-weight:700'; v.textContent = value; const l = document.createElement('div'); l.className = 'dim'; l.textContent = label; d.append(v, l); return d; };
    grid.append(stat('desk actions', friction.events), stat('people active', friction.activeStaff),
      stat('forms abandoned', (friction.abandonedForms || []).reduce((n, x) => n + (x.count || 0), 0)),
      stat('empty searches', (friction.emptySearches || []).reduce((n, x) => n + (x.count || 0), 0)),
      stat('copilot drafts rewritten', (friction.copilotRewrites || []).reduce((n, x) => n + (x.count || 0), 0)),
      stat('pages nobody opened', (friction.unusedFeatures || []).length));
    panel.append(grid);
    const exp = document.createElement('button'); exp.className = 'ghost small'; exp.textContent = 'Copy the anonymised report for the product team';
    exp.style.justifySelf = 'start';
    exp.addEventListener('click', async () => {
      try { const r = await authFetch('/insight/v1/desk/export?days=7'); const j = await r.json(); await navigator.clipboard.writeText(JSON.stringify(j, null, 2)); exp.textContent = 'Copied — counts only, no names, no values.'; } catch { exp.textContent = 'Could not copy.'; }
    });
    panel.append(exp);
  }
  host.append(panel);
}
