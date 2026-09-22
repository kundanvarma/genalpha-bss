'use strict';

/* ---------- AI data flows (T-P3): the exposure receipts, live. What class
 * of data left on every AI call, to which provider and jurisdiction — read
 * from the same ledger the governor writes. */
async function renderAiFlows() {
  const panel = copilotPanel();
  panel.replaceChildren();
  panel.dataset.testid = 'aiflows-pane';
  const intro = document.createElement('p');
  intro.className = 'dim'; intro.style.cssText = 'font-size:13px;margin:6px 0 12px';
  intro.textContent = 'Every AI call carries an exposure receipt: what CLASS of data left, to which '
    + 'provider and jurisdiction. Raw exposure is a per-tenant opt-in, never assumed.';
  const totals = document.createElement('div'); totals.dataset.testid = 'aiflows-totals';
  totals.style.cssText = 'margin:0 0 14px';
  const table = document.createElement('div'); table.dataset.testid = 'aiflows-table';

  const COLORS = { none: '#2e7d32', twin: '#0E7C7B', aggregate: '#4a90e2',
    'raw-redacted': '#f5a623', raw: '#c62828', unlabelled: '#607d8b' };
  const rep = await (await authFetch('/ai/v1/exposure')).json()
    .catch(() => ({ byExposure: {}, flows: [], classes: {} }));

  for (const [cls, n] of Object.entries(rep.byExposure || {})) {
    const chip = document.createElement('span');
    chip.style.cssText = `display:inline-block;margin:0 8px 6px 0;padding:4px 12px;border-radius:14px;`
      + `font-size:13px;font-weight:600;background:${COLORS[cls] || '#607d8b'};color:#fff`;
    chip.textContent = `${cls}: ${n}`;
    chip.title = (rep.classes || {})[cls] || '';
    totals.append(chip);
  }
  const legend = document.createElement('p'); legend.className = 'dim';
  legend.style.cssText = 'font-size:11px;margin:4px 0 0';
  legend.textContent = 'none = nothing left · twin = deterministic fiction (Tvilling) · '
    + 'aggregate = rollups only · raw = actual customer data (opt-in) · unlabelled = pre-receipt calls';
  totals.append(legend);

  for (const f of (rep.flows || [])) {
    const row = document.createElement('div'); row.className = 'panel'; row.dataset.testid = 'aiflow-row';
    row.style.cssText = 'padding:8px 12px;margin:6px 0;display:flex;gap:14px;align-items:baseline';
    const cls = document.createElement('span');
    cls.style.cssText = `min-width:90px;font-weight:700;color:${COLORS[f.exposure] || '#607d8b'}`;
    cls.textContent = f.exposure;
    const uc = document.createElement('span'); uc.style.cssText = 'min-width:200px;font-weight:600';
    uc.textContent = f.useCase;
    const meta = document.createElement('span'); meta.className = 'dim'; meta.style.fontSize = '12px';
    meta.textContent = `${f.provider} · ${f.jurisdiction} · ${f.calls} call(s)`;
    row.append(cls, uc, meta);
    table.append(row);
  }
  if (!(rep.flows || []).length) {
    const p = document.createElement('p'); p.className = 'dim';
    p.textContent = 'No AI calls on the ledger yet.'; table.append(p);
  }
  panel.append(intro, totals, table);
}
