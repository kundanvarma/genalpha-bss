'use strict';

// A KPI card in the workforce/reporting house style.
function kpiCard(label, value, hint, testid) {
  const div = document.createElement('div');
  div.style.cssText = 'flex:1 1 9rem;min-width:9rem;padding:0.8rem 1rem;border:1px solid var(--line,#ddd);border-radius:10px;background:var(--card,#fafafa)';
  if (testid) div.dataset.testid = testid;
  const b = document.createElement('b');
  b.style.cssText = 'display:block;font-size:1.4rem';
  b.textContent = value == null ? '—' : String(value);
  const s = document.createElement('span');
  s.style.cssText = 'font-size:0.8rem;color:var(--dim,#777)';
  s.textContent = label;
  div.append(b, s);
  if (hint) div.title = hint;
  return div;
}
function panelFor(id) {
  // custom panels replace the generic table entirely — clear + hide it so no
  // stale row/count bleeds through.
  el('total').textContent = '';
  el('listing-head').replaceChildren();
  el('listing-body').replaceChildren();
  document.querySelector('.table-wrap')?.setAttribute('hidden', '');
  let panel = document.getElementById(id);
  if (!panel) {
    panel = document.createElement('div');
    panel.id = id;
    document.querySelector('.table-wrap').after(panel);
  }
  panel.hidden = false;
  panel.replaceChildren();
  return panel;
}

// REPORTING — governed sales/finance summary from the subledger (P1).
async function renderReporting() {
  const panel = panelFor('reporting-panel');
  // the operator's currency, not a hardcoded euro: the tenant manifest says which
  const tenantCfg = window.BSS_CONSOLE_CONFIG || {};
  const money = (v) => {
    if (v == null) return '—';
    try {
      return new Intl.NumberFormat(tenantCfg.country ? `${tenantCfg.locale === 'no' ? 'nb' : (tenantCfg.locale || 'en')}-${tenantCfg.country}` : undefined,
        { style: 'currency', currency: tenantCfg.currency || 'EUR',
          ...(Number.isInteger(tenantCfg.priceDecimals) ? { minimumFractionDigits: tenantCfg.priceDecimals, maximumFractionDigits: tenantCfg.priceDecimals } : {}) }).format(Number(v));
    } catch { return Number(v).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + ' ' + (tenantCfg.currency || 'EUR'); }
  };

  const today = new Date().toISOString().slice(0, 10);
  const monthStart = today.slice(0, 8) + '01';
  const controls = document.createElement('div');
  controls.style.cssText = 'display:flex;gap:0.5rem;align-items:center;flex-wrap:wrap;margin:0.2rem 0 1rem';
  const from = document.createElement('input'); from.type = 'date'; from.value = monthStart;
  const to = document.createElement('input'); to.type = 'date'; to.value = today;
  const go = document.createElement('button'); go.textContent = 'Refresh';
  go.style.cssText = 'padding:0.35rem 0.9rem';
  const hint = (t) => { const s = document.createElement('span'); s.className = 'dimhint'; s.textContent = t; return s; };
  controls.append(hint('From'), from, hint('To'), to, go);
  const body = document.createElement('div');
  panel.append(controls, body);

  async function load() {
    body.replaceChildren();
    const res = await authFetch(`${REVENUE_BASE}/summary?fromDate=${from.value}&toDate=${to.value}`);
    if (!res.ok) {
      body.textContent = res.status === 403
        ? 'You do not have access to revenue reporting.' : 'Reporting data unavailable.';
      return;
    }
    const d = await res.json();
    let delta = '—';
    if (d.revenueDeltaPct != null) {
      const p = Number(d.revenueDeltaPct);
      const mag = Math.abs(p) >= 1000 ? '>1000' : Math.abs(p).toFixed(1);
      delta = (p >= 0 ? '▲ ' : '▼ ') + mag + '%';
    }
    const cards = document.createElement('div');
    cards.style.cssText = 'display:flex;flex-wrap:wrap;gap:0.7rem;margin:0.2rem 0 1.2rem';
    cards.append(
      kpiCard('Net revenue', money(d.netRevenue), 'credit − debit over the revenue accounts, this period', 'rep-netrev'),
      kpiCard('vs prior period', delta, 'change vs the prior equal-length window'),
      kpiCard('Cash collected', money(d.cashCollected), 'payments received into the cash account'),
      kpiCard('Tax collected', money(d.taxCollected), 'VAT payable booked this period'),
      kpiCard('Invoices issued', d.invoicesIssued, 'bills booked to the subledger this period', 'rep-invoices'),
    );
    body.append(cards);

    const h = document.createElement('h3');
    h.textContent = 'Revenue by account';
    h.style.cssText = 'margin:1rem 0 0.4rem';
    body.append(h);
    const table = document.createElement('table');
    table.style.cssText = 'width:100%;border-collapse:collapse';
    const head = document.createElement('tr');
    ['Account', 'Code', 'Net'].forEach((t, i) => {
      const th = document.createElement('th');
      th.textContent = t;
      th.style.cssText = 'text-align:' + (i === 2 ? 'right' : 'left') + ';padding:0.35rem 0.5rem;border-bottom:1px solid var(--line,#ddd);color:var(--dim,#777);font-size:0.8rem';
      head.append(th);
    });
    table.append(head);
    for (const a of (d.byAccount || [])) {
      const tr = document.createElement('tr');
      [a.accountName, a.accountCode, money(a.net)].forEach((v, i) => {
        const td = document.createElement('td');
        td.textContent = v;
        td.style.cssText = 'padding:0.35rem 0.5rem;border-bottom:1px solid var(--line,#f0f0f0)' + (i === 2 ? ';text-align:right;font-variant-numeric:tabular-nums' : '');
        tr.append(td);
      });
      table.append(tr);
    }
    body.append(table);

    // ---- P3: the subscription-metrics engine (MRR waterfall / ARPU / churn / NRR) ----
    try {
      const smRes = await authFetch(`${REVENUE_BASE}/subscriptionMetrics?toDate=${to.value}`);
      if (smRes.ok) {
        const sm = await smRes.json();
        const months = sm.months || [];
        const latest = [...months].reverse().find((m) => Number(m.mrr) > 0) || months[months.length - 1];
        const h2 = document.createElement('h3');
        h2.textContent = 'Subscription metrics (billed MRR)';
        h2.style.cssText = 'margin:1.4rem 0 0.4rem';
        body.append(h2);
        if (latest) {
          const row2 = document.createElement('div');
          row2.style.cssText = 'display:flex;gap:0.8rem;flex-wrap:wrap';
          row2.append(
            kpiCard('MRR · ' + latest.month, money(latest.mrr), 'billed recurring revenue (acct 4000) for the month', 'rep-mrr'),
            kpiCard('ARPU', money(latest.arpu), 'MRR / active billed accounts', 'rep-arpu'),
            kpiCard('Active accounts', latest.activeAccounts, 'accounts with recurring revenue this month', 'rep-active'),
            kpiCard('Churn rate', latest.churnRatePct == null ? '—' : latest.churnRatePct + '%', 'accounts billed last month, absent this month', 'rep-churn'),
            kpiCard('Net revenue retention', latest.nrrPct == null ? '—' : latest.nrrPct + '%', 'what last month\'s customers are worth now vs then', 'rep-nrr'));
          body.append(row2);
        }
        // waterfall table by month
        const wt = document.createElement('table');
        wt.style.cssText = 'width:100%;border-collapse:collapse;margin-top:0.6rem';
        wt.dataset.testid = 'rep-waterfall';
        const wh = document.createElement('tr');
        ['Month', 'MRR', 'New', 'Expansion', 'Contraction', 'Churned', 'ARPU', 'NRR %'].forEach((t, i) => {
          const th = document.createElement('th');
          th.textContent = t;
          th.style.cssText = 'text-align:' + (i ? 'right' : 'left') + ';padding:0.35rem 0.5rem;border-bottom:1px solid var(--line,#ddd);color:var(--dim,#777);font-size:0.8rem';
          wh.append(th);
        });
        wt.append(wh);
        for (const m of months) {
          const tr = document.createElement('tr');
          [m.month + (m.baseline ? ' (baseline)' : ''), money(m.mrr), '+' + money(m.newMrr), '+' + money(m.expansionMrr),
            '−' + money(m.contractionMrr), '−' + money(m.churnedMrr), money(m.arpu),
            m.nrrPct == null ? '—' : m.nrrPct].forEach((v, i) => {
            const td = document.createElement('td');
            td.textContent = v;
            td.style.cssText = 'padding:0.35rem 0.5rem;border-bottom:1px solid var(--line,#f0f0f0)' + (i ? ';text-align:right;font-variant-numeric:tabular-nums' : '');
            tr.append(td);
          });
          wt.append(tr);
        }
        body.append(wt);
        // drill-down: who moved the number this month
        const dd = sm.drillDown || [];
        if (dd.length) {
          const h3 = document.createElement('h3');
          h3.textContent = 'Who moved the number — ' + (latest ? latest.month : '');
          h3.style.cssText = 'margin:1rem 0 0.4rem';
          body.append(h3);
          const dt = document.createElement('table');
          dt.style.cssText = 'width:100%;border-collapse:collapse';
          dt.dataset.testid = 'rep-drilldown';
          const dh = document.createElement('tr');
          ['Customer', 'Movement', 'Δ MRR', 'MRR now'].forEach((t, i) => {
            const th = document.createElement('th');
            th.textContent = t;
            th.style.cssText = 'text-align:' + (i > 1 ? 'right' : 'left') + ';padding:0.35rem 0.5rem;border-bottom:1px solid var(--line,#ddd);color:var(--dim,#777);font-size:0.8rem';
            dh.append(th);
          });
          dt.append(dh);
          for (const r of dd.slice(0, 15)) {
            const tr = document.createElement('tr');
            [r.partyId.slice(0, 12) + '…', r.kind, money(r.delta), money(r.mrr)].forEach((v, i) => {
              const td = document.createElement('td');
              td.textContent = v;
              td.style.cssText = 'padding:0.35rem 0.5rem;border-bottom:1px solid var(--line,#f0f0f0)' + (i > 1 ? ';text-align:right;font-variant-numeric:tabular-nums' : '');
              tr.append(td);
            });
            dt.append(tr);
          }
          body.append(dt);
        }
        // CSV export — the console's first real download
        const dl = document.createElement('button');
        dl.textContent = 'Download CSV';
        dl.dataset.testid = 'rep-csv';
        dl.style.cssText = 'margin-top:0.7rem;padding:0.3rem 0.9rem';
        dl.addEventListener('click', async () => {
          const r = await authFetch(`${REVENUE_BASE}/subscriptionMetrics?toDate=${to.value}&format=csv`);
          if (!r.ok) return;
          const blob = new Blob([await r.text()], { type: 'text/csv' });
          const a = document.createElement('a');
          a.href = URL.createObjectURL(blob);
          a.download = 'subscription-metrics.csv';
          a.click();
          URL.revokeObjectURL(a.href);
        });
        body.append(dl);
      }
    } catch (e) { /* metrics are additive — the summary above stands alone */ }

    const note = document.createElement('p');
    note.className = 'dimhint';
    note.style.marginTop = '0.8rem';
    note.textContent = 'Computed once from the subledger — the source of truth. MRR here is BILLED recurring revenue (account 4000) by month; a month shows what the billing run recognised, not catalog list price.';
    body.append(note);
  }
  go.addEventListener('click', load);
  load();
}
