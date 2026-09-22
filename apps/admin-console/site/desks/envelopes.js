'use strict';

/* ---------------- Launch governance: envelopes (pre-approved launch shapes) ---------------- */
// An envelope is a TMF723 policy rule (domain "launch", effect "allow"). The
// pickers are the truth the human sees; the JSON-logic condition is compiled
// from them and stored beside them (experience.envelope) so it can be edited
// back with the same pickers. Nobody ever types a channel id.
const AGENT_CHANNELS = ['agent-acp', 'agent-mcp', 'agent-a2a'];

function envelopeCondition(p) {
  const and = [];
  if ((p.category || []).length) and.push({ or: p.category.map((c) => ({ in: [c.toLowerCase(), { var: 'category' }] })) });
  if (p.priceType && p.priceType !== 'any') and.push({ '==': [{ var: 'priceType' }, p.priceType] });
  if (p.priceMin != null && p.priceMin !== '') and.push({ '>=': [{ var: 'price' }, Number(p.priceMin)] });
  if (p.priceMax != null && p.priceMax !== '') and.push({ '<=': [{ var: 'price' }, Number(p.priceMax)] });
  if (p.allowanceMaxGb != null && p.allowanceMaxGb !== '') and.push({ or: [{ '!': { var: 'allowanceGb' } }, { '<=': [{ var: 'allowanceGb' }, Number(p.allowanceMaxGb)] }] });
  if (p.validityMaxDays != null && p.validityMaxDays !== '') and.push({ or: [{ '!': { var: 'validityDays' } }, { '<=': [{ var: 'validityDays' }, Number(p.validityMaxDays)] }] });
  if ((p.channel || []).length && p.channel.length < CHANNELS.length) {
    const outside = CHANNELS.map((c) => c.value).filter((c) => !p.channel.includes(c));
    and.push({ none: [{ var: 'channel' }, { in: [{ var: '' }, outside] }] });
  }
  if (p.zeroRatedApps === 'no') and.push({ '!': { var: 'zeroRatedApps' } });
  if (p.bundles === 'no') and.push({ '!': { var: 'isBundle' } });
  return and.length ? { and } : { '==': [1, 1] };
}

function envelopeSentence(p, cur) {
  const parts = [];
  parts.push((p.category || []).length ? `${p.category.join(' or ')} offers` : 'any offer');
  if (p.priceMin !== '' && p.priceMin != null && p.priceMax !== '' && p.priceMax != null) parts.push(`priced ${p.priceMin}–${p.priceMax} ${cur}`);
  else if (p.priceMax !== '' && p.priceMax != null) parts.push(`under ${p.priceMax} ${cur}`);
  else if (p.priceMin !== '' && p.priceMin != null) parts.push(`from ${p.priceMin} ${cur}`);
  if (p.priceType && p.priceType !== 'any') parts.push(p.priceType === 'recurring' ? 'per month' : 'one-off');
  if (p.allowanceMaxGb !== '' && p.allowanceMaxGb != null) parts.push(`up to ${p.allowanceMaxGb} GB`);
  if (p.validityMaxDays !== '' && p.validityMaxDays != null) parts.push(`valid ${p.validityMaxDays} days or less`);
  if ((p.channel || []).length && p.channel.length < CHANNELS.length) parts.push(`sold only via ${p.channel.map((id) => (CHANNELS.find((c) => c.value === id) || {}).label || id).join(', ')}`);
  if (p.zeroRatedApps === 'no') parts.push('no zero-rated apps');
  if (p.bundles === 'no') parts.push('no bundles');
  return parts.join(', ') + ' — launch without approval.';
}

// a tiny JSON-logic evaluator for the live preview (the server one decides for real)
function jsonLogicApply(rule, data) {
  if (rule === null || typeof rule !== 'object' || Array.isArray(rule)) return rule;
  const op = Object.keys(rule)[0]; const args = [].concat(rule[op]);
  const v = (x) => jsonLogicApply(x, data);
  const get = (path) => (path === '' ? data : String(path).split('.').reduce((o, k) => (o == null ? undefined : o[k]), data));
  switch (op) {
    case 'var': return get(args[0]);
    case 'and': return args.every((a) => v(a));
    case 'or': return args.some((a) => v(a));
    case '!': return !v(args[0]);
    case '!!': return Boolean(v(args[0]));
    case '==': return v(args[0]) == v(args[1]); // eslint-disable-line eqeqeq
    case '>=': return Number(v(args[0])) >= Number(v(args[1]));
    case '<=': return Number(v(args[0])) <= Number(v(args[1]));
    case '>': return Number(v(args[0])) > Number(v(args[1]));
    case '<': return Number(v(args[0])) < Number(v(args[1]));
    case 'in': { const a = v(args[0]); const b = v(args[1]); return Array.isArray(b) ? b.includes(a) : String(b || '').includes(a); }
    case 'none': { const arr = v(args[0]) || []; return !arr.some((item) => jsonLogicApply(args[1], item)); }
    case 'some': { const arr = v(args[0]) || []; return arr.some((item) => jsonLogicApply(args[1], item)); }
    case 'all': { const arr = v(args[0]) || []; return arr.every((item) => jsonLogicApply(args[1], item)); }
    default: return false;
  }
}

async function renderEnvelopes() {
  const panel = copilotPanel();
  document.getElementById('list-search')?.setAttribute('hidden', '');
  document.querySelector('.pager')?.setAttribute('hidden', '');
  panel.replaceChildren();
  panel.dataset.testid = 'envelopes';
  const cur = (window.BSS_CONSOLE_CONFIG || {}).currency || '';
  const settings = await authFetch(`${API_BASE}/governance/settings`).then((r) => (r.ok ? r.json() : null)).catch(() => null);
  const intro = document.createElement('p'); intro.className = 'dim'; intro.style.cssText = 'font-size:13px;margin:6px 0 12px';
  intro.textContent = 'An envelope is a launch shape the business has already approved: category, price band, allowance, validity, channels. '
    + 'A draft inside one launches by itself and the ledger names the envelope; anything outside waits for an approver. '
    + (settings ? `This tenant: launch-governance = ${settings.mode}.` : '');
  const bar = document.createElement('div'); bar.className = 'staffbar';
  const add = document.createElement('button'); add.className = 'primary'; add.textContent = 'New envelope'; add.dataset.testid = 'envelope-new';
  const draft = document.createElement('button'); draft.textContent = 'Draft from a sentence…'; draft.dataset.testid = 'envelope-draft';
  bar.append(add, draft);
  const list = document.createElement('div'); list.dataset.testid = 'envelope-list';
  const editor = document.createElement('div'); editor.hidden = true; editor.dataset.testid = 'envelope-editor';
  editor.style.cssText = 'border:1px solid var(--line,#ddd);border-radius:10px;padding:12px 14px;margin:0 0 14px';
  panel.append(intro, bar, editor, list);

  const cats = await authFetch(`${API_BASE}/category?limit=100`).then((r) => (r.ok ? r.json() : [])).catch(() => []);
  const catNames = Array.from(new Set(cats.map((c) => c.name).filter(Boolean)));

  const openEditor = (rule) => {
    const p = Object.assign({ category: [], priceType: 'any', priceMin: '', priceMax: '', allowanceMaxGb: '', validityMaxDays: '',
      channel: CHANNELS.map((c) => c.value), zeroRatedApps: 'any', bundles: 'any' }, (rule && rule.experience && rule.experience.envelope) || {});
    editor.hidden = false; editor.replaceChildren();
    const h = document.createElement('h3'); h.style.cssText = 'font-size:15px;margin:0 0 8px'; h.textContent = rule && rule.id ? `Edit envelope: ${rule.name}` : 'New envelope';
    const grid = document.createElement('div'); grid.style.cssText = 'display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px 16px;align-items:start';
    const field = (label, control, span) => { const l = document.createElement('label'); l.className = 'field'; if (span) l.style.gridColumn = `span ${span}`; const sp = document.createElement('span'); sp.textContent = label; l.append(sp, control); return l; };
    const name = document.createElement('input'); name.value = rule ? rule.name || '' : ''; name.placeholder = 'Everyday top-ups'; name.dataset.testid = 'env-name';
    const cat = document.createElement('div'); cat.style.cssText = 'display:grid;grid-template-columns:repeat(auto-fill,minmax(160px,1fr));gap:6px 14px;padding:8px 10px;border:1px solid var(--line,#ddd);background:var(--paper,#fff)';
    for (const c of catNames) { const l = document.createElement('label'); l.style.cssText = 'display:inline-flex;gap:4px;font-weight:400'; const cb = document.createElement('input'); cb.type = 'checkbox'; cb.value = c; cb.checked = (p.category || []).map((x) => x.toLowerCase()).includes(c.toLowerCase()); cb.dataset.testid = `env-cat-${c}`; cb.addEventListener('change', () => { p.category = [...cat.querySelectorAll('input:checked')].map((x) => x.value); sync(); }); l.append(cb, document.createTextNode(c)); cat.append(l); }
    const sel = (opts, val, on) => { const s = document.createElement('select'); for (const [v, lab] of opts) { const o = document.createElement('option'); o.value = v; o.textContent = lab; if (v === val) o.selected = true; s.append(o); } s.addEventListener('change', () => { on(s.value); sync(); }); return s; };
    const num = (val, ph, on, testid) => { const i = document.createElement('input'); i.type = 'number'; i.min = '0'; i.value = val; i.placeholder = ph; i.dataset.testid = testid; i.addEventListener('input', () => { on(i.value); sync(); }); return i; };
    const priceType = sel([['any', 'any'], ['recurring', 'per month'], ['oneTime', 'one-off']], p.priceType, (v) => { p.priceType = v; });
    const priceMin = num(p.priceMin, '0', (v) => { p.priceMin = v; }, 'env-price-min');
    const priceMax = num(p.priceMax, '399', (v) => { p.priceMax = v; }, 'env-price-max');
    const allowance = num(p.allowanceMaxGb, 'any', (v) => { p.allowanceMaxGb = v; }, 'env-allowance');
    const validity = num(p.validityMaxDays, 'any', (v) => { p.validityMaxDays = v; }, 'env-validity');
    const ch = document.createElement('div'); ch.style.cssText = 'display:grid;grid-template-columns:repeat(auto-fill,minmax(160px,1fr));gap:6px 14px;padding:8px 10px;border:1px solid var(--line,#ddd);background:var(--paper,#fff)';
    for (const c of CHANNELS) { const l = document.createElement('label'); l.style.cssText = 'display:inline-flex;gap:4px;font-weight:400'; const cb = document.createElement('input'); cb.type = 'checkbox'; cb.value = c.value; cb.checked = (p.channel || []).includes(c.value); cb.dataset.testid = `env-ch-${c.value}`; cb.addEventListener('change', () => { p.channel = [...ch.querySelectorAll('input:checked')].map((x) => x.value); sync(); }); l.append(cb, document.createTextNode(c.label)); ch.append(l); }
    const zero = sel([['any', 'allowed'], ['no', 'not allowed']], p.zeroRatedApps, (v) => { p.zeroRatedApps = v; });
    const bundles = sel([['any', 'allowed'], ['no', 'not allowed']], p.bundles, (v) => { p.bundles = v; });
    grid.append(field('Name', name, 2), field('Price type', priceType),
      field(`Price from (${cur})`, priceMin), field(`Price up to (${cur})`, priceMax), field('Data allowance up to (GB)', allowance),
      field('Validity up to (days)', validity), field('Zero-rated apps', zero), field('Bundles', bundles),
      field('Categories (any of — none ticked = any category)', cat, 3), field('Channels (the offer may be sold only through these)', ch, 3));
    const readback = document.createElement('p'); readback.dataset.testid = 'env-readback'; readback.style.cssText = 'margin:10px 0 4px;font-size:13px';
    const warn = document.createElement('p'); warn.className = 'error'; warn.style.cssText = 'font-size:12px;margin:0 0 6px';
    const preview = document.createElement('div'); preview.dataset.testid = 'env-preview'; preview.className = 'dim'; preview.style.cssText = 'font-size:12px;margin:0 0 10px';
    const acts = document.createElement('div'); acts.className = 'actions';
    const save = document.createElement('button'); save.className = 'primary'; save.textContent = rule && rule.id ? 'Save envelope' : 'Create envelope'; save.dataset.testid = 'env-save';
    const cancel = document.createElement('button'); cancel.textContent = 'Cancel'; cancel.addEventListener('click', () => { editor.hidden = true; });
    const msg = document.createElement('span'); msg.className = 'dim'; msg.style.marginLeft = '8px';
    acts.append(save, cancel, msg);
    editor.append(h, grid, readback, warn, preview, acts);
    let previewTimer = null;
    const sync = () => {
      readback.textContent = envelopeSentence(p, cur);
      const problems = [];
      if (p.priceMin !== '' && p.priceMax !== '' && Number(p.priceMin) > Number(p.priceMax)) problems.push('price "from" is above price "up to"');
      if (!(p.channel || []).length) problems.push('pick at least one channel');
      if (AGENT_CHANNELS.some((a) => (p.channel || []).includes(a)) && p.priceMax === '') problems.push('an envelope open to AI agents should carry a price cap');
      warn.textContent = problems.join(' · ');
      save.disabled = problems.length > 0;
      clearTimeout(previewTimer);
      previewTimer = setTimeout(async () => {
        const drafts = await authFetch(`${API_BASE}/productOffering?lifecycleStatus=In%20design&limit=50`).then((r) => (r.ok ? r.json() : [])).catch(() => []);
        const cond = envelopeCondition(p);
        const hits = [];
        for (const d of drafts.slice(0, 20)) {
          const ctx = await authFetch(`${API_BASE}/governance/dry-run`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(d) }).then((r) => (r.ok ? r.json() : null)).catch(() => null);
          if (ctx && jsonLogicApply(cond, ctx.context)) hits.push(d.name);
        }
        preview.textContent = drafts.length ? `Of ${drafts.length} current draft(s), this envelope would let through: ${hits.length ? hits.join(', ') : 'none'}.` : 'No drafts to preview against yet.';
      }, 400);
    };
    sync();
    save.addEventListener('click', async () => {
      if (!name.value.trim()) { msg.textContent = 'Name it.'; return; }
      const body = { name: name.value.trim(), domain: 'launch', effect: 'allow', priority: 100, enabled: rule && rule.id ? rule.enabled !== false : true,
        description: envelopeSentence(p, cur), message: envelopeSentence(p, cur).replace(' — launch without approval.', ''),
        condition: JSON.stringify(envelopeCondition(p)), experience: { envelope: p } };
      const r = await authFetch(`${POLICY_BASE}/policyRule${rule && rule.id ? '/' + rule.id : ''}`, { method: rule && rule.id ? 'PATCH' : 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
      if (!r.ok) { msg.textContent = (await r.json().catch(() => ({}))).message || 'save failed'; return; }
      editor.hidden = true; load();
    });
  };

  const load = async () => {
    const rules = await authFetch(`${POLICY_BASE}/policyRule?limit=200`).then((r) => (r.ok ? r.json() : [])).catch(() => []);
    const envs = rules.filter((r) => r.domain === 'launch');
    list.replaceChildren();
    if (!envs.length) { const p = document.createElement('p'); p.className = 'dim'; p.textContent = 'No envelopes yet — every launch waits for an approver. Create one, or draft it from a sentence.'; list.append(p); return; }
    for (const r of envs) {
      const card = document.createElement('div'); card.className = 'stepcard'; card.dataset.testid = 'envelope-card';
      card.style.cssText = 'border:1px solid var(--line,#ddd);border-radius:10px;padding:10px 14px;margin:0 0 10px' + (r.enabled === false ? ';opacity:.6' : '');
      const head = document.createElement('div'); head.style.cssText = 'display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap;align-items:baseline';
      const t = document.createElement('strong'); t.textContent = r.name + (r.enabled === false ? ' (off)' : '');
      const acts = document.createElement('span');
      const edit = document.createElement('button'); edit.textContent = 'Edit'; edit.addEventListener('click', () => openEditor(r));
      const clone = document.createElement('button'); clone.textContent = 'Clone'; clone.addEventListener('click', () => openEditor({ ...r, id: undefined, name: `${r.name} (copy)` }));
      const toggle = document.createElement('button'); toggle.textContent = r.enabled === false ? 'Turn on' : 'Turn off';
      toggle.addEventListener('click', async () => { await authFetch(`${POLICY_BASE}/policyRule/${r.id}`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ enabled: r.enabled === false }) }); load(); });
      const del = document.createElement('button'); del.textContent = 'Delete'; del.addEventListener('click', async () => { if (confirm(`Delete envelope "${r.name}"?`)) { await authFetch(`${POLICY_BASE}/policyRule/${r.id}`, { method: 'DELETE' }); load(); } });
      acts.append(edit, clone, toggle, del); acts.style.cssText = 'display:inline-flex;gap:6px';
      head.append(t, acts);
      const sentence = document.createElement('div'); sentence.style.cssText = 'font-size:13px;margin-top:4px';
      const p = (r.experience && r.experience.envelope) || null;
      sentence.textContent = p ? envelopeSentence(p, cur) : (r.description || r.message || '(condition authored outside the console)');
      card.append(head, sentence);
      list.append(card);
    }
  };
  add.addEventListener('click', () => openEditor(null));
  draft.addEventListener('click', async () => {
    const sentence = prompt('Describe the envelope in a sentence, e.g. "top-ups under 199, 30 days or less, app and web only":', '');
    if (!sentence) return;
    const p = { category: [], priceType: 'any', priceMin: '', priceMax: '', allowanceMaxGb: '', validityMaxDays: '', channel: CHANNELS.map((c) => c.value), zeroRatedApps: 'any', bundles: 'any' };
    const low = sentence.toLowerCase();
    for (const c of catNames) if (low.includes(c.toLowerCase())) p.category.push(c);
    const under = low.match(/(?:under|below|up to|max(?:imum)?|<)\s*([\d.,]+)/); if (under) p.priceMax = under[1].replace(/[,.](?=\d{3}\b)/g, '');
    const band = low.match(/([\d.,]+)\s*(?:–|-|to)\s*([\d.,]+)/); if (band) { p.priceMin = band[1].replace(/[,.](?=\d{3}\b)/g, ''); p.priceMax = band[2].replace(/[,.](?=\d{3}\b)/g, ''); }
    const days = low.match(/(\d+)\s*days?/); if (days) p.validityMaxDays = days[1];
    const gb = low.match(/(\d+)\s*gb/); if (gb) p.allowanceMaxGb = gb[1];
    if (/per month|monthly|recurring/.test(low)) p.priceType = 'recurring'; else if (/one[- ]off|one[- ]time|top-?up|pass/.test(low)) p.priceType = 'oneTime';
    const named = CHANNELS.filter((c) => low.includes(c.value) || low.includes(c.label.toLowerCase().split(' ')[0]));
    if (/only/.test(low) && named.length) p.channel = named.map((c) => c.value);
    if (/no bundles/.test(low)) p.bundles = 'no';
    if (/no zero|no free apps/.test(low)) p.zeroRatedApps = 'no';
    openEditor({ name: sentence.replace(/[.,;:]+$/, '').slice(0, 60), experience: { envelope: p } });
  });
  await load();
}
