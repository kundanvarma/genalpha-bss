'use strict';

/* ---------------- Kanban pipeline board ---------------- */

const PIPELINE_STAGES = [
  { key: 'qualification', label: 'Qualification' },
  { key: 'needsAnalysis', label: 'Needs analysis' },
  { key: 'proposal', label: 'Proposal' },
  { key: 'negotiation', label: 'Negotiation' },
];

async function renderPipelineBoard() {
  const panel = panelFor('pipeline-panel');
  panel.dataset.testid = 'pipeline-board';
  panel.innerHTML = '<p class="dim">loading pipeline…</p>';
  let opps = [];
  let forecast = { openCount: 0, openAmount: 0, weightedForecast: 0, currency: 'USD', stages: [] };
  let funnel = null;
  try {
    [opps, forecast, funnel] = await Promise.all([
      authFetch(`${SALES_BASE}/salesOpportunity`).then((r) => r.json()),
      authFetch(`${SALES_BASE}/salesOpportunity/pipeline`).then((r) => r.json()),
      authFetch(`${SALES_BASE}/salesOpportunity/funnel`).then((r) => r.json()).catch(() => null),
    ]);
  } catch (e) {
    panel.innerHTML = `<p class="error">Could not load the pipeline: ${esc(e.message)}</p>`;
    return;
  }
  const cur = forecast.currency || 'USD';
  const money = (v) => `${cur} ${Number(v || 0).toLocaleString()}`;
  const byStage = (k) => (Array.isArray(opps) ? opps : []).filter(
    (o) => o.state === 'developed' && (o.stage || 'qualification') === k);
  const stageTotal = (k) => byStage(k).reduce((s, o) => s + Number(o.amount || 0), 0);

  let html = '<div class="pipeline-banner" style="display:flex;gap:2rem;align-items:baseline;'
    + 'padding:0.75rem 1rem;border:1px solid var(--line,#3333);border-radius:0.6rem;margin-bottom:1rem;flex-wrap:wrap">'
    + `<div><span class="dim">Weighted forecast</span> <b data-testid="pl-forecast" style="font-size:1.3rem">${esc(money(forecast.weightedForecast))}</b></div>`
    + `<div><span class="dim">Open pipeline</span> <b>${esc(money(forecast.openAmount))}</b></div>`
    + `<div><span class="dim">Open deals</span> <b>${esc(forecast.openCount)}</b></div>`
    + '<div class="dim" style="margin-left:auto;font-size:0.8rem">drag a card to re-stage · closed deals live on the Opportunities tab</div>'
    + '</div>';
  // forecast-category roll-up (Commit = will-land, Best case = upside)
  if (Array.isArray(forecast.byCategory) && forecast.byCategory.length) {
    const label = { commit: 'Commit', bestCase: 'Best case', pipeline: 'Pipeline' };
    html += '<div class="pipeline-cats" style="display:flex;gap:1.5rem;margin:-0.4rem 0 1rem;padding:0 0.2rem;font-size:0.85rem">';
    for (const c of forecast.byCategory) {
      html += `<span class="dim">${esc(label[c.category] || c.category)}: <b>${esc(money(c.amount))}</b> (${esc(c.count)})</span>`;
    }
    html += '</div>';
  }
  html += '<div class="pipeline-cols" style="display:grid;grid-template-columns:repeat(4,1fr);gap:0.75rem;align-items:start">';
  for (const st of PIPELINE_STAGES) {
    const cards = byStage(st.key);
    html += `<div class="pl-col" data-stage="${st.key}" data-testid="pl-col-${st.key}" `
      + 'style="background:var(--panel,#8881);border:1px solid var(--line,#3333);border-radius:0.6rem;padding:0.5rem;min-height:8rem">'
      + `<div class="pl-col-head" style="display:flex;justify-content:space-between;font-weight:600;padding:0.25rem 0.4rem 0.5rem">`
      + `<span>${esc(st.label)}</span><span class="dim" data-testid="pl-count-${esc(st.key)}">${cards.length} · ${esc(money(stageTotal(st.key)))}</span></div>`;
    for (const o of cards) {
      const owner = (o.owner && o.owner.name) ? o.owner.name : '—';
      html += `<div class="pl-card" draggable="true" data-id="${esc(o.id)}" data-testid="pl-card" `
        + 'style="background:var(--card,#fff2);border:1px solid var(--line,#3333);border-radius:0.5rem;'
        + 'padding:0.5rem 0.6rem;margin-bottom:0.5rem;cursor:grab">'
        + `<div style="font-weight:600">${esc(o.name)}</div>`
        + `<div class="dim" style="font-size:0.85rem;margin-top:0.2rem">${esc(o.amount != null ? money(o.amount) : 'no value yet')} · ${esc(o.probability != null ? o.probability + '%' : '—')}</div>`
        + `<div class="dim" style="font-size:0.8rem">${esc(owner)}</div>`
        + '</div>';
    }
    html += '</div>';
  }
  html += '</div>';
  // Funnel analytics strip — conversion, win rate, cycle; and the copilot-ready line.
  if (funnel && Array.isArray(funnel.stageConversion)) {
    const convHtml = funnel.stageConversion
      .map((c) => `${esc(c.from)}→${esc(c.to)} <b>${esc(c.conversionPct)}%</b>`).join(' · ');
    html += '<div class="pipeline-funnel" data-testid="pl-funnel" style="margin-top:1.25rem;'
      + 'padding:0.75rem 1rem;border:1px solid var(--line,#3333);border-radius:0.6rem">'
      + `<div style="display:flex;gap:2rem;flex-wrap:wrap"><div><span class="dim">Win rate</span> `
      + `<b>${esc(funnel.winRatePct)}%</b></div><div><span class="dim">Avg cycle</span> `
      + `<b>${esc(funnel.avgCycleDays)} days</b></div><div><span class="dim">Closed</span> `
      + `<b>${esc(funnel.wonCount)} won / ${esc(funnel.lostCount)} lost</b></div></div>`
      + `<div class="dim" style="margin-top:0.4rem;font-size:0.85rem">Stage conversion: ${convHtml}</div>`
      + `<div class="dim" style="margin-top:0.4rem;font-style:italic">${esc(funnel.summary || '')}</div>`
      + '</div>';
  }
  html += '<p id="pl-msg" class="dim" style="margin-top:0.75rem;min-height:1.2rem"></p>';
  panel.innerHTML = html;

  // HTML5 drag-and-drop: pick up a card, drop on a column → PATCH its stage.
  let dragId = null;
  panel.querySelectorAll('.pl-card').forEach((card) => {
    card.addEventListener('dragstart', (e) => {
      dragId = card.dataset.id;
      e.dataTransfer.setData('text/plain', card.dataset.id);
      e.dataTransfer.effectAllowed = 'move';
      card.style.opacity = '0.4';
    });
    card.addEventListener('dragend', () => { card.style.opacity = '1'; });
  });
  panel.querySelectorAll('.pl-col').forEach((col) => {
    col.addEventListener('dragover', (e) => { e.preventDefault(); col.style.outline = '2px dashed var(--accent,#69f)'; });
    col.addEventListener('dragleave', () => { col.style.outline = 'none'; });
    col.addEventListener('drop', async (e) => {
      e.preventDefault();
      col.style.outline = 'none';
      const id = dragId || e.dataTransfer.getData('text/plain');
      const stage = col.dataset.stage;
      if (!id || !stage) return;
      const msg = panel.querySelector('#pl-msg');
      msg.textContent = 'Moving…';
      try {
        const r = await authFetch(`${SALES_BASE}/salesOpportunity/${id}`, {
          method: 'PATCH', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ stage }),
        });
        if (!r.ok) { const p = await r.json().catch(() => ({})); throw new Error(p.message || `HTTP ${r.status}`); }
        renderPipelineBoard();
      } catch (err) { msg.textContent = err.message; msg.className = 'error'; }
    });
  });
}
