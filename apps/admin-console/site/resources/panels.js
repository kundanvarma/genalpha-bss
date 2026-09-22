/* Resources 9/9 — the custom panes (copilots, approvals, decisions, ontology, staff, workforce, reporting, integrations …): each a stub whose flag loadList dispatches on. */
'use strict';

RESOURCES.push(
  {
    path: 'copilot',
    title: 'Product copilot',
    copilot: true,      // custom chat panel, not the generic CRUD table
    readOnly: true,
    fields: [],
    columns: [],
  },
  {
    path: 'approvals',
    title: 'Approvals',
    approvals: true,    // the launch desk: drafts to request, decisions, ticks, holds
    readOnly: true,
    fields: [],
    columns: [],
  },
  {
    path: 'envelopes',
    title: 'Pre-approved launches', // was 'Envelopes' — what the page holds: standing rules that let routine offers launch without an approver (Kundan, 21 Sep)
    envelopes: true,    // pre-approved launch envelopes, authored with pickers
    readOnly: true,
    fields: [],
    columns: [],
  },
  {
    path: 'growthCopilot',
    title: 'Marketing copilot',
    growthCopilot: true, // conversational journey/campaign authoring
    readOnly: true,
    fields: [],
    columns: [],
  },
  {
    path: 'audienceBuilder',
    title: 'Audience builder',
    audienceBuilder: true, // BSS-native rule-tree audiences (no CDP round-trip)
    readOnly: true,
    fields: [],
    columns: [],
  },
  {
    path: 'socialListening',
    title: 'Social listening',
    socialListening: true, // inbound: brand mentions + sentiment
    readOnly: true,
    fields: [],
    columns: [],
  },
  {
    path: 'socialCare',
    title: 'Social care',
    socialCare: true, // inbound DMs -> triage -> trouble tickets
    readOnly: true,
    fields: [],
    columns: [],
  },
  {
    path: 'attribution',
    title: 'Attribution',
    attribution: true, // portfolio lift + incremental revenue across programs
    readOnly: true,
    fields: [],
    columns: [],
  },
  {
    path: 'aiflows',
    title: 'AI data flows',
    aiflows: true, // T-P3: exposure receipts — what class of data left, where
    readOnly: true,
    fields: [],
    columns: [],
  },
  {
    path: 'decisions',
    title: 'Decisions',
    intro: 'Why did we do that? Every automatic choice, in one sentence each; open one for the receipt.',
    decisions: true, // continuous learning: the decision log and its receipts
    readOnly: true,
    fields: [],
    columns: [],
  },
  {
    path: 'learning-contracts',
    title: 'Learning contracts',
    intro: 'What the system may decide on its own — one written contract per kind of decision.',
    learningContracts: true, // continuous learning: intent per decision point, as configuration
    readOnly: true,
    fields: [],
    columns: [],
  },
  {
    path: 'device-entitlements',
    title: 'Device entitlements',
    intro: 'Phones ask this server what their subscription includes — Wi-Fi calling, VoLTE, an eSIM for the watch. The plan decides; the SIM proves itself.',
    deviceEntitlements: true, // GSMA TS.43: what each line's phone may use, and what the phones asked
    readOnly: true,
    fields: [],
    columns: [],
  },
  {
    path: 'ontology',
    title: 'What the BSS can do',
    intro: 'The operational ontology: the things this business knows, what can be done with them, under which conditions, by whom, and what follows. The same definitions govern execution, the SDK and the agents’ tools.',
    ontology: true, // the Operational Semantic Registry, in words — and a dry run of any action
    readOnly: true,
    fields: [],
    columns: [],
  },
  {
    path: 'voc',
    title: 'Voice of Customer',
    voc: true, // SI-P4: battery aggregates per aspect + early-warning alerts
    readOnly: true,
    fields: [],
    columns: [],
  },
  {
    path: 'landing',
    base: '/insight/v1',
    title: 'Landing pages',
    // Author a standalone campaign landing page + consent-first lead form. Create
    // POSTs (upsert by slug); Edit pre-fills the form and PATCHes by id (the slug,
    // the page's public URL, stays fixed on edit).
    fields: [
      { name: 'headline', label: 'Headline', required: true },
      { name: 'subhead', label: 'Subhead — one line under the headline', kind: 'longtext' },
      { name: 'ctaLabel', label: 'Button label', placeholder: 'Get the offer' },
      { name: 'utmSource', label: 'Campaign (utm_source) — captured leads are stamped with this', required: true },
      { name: 'slug', label: 'URL slug (optional — derived from the headline)' },
      { name: 'logoUrl', label: 'Logo URL (optional)' },
      { name: 'heroImageUrl', label: 'Hero image URL (optional)' },
      { name: 'brandColor', label: 'Brand colour #hex (optional)', placeholder: '#0f766e' },
      { name: 'ctaUrl', label: 'Secondary "learn more" link (optional)' },
      { name: 'privacyUrl', label: 'Privacy link URL (optional)' },
    ],
    columns: ['slug', 'headline', 'utmSource', 'url'],
    rowAction: {
      label: () => 'Open page',
      apply: (item) => { window.open(item.url, '_blank', 'noopener'); return Promise.resolve(); },
    },
  },
  {
    path: 'staff',
    title: 'Staff',
    staff: true,        // custom panel, not the generic CRUD table
    readOnly: true,
    fields: [],
    columns: [],
  },
  {
    path: 'desk-suggestions',
    title: 'Suggestions',
    deskLearning: true, // custom panel: what the desk learned from how it is used
    readOnly: true,
    fields: [],
    columns: [],
  },
  {
    path: 'audit',
    base: '/ai/v1',
    title: 'AI Audit',
    readOnly: true,
    fields: [],
    // the control-plane columns: what each turn COST and how it ended
    // (refused-budget / refused-disabled rows are the governor speaking)
    columns: ['createdAt', 'useCase', 'model', 'tokens', 'costMicros', 'outcome', 'action', 'prompt', 'response'],
  },
  {
    path: 'workforce',
    base: '/ai/v1',
    title: 'AI Workforce',
    workforce: true,    // custom panel: the digital-workforce scoreboard
    readOnly: true,
    fields: [],
    columns: [],
  },
  {
    path: 'reporting',
    title: 'Reporting',
    reporting: true,    // custom panel: governed sales/finance summary
    readOnly: true,
    fields: [],
    columns: [],
  },
  {
    path: 'integrations',
    title: 'Integrations',
    integrations: true, // custom panel: the provider catalog (platform seams)
    readOnly: true,
    fields: [],
    columns: [],
  },
);
