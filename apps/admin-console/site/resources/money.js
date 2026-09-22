/* Resources 2/9 — bills, the subledger and disputes. */
'use strict';

RESOURCES.push(
  {
    path: 'customerBill',
    base: BILLING_BASE,
    title: 'Customer Bills',
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
    path: 'journalEntry',
    base: REVENUE_BASE,
    title: 'Journal',
    readOnly: true,
    // the subledger: every billing/payment event as a BALANCED double-entry
    // posting — what the ERP's general ledger ingests (docs/revenue-export-plan.md)
    fields: [],
    columns: ['entryDate', 'sourceType', 'description', 'currency'],
    detail: async (item) => {
      const res = await authFetch(`${REVENUE_BASE}/journalEntry/${item.id}`);
      const entry = res.ok ? await res.json() : { lines: [] };
      return (entry.lines || []).map((l) => ({
        account: `${l.accountCode} ${l.accountName}`,
        debit: Number(l.debit) ? Number(l.debit).toFixed(2) : '',
        credit: Number(l.credit) ? Number(l.credit).toFixed(2) : '',
        ref: l.ref,
        line: l.description,
      }));
    },
  },
  {
    path: 'accountMapping',
    base: REVENUE_BASE,
    title: 'Chart of accounts',
    noEdit: true,
    noDelete: true,
    // posting rules as DATA: finance's own codes and names; a remap applies
    // to FUTURE postings only — booked journal lines keep their snapshot.
    // configValue: VAT percent on 'tax'; currency-per-point on 'loyalty:liability'.
    fields: [
      { name: 'key', label: 'Posting key', kind: 'select', required: true, options: [
        { value: 'ar', label: 'Accounts receivable (control)' },
        { value: 'cash', label: 'Cash / PSP clearing' },
        { value: 'rate:recurringCharge', label: 'Service revenue' },
        { value: 'rate:usageCharge', label: 'Usage revenue' },
        { value: 'rate:discount', label: 'Discounts (contra)' },
        { value: 'rate:priceAdjustment', label: 'Pricing adjustments' },
        { value: 'rate:disputeCredit', label: 'Dispute credits (billed lines)' },
        { value: 'creditNote', label: 'Credit notes (contra-revenue)' },
        { value: 'dispute', label: 'Dispute credits (post-journal)' },
        { value: 'refund', label: 'Refunds (contra)' },
        { value: 'tax', label: 'VAT payable — configValue = percent' },
        { value: 'loyalty:expense', label: 'Loyalty program expense' },
        { value: 'loyalty:liability', label: 'Loyalty points liability — configValue = value per point' },
      ] },
      { name: 'accountCode', label: 'Account code (your GL)', required: true },
      { name: 'accountName', label: 'Account name', required: true },
      { name: 'configValue', label: 'Config value (tax % / per-point value — see key)', kind: 'number' },
    ],
    columns: ['key', 'accountCode', 'accountName', 'configValue'],
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
