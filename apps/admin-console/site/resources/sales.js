/* Resources 7/9 — leads, scoring and routing rules, guided selling, quotas, pricing rules, opportunities, the pipeline, config rules. */
'use strict';

RESOURCES.push(
  {
    path: 'salesLead',
    base: SALES_BASE,
    title: 'Sales leads',
    // TMF699: prospects knocking — the storefront's "Talk to sales" form,
    // campaigns, CSRs. Qualify turns a lead into an opportunity.
    noEdit: true,
    noDelete: true,
    fields: [
      { name: 'name', label: 'What is the lead about?', required: true },
      { name: 'contactName', label: 'Contact name' },
      { name: 'contactEmail', label: 'Contact email' },
      { name: 'company', label: 'Company' },
      { name: 'description', label: 'Notes', kind: 'longtext' },
    ],
    columns: ['name', 'company', 'source', 'score', 'grade', 'state', 'lastUpdate'],
    rowAction: {
      label: (item) => (item.state === 'acknowledged' ? 'Qualify' : '—'),
      apply: (item) => (item.state === 'acknowledged'
        ? authFetch(`${SALES_BASE}/salesLead/${item.id}`, {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ state: 'qualified' }),
          })
        : Promise.resolve()),
    },
    detail: async (item) => [{
      contact: [item.contactName, item.contactEmail, item.company].filter(Boolean).join(' · ') || '—',
      notes: item.description || '—',
      score: `${item.score ?? 0} (${item.grade || '—'})`,
      routedTo: (item.owner && item.owner.name) || '— (no routing band cleared)',
      opportunity: item.salesOpportunity ? item.salesOpportunity.id : '— (qualify to create one)',
    }],
  },
  {
    // Lead scoring rules: signal → points (source / company / size / keyword).
    path: 'scoringRule',
    base: '/tmf-api/salesManagement/v4/salesLead',
    title: 'Lead scoring',
    intro: 'Every new lead gets a score from the signals that predict a sale. Each rule here says: when a lead shows this signal, add these points. The total decides which sales band the lead lands in (Lead routing, next tab). A rule is a row — add one, or remove one; there is nothing else to configure.',
    noEdit: true,
    noDelete: true,
    fields: [
      { name: 'field', label: 'When the lead…', kind: 'select', options: [
        { label: 'came from a source (web, store, partner, campaign…)', value: 'source' },
        { label: 'names a company', value: 'companyPresent' },
        { label: 'is a company of at least this many employees', value: 'companySizeMin' },
        { label: 'mentions a keyword', value: 'keyword' },
        { label: 'has engaged with us before (from the customer data platform)', value: 'engagement' },
      ] },
      { name: 'value', label: 'What to match — the source name, the employee count, the keyword, or one of: opened, clicked, engaged, knownProspect (leave empty for "names a company")' },
      { name: 'points', label: 'Points to add to the lead\'s score', kind: 'number', required: true },
    ],
    columns: ['field', 'value', 'points'],
  },
  {
    // Lead routing bands: score ≥ minScore → assignee (highest band wins).
    path: 'routingRule',
    base: '/tmf-api/salesManagement/v4/salesLead',
    title: 'Lead routing',
    intro: 'Who works a lead, by its score. Each band says: from this score upwards, hand the lead to this person or team. The highest band the lead clears wins; a lead below every band stays unassigned until someone picks it up.',
    noEdit: true,
    noDelete: true,
    fields: [
      { name: 'minScore', label: 'From this score upwards', kind: 'number', required: true },
      { name: 'assignee', label: 'Hand the lead to (a name or a team)', required: true },
    ],
    columns: ['minScore', 'assignee'],
  },
  {
    // Guided-selling questionnaire.
    path: 'guidedQuestion',
    base: '/tmf-api/quoteManagement/v4/quote',
    title: 'Guided questions',
    noEdit: true,
    noDelete: true,
    fields: [
      { name: 'questionKey', label: 'Answer key', required: true },
      { name: 'prompt', label: 'Question', required: true },
      { name: 'sortOrder', label: 'Order', kind: 'number' },
    ],
    columns: ['questionKey', 'prompt', 'sortOrder'],
  },
  {
    // Guided-selling rules: an answer recommends an offering.
    path: 'guidedRecommendation',
    base: '/tmf-api/quoteManagement/v4/quote',
    title: 'Guided rules',
    noEdit: true,
    noDelete: true,
    fields: [
      { name: 'questionKey', label: 'Answer key', required: true },
      { name: 'answerValue', label: 'When answer is', required: true },
      { name: 'offeringName', label: 'Recommend offering', required: true },
      { name: 'quantity', label: 'Quantity', kind: 'number' },
    ],
    columns: ['questionKey', 'answerValue', 'offeringName', 'quantity'],
  },
  {
    // Sales quotas per owner/period.
    path: 'quota',
    base: '/tmf-api/salesManagement/v4/salesOpportunity',
    title: 'Sales quotas',
    noEdit: true,
    noDelete: true,
    fields: [
      { name: 'ownerName', label: 'Owner', required: true },
      { name: 'team', label: 'Team (rolls up)' },
      { name: 'quotaPeriod', label: 'Period (YYYY-MM)', required: true },
      { name: 'amount', label: 'Quota amount', kind: 'number', required: true },
    ],
    columns: ['ownerName', 'team', 'quotaPeriod', 'amount'],
  },
  {
    // Volume pricing tiers: quantity ≥ minQuantity of an offering → line discount.
    path: 'pricingRule',
    base: '/tmf-api/quoteManagement/v4/quote',
    title: 'Volume pricing',
    noEdit: true,
    noDelete: true,
    fields: [
      { name: 'offeringName', label: 'Offering', required: true },
      { name: 'minQuantity', label: 'From quantity', kind: 'number', required: true },
      { name: 'discountPercent', label: 'Discount %', kind: 'number', required: true },
      { name: 'segment', label: 'CDP segment (optional — a trait value; beats the volume tier)' },
    ],
    columns: ['offeringName', 'minQuantity', 'discountPercent', 'segment'],
  },
  {
    path: 'salesOpportunity',
    base: SALES_BASE,
    title: 'Opportunities',
    // The B2B pipeline object: born by qualifying a lead, then WORKED here —
    // moved along the stages, given a value, a close date and an owner, until
    // it is won (ideally with the quote that sealed it) or lost. A weighted
    // forecast falls out of stage × amount. Not authored from scratch (noCreate).
    noCreate: true,
    noDelete: true,
    fields: [
      { name: 'stage', label: 'Pipeline stage', kind: 'select', options: [
        { label: 'Qualification', value: 'qualification' },
        { label: 'Needs analysis', value: 'needsAnalysis' },
        { label: 'Proposal', value: 'proposal' },
        { label: 'Negotiation', value: 'negotiation' },
      ] },
      { name: 'forecastCategory', label: 'Forecast category', kind: 'select', options: [
        { label: 'Pipeline', value: 'pipeline' },
        { label: 'Best case', value: 'bestCase' },
        { label: 'Commit', value: 'commit' },
      ] },
      { name: 'amount', label: 'Deal value', kind: 'number' },
      { name: 'probability', label: 'Win probability %', kind: 'number' },
      { name: 'expectedCloseDate', label: 'Expected close', kind: 'date', plain: true },
      { name: 'ownerName', label: 'Owner' },
      { name: 'partyId', label: 'Account party id (enables the 360 timeline)' },
      { name: 'description', label: 'Notes', kind: 'longtext' },
    ],
    columns: ['name', 'stage', 'amount', 'probability', 'state', 'lastUpdate'],
    // The one lifecycle button on the row; field edits (incl. re-staging) go
    // through Edit. Close-lost and quote-attach are the API's job.
    rowAction: {
      label: (item) => (item.state === 'developed' ? 'Mark won' : '—'),
      apply: (item) => (item.state === 'developed'
        ? authFetch(`${SALES_BASE}/salesOpportunity/${item.id}`, {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ state: 'won' }),
          })
        : Promise.resolve()),
    },
    // A uniform field/value property sheet — the detail engine renders one
    // table off the first row's keys, so every row must share the same shape.
    detail: async (item) => {
      const money = (v) => (v == null ? '—' : `${item.currency || 'USD'} ${v}`);
      const rows = [
        { field: 'Stage', value: item.stage || '—' },
        { field: 'Forecast category', value: item.forecastCategory || '—' },
        { field: 'Days in stage', value: item.daysInStage == null ? '—' : String(item.daysInStage) },
        { field: 'Deal value', value: money(item.amount) },
        { field: 'Win probability', value: item.probability == null ? '—' : item.probability + '%' },
        { field: 'Weighted', value: item.amount != null && item.probability != null
          ? money((Number(item.amount) * item.probability / 100).toFixed(2)) : '—' },
        { field: 'Expected close', value: item.expectedCloseDate || '—' },
        { field: 'Owner', value: (item.owner && item.owner.name) || '—' },
        { field: 'Account (360)', value: item.partyId || '— (prospect — not on a 360)' },
        { field: 'From lead', value: item.salesLead ? item.salesLead.id : '—' },
        { field: 'Quote', value: item.quote ? item.quote.id : '— (win with a quote ref via API)' },
        { field: 'Close reason', value: item.closeReason || '—' },
      ];
      (item.items || []).forEach((li) => rows.push({
        field: `Line — ${li.quantity}× ${li.offeringName}`,
        value: `${money(li.unitPrice)} each · ${money(li.lineTotal)} total`,
      }));
      (item.activities || []).slice(0, 8).forEach((a) => rows.push({
        field: `Activity — ${a.type}`,
        value: `${a.note}  (${a.occurredAt})`,
      }));
      return rows;
    },
  },
  {
    // The visual pipeline: stage columns, draggable deal cards, per-column
    // totals and a probability-weighted forecast — the surface a sales team
    // runs its pipeline review on. Drag a card to re-stage (PATCH).
    path: 'salesPipeline',
    title: 'Pipeline board',
    pipelineBoard: true,
  },
  {
    // CPQ configuration rules: requires / excludes / min / max on offerings,
    // enforced when a quote is built (and checkable via the validate endpoint).
    path: 'configRule',
    base: '/tmf-api/quoteManagement/v4/quote',
    title: 'CPQ rules',
    noEdit: true,
    noDelete: true,
    fields: [
      { name: 'ruleType', label: 'Rule', kind: 'select', options: [
        { label: 'Requires', value: 'requires' },
        { label: 'Excludes', value: 'excludes' },
        { label: 'Min quantity', value: 'minQty' },
        { label: 'Max quantity', value: 'maxQty' },
      ] },
      { name: 'subjectOffering', label: 'Offering', required: true },
      { name: 'objectOffering', label: 'Other offering (requires/excludes)' },
      { name: 'qty', label: 'Quantity bound (min/max)', kind: 'number' },
      { name: 'message', label: 'Message shown on violation' },
    ],
    columns: ['ruleType', 'subjectOffering', 'objectOffering', 'qty', 'message'],
  },
);
