'use strict';

// THE AUDIENCE RULE-TREE BUILDER: compose an audience from a criteria tree,
// over the operator's OWN BSS data (a product held, later a churn band) — no
// dump to a marketing tool, no reverse-ETL round-trip to a warehouse and back.
// Behaviour (browsing interest) and analytics (GA4) leaves are here too, but
// the star is "Customer data": traits the BSS already knows, offered as real
// choices. Save -> POST /insight/v1/audience; Preview -> resolve members live.
async function renderAudienceBuilder() {
  const panel = copilotPanel();
  panel.replaceChildren();
  panel.dataset.testid = 'audience-builder';
  const intro = document.createElement('p');
  intro.className = 'dim';
  intro.style.cssText = 'font-size:13px;margin:6px 0 12px';
  intro.textContent = 'Build an audience from a rule tree over your OWN BSS data — a product held, '
    + 'a plan, a behaviour — no export to a marketing tool, no round-trip. Save it, then a campaign or '
    + 'journey targets it by name; Preview resolves the members live.';

  // BSS traits this tenant actually holds, grouped key -> [values], for real dropdowns.
  let facets = [];
  try { const r = await authFetch('/insight/v1/audience/facets'); if (r.ok) facets = await r.json(); } catch { /* offer free-text */ }
  const traitKeys = [...new Set(facets.map((f) => f.key))];
  const valuesFor = (k) => facets.filter((f) => f.key === k).map((f) => f.value);

  /* ---------- the builder card ---------- */
  const card = document.createElement('div');
  card.className = 'panel'; card.style.cssText = 'padding:14px 16px;margin-bottom:16px';
  const nameRow = document.createElement('div'); nameRow.className = 'staffbar';
  const name = document.createElement('input');
  name.id = 'audience-name'; name.placeholder = 'Audience name — e.g. Fibre holders at churn risk'; name.style.flex = '1';
  const popSel = document.createElement('select'); popSel.id = 'audience-population'; popSel.dataset.testid = 'audience-population';
  for (const [v, l] of [['customer', 'Customers'], ['prospect', 'Prospects (not customers yet)'], ['organization', 'Organizations (B2B)'], ['visitor', 'Visitors (anonymous / retargeting)']]) {
    const o = document.createElement('option'); o.value = v; o.textContent = l; popSel.append(o);
  }
  const matchSel = document.createElement('select'); matchSel.id = 'audience-match';
  for (const [v, l] of [['all', 'Match ALL conditions'], ['any', 'Match ANY condition']]) {
    const o = document.createElement('option'); o.value = v; o.textContent = l; matchSel.append(o);
  }
  nameRow.append(name, popSel, matchSel);
  // leaf types differ by population: customers have BSS/behaviour data,
  // prospects are filtered by where the lead came from.
  const leafTypes = () => popSel.value === 'prospect'
    ? [['source', 'Lead source']]
    : popSel.value === 'organization'
    ? [['trait', 'Company data (BSS)']]
    : popSel.value === 'visitor'
    ? [['interest', 'Browsing interest']]
    : [['trait', 'Customer data (BSS)'], ['interest', 'Behaviour (browsing)'], ['audience', 'Analytics audience']];

  const conds = document.createElement('div'); conds.id = 'audience-conditions'; conds.style.cssText = 'margin:12px 0';
  const addCondition = () => {
    const row = document.createElement('div');
    row.className = 'aud-cond'; row.dataset.testid = 'aud-cond';
    row.style.cssText = 'display:flex;gap:8px;align-items:center;margin:6px 0;flex-wrap:wrap';
    const not = document.createElement('label');
    not.style.cssText = 'display:flex;gap:4px;align-items:center;font-size:12px';
    const notBox = document.createElement('input'); notBox.type = 'checkbox'; notBox.dataset.testid = 'aud-cond-not';
    not.append(notBox, document.createTextNode('exclude'));
    const type = document.createElement('select'); type.dataset.testid = 'aud-cond-type';
    for (const [v, l] of leafTypes()) {
      const o = document.createElement('option'); o.value = v; o.textContent = l; type.append(o);
    }
    const valueWrap = document.createElement('span');
    valueWrap.style.cssText = 'display:flex;gap:8px;flex:1;min-width:220px';
    const renderValue = () => {
      valueWrap.replaceChildren();
      if (type.value === 'trait') {
        const key = document.createElement('select'); key.dataset.testid = 'aud-cond-key';
        if (traitKeys.length) {
          for (const k of traitKeys) { const o = document.createElement('option'); o.value = k; o.textContent = k; key.append(o); }
        } else { const o = document.createElement('option'); o.value = ''; o.textContent = '(no BSS traits yet)'; key.append(o); }
        const op = document.createElement('select'); op.dataset.testid = 'aud-cond-op'; op.style.maxWidth = '64px';
        for (const [v, l] of [['eq', '='], ['gte', '≥'], ['lte', '≤']]) { const o = document.createElement('option'); o.value = v; o.textContent = l; op.append(o); }
        const valBox = document.createElement('span'); valBox.style.cssText = 'display:flex;flex:1';
        const renderVal = () => {
          valBox.replaceChildren();
          if (op.value === 'eq') { // exact match: a dropdown of the values held
            const val = document.createElement('select'); val.dataset.testid = 'aud-cond-value'; val.style.flex = '1';
            for (const v of valuesFor(key.value)) { const o = document.createElement('option'); o.value = v; o.textContent = v; val.append(o); }
            valBox.append(val);
          } else { // numeric compare: a number to compare against (e.g. spend >= 50)
            const val = document.createElement('input'); val.dataset.testid = 'aud-cond-value'; val.type = 'number';
            val.placeholder = 'number, e.g. 50'; val.style.flex = '1'; valBox.append(val);
          }
        };
        key.addEventListener('change', () => { if (op.value === 'eq') renderVal(); });
        op.addEventListener('change', renderVal); renderVal();
        valueWrap.append(key, op, valBox);
      } else {
        const val = document.createElement('input'); val.dataset.testid = 'aud-cond-value'; val.style.flex = '1';
        val.placeholder = type.value === 'interest' ? 'interest category, e.g. Devices'
          : type.value === 'source' ? 'lead source, e.g. web-coverage or purchased:acme'
          : 'analytics audience name';
        valueWrap.append(val);
      }
    };
    type.addEventListener('change', renderValue); renderValue();
    const rm = document.createElement('button'); rm.className = 'ghost'; rm.type = 'button'; rm.textContent = '✕';
    rm.title = 'remove condition'; rm.addEventListener('click', () => row.remove());
    row.append(not, type, valueWrap, rm);
    conds.append(row);
  };
  addCondition();
  popSel.addEventListener('change', () => { conds.replaceChildren(); addCondition(); }); // leaf types change with population

  const actions = document.createElement('div'); actions.className = 'staffbar'; actions.style.marginTop = '4px';
  const addBtn = document.createElement('button'); addBtn.className = 'ghost'; addBtn.type = 'button';
  addBtn.textContent = '+ Add condition'; addBtn.dataset.testid = 'aud-add-cond';
  addBtn.addEventListener('click', addCondition);
  const save = document.createElement('button'); save.className = 'primary'; save.textContent = 'Save audience';
  save.dataset.testid = 'aud-save';
  const note = document.createElement('span'); note.className = 'dim'; note.style.cssText = 'font-size:12px;margin-left:8px';
  actions.append(addBtn, save, note);

  const collectCriteria = () => {
    const leaves = [];
    for (const row of conds.querySelectorAll('.aud-cond')) {
      const t = row.querySelector('[data-testid=aud-cond-type]').value;
      const value = (row.querySelector('[data-testid=aud-cond-value]').value || '').trim();
      if (!value) continue;
      let leaf = t === 'trait'
        ? { type: 'trait', key: row.querySelector('[data-testid=aud-cond-key]').value,
            op: (row.querySelector('[data-testid=aud-cond-op]') || {}).value || 'eq', value }
        : { type: t, value };
      if (row.querySelector('[data-testid=aud-cond-not]').checked) leaf = { not: leaf };
      leaves.push(leaf);
    }
    return { [matchSel.value]: leaves };
  };
  save.addEventListener('click', async () => {
    const nm = name.value.trim();
    const criteria = collectCriteria();
    if (!nm || !criteria[matchSel.value].length) { note.textContent = 'name and at least one condition are required'; return; }
    note.textContent = 'saving…';
    const res = await authFetch('/insight/v1/audience', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: nm, criteria, population: popSel.value }),
    });
    if (res.ok) { note.textContent = 'saved ✓'; name.value = ''; conds.replaceChildren(); addCondition(); loadSaved(); }
    else { note.textContent = 'save failed (HTTP ' + res.status + ')'; }
  });
  card.append(nameRow, conds, actions);

  /* ---------- saved audiences, with live member preview ---------- */
  const savedWrap = document.createElement('div'); savedWrap.dataset.testid = 'aud-saved';
  const summarize = (crit) => {
    const join = (arr, sep) => arr.map(one).join(sep);
    const one = (n) => {
      if (n.all) return '(' + join(n.all, ' AND ') + ')';
      if (n.any) return '(' + join(n.any, ' OR ') + ')';
      if (n.not) return 'NOT ' + one(n.not);
      if (n.type === 'trait') return n.key + '=' + n.value;
      return (n.type || '?') + ':' + n.value;
    };
    try { return one(typeof crit === 'string' ? JSON.parse(crit) : crit); } catch { return '—'; }
  };
  const loadSaved = async () => {
    savedWrap.replaceChildren();
    const h = document.createElement('h3'); h.textContent = 'Saved audiences'; h.style.cssText = 'font-size:14px;margin:8px 0';
    savedWrap.append(h);
    let list = [];
    try { const r = await authFetch('/insight/v1/audience'); if (r.ok) list = await r.json(); } catch { /* empty */ }
    if (!list.length) { const p = document.createElement('p'); p.className = 'dim'; p.textContent = 'No saved audiences yet.'; savedWrap.append(p); return; }
    for (const a of list) {
      const row = document.createElement('div'); row.className = 'panel'; row.dataset.testid = 'aud-row';
      row.style.cssText = 'padding:10px 12px;margin:6px 0;display:flex;gap:10px;align-items:center;justify-content:space-between';
      const left = document.createElement('div');
      const nm = document.createElement('strong'); nm.textContent = a.name; nm.dataset.testid = 'aud-row-name';
      const sm = document.createElement('div'); sm.className = 'dim'; sm.style.fontSize = '12px'; sm.textContent = summarize(a.criteria);
      left.append(nm, sm);
      const prev = document.createElement('button'); prev.className = 'ghost'; prev.type = 'button';
      prev.textContent = 'Preview members'; prev.dataset.testid = 'aud-preview';
      const count = document.createElement('span'); count.className = 'dim'; count.style.cssText = 'font-size:12px;margin-left:8px';
      prev.addEventListener('click', async () => {
        count.textContent = '…';
        const r = await authFetch(`/insight/v1/audience/${a.id}/members`);
        const m = r.ok ? await r.json() : [];
        count.textContent = m.length + (m.length === 1 ? ' member' : ' members');
      });
      const right = document.createElement('div'); right.append(prev, count);
      row.append(left, right); savedWrap.append(row);
    }
  };
  loadSaved();

  /* ---------- import prospects (a captured list, a bought list, a lead-form) ---------- */
  const importCard = document.createElement('div');
  importCard.className = 'panel'; importCard.dataset.testid = 'import-card';
  importCard.style.cssText = 'padding:14px 16px;margin-bottom:16px';
  const ih = document.createElement('h3'); ih.textContent = 'Import prospects (leads / lists)';
  ih.style.cssText = 'font-size:14px;margin:0 0 6px';
  const ihint = document.createElement('p'); ihint.className = 'dim'; ihint.style.cssText = 'font-size:12px;margin:0 0 10px';
  ihint.textContent = 'One per line: email, name (name optional). A lawful basis makes them reachable; leave it '
    + 'blank for a bought/unverified list — they are captured but NOT messaged until consented.';
  const emails = document.createElement('textarea'); emails.dataset.testid = 'prospect-emails';
  emails.rows = 4; emails.style.cssText = 'width:100%;box-sizing:border-box';
  emails.placeholder = 'ada@example.com, Ada Byrne\ngrace@example.com';
  const irow = document.createElement('div'); irow.className = 'staffbar'; irow.style.marginTop = '8px';
  const src = document.createElement('input'); src.dataset.testid = 'prospect-source'; src.placeholder = 'source, e.g. web-coverage'; src.style.flex = '1';
  const basis = document.createElement('input'); basis.dataset.testid = 'prospect-basis'; basis.placeholder = 'lawful basis (blank = unconsented)'; basis.style.flex = '1';
  const imp = document.createElement('button'); imp.className = 'primary'; imp.textContent = 'Import'; imp.dataset.testid = 'prospect-import';
  const ires = document.createElement('span'); ires.className = 'dim'; ires.dataset.testid = 'prospect-import-result'; ires.style.cssText = 'font-size:12px;margin-left:8px';
  irow.append(src, basis, imp, ires);
  imp.addEventListener('click', async () => {
    const rows = emails.value.split('\n').map((l) => l.trim()).filter(Boolean).map((l) => {
      const [email, ...rest] = l.split(',').map((s) => s.trim());
      return { email, name: rest.join(', ') || undefined, source: src.value.trim() || 'import', lawfulBasis: basis.value.trim() || undefined };
    }).filter((p) => p.email);
    if (!rows.length) { ires.textContent = 'paste at least one email'; return; }
    ires.textContent = 'importing…';
    const res = await authFetch('/insight/v1/prospect/import', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ prospects: rows, source: src.value.trim() || 'import' }),
    });
    if (res.ok) { const r = await res.json(); ires.textContent = `${r.imported} new, ${r.reachable} reachable, ${r.heldUnconsented} held (unconsented)`; emails.value = ''; }
    else { ires.textContent = 'import failed (HTTP ' + res.status + ')'; }
  });
  importCard.append(ih, ihint, emails, irow);

  /* ---------- ops: auto-refresh status + controls (pause without a restart) ---------- */
  const sched = document.createElement('div'); sched.className = 'panel'; sched.dataset.testid = 'scheduler-card';
  sched.style.cssText = 'padding:12px 16px;margin-top:16px';
  const sh = document.createElement('h3'); sh.textContent = 'Audience auto-refresh'; sh.style.cssText = 'font-size:14px;margin:0 0 6px';
  const sstat = document.createElement('div'); sstat.dataset.testid = 'scheduler-status'; sstat.style.cssText = 'font-size:12.5px;line-height:1.7';
  const sbar = document.createElement('div'); sbar.className = 'staffbar'; sbar.style.marginTop = '8px';
  const pauseBtn = document.createElement('button'); pauseBtn.className = 'ghost'; pauseBtn.type = 'button'; pauseBtn.textContent = 'Pause'; pauseBtn.dataset.testid = 'scheduler-pause';
  const resumeBtn = document.createElement('button'); resumeBtn.className = 'ghost'; resumeBtn.type = 'button'; resumeBtn.textContent = 'Resume'; resumeBtn.dataset.testid = 'scheduler-resume';
  const runBtn = document.createElement('button'); runBtn.className = 'ghost'; runBtn.type = 'button'; runBtn.textContent = 'Run now'; runBtn.dataset.testid = 'scheduler-run';
  sbar.append(pauseBtn, resumeBtn, runBtn);
  const paintSched = (s) => {
    if (!s) { sstat.textContent = 'status unavailable'; return; }
    const badge = s.enabled
      ? '<span data-testid="scheduler-enabled" style="color:#2e7d32;font-weight:600">● running</span>'
      : '<span data-testid="scheduler-enabled" style="color:#c62828;font-weight:600">● paused</span>';
    const last = s.lastRunAt ? new Date(s.lastRunAt).toLocaleTimeString() : '—';
    // badge and last are built above from literals and a formatted clock; the
    // counters come off the wire, so they are escaped like any other value.
    sstat.innerHTML = `${badge} · every ${Math.round((s.intervalMs || 0) / 1000)}s · cap ${esc(s.maxPerRun)}/run<br>`
      + `runs ${esc(s.totalRuns)} · refreshed ${esc(s.totalRefreshed)} · errors ${esc(s.totalErrors)} · last ${last} (${esc(s.lastDurationMs)}ms)<br>`
      + `<span class="dim">JVM heap ${esc(s.heapUsedMb)} / ${esc(s.heapMaxMb)} MB — watch this against runs to spot a memory climb</span>`;
    pauseBtn.disabled = !s.enabled; resumeBtn.disabled = s.enabled;
  };
  const loadSched = async () => { try { const r = await authFetch('/insight/v1/refresh/status'); paintSched(r.ok ? await r.json() : null); } catch { paintSched(null); } };
  const act = (path) => async () => { const r = await authFetch('/insight/v1/refresh/' + path, { method: 'POST' }); paintSched(r.ok ? await r.json() : null); };
  pauseBtn.addEventListener('click', act('pause'));
  resumeBtn.addEventListener('click', act('resume'));
  runBtn.addEventListener('click', act('run'));
  loadSched();
  sched.append(sh, sstat, sbar);

  panel.append(intro, importCard, card, savedWrap, sched);
  name.focus();
}
