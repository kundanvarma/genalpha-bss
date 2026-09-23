'use strict';

// W-M5 — mobile wholesale (MVNE): what the MVNO owes its host for the traffic its
// subscribers burned, rated at the wholesale rate card, with a reconciliation check.
const USAGE_BASE_C = '/tmf-api/usageManagement/v4';
async function renderMobileWholesale(periodStart, periodEnd) {
  const panel = panelFor('wholesale-panel');
  if (!periodStart) {
    const now = new Date();
    const y = now.getFullYear(); const mi = now.getMonth();
    periodStart = `${y}-${String(mi + 1).padStart(2, '0')}-01`;
    periodEnd = `${y}-${String(mi + 1).padStart(2, '0')}-${String(new Date(y, mi + 1, 0).getDate()).padStart(2, '0')}`;
  }
  panel.innerHTML = '<div class="editor"><h2>Mobile wholesale</h2><p class="dim">Loading…</p></div>';
  const q = `periodStart=${periodStart}&periodEnd=${periodEnd}`;
  const [cards, imsi, s] = await Promise.all([
    authFetch(`${USAGE_BASE_C}/wholesaleRateCard`).then((r) => (r.ok ? r.json() : [])).catch(() => []),
    authFetch(`${USAGE_BASE_C}/imsiRange`).then((r) => (r.ok ? r.json() : [])).catch(() => []),
    authFetch(`${USAGE_BASE_C}/mobileWholesaleSettlement?${q}`).then((r) => (r.ok ? r.json() : null)).catch(() => null),
  ]);
  const lines = (s && s.line) || [];
  let html = '<div class="editor"><h2>Mobile wholesale (MVNE)</h2>'
    + '<p class="dim small">As a light MVNO, we owe a host MNO for the traffic our subscribers burn, '
    + 'rated at the wholesale rate card. The reconciliation check compares the rated units against the '
    + 'live CDRs — a late CDR is flagged.</p>'
    + `<div class="actions" style="align-items:center">Period `
    + `<input id="mw-start" value="${esc(periodStart)}" style="width:9rem">`
    + `<input id="mw-end" value="${esc(periodEnd)}" style="width:9rem">`
    + `<button class="ghost" id="mw-refresh">Refresh</button>`
    + `<button class="primary" id="mw-rate">Rate this period</button>`
    + `<span id="mw-msg" class="dim"></span></div>`;
  // settlement
  html += '<h2 style="font-size:1rem;margin-top:1rem">Settlement — what we owe the host</h2>';
  if (!lines.length) {
    html += '<p class="dim">Nothing rated for this period yet — press “Rate this period”.</p>';
  } else {
    const badgeHtml = s.reconciled
      ? '<span style="color:var(--teal)">✓ reconciled</span>'
      : '<span style="color:var(--danger,#b64a3a)">⚠ a late CDR is unreconciled</span>';
    html += '<div class="table-wrap"><table><thead><tr><th>Usage type</th><th>Rated units</th>'
      + '<th>Live units</th><th>Rate</th><th>Amount</th><th>Reconciled</th></tr></thead><tbody>';
    for (const l of lines) {
      html += `<tr><td>${esc(l.usageSpecName)}</td><td>${esc(l.ratedUnits)} ${esc(l.unit)}</td>`
        + `<td>${esc(l.liveUnits)} ${esc(l.unit)}</td><td>${esc(l.wholesaleRate)}</td>`
        + `<td>${esc(l.amount)} ${esc(l.currency)}</td><td>${l.reconciled ? '✓' : '⚠'}</td></tr>`;
    }
    html += `</tbody></table></div><p><b>Total owed: ${esc(s.totalOwed)} ${esc(s.currency)}</b> · ${badgeHtml}</p>`;
  }
  // rate card
  html += '<h2 style="font-size:1rem;margin-top:1.5rem">Wholesale rate card</h2>';
  if (!cards.length) {
    html += '<p class="dim">No rate card — run ops/seed/seed_mobile_wholesale.py or add rows via the API.</p>';
  } else {
    html += '<div class="table-wrap"><table><thead><tr><th>Usage type</th><th>Rate</th><th>Unit</th>'
      + '<th>Host</th></tr></thead><tbody>';
    for (const c of cards) {
      html += `<tr><td>${esc(c.usageSpecName)}</td><td>${esc(c.wholesaleRate)}</td>`
        + `<td>${esc(c.unit)}</td><td>${esc(c.hostName)}</td></tr>`;
    }
    html += '</tbody></table></div>';
  }
  // P4 — the negotiation twin: real CDRs vs a hypothetical rate card
  html += '<h2 style="font-size:1rem;margin-top:1.5rem">Negotiation twin — what if the host priced differently?</h2>'
    + '<p class="dim small">The period\u2019s REAL traffic replayed against a hypothetical rate. '
    + 'Read-only: nothing is rated, booked or stored.</p>'
    + '<div class="actions" style="align-items:center">'
    + `<select id="nt-spec" data-testid="nt-spec">${cards.map((c) =>
        `<option value="${esc(c.usageSpecName)}">${esc(c.usageSpecName)} (now ${esc(c.wholesaleRate)})</option>`).join('')}</select>`
    + '<input id="nt-rate" data-testid="nt-rate" placeholder="proposed rate" style="width:8rem">'
    + '<button class="ghost" id="nt-run" data-testid="nt-run">Run the twin</button></div>'
    + '<div id="nt-result" data-testid="nt-result"></div>';

  // IMSI
  html += '<h2 style="font-size:1rem;margin-top:1.5rem">IMSI ranges (lent by the host)</h2>';
  if (!imsi.length) {
    html += '<p class="dim">None allocated.</p>';
  } else {
    html += '<div class="table-wrap"><table><thead><tr><th>Prefix</th><th>From</th><th>To</th>'
      + '<th>Capacity</th><th>Host</th></tr></thead><tbody>';
    for (const r of imsi) {
      html += `<tr><td>${esc(r.prefix)}</td><td>${esc(r.fromImsi)}</td><td>${esc(r.toImsi)}</td>`
        + `<td>${esc(r.capacity)}</td><td>${esc(r.hostName)}</td></tr>`;
    }
    html += '</tbody></table></div>';
  }
  html += '</div>';
  panel.innerHTML = html;
  panel.querySelector('#nt-run')?.addEventListener('click', async () => {
    const spec = panel.querySelector('#nt-spec').value;
    const rate = Number(panel.querySelector('#nt-rate').value);
    const out = panel.querySelector('#nt-result');
    if (!spec || !(rate > 0)) { out.innerHTML = '<p class="dim">Pick a usage type and a positive rate.</p>'; return; }
    out.innerHTML = '<p class="dim">Replaying the period\u2019s CDRs…</p>';
    const res = await authFetch(`${USAGE_BASE_C}/simulateWholesale?${q}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ rateCard: [{ usageSpecName: spec, wholesaleRate: rate }] }) });
    if (!res.ok) { out.innerHTML = '<p class="copilot-warn">The twin could not answer.</p>'; return; }
    const sim = await res.json();
    let t = '<div class="table-wrap"><table><thead><tr><th>Usage type</th><th>Units</th><th>Current rate</th>'
      + '<th>Proposed</th><th>Current cost</th><th>Proposed cost</th><th>Δ</th></tr></thead><tbody>';
    for (const l of sim.line || []) {
      t += `<tr><td>${esc(l.usageSpecName)}</td><td>${esc(l.units)} ${esc(l.unit)}</td>`
        + `<td>${esc(l.currentRate)}</td><td>${esc(l.proposedRate)}</td>`
        + `<td>${esc(l.currentCost)}</td><td>${esc(l.proposedCost)}</td><td><b>${esc(l.delta)}</b></td></tr>`;
    }
    t += `</tbody></table></div><p><b>Period total: ${esc(sim.currentTotal)} → ${esc(sim.proposedTotal)} `
      + `${esc(sim.currency || '')} (Δ ${esc(sim.delta)})</b></p>`
      + `<p class="dim" style="font-size:12px">${(sim.assumptions || []).map(esc).join(' · ')}</p>`;
    out.innerHTML = t;
  });
  panel.querySelector('#mw-refresh').addEventListener('click', () =>
    renderMobileWholesale(panel.querySelector('#mw-start').value.trim(), panel.querySelector('#mw-end').value.trim()));
  panel.querySelector('#mw-rate').addEventListener('click', async () => {
    const msg = panel.querySelector('#mw-msg');
    const ps = panel.querySelector('#mw-start').value.trim();
    const pe = panel.querySelector('#mw-end').value.trim();
    msg.textContent = 'Rating…'; msg.className = 'dim';
    try {
      const r = await authFetch(`${USAGE_BASE_C}/rateWholesale?periodStart=${ps}&periodEnd=${pe}`, { method: 'POST' });
      if (!r.ok) { const p = await r.json().catch(() => ({})); throw new Error(p.message || `HTTP ${r.status}`); }
      msg.textContent = 'Rated ✓'; msg.className = 'ok';
      setTimeout(() => renderMobileWholesale(ps, pe), 500);
    } catch (e) { msg.textContent = e.message; msg.className = 'error'; }
  });
}

// W-M7 — mobile wholesale PROVIDER: as the host MNE, bill external MVNOs. Per-MVNO
// rate cards are the SLA/tier lever; the settlement is the host's per-MVNO book.
async function renderMobileWholesaleProvider(periodStart) {
  const panel = panelFor('wholesale-panel');
  if (!periodStart) {
    const now = new Date();
    periodStart = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-01`;
  }
  panel.innerHTML = '<div class="editor"><h2>Mobile wholesale — host (provider)</h2><p class="dim">Loading…</p></div>';
  const [cards, orgs, s] = await Promise.all([
    authFetch(`${USAGE_BASE_C}/providerRateCard`).then((r) => (r.ok ? r.json() : [])).catch(() => []),
    authFetch(`${PARTY_BASE}/organization?limit=100`).then((r) => (r.ok ? r.json() : [])).catch(() => []),
    authFetch(`${USAGE_BASE_C}/mobileWholesaleProviderSettlement?periodStart=${periodStart}`)
      .then((r) => (r.ok ? r.json() : null)).catch(() => null),
  ]);
  const orgOptsHtml = (orgs || []).map((o) => `<option value="${esc(o.id)}|${esc(o.name)}">${esc(o.name)}</option>`).join('');
  const mvnos = (s && s.mvno) || [];
  let html = '<div class="editor"><h2>Mobile wholesale — host (provider)</h2>'
    + '<p class="dim small">As the host MNE we bill external MVNOs (who run their own BSS) for the '
    + 'traffic they carry. A <b>per-MVNO rate</b> overrides the default — the SLA/tier lever: a premium '
    + 'MVNO pays more than a budget one for the same usage.</p>'
    // rate card form
    + '<div class="fields">'
    + `<label class="field"><span>MVNO (blank = default rate for all)</span><select id="pr-mvno"><option value="">— default —</option>${orgOptsHtml}</select></label>`
    + '<label class="field"><span>Usage type *</span><input id="pr-spec" placeholder="Mobile data"></label>'
    + '<label class="field"><span>Rate *</span><input id="pr-rate" type="number" step="0.001" placeholder="2.50"></label>'
    + '<label class="field"><span>Unit</span><input id="pr-unit" placeholder="GB"></label>'
    + '</div><div class="actions"><button class="primary" id="pr-add">Set rate</button>'
    + '<span id="pr-msg" class="dim"></span></div>';
  // rate card table
  html += '<h2 style="font-size:1rem;margin-top:1.25rem">Provider rate card</h2>';
  if (!cards.length) {
    html += '<p class="dim">No rates yet — add one above (a default, plus per-MVNO overrides).</p>';
  } else {
    html += '<div class="table-wrap"><table><thead><tr><th>MVNO</th><th>Usage type</th><th>Rate</th>'
      + '<th>Unit</th></tr></thead><tbody>';
    for (const c of cards) {
      html += `<tr><td>${c.mvnoPartyId ? esc(c.mvnoName || c.mvnoPartyId) : '<i>default</i>'}</td>`
        + `<td>${esc(c.usageSpecName)}</td><td>${esc(c.rate)}</td><td>${esc(c.unit)}</td></tr>`;
    }
    html += '</tbody></table></div>';
  }
  // settlement
  html += `<div class="actions" style="align-items:center;margin-top:1.25rem">Period `
    + `<input id="pr-period" value="${esc(periodStart)}" style="width:9rem">`
    + `<button class="ghost" id="pr-refresh">Refresh</button></div>`
    + '<h2 style="font-size:1rem">Settlement — what each MVNO owes you</h2>';
  if (!mvnos.length) {
    html += '<p class="dim">No external-MVNO usage rated for this period yet.</p>';
  } else {
    html += '<div class="table-wrap"><table><thead><tr><th>MVNO</th><th>Usage type</th>'
      + '<th>Units</th><th>Rate</th><th>Amount</th></tr></thead><tbody>';
    for (const m of mvnos) {
      const lines = m.line || [];
      lines.forEach((l, i) => {
        html += `<tr><td>${i === 0 ? esc(m.mvnoName || m.mvnoPartyId) : ''}</td>`
          + `<td>${esc(l.usageSpecName)}</td><td>${esc(l.totalUnits)} ${esc(l.unit)}</td>`
          + `<td>${esc(l.rate)}</td><td>${esc(l.amount)} ${esc(l.currency)}</td></tr>`;
      });
      html += `<tr><td colspan="4" style="text-align:right"><b>${esc(m.mvnoName || m.mvnoPartyId)} total</b></td>`
        + `<td><b>${esc(m.total)}</b></td></tr>`;
    }
    html += `</tbody></table></div><p><b>Total wholesale revenue: ${esc(s.totalRevenue)} ${esc(s.currency)}</b></p>`;
  }
  html += '</div>';
  panel.innerHTML = html;
  panel.querySelector('#pr-refresh').addEventListener('click', () =>
    renderMobileWholesaleProvider(panel.querySelector('#pr-period').value.trim()));
  panel.querySelector('#pr-add').addEventListener('click', async () => {
    const msg = panel.querySelector('#pr-msg');
    const spec = panel.querySelector('#pr-spec').value.trim();
    const rate = panel.querySelector('#pr-rate').value.trim();
    const unit = panel.querySelector('#pr-unit').value.trim();
    const mvnoRaw = panel.querySelector('#pr-mvno').value;
    if (!spec || !rate) { msg.textContent = 'Fill usage type + rate.'; msg.className = 'error'; return; }
    const body = { usageSpecName: spec, rate: Number(rate), unit: unit || null };
    if (mvnoRaw) { const [id, name] = mvnoRaw.split('|'); body.mvnoPartyId = id; body.mvnoName = name; }
    msg.textContent = 'Saving…'; msg.className = 'dim';
    try {
      const r = await authFetch(`${USAGE_BASE_C}/providerRateCard`, { method: 'POST',
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
      if (!r.ok) { const p = await r.json().catch(() => ({})); throw new Error(p.message || `HTTP ${r.status}`); }
      msg.textContent = 'Saved ✓'; msg.className = 'ok';
      setTimeout(() => renderMobileWholesaleProvider(panel.querySelector('#pr-period')?.value.trim()), 500);
    } catch (e) { msg.textContent = e.message; msg.className = 'error'; }
  });
}
