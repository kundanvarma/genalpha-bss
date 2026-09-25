/* Resources 2/9 — bills, the subledger and disputes. */
'use strict';

RESOURCES.push(
  {
    // The desk's first screen: what needs attention, what is queued, where the
    // book stands. A React island (ADR-0022) because the paper's priority rule
    // — exceptions loud, queues visible, normal states calm — is a layout, not
    // a table. Every figure is a fact a service already decided.
    path: 'billingOverview',
    title: 'Overview',
    island: 'billingOverview',
    readOnly: true,
    noCreate: true,
    fields: [],
    columns: [],
  },
  {
    // Bills are money owed; payments are money received. Related jobs, not the
    // same job — so unapplied cash is stated here as the exception it is,
    // beside the action that resolves it.
    path: 'payments',
    title: 'Payments',
    island: 'payments',
    readOnly: true,
    noCreate: true,
    fields: [],
    columns: [],
  },
  {
    // A collections case is one customer's whole story: the debt, the
    // commitments, the stage, what was said, what is restricted, the risk.
    path: 'collections',
    title: 'Collections',
    island: 'collections',
    readOnly: true,
    noCreate: true,
    fields: [],
    columns: [],
  },
  {
    path: 'customerBill',
    base: BILLING_BASE,
    // "Bills": on this desk the customer context is implicit. The page is a
    // React island (ADR-0022) because a bill's situation, its chips and its
    // workspace are more than the generic table can say; the path and the role
    // gate are untouched, so #/customerBill and the suites still land here.
    title: 'Bills',
    island: 'bills',
    readOnly: true,
    fields: [],
    columns: ['billNo', 'relatedParty', 'billingPeriod', 'amountDue', 'state', 'lastUpdate'],
    // a bill is its LINES — expand them in place
    detail: async (item) => {
      const res = await authFetch(`${BILLING_BASE}/customerBill/${item.id}/appliedCustomerBillingRate`);
      const rates = res.ok ? await res.json() : [];
      return Promise.all(rates.map(async (r) => ({
        line: r.name,
        type: r.type,
        amount: `${Number(r.taxExcludedAmount?.value ?? 0).toFixed(2)} ${r.taxExcludedAmount?.unit || ''}`,
        for: r.forParty?.id ? await partyName(r.forParty.id) : '—',
      })));
    },
  },
  {
    // Journal and Chart of accounts are ONE job — what the books say, and what
    // the books are told to say — so they are one React island (ADR-0022) with
    // two views. Both tabs keep their path, title and role gate, and each
    // lands on its own view, so every deep link and every suite still works.
    path: 'journalEntry',
    base: REVENUE_BASE,
    title: 'Journal',
    island: 'accounting',
    islandView: 'journal',
    readOnly: true,
    noCreate: true,
    fields: [],
    columns: [],
  },
  {
    path: 'accountMapping',
    base: REVENUE_BASE,
    title: 'Chart of accounts',
    island: 'accounting',
    islandView: 'chart',
    readOnly: true,
    noCreate: true,
    // Nothing is edited from a table any more. These settings decide which
    // account real money lands in, so a change is PROPOSED here and climbs the
    // ladder on Configuration: drafted, validated, approved, activated.
    fields: [],
    columns: [],
  },
  {
    // Where live financial configuration is changed, and where the setup pages
    // that nobody needs for today's work now live: the ladder over the chart of
    // accounts, bill formats, deliveries and the shadow bill run.
    path: 'financialConfiguration',
    title: 'Configuration',
    island: 'financialConfiguration',
    islandView: 'changes',
    readOnly: true,
    noCreate: true,
    fields: [],
    columns: [],
  },
  {
    path: 'dispute',
    base: BILLING_BASE,
    title: 'Disputes',
    readOnly: true,
    // the back-office worklist: contested money waits for a DECISION with
    // a name on it — credit (a numbered credit note) or uphold (explained)
    fields: [],
    columns: ['billNo', 'reason', 'status', 'creditAmount', 'resolutionNote'],
    rowAction: {
      label: (item) => (item.status === 'open' ? 'Decide' : ''),
      apply: async (item) => {
        if (item.status !== 'open') return;
        const outcome = window.prompt('Decision — type "credit" or "uphold":');
        if (!outcome || !['credit', 'uphold'].includes(outcome)) return;
        let body = { outcome };
        if (outcome === 'credit') {
          const amount = window.prompt('Credit amount:');
          if (!amount) return;
          body.amount = Number(amount);
        }
        const note = window.prompt('Resolution note (told to the customer):');
        if (note) body.note = note;
        await authFetch(`${BILLING_BASE}/dispute/${item.id}/resolve`, {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
        });
      },
    },
  },
);
