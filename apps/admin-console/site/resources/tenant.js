/* Resources 5/9 — tenant settings, shadow billing drift, dunning, bill formats, findings, the operator. */
'use strict';

RESOURCES.push(
  {
    path: 'settings',
    base: CAMPAIGN_BASE,
    title: 'Guardrails',
    // The tenant's marketing-touch budget: campaigns skip and journeys
    // postpone once a customer is at their cap. 0 = off. Saving replaces
    // the single settings row.
    noEdit: true,
    noDelete: true,
    fields: [
      { name: 'maxMarketingMessages', label: 'Max marketing messages per customer (0 = no cap)', kind: 'number', required: true },
      { name: 'perDays', label: '…per how many days', kind: 'number', placeholder: '1' },
      { name: 'quietStart', label: 'Quiet hours start (HH:mm, tenant-local) — campaigns skip, journeys park', placeholder: 'e.g. 21:00' },
      { name: 'quietEnd', label: 'Quiet hours end (HH:mm)', placeholder: 'e.g. 08:00' },
      { name: 'timeZone', label: 'Time zone', placeholder: 'e.g. Europe/Oslo (blank = server zone)' },
    ],
    columns: ['maxMarketingMessages', 'perDays', 'capActive', 'quietStart', 'quietEnd', 'quietActive'],
  },
  {
    path: 'shadowDrift',
    base: BILLING_BASE,
    title: 'Shadow billing',
    // P3 — the parallel bill run, standing: what will bill DIFFERENTLY next
    // cycle vs the last real invoice, caught by the sweep before it lands.
    // Setup, not daily work: it is one area of the Configuration island now,
    // and this tab keeps its path and title so a deep link still lands on it.
    island: 'financialConfiguration',
    islandView: 'shadow',
    noEdit: true,
    noDelete: true,
    readOnly: true,
    noCreate: true,
    fields: [],
    columns: [],
  },
  {
    path: 'dunning',
    base: BILLING_BASE,
    title: 'Dunning',
    // Who is overdue, who broke their plan, what is still owed — the
    // collections worklist, fed by the installment sweep.
    readOnly: true,
    fields: [],
    columns: ['billNo', 'partyId', 'paidCount', 'installments', 'remaining', 'currency', 'status', 'nextDueAt'],
  },
  {
    path: 'billFormatProfile',
    base: BILLING_BASE,
    title: 'Bill formats',
    // Setup, not daily work: one area of the Configuration island (ADR-0022).
    // The tab keeps its path, title and role gate; the form is React now.
    island: 'financialConfiguration',
    islandView: 'formats',
    readOnly: true,
    noCreate: true,
    // FORMAT PROFILES AS CONFIG ROWS: what a country's e-invoice profile
    // IS — the syntax, the CustomizationID/ProfileID it declares, whether
    // a payment reference is required. Adding a country here is an
    // insert, not a deploy; the tenant's distribution format picks a row
    // by code and the renderer follows the row.
    noDelete: true,
    fields: [],
    columns: [],
  },
  {
    path: 'findings',
    base: ADVISOR_BASE,
    title: 'Product advisor',
    // RECEIPTS, then advice: the advisor counts (top-up attach, market
    // price gaps from the tenant's feed), the LLM only narrates, and
    // Adopt births a DRAFT offering ("In study") — humans decide.
    noEdit: true,
    noDelete: true,
    readOnly: true,
    fields: [],
    columns: ['kind', 'offering', 'insight', 'suggestion'],
    rowAction: {
      label: (item) => (item.proposal ? 'Adopt as draft…' : '—'),
      apply: (item) => (item.proposal
        ? authFetch(`${ADVISOR_BASE}/adopt`, { method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(item.proposal) })
        : Promise.resolve()),
    },
  },
  {
    path: 'operator',
    base: ONBOARDING_BASE,
    title: 'Operators',
    // OPERATOR-AS-A-FORM: the host admin mints a whole new operator —
    // realm, registry entry, starter catalog — from this page. The fleet
    // picks the newcomer up LIVE; nothing restarts, nothing rebuilds.
    // editing = LIVE mutation: brand/locale/currency follow the form
    noDelete: true,
    fields: [
      { name: 'id', label: 'Operator id (a-z, digits — becomes the realm & hostnames)', required: true },
      { name: 'name', label: 'Brand name', required: true },
      { name: 'locale', label: 'Locale (en, no, da, sv…)', placeholder: 'en' },
      { name: 'currency', label: 'Currency', placeholder: 'EUR' },
      { name: 'color', label: 'Brand color', placeholder: '#B85C38' },
      { name: 'tagline', label: 'Storefront tagline — the hero line under the brand name (blank = the built-in line)' },
      // Price parity is COMMERCIAL policy, so it lives on the host desk:
      // uniform refuses channel-priced rules; per-channel allows them and
      // the storefront/agent manifest says so openly.
      { name: 'priceParityMode', label: 'Price parity (uniform | per-channel)', placeholder: 'uniform' },
      { name: 'catalogGovernance', label: 'Catalog governance (direct | governed) — governed: create lands as a draft, Launch is a decision', placeholder: 'direct' },
      // Agentic commerce: how much of this operator AI shopping agents see.
      // New operators are born 'off' — being shopped by agents is opt-in.
      { name: 'agentCommerce', label: 'Agent commerce (off | discovery | full)', placeholder: 'off' },
    ],
    columns: ['id', 'name', 'locale', 'currency', 'agentCommerce'],
  },
);
