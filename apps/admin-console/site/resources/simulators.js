/* Resources 6/9 — the commercial simulators, My operator, bill distribution, unapplied remittances. */
'use strict';

RESOURCES.push(
  {
    path: 'simulate/priceChange',
    base: '/ai/v1',
    title: 'Simulator',
    // SIMULATE THE MONEY BEFORE YOU MOVE IT (P1): a proposed price replayed
    // against the REAL base, catalog and wholesale rate card. Honesty on the
    // face: every report lists its assumptions; nothing mutates production —
    // the report is the only thing written.
    noEdit: true,
    noDelete: true,
    fields: [
      { name: 'offeringName', label: 'Offering (exact catalog name)', required: true },
      { name: 'newMonthlyPrice', label: 'Proposed monthly price', kind: 'number', required: true },
      { name: 'assumedChurnPct', label: 'Assumed churn % on the raised base (blank = mechanical, everyone stays)', kind: 'number' },
      { name: 'name', label: 'Report name (blank = auto)' },
    ],
    assemble: (body) => ({
      name: body.name,
      assumedChurnPct: body.assumedChurnPct,
      changes: [{ offeringName: body.offeringName, newMonthlyPrice: Number(body.newMonthlyPrice) }],
    }),
    columns: ['name', 'totalAnnualRevenueDelta', 'currency', 'subscribersAtChurnRisk', 'createdAt'],
    detail: async (item) => {
      const r = item.report || {};
      const rows = (r.lines || []).map((l) => ({
        offering: l.offeringName,
        subscribers: l.subscribers,
        price: `${l.currentMonthly} → ${l.proposedMonthly}`,
        'Δ / year': l.annualRevenueDelta,
        'margin/sub': l.marginPerSubBefore != null ? `${l.marginPerSubBefore} → ${l.marginPerSubAfter}` : '—',
        'churn-risk subs': l.subscribersAtChurnRisk,
        'Δ w/ assumed churn': l.annualRevenueDeltaWithAssumedChurn ?? '—',
      }));
      rows.push({ offering: 'ASSUMPTIONS', subscribers: '', price: '',
        'Δ / year': '', 'margin/sub': '', 'churn-risk subs': '',
        'Δ w/ assumed churn': (r.assumptions || []).join(' · ') });
      return rows;
    },
  },
  {
    path: 'simulate/prospect',
    base: '/ai/v1',
    title: 'Prospect sim',
    // P4 — "your business on this BSS": a price list + an assumed base mix
    // become revenue, cost ceiling and margin floor. The ONLY simulator with
    // no real data behind it — and its report says so first.
    noEdit: true,
    noDelete: true,
    fields: [
      { name: 'name', label: 'Scenario name', required: true },
      { name: 'currency', label: 'Currency', placeholder: 'NOK' },
      { name: 'wholesaleDataRatePerGb', label: 'Wholesale data rate per GB (blank = no cost side)', kind: 'number' },
      { name: 'offerings', label: 'Offerings as JSON: [{"name","monthlyPrice","subscribers","allowanceGb"?}, …]', kind: 'longtext', required: true },
    ],
    assemble: (body) => {
      let offerings = body.offerings;
      try { offerings = JSON.parse(body.offerings); } catch { /* the API rejects with a clear message */ }
      return { name: body.name, currency: body.currency,
        wholesaleDataRatePerGb: body.wholesaleDataRatePerGb, offerings };
    },
    columns: ['name', 'totalSubscribers', 'annualRevenue', 'annualGrossMarginFloor', 'currency', 'createdAt'],
    detail: async (item) => {
      const r = item.report || {};
      const rows = (r.lines || []).map((l) => ({
        offering: l.name, subscribers: l.subscribers, 'price/mo': l.monthlyPrice,
        'revenue/yr': l.annualRevenue,
        'margin/sub': l.marginPerSub ?? '—',
      }));
      rows.push({ offering: 'ASSUMPTIONS', subscribers: '', 'price/mo': '', 'revenue/yr': '',
        'margin/sub': (r.assumptions || []).join(' · ') });
      return rows;
    },
  },
  {
    path: 'myOperator',
    base: ONBOARDING_BASE,
    title: 'Brand',
    // THE TENANT'S OWN VOICE: a hosted operator's marketing team edits its
    // storefront brand — name, color, tagline — without host-admin rights.
    // The shop follows within one refresh interval; blank tagline = the
    // built-in line.
    noCreate: true,
    noDelete: true,
    fields: [
      { name: 'name', label: 'Brand name', required: true },
      { name: 'color', label: 'Brand color', placeholder: '#E63329' },
      { name: 'tagline', label: 'Storefront tagline — the hero line under the brand name (blank = the built-in line)' },
    ],
    columns: ['name', 'color', 'tagline'],
  },
  {
    path: 'billDistribution',
    base: BILLING_BASE,
    title: 'Deliveries',
    // THE DELIVERY LEDGER: every bill's trip to the distribution partner
    // — sent after how many tries, what the buyer answered (Peppol
    // Invoice Response), and what FAILED and deserves a retry.
    noEdit: true,
    noDelete: true,
    readOnly: true,
    fields: [],
    columns: ['billNo', 'format', 'channel', 'status', 'attempts', 'buyerStatus', 'lastError', 'sentAt'],
    rowAction: {
      label: (item) => (item.status === 'failed' ? 'Retry' : '—'),
      apply: (item) => (item.status === 'failed'
        ? authFetch(`${BILLING_BASE}/billDistribution/${item.id}/retry`, { method: 'POST' })
        : Promise.resolve()),
    },
  },
  {
    path: 'remittance/unapplied',
    base: BILLING_BASE,
    title: 'Unapplied cash',
    // Money the bank says arrived but no bill cleanly claims — unknown
    // reference, wrong amount, an already-settled bill. The classic AR
    // queue a human resolves; nothing is ever guessed at or dropped.
    noEdit: true,
    noDelete: true,
    readOnly: true,
    fields: [],
    columns: ['reference', 'amount', 'reason', 'batchRef', 'receivedAt'],
  },
);
