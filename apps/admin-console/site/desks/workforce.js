'use strict';

// THE WORKFORCE SCOREBOARD: what the digital workers did, what it saved
// (labeled as the estimate it is), the honesty metric (reopen rate), and
// the approvals queue — where a human's click IS the write.
async function renderWorkforce() {
  let panel = document.getElementById('workforce-panel');
  if (!panel) {
    panel = document.createElement('div');
    panel.id = 'workforce-panel';
    document.querySelector('.table-wrap').after(panel);
  }
  panel.hidden = false;

  const [kpiRes, pendRes, ledgerRes] = await Promise.all([
    authFetch('/ai/v1/workforce/kpis'),
    authFetch('/ai/v1/workforce/approvals?status=pending'),
    authFetch('/ai/v1/workforce/ledger'),
  ]);
  // old rows stay on screen until the new data has arrived — no blank-panel
  // window for any observer, human or test
  panel.replaceChildren();
  if (!kpiRes.ok) { panel.textContent = 'Workforce data unavailable.'; return; }
  const kpis = await kpiRes.json();
  const pendings = pendRes.ok ? await pendRes.json() : [];
  const ledger = ledgerRes.ok ? await ledgerRes.json() : [];

  const card = (label, value, hint, testid) => {
    const div = document.createElement('div');
    div.style.cssText = 'flex:1 1 9rem;min-width:9rem;padding:0.8rem 1rem;border:1px solid var(--line,#ddd);border-radius:10px;background:var(--card,#fafafa)';
    if (testid) div.dataset.testid = testid;
    const b = document.createElement('b');
    b.style.cssText = 'display:block;font-size:1.5rem';
    b.textContent = value == null ? '—' : String(value);
    const s = document.createElement('span');
    s.style.cssText = 'font-size:0.8rem;color:var(--dim,#777)';
    s.textContent = label;
    div.append(b, s);
    if (hint) { div.title = hint; }
    return div;
  };
  const cards = document.createElement('div');
  cards.style.cssText = 'display:flex;flex-wrap:wrap;gap:0.7rem;margin:0.6rem 0 1.2rem';
  const mins = kpis.humanMinutesSaved || {};
  const staffing = kpis.staffing || {};
  cards.append(
    card('Working now', kpis.workingNow, 'workers holding a live task lease', 'wf-kpi-working'),
    card('Backlog' + (staffing.surge ? ' — SURGE' : ''), staffing.backlogDepth,
      staffing.definition, 'wf-kpi-backlog'),
    card('Tasks completed', kpis.completed, null, 'wf-kpi-completed'),
    card('Escalated to humans', kpis.escalated, 'escalations are counted, not punished', 'wf-kpi-escalated'),
    card('Deflection', kpis.deflectionRate == null ? '—' : Math.round(kpis.deflectionRate * 100) + '%',
      'completed / (completed + escalated)'),
    card('Avg handle time', kpis.avgHandleSeconds == null ? '—' : kpis.avgHandleSeconds + 's'),
    card('Reopen rate', kpis.reopen ? Math.round(kpis.reopen.rate * 100) + '%' : '—',
      kpis.reopen && kpis.reopen.definition, 'wf-kpi-reopen'),
    card('Minutes saved (est.)', mins.minutes, mins.definition, 'wf-kpi-saved'),
    card('Self-reported cost', ((kpis.selfReportedCostMicros || 0) / 1e6).toFixed(4) + ' ' + tenantCurrency(),
      kpis.selfReportedCostLabel),
    card('Approvals pending', kpis.approvals ? kpis.approvals.pending : 0, null, 'wf-kpi-pending'),
  );

  const h = (text) => {
    const el2 = document.createElement('h3');
    el2.textContent = text;
    el2.style.cssText = 'margin:1.2rem 0 0.5rem';
    return el2;
  };

  const controls = workforceControls(staffing);

  // THE CREW: how many of each type of worker, then each worker's own
  // record. Type is OBSERVED from the ledger (what it actually works —
  // care / back-office / generalist), never self-declared.
  const crewBox = document.createElement('div');
  const types = kpis.workerTypes || {};
  const typeCards = document.createElement('div');
  typeCards.style.cssText = 'display:flex;flex-wrap:wrap;gap:0.7rem;margin-bottom:0.8rem';
  for (const [type, t] of Object.entries(types)) {
    const div = document.createElement('div');
    div.dataset.testid = 'wf-type-card';
    div.style.cssText = 'flex:1 1 12rem;min-width:12rem;padding:0.7rem 1rem;border:1px solid var(--line,#ddd);border-radius:10px;background:var(--card,#fafafa)';
    const head = document.createElement('b');
    head.textContent = `${t.workers} × ${type}`;
    const sub = document.createElement('div');
    sub.style.cssText = 'font-size:0.82rem;color:var(--dim,#777)';
    sub.textContent = `${t.workingNow} working now · ${t.completed} done · ${t.escalated} escalated`
      + (t.deflectionRate != null ? ` · deflection ${Math.round(t.deflectionRate * 100)}%` : '');
    div.append(head, sub);
    typeCards.append(div);
  }
  crewBox.append(typeCards);
  for (const w of (kpis.workers || [])) {
    const row = document.createElement('div');
    row.dataset.testid = 'wf-worker-row';
    row.style.cssText = 'display:flex;align-items:center;gap:0.7rem;padding:0.45rem 0.8rem;border-bottom:1px solid var(--line,#eee);font-size:0.9rem';
    const dot = document.createElement('span');
    dot.textContent = w.workingNow ? '●' : '○';
    dot.title = w.workingNow ? 'holding a live task lease right now' : 'idle';
    dot.style.color = w.workingNow ? '#2e9e5b' : 'var(--dim,#999)';
    const who = document.createElement('span');
    who.style.flex = '1';
    who.textContent = `${w.workerName || w.worker} — ${w.type}`;
    who.title = w.worker; // the stable subject id, on hover
    const nums = document.createElement('span');
    nums.style.cssText = 'color:var(--dim,#777)';
    nums.textContent = `${w.completed} done · ${w.escalated} esc`
      + (w.avgHandleSeconds != null ? ` · ${w.avgHandleSeconds}s avg` : '')
      + ` · ${((w.selfReportedCostMicros || 0) / 1e6).toFixed(4)} ${tenantCurrency()} (self-rep.)`
      + (w.lastActiveAt ? ` · last ${new Date(w.lastActiveAt).toLocaleTimeString()}` : '');
    row.append(dot, who, nums);
    crewBox.append(row);
  }
  if (!(kpis.workers || []).length) {
    const none = document.createElement('p');
    none.textContent = 'No workers have worked yet — hire one (Staff → grant digital-worker).';
    none.style.color = 'var(--dim,#777)';
    crewBox.append(none);
  }

  const approvalsBox = document.createElement('div');
  if (!pendings.length) {
    const none = document.createElement('p');
    none.textContent = 'Nothing waits for a human — the queue is clear.';
    none.style.color = 'var(--dim,#777)';
    approvalsBox.append(none);
  }
  for (const a of pendings) {
    const row = document.createElement('div');
    row.dataset.testid = 'wf-approval-row';
    row.style.cssText = 'display:flex;align-items:center;gap:0.8rem;padding:0.6rem 0.8rem;border:1px solid var(--line,#ddd);border-radius:8px;margin-bottom:0.5rem';
    const info = document.createElement('div');
    info.style.flex = '1';
    const title = document.createElement('b');
    title.textContent = `${a.action} — ${a.method} ${a.path}`;
    const why = document.createElement('div');
    why.style.cssText = 'font-size:0.85rem;color:var(--dim,#777)';
    why.textContent = `${a.reason} · asked by ${a.requestedByName || a.requestedBy}`;
    info.append(title, why);
    const ok = document.createElement('button');
    ok.textContent = 'Approve';
    ok.dataset.testid = 'wf-approve';
    ok.addEventListener('click', async () => {
      ok.disabled = true;
      const res = await authFetch(`/ai/v1/workforce/approvals/${a.id}/approve`, { method: 'POST' });
      if (!res.ok) {
        ok.disabled = false;
        why.textContent = 'Refused downstream: ' + (await res.text()).slice(0, 160);
        return;
      }
      renderWorkforce();
    });
    const no = document.createElement('button');
    no.textContent = 'Refuse';
    no.dataset.testid = 'wf-refuse';
    no.addEventListener('click', async () => {
      const note = prompt('Why refuse?') || '';
      await authFetch(`/ai/v1/workforce/approvals/${a.id}/refuse`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ note }),
      });
      renderWorkforce();
    });
    row.append(info, ok, no);
    approvalsBox.append(row);
  }

  const ledgerBox = document.createElement('div');
  for (const t of ledger.slice(0, 20)) {
    const row = document.createElement('div');
    row.dataset.testid = 'wf-ledger-row';
    row.style.cssText = 'padding:0.45rem 0.8rem;border-bottom:1px solid var(--line,#eee);font-size:0.9rem';
    row.textContent = `[${t.status}] ${t.kind} · ${t.summary || t.subjectRef} · ${t.claimedByName || t.claimedBy || ''}`
      + (t.outcome ? ` → ${t.outcome}` : '');
    ledgerBox.append(row);
  }
  if (!ledger.length) {
    ledgerBox.textContent = 'No shifts worked yet — hire a worker (Staff → grant digital-worker).';
    ledgerBox.style.color = 'var(--dim,#777)';
  }

  panel.append(cards, controls, h('The crew'), crewBox,
    h('Waiting for a human'), approvalsBox, h('The shift ledger'), ledgerBox);
}
