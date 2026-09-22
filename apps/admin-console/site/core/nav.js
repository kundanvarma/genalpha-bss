/* Who sees which page: role gates, the door gate, departments, the rail. */
'use strict';

// Role-scoped tabs: the console only shows the areas this operator's token can
// actually use (the APIs enforce the same roles server-side — hiding a tab is
// ergonomics, the 403 underneath is the security).
// Gates use STAFF-grade roles: the default 'customer' composite carries baseline
// read/write (customers pay bills, book slots), so back-office visibility keys
// on roles customers never hold.
const TAB_ROLE = {
  // 'home' is deliberately ABSENT: a tab without a gate shows to every STAFF token
  // (the door gate isStaff() below still keeps customers out). Any-of arrays here drop
  // customer-baseline roles (catalog:read, billing:read, ordering:write …), so listing
  // them would not widen the gate — the absent entry is the "every staff member" mechanism.
  productOffering: 'catalog:write',
  productSpecification: 'catalog:write',
  productOfferingPrice: 'catalog:write',
  productStock: 'stock:read',
  customerBill: 'billing:admin',
  journalEntry: 'billing:admin',
  accountMapping: 'billing:admin',
  dispute: 'billing:admin',
  // the TMF701 API is party-scoped (customers read their own flows);
  // the TAB is the ops desk's window — keyed on ops-floor roles, since
  // the baseline customer composite legitimately holds ordering:write
  processFlow: ['workforce:use', 'service:write'],
  runbook: 'ai:admin',
  serviceableArea: 'qualification:write',
  wholesaleOwners: 'wholesale:admin',
  accessProduct: 'wholesale:admin',
  wholesaleSettlement: 'wholesale:admin',
  mobileWholesale: 'wholesale:admin',
  mobileWholesaleProvider: 'wholesale:admin',
  partyRiskAssessment: 'risk:assess',
  productOrder: ['ordering:write', 'service:write'],
  appointment: 'appointment:admin',
  campaign: 'campaign:read',
  journey: 'campaign:read',
  policyRule: 'policy:read',
  article: 'knowledge:write',
  settings: 'campaign:read',
  dunning: 'billing:admin',
  billFormatProfile: 'billing:admin',
  findings: 'catalog:write',
  operator: 'roles:admin',
  shadowDrift: 'billing:admin',
  myOperator: 'campaign:write',
  'simulate/priceChange': 'catalog:write',
  'simulate/prospect': 'catalog:write',
  billDistribution: 'billing:admin',
  'remittance/unapplied': 'billing:admin',
  salesLead: 'quote:read',
  salesOpportunity: 'quote:read',
  salesPipeline: 'quote:read',
  configRule: 'quote:read',
  scoringRule: 'quote:read',
  routingRule: 'quote:read',
  guidedQuestion: 'quote:read',
  guidedRecommendation: 'quote:read',
  quota: 'quote:read',
  pricingRule: 'quote:read',
  audience: 'insight:read',
  audienceBuilder: 'insight:read',
  socialListening: 'insight:read',
  socialCare: 'insight:read',
  // VoC is the readout over the insight signal store — same gate as its
  // sibling sources; ungated it leaked the Marketing desk to every staff token.
  voc: 'insight:read',
  attribution: 'campaign:read',
  landing: 'insight:read',
  profile: 'insight:read',
  numberPortingOrder: 'porting:write',
  copilot: 'catalog:write',
  growthCopilot: 'campaign:write',
  staff: 'roles:admin',
  // the audit trail rides with AI POWER on a desk of its own (product's
  // copilot, ops' workforce, the governor) — marketing-staff gained ai:use
  // for its copilot (e7e855eb), and gating the ledger on bare ai:use would
  // drag the whole AI & Automation desk onto the marketing persona's screen
  audit: ['catalog:write', 'workforce:use', 'ai:admin'],
  workforce: ['workforce:use', 'ai:admin'],
  reporting: ['billing:admin', 'billing:read'],
  integrations: ['roles:admin', 'document:write'],
  // DEPARTMENT WALLS (last wins over the older per-tab gates above):
  // Marketing is marketing's desk; Wholesale the wholesale desk; the platform
  // and AI governance rooms are the admin's — a product manager sees the
  // catalog, their help and their suggestions, nothing else
  growthCopilot: 'campaign:write', landing: 'campaign:read', audienceBuilder: 'campaign:read', audience: 'campaign:read',
  attribution: 'campaign:read', socialListening: 'campaign:read', socialCare: ['campaign:read', 'ticket:write'], voc: 'campaign:read',
  coverageMap: 'wholesale:admin', serviceSpecification: 'wholesale:admin',
  // the AI audit trail rides along with AI power, by design (auditability)
  audit: ['catalog:write', 'ai:admin'], workforce: ['workforce:use', 'ai:admin'], profile: 'ai:admin', aiflows: 'ai:admin',
  policyRule: ['catalog:write', 'roles:admin'], integrations: 'roles:admin', staff: 'roles:admin',
  approvals: 'catalog:write', envelopes: 'catalog:write',
  'desk-suggestions': ['catalog:write', 'ai:admin'], // AI & Automation is the product owner's and the admin's room (suite #87)
  // the decision log and the contracts are the product owner's and the admin's room, like the suggestions (gro, marketing, sees no AI desk)
  decisions: ['catalog:write', 'ai:admin'], 'learning-contracts': ['catalog:write', 'ai:admin'],
  // the entitlement server is a network-ops surface: who may read what the phones were told
  'device-entitlements': ['entitlement:read'],
  // the ontology is every staff member's to read; an action's own permissions decide who may run it
  ontology: ['catalog:read', 'ordering:write', 'ai:use', 'insight:read'],
};
let visible = RESOURCES;
// The baseline SHOP-CUSTOMER composite — EXACTLY what every self-registered
// shopper's token carries. Staff hold at least one authority BEYOND this set.
// Per-tab role gates cannot tell staff from customers on their own, because a
// customer legitimately holds billing:read (their bills), ordering:write (their
// orders), service:read, paymentmethod:* … — so the console gates the DOOR on
// "holds a staff authority", not on any single tab's role. This is the security
// boundary; the APIs are party-scoped underneath regardless.
const CUSTOMER_BASELINE = new Set([
  'catalog:read', 'ordering:read', 'ordering:write', 'inventory:read', 'party:read',
  'party:write', 'payment:read', 'payment:write', 'billing:read', 'billing:write',
  'appointment:read', 'appointment:write', 'ticket:read', 'ticket:write', 'interaction:read',
  'communication:read', 'communication:write', 'usage:read', 'agreement:read',
  'recommendation:read', 'paymentmethod:read', 'paymentmethod:write', 'service:read',
  'knowledge:read',
  // marker roles a shopper's token also carries — none of these make them staff
  'customer', 'default-roles-bss', 'default-roles-nova', 'offline_access', 'uma_authorization',
]);
function isStaff() {
  const roles = (tokenClaims().realm_access || {}).roles || [];
  return roles.some((r) => !CUSTOMER_BASELINE.has(r));
}

function computeVisible() {
  const roles = (tokenClaims().realm_access || {}).roles || [];
  // a tab shows only when the token holds a STAFF authority for it — a role in
  // the tab's gate that is NOT part of the customer baseline (defence in depth
  // behind the door gate below).
  visible = isStaff() ? RESOURCES.filter((r) => {
    const need = TAB_ROLE[r.path];
    if (!need) return true;
    return (Array.isArray(need) ? need : [need])
      .some((x) => roles.includes(x) && !CUSTOMER_BASELINE.has(x));
  }) : [];
}

// WORKSPACES: the console as departments — a group renders only when the
// token can see at least one of its tabs, so each persona gets their own
// desk and nothing else. Membership is data; the .tab DOM contract the
// suites click by text is untouched.
const WORKSPACES = [
  { label: 'Home', tabs: ['home'] },
  // A department may carry `groups`: sub-headings inside the page row
  // (CATALOG · PRICING · …) so eleven peer pages read as five jobs. `tabs`
  // stays the flat union — every `ws.tabs.includes(path)` lookup and the
  // suites' `.tab` contract are untouched. `short` renames a page ONLY in
  // the row (the resource title, crumb and rail keep their full name).
  { label: 'Catalog & Pricing', tabs: ['productOffering', 'productSpecification',
    'productOfferingPrice', 'productStock', 'serviceableArea', 'findings', 'copilot', 'approvals', 'envelopes',
    // both simulators live where their gate lives: /ai/v1/simulate/** is
    // catalog:write server-side, so a Sales placement leaked a one-tab Sales
    // desk to product staff while real sellers would only have met the 403
    'simulate/priceChange', 'simulate/prospect'],
    // Ivan's navigation paper (21 Sep): a few stable primaries, the pages of
    // the active one on a second line — never all destinations at once. The
    // copilot is not a destination here: "Ask Copilot" sits on every catalog
    // page (its tab stub stays for the palette and the suites).
    groups: [
      { label: 'Products', tabs: ['productOffering', 'productSpecification'] },
      { label: 'Pricing', tabs: ['productOfferingPrice'] },
      { label: 'Availability', tabs: ['productStock', 'serviceableArea'] },
      { label: 'Lifecycle', tabs: ['approvals', 'envelopes'] },
      { label: 'Tools', tabs: ['findings', 'simulate/priceChange', 'simulate/prospect'] },
    ],
    quiet: ['copilot'] },
  { label: 'Wholesale', tabs: ['wholesaleOwners', 'accessProduct', 'serviceSpecification',
    'coverageMap', 'wholesaleSettlement', 'mobileWholesale', 'mobileWholesaleProvider'] },
  { label: 'Money', tabs: ['customerBill', 'journalEntry', 'accountMapping', 'dispute',
    'dunning', 'billFormatProfile', 'billDistribution', 'remittance/unapplied', 'partyRiskAssessment',
    'shadowDrift'] },
  { label: 'Reporting', tabs: ['reporting'] },
  { label: 'Care & Ops', tabs: ['productOrder', 'processFlow', 'appointment', 'numberPortingOrder', 'device-entitlements', 'article'] },
  // "Growth" split by persona (the marketer, the seller, the sales-ops admin) —
  // one theme was three jobs. Role gates unchanged, so a narrow role lands on
  // just its own desk.
  { label: 'Marketing', tabs: ['growthCopilot', 'campaign', 'journey', 'landing',
    'audienceBuilder', 'audience', 'attribution', 'socialListening', 'socialCare', 'voc', 'settings', 'myOperator'],
    groups: [
      { label: 'Campaigns', tabs: ['growthCopilot', 'campaign', 'journey'], short: { growthCopilot: 'Copilot' } },
      { label: 'Audiences', tabs: ['audienceBuilder', 'audience'], short: { audienceBuilder: 'Builder', audience: 'Saved' } },
      { label: 'Content', tabs: ['landing'] },
      { label: 'Insights', tabs: ['attribution', 'socialListening', 'voc'], short: { socialListening: 'Listening', voc: 'Voice of Customer' } },
      { label: 'Care', tabs: ['socialCare'], short: { socialCare: 'Social care' } },
      { label: 'Brand & guardrails', tabs: ['settings', 'myOperator'] },
    ] },
  { label: 'Sales', tabs: ['salesLead', 'salesPipeline', 'salesOpportunity', 'quota'] },
  { label: 'Sales setup', tabs: ['scoringRule', 'routingRule', 'configRule',
    'guidedQuestion', 'guidedRecommendation', 'pricingRule'] },
  { label: 'AI & Automation', tabs: ['desk-suggestions', 'decisions', 'learning-contracts', 'audit', 'runbook', 'workforce'],
    groups: [
      { label: 'Work', tabs: ['desk-suggestions', 'workforce', 'runbook'], short: { workforce: 'Workforce' } },
      { label: 'Decisions', tabs: ['decisions', 'learning-contracts'], short: { 'learning-contracts': 'Contracts' } },
      { label: 'Audit', tabs: ['audit'], short: { audit: 'Audit trail' } },
    ] },
  // 'profile' (Visitor consent) is a consent/accountability surface, not a growth
  // lever — it lives with governance, and Growth links to it for debugging.
  { label: 'Privacy & governance', tabs: ['profile', 'aiflows'] },
  { label: 'Platform', tabs: ['operator', 'staff', 'policyRule', 'integrations'] },
  // the ontology is every staff member's reading room, so it is its own desk: a persona
  // that may read it must not thereby see the admin's Platform desk (suite console_workspaces)
  { label: 'What the BSS can do', tabs: ['ontology'] },
];

function renderTabs() {
  const tabButton = (r) => {
    const b = document.createElement('button');
    b.textContent = r.title;
    b.className = r === active ? 'tab on' : 'tab';
    b.addEventListener('click', () => { if (DESK.started && !DESK.started.submitted) desk('form.abandon', DESK.started.form); DESK.started = null; desk('tab.open', r.path);
      active = r; offset = 0; listFilter = ''; listSortCol = null; stopEditing();
      sessionStorage.setItem('bss.console.tab', r.path); renderTabs(); loadList(); });
    return b;
  };
  // The rail shows DEPARTMENTS. A department's pages show once, in the row
  // above the content (renderPageRow). The page tabs still live here, one per
  // page, as 1px silent stubs: that is the `.tab` contract thirty browser
  // suites click by text, and a click on a stub opens the page like any tab.
  const placed = new Set();
  const nodes = [];
  const deptBox = (ws, rows) => {
    const group = document.createElement('div');
    group.className = 'tabgroup' + (rows.includes(active) ? ' on' : '');
    if (ws) {
      const label = document.createElement('button');
      label.type = 'button'; label.className = 'tabgroup-label dept'; label.textContent = ws.label;
      label.dataset.testid = 'dept';
      label.addEventListener('click', () => { const first = rows.includes(active) ? active : rows[0]; if (first !== active) { active = first; offset = 0; listFilter = ''; listSortCol = null; stopEditing(); sessionStorage.setItem('bss.console.tab', first.path); } renderTabs(); loadList(); });
      group.append(label);
    }
    const row = document.createElement('div');
    row.className = 'tabgroup-row';
    rows.forEach((r, i) => { const b = tabButton(r); b.classList.add('offdept'); b.style.left = `${2 + i * 2}px`; b.setAttribute('aria-hidden', 'true'); b.tabIndex = -1; row.append(b); });
    group.append(row);
    return group;
  };
  for (const ws of WORKSPACES) {
    const rows = ws.tabs
      .map((path) => visible.find((r) => r.path === path))
      .filter(Boolean);
    rows.forEach((r) => placed.add(r));
    if (!rows.length) continue;
    nodes.push(deptBox(ws, rows));
  }
  const stray = visible.filter((r) => !placed.has(r));
  if (stray.length) nodes.push(deptBox({ label: 'More' }, stray));
  el('tabs').replaceChildren(...nodes);
}
