'use strict';

/* ---------------- Launch governance: the approvals desk ---------------- */
const GOV_STATE_LABEL = { requested: 'Waiting for approval', approved: 'Approved — not launched', held: 'On hold', launched: 'Launched',
  rejected: 'Rejected', expired: 'Approval expired', none: 'Draft' };

async function renderApprovalsDesk() {
  const panel = copilotPanel();
  document.getElementById('list-search')?.setAttribute('hidden', '');
  document.querySelector('.pager')?.setAttribute('hidden', '');
  panel.replaceChildren();
  panel.dataset.testid = 'approvals-desk';
  const settings = await authFetch(`${API_BASE}/governance/settings`).then((r) => (r.ok ? r.json() : null)).catch(() => null);
  const intro = document.createElement('p'); intro.className = 'dim'; intro.style.cssText = 'font-size:13px;margin:6px 0 12px';
  if (!settings || settings.mode === 'none') {
    intro.textContent = 'Launch governance is off for this tenant (launch-governance: none): a write is a launch. Turn it on per tenant to route launches through this desk.';
    panel.append(intro); return;
  }
  intro.textContent = settings.mode === 'envelope'
    ? 'Offers inside a pre-approved envelope launch by themselves and are ledgered to it; everything else waits here for an approver. Readiness owners tick before launch; anyone may hold.'
    : 'Every launch waits here for an approver. Readiness owners tick before launch; anyone may hold.';
  const bar = document.createElement('div'); bar.className = 'staffbar';
  const refresh = document.createElement('button'); refresh.textContent = 'Refresh'; refresh.dataset.testid = 'approvals-refresh';
  const who = document.createElement('span'); who.className = 'dim'; who.style.cssText = 'font-size:12px;margin-left:8px';
  who.textContent = settings.canApprove ? 'You can approve, reject and force-launch.' : 'You can request, tick your readiness item, hold and resume; an approver decides.';
  bar.append(refresh, who);
  const drafts = document.createElement('div'); drafts.dataset.testid = 'approvals-drafts'; drafts.style.margin = '0 0 14px';
  const list = document.createElement('div'); list.dataset.testid = 'approvals-list';
  panel.append(intro, bar, drafts, list);

  const door = async (id, name, body) => {
    const r = await authFetch(`${API_BASE}/productOffering/${id}/governance/${name}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) });
    const out = await r.json().catch(() => ({}));
    if (!r.ok) alert(out.message || `${name} failed`);
    return r.ok ? out : null;
  };
  const btn = (label, cls, fn, testid) => { const b = document.createElement('button'); b.textContent = label; if (cls) b.className = cls; b.addEventListener('click', fn); if (testid) b.dataset.testid = testid; return b; };
  const fmt = (iso) => (iso ? new Date(iso).toLocaleString() : '');
  const load = async () => {
    const [items, inDesign] = await Promise.all([
      authFetch(`${API_BASE}/governance/queue`).then((r) => (r.ok ? r.json() : [])).catch(() => []),
      authFetch(`${API_BASE}/productOffering?lifecycleStatus=In%20design&limit=50`).then((r) => (r.ok ? r.json() : [])).catch(() => []),
    ]);
    // drafts nobody has asked to launch yet
    drafts.replaceChildren();
    const queued = new Set(items.map((i) => i.id));
    const waiting = inDesign.filter((d) => !queued.has(d.id));
    if (waiting.length) {
      const h = document.createElement('h3'); h.style.cssText = 'font-size:14px;margin:0 0 6px'; h.textContent = `Drafts (${waiting.length}) — request a launch`;
      drafts.append(h);
      for (const d of waiting) {
        const row = document.createElement('div'); row.style.cssText = 'display:flex;gap:10px;align-items:center;font-size:13px;margin:2px 0'; row.dataset.testid = 'draft-row';
        const dry = document.createElement('span'); dry.className = 'dim'; dry.style.fontSize = '12px';
        const req = btn('Request launch', '', async () => {
          const note = prompt(`Request the launch of "${d.name}"? A note for the approver (optional):`, '');
          if (note === null) return;
          const out = await door(d.id, 'request', { note });
          if (out) load();
        }, 'gov-request');
        row.append(document.createTextNode(d.name), dry, req);
        drafts.append(row);
        authFetch(`${API_BASE}/governance/dry-run`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(d) })
          .then((r) => (r.ok ? r.json() : null)).then((v) => { if (v) dry.textContent = `· ${v.verdict}`; }).catch(() => {});
      }
    }
    list.replaceChildren();
    if (!items.length) { const p = document.createElement('p'); p.className = 'dim'; p.textContent = 'Nothing waits on a decision.'; list.append(p); return; }
    const focus = sessionStorage.getItem('bss.console.approvals.focus');
    for (const it of items) {
      const card = document.createElement('div'); card.className = 'stepcard'; card.dataset.testid = 'approval-card'; card.dataset.offeringId = it.id;
      card.style.cssText = 'border:1px solid var(--line,#ddd);border-radius:10px;padding:12px 14px;margin:0 0 12px' + (it.id === focus ? ';box-shadow:0 0 0 2px var(--accent,#4A4AC3)' : '');
      const head = document.createElement('div'); head.style.cssText = 'display:flex;justify-content:space-between;gap:12px;align-items:baseline;flex-wrap:wrap';
      const title = document.createElement('strong'); title.textContent = it.name;
      const state = document.createElement('span'); state.dataset.testid = 'approval-state';
      const tone = { requested: '#b45309', approved: '#1d4ed8', held: '#6b7280', launched: '#15803d', rejected: '#b91c1c', expired: '#b91c1c' }[it.governanceState] || '#6b7280';
      state.style.cssText = `padding:2px 10px;border-radius:12px;font-size:12px;color:#fff;background:${tone}`;
      state.textContent = GOV_STATE_LABEL[it.governanceState] || it.governanceState;
      head.append(title, state);
      const reason = document.createElement('div'); reason.className = 'dim'; reason.style.cssText = 'font-size:13px;margin:6px 0';
      const bits = [];
      bits.push(`${it.lifecycleStatus}${it.channel && it.channel.length ? ' · ' + it.channel.map((c) => c.name || c.id).join(', ') : ' · every channel'}`);
      if (it.requestedBy) bits.push(`requested by ${it.requestedBy}${it.note ? ` — “${it.note}”` : ''}`);
      if (it.envelope) bits.push(`pre-approved by envelope ‘${it.envelope.name}’`);
      else if (it.approvedBy) bits.push(`approved by ${it.approvedBy}${it.approvalExpiresAt ? `, good until ${fmt(it.approvalExpiresAt).slice(0, 10)}` : ''}`);
      if (it.governanceState === 'requested' && settings.mode === 'envelope') bits.push('outside every envelope — needs a decision');
      if (it.governanceState === 'held') bits.push(`held by ${it.heldBy}${it.holdUntil ? ` until ${fmt(it.holdUntil)}` : ' until resumed'}${it.holdReason ? ` — “${it.holdReason}”` : ''}`);
      if (it.governanceState === 'rejected') bits.push(`rejected by ${it.rejectedBy}${it.rejectedReason ? ` — “${it.rejectedReason}”` : ''}`);
      if (it.validFrom) bits.push(`on sale from ${fmt(it.validFrom)}`);
      reason.textContent = bits.join(' · ');
      const ready = document.createElement('div'); ready.style.cssText = 'display:flex;flex-wrap:wrap;gap:6px 14px;margin:6px 0';
      for (const r of it.readiness || []) {
        const l = document.createElement('label'); l.style.cssText = 'display:inline-flex;gap:6px;align-items:center;font-size:13px;font-weight:400';
        const cb = document.createElement('input'); cb.type = 'checkbox'; cb.checked = Boolean(r.done); cb.dataset.testid = `ready-${r.owner}`;
        cb.disabled = ['launched', 'rejected'].includes(it.governanceState);
        cb.addEventListener('change', async () => { if (!(await door(it.id, 'ready', { owner: r.owner, done: cb.checked }))) cb.checked = !cb.checked; load(); });
        l.append(cb, document.createTextNode(`${r.label}${r.done && r.by ? ` (${r.by})` : ''}`)); l.title = `owner: ${r.owner}`;
        ready.append(l);
      }
      const acts = document.createElement('div'); acts.className = 'actions'; acts.style.cssText = 'display:flex;flex-wrap:wrap;gap:6px;margin-top:6px';
      const st = it.governanceState;
      if (settings.canApprove && ['requested', 'expired', 'rejected', 'none'].includes(st)) {
        acts.append(btn('Approve', 'primary', async () => { const note = prompt('Approval note (optional):', ''); if (note !== null && await door(it.id, 'approve', { note })) load(); }, 'gov-approve'));
        acts.append(btn('Reject', '', async () => { const note = prompt('Why?', ''); if (note !== null && await door(it.id, 'reject', { note })) load(); }, 'gov-reject'));
      }
      if (st === 'approved') {
        const open = (it.readiness || []).filter((r) => !r.done).length;
        acts.append(btn(open ? `Launch now (${open} not ready)` : 'Launch now', 'primary', async () => {
          if (open && !settings.canApprove) { alert(`Not ready: ${(it.readiness || []).filter((r) => !r.done).map((r) => r.label).join(', ')}`); return; }
          if (open && !confirm(`${open} readiness item(s) are open. Launch anyway (ledgered as forced)?`)) return;
          if (await door(it.id, 'launch', { force: Boolean(open) })) load();
        }, 'gov-launch'));
        acts.append(btn('Launch on a date…', '', async () => { const d = prompt('On sale from (ISO date-time, e.g. 2026-10-01T00:00:00+02:00):', ''); if (d && await door(it.id, 'launch', { validFrom: d, force: settings.canApprove })) load(); }));
      }
      if (['requested', 'approved', 'launched'].includes(st)) {
        acts.append(btn('Hold…', '', async () => { const note = prompt('Why hold? (e.g. marketing collateral not ready)', ''); if (note === null) return; const until = prompt('Hold until (ISO date-time) — blank = until someone resumes:', ''); if (await door(it.id, 'hold', { note, until: until || null })) load(); }, 'gov-hold'));
      }
      if (st === 'held') acts.append(btn('Resume', 'primary', async () => { if (await door(it.id, 'resume', {})) load(); }, 'gov-resume'));
      if (st === 'launched') acts.append(btn('Unlaunch…', '', async () => { const end = prompt('Sales end (ISO date-time) — blank = now:', ''); if (end === null) return; if (await door(it.id, 'unlaunch', { endDate: end || null })) load(); }, 'gov-unlaunch'));
      const trail = document.createElement('details'); const sum = document.createElement('summary'); sum.className = 'dim'; sum.style.fontSize = '12px'; sum.textContent = `Trail (${(it.ledger || []).length})`; trail.append(sum);
      const ul = document.createElement('ul'); ul.style.cssText = 'font-size:12px;margin:4px 0 0 16px;padding:0';
      for (const l of it.ledger || []) { const li = document.createElement('li'); li.textContent = `${fmt(l.at)} — ${l.action} · ${l.actor}${l.note ? ` — ${l.note}` : ''}${l.envelopeName ? ` [envelope: ${l.envelopeName}]` : ''}`; ul.append(li); }
      trail.append(ul);
      card.append(head, reason, ready, acts, trail);
      list.append(card);
    }
  };
  refresh.addEventListener('click', load);
  await load();
}
