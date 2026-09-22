'use strict';

/* --------- Growth Copilot: chat about an outreach, create the journey/campaign --------- */
async function renderGrowthCopilot() {
  const panel = copilotPanel();
  panel.replaceChildren();
  const intro = document.createElement('p');
  intro.className = 'dim';
  intro.style.cssText = 'font-size:13px;margin:6px 0 10px';
  intro.textContent = 'Describe the outreach you want — the copilot asks what it needs, then proposes '
    + 'a journey or a campaign and creates it when you confirm. It proposes; you decide.';
  const log = document.createElement('div'); log.id = 'growth-copilot-log';
  const bar = document.createElement('div'); bar.className = 'staffbar';
  const input = document.createElement('input');
  input.id = 'growth-copilot-input'; input.style.flex = '1';
  input.placeholder = 'e.g. welcome new customers when they sign up…';
  const send = document.createElement('button');
  send.className = 'primary'; send.id = 'growth-copilot-send'; send.textContent = 'Send';
  bar.append(input, send);
  panel.append(intro, log, bar);

  const chat = [];
  const bubble = (cls, text) => {
    const b = document.createElement('div'); b.className = 'copilot-msg ' + cls; b.textContent = text;
    log.append(b); log.scrollTop = log.scrollHeight; return b;
  };
  async function submit() {
    const text = input.value.trim(); if (!text) return;
    input.value = ''; bubble('copilot-user', text); chat.push({ role: 'owner', content: text });
    const thinking = bubble('copilot-ai', '…');
    try {
      const res = await authFetch('/ai/v1/journeyCopilot', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ messages: chat }),
      });
      if (!res.ok) { const pr = await res.json().catch(() => null); throw new Error(pr?.message || ('copilot unavailable (HTTP ' + res.status + ')')); }
      const reply = await res.json();
      thinking.textContent = reply.message;
      chat.push({ role: 'assistant', content: reply.message });
      if (reply.kind === 'proposal' && reply.proposal) { log.append(growthProposalCard(reply, bubble)); log.scrollTop = log.scrollHeight; }
    } catch (e) { thinking.textContent = e.message; thinking.classList.add('copilot-warn'); }
  }
  send.addEventListener('click', submit);
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter') submit(); });
  attachCopilotMic(input, bar, send); // speak the outreach instead of typing it
  input.focus();
}

/** A human-readable card for a journey/campaign proposal, with Create = apply. */
function growthProposalCard(reply, bubble) {
  const p = reply.proposal; const isJourney = p.artifact === 'journey';
  const obj = isJourney ? p.journey : p.campaign;
  const card = document.createElement('div'); card.className = 'panel'; card.dataset.testid = 'growth-proposal';
  card.style.cssText = 'margin:8px 0;padding:12px 14px';
  const h = document.createElement('div'); h.style.cssText = 'font-weight:600;margin-bottom:4px';
  h.textContent = (isJourney ? 'Journey — ' : 'Campaign — ') + (obj.name || '');
  const meta = document.createElement('div'); meta.className = 'dim'; meta.style.fontSize = '12.5px';
  meta.textContent = (obj.triggerEventType ? 'Trigger: ' + obj.triggerEventType
    : obj.segmentName ? 'Segment: ' + obj.segmentName : '')
    + (obj.holdoutPercent != null ? ' · holdout ' + obj.holdoutPercent + '%' : '')
    + (isJourney && obj.steps ? ' · ' + obj.steps.length + ' steps' : '');
  card.append(h, meta);
  if (isJourney && Array.isArray(obj.steps)) {
    const ul = document.createElement('ul'); ul.style.cssText = 'margin:8px 0;padding-left:18px;font-size:12.5px';
    obj.steps.forEach((s) => {
      const li = document.createElement('li');
      li.textContent = (s.stage ? '[' + s.stage + '] ' : '')
        + (s.type === 'message' ? 'message: ' + (s.subject || '')
          : s.type === 'wait' ? 'wait ' + (s.days ? s.days + 'd' : (s.hours || '') + 'h') : s.type);
      ul.append(li);
    });
    card.append(ul);
  } else if (!isJourney && obj.message) {
    const m = document.createElement('div'); m.style.cssText = 'font-size:12.5px;margin:6px 0';
    m.textContent = '“' + (obj.message.subject || '') + '” — ' + (obj.message.content || '');
    card.append(m);
  }
  const create = document.createElement('button'); create.className = 'primary';
  create.dataset.testid = 'growth-create'; create.textContent = 'Create';
  create.addEventListener('click', async () => {
    create.disabled = true; create.textContent = 'Creating…';
    try {
      const url = CAMPAIGN_BASE + (isJourney ? '/journey' : '/campaign');
      const res = await authFetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(obj) });
      if (!res.ok) { const pr = await res.json().catch(() => null); throw new Error(pr?.message || ('HTTP ' + res.status)); }
      create.textContent = '✓ Created';
      bubble('copilot-ai', 'Created the ' + (isJourney ? 'journey' : 'campaign')
        + '. Open the ' + (isJourney ? 'Journeys' : 'Campaigns') + ' tab to see it, tune it or view its funnel.');
    } catch (e) { create.disabled = false; create.textContent = 'Create'; bubble('copilot-ai', 'Could not create: ' + e.message).classList.add('copilot-warn'); }
  });
  card.append(create);
  return card;
}

// ATTRIBUTION: the portfolio readout. Lift + INCREMENTAL revenue across every
// campaign and journey — one page a marketer reads to see what actually moved
// money, with the honest rule shown in the open: no control group, no lift claim.
async function renderAttribution() {
  const panel = copilotPanel();
  panel.replaceChildren();
  panel.dataset.testid = 'attribution';
  const intro = document.createElement('p');
  intro.className = 'dim'; intro.style.cssText = 'font-size:13px;margin:6px 0 12px';
  intro.textContent = 'Every campaign and journey in one readout. Incremental revenue is holdout-adjusted — the '
    + 'money the message actually made — never the gross a message cannon would claim. A program with no '
    + 'holdout shows reach and gross but no lift: you cannot measure what you never left room to see.';
  const kpis = document.createElement('div'); kpis.className = 'kpis'; kpis.style.cssText = 'display:flex;gap:0.7rem;flex-wrap:wrap;margin:10px 0 16px';
  kpis.dataset.testid = 'attribution-kpis';
  const channels = document.createElement('div'); channels.className = 'dim'; channels.style.cssText = 'font-size:12px;margin:0 0 12px';
  const tableWrap = document.createElement('div'); tableWrap.style.cssText = 'overflow-x:auto';
  const table = document.createElement('table'); table.dataset.testid = 'attribution-table';
  table.style.cssText = 'width:100%;border-collapse:collapse;font-size:13px';
  tableWrap.append(table);

  const money = (v) => (v == null ? '—' : Number(v).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }));
  const load = async () => {
    const r = await authFetch('/tmf-api/campaignManagement/v4/campaign/attribution');
    const rep = r.ok ? await r.json() : { programs: [], portfolio: {}, byChannel: {} };
    const p = rep.portfolio || {};
    const rev = p.revenue || {};
    kpis.replaceChildren(
      kpiCard('Programs', p.programs ?? 0, 'campaigns + journeys', 'kpi-programs'),
      kpiCard('Reached', p.totalReached ?? 0, 'treated across all programs', 'kpi-reached'),
      kpiCard('Blended lift', p.blendedLiftPoints != null ? p.blendedLiftPoints + ' pts' : '—', 'treated rate minus holdout rate', 'kpi-lift'),
      kpiCard('Incremental / mo', money(rev.incremental), 'holdout-adjusted — the money the messages made', 'kpi-incremental'),
      kpiCard('Gross attributed', money(rev.grossAttributed), 'total monthly value of converting orders', 'kpi-gross'));
    const bc = rep.byChannel || {};
    channels.textContent = 'By channel: '
      + (['campaign', 'journey'].filter((k) => bc[k]).map((k) => `${bc[k].programs} ${k}${bc[k].programs === 1 ? '' : 's'} → reached ${bc[k].reached}, ${money(bc[k].attributedRevenue)}/mo`).join(' · ') || '—');
    table.replaceChildren();
    const head = document.createElement('tr');
    for (const h of ['Program', 'Type', 'Reached', 'Held out', 'Conv (t/h)', 'Lift', 'Incremental/mo']) {
      const th = document.createElement('th'); th.textContent = h;
      th.style.cssText = 'text-align:left;border-bottom:1px solid var(--line,#ddd);padding:5px 8px;white-space:nowrap';
      head.append(th);
    }
    table.append(head);
    const rows = (rep.programs || []).filter((x) => x.reached || x.heldOut).slice(0, 60);
    for (const x of rows) {
      const tr = document.createElement('tr'); tr.dataset.testid = 'attribution-row';
      const inc = x.revenue ? x.revenue.incremental : null;
      const cells = [
        x.name || x.id, x.type, x.reached ?? 0, x.heldOut ?? 0,
        `${x.conversions?.treated ?? 0}/${x.conversions?.holdout ?? 0}`,
        x.liftPoints != null ? `${x.liftPoints} pts` : '—',
        x.heldOut ? money(inc) : '— (no holdout)'];
      cells.forEach((c, i) => {
        const td = document.createElement('td'); td.textContent = c;
        td.style.cssText = 'padding:5px 8px;border-bottom:1px solid var(--line,#f0f0f0)' + (i >= 2 ? ';white-space:nowrap' : '');
        tr.append(td);
      });
      table.append(tr);
    }
    if (rows.length === 0) {
      const tr = document.createElement('tr'); const td = document.createElement('td');
      td.colSpan = 7; td.className = 'dim'; td.textContent = 'No measured programs yet — run a campaign or journey with a holdout.';
      td.style.padding = '8px'; tr.append(td); table.append(tr);
    }
  };
  panel.append(intro, kpis, channels, tableWrap);
  load();
}
