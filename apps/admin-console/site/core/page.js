/* The page frame: goal line, crumb, + New, the department's page row. */
'use strict';

/* A one-paragraph orientation under a tab's title: the tab's own intro, else the
 * page's GOAL — what a person on it is working towards (Ivan: say what the page is for). */
const PAGE_GOALS = {
  home: 'Goal: know in five seconds what needs you today.',
  productOffering: 'Goal: every offer on the shelf is right and on sale when it should be. Watch: drafts waiting, offers past their window.',
  productSpecification: 'Goal: the facts behind each offer (data, validity, pickers) are complete, so the shop, the network and the bill agree.',
  productOfferingPrice: 'Goal: one price per thing a customer pays for; discounts live in Rules, not here.',
  approvals: 'Goal: nothing launches without its decision and its readiness — and nothing waits longer than it must.',
  envelopes: 'Goal: rules that let routine offers launch without an approver; anything outside them waits on Approvals. Keep them tight enough that only the exceptions reach the desk.',
  productStock: 'Goal: what the shop sells is in stock; what is out of stock says so before checkout.',
  customerBill: 'Goal: every bill is right, on time, and paid. Watch: disputes open, bills overdue.',
  productOrder: 'Goal: every order reaches active without a hand touching it; the ones that stall are visible here first.',
  campaign: 'Goal: campaigns with a measurable lift. Watch: holdout on, consent respected, spend against plan.',
  journey: 'Goal: the right message at the right moment, provably better than silence.',
  article: 'Goal: every question staff and customers ask has an answer on the shelf, so Ask is the exception.',
  policyRule: 'Goal: business rules as data — pricing, eligibility, launch envelopes — readable by the people they affect.',
  appointment: 'Goal: installations booked into real capacity, never overbooked, never idle.',
  processFlow: 'Goal: see where an order or a launch is, and how long each step took against its allowance.',
  decisions: 'Goal: any choice the system made can be explained to a customer or an auditor in six sentences. Watch: choices that fell back to the default.',
  'learning-contracts': 'Goal: every kind of automatic decision runs under a written intent — what to optimise, what must never happen, what it may choose, how much it may do alone. Watch: paused rules.',
  'device-entitlements': 'Goal: every line\'s phone knows exactly what it may use — nothing more, nothing less. Watch: refusals in the request list — a SIM the network knows but the BSS has not bound is a provisioning gap.',
  scoringRule: 'Goal: the leads worth a call float to the top. Watch: a rule nobody remembers why — points without a reason drift the score.',
  routingRule: 'Goal: every scored lead lands with someone who will call it. Watch: leads below every band — they wait for nobody.',
  ontology: 'Goal: every business action the BSS can perform is written down once — meaning, conditions, who may, what follows — and that one definition is what the console, the SDK and the AI agents run against. Watch: an action whose conditions you cannot say in words.',
};
function renderIntro(resource) {
  let p = document.getElementById('tab-intro');
  const text = resource.intro || PAGE_GOALS[resource.path];
  if (!text) { if (p) p.hidden = true; return; }
  if (!p) {
    p = document.createElement('p'); p.id = 'tab-intro'; p.className = 'dim'; p.dataset.testid = 'tab-intro';
    p.style.cssText = 'margin:0 0 12px;font-size:13px;max-width:900px;line-height:1.45';
    document.querySelector('.panel-head')?.after(p);
  }
  p.hidden = false; p.textContent = text;
}

/* ---------------- Interaction hierarchy: department › page, list first, form on demand ---------------- */
function renderCrumb(resource) {
  const ws = WORKSPACES.find((w) => w.tabs.includes(resource.path));
  const c = el('crumb');
  if (c) c.textContent = ws ? `${ws.label} › ${resource.title}` : resource.title;
}

function newButtonFor(resource) {
  const b = el('new-button');
  if (!b) return;
  const creatable = !resource.readOnly && !resource.noCreate && !resource.copilot && !resource.approvals
    && !resource.envelopes && !resource.growthCopilot && !resource.audienceBuilder && !resource.pipelineBoard
    && !resource.socialListening && !resource.decisions && !resource.learningContracts && !resource.deviceEntitlements && !resource.ontology && (resource.fields || []).length > 0;
  b.hidden = !creatable;
  b.textContent = `+ New ${resource.singular || resource.title.replace(/s$/, '').toLowerCase()}`;
  b.onclick = () => {
    stopEditing();
    const ed = el('editor'); ed.hidden = false;
    openDrawer();
    setTimeout(() => ed.querySelector('input:not([type=checkbox]), select, textarea')?.focus(), 150);
  };
}



/* ---------------- Ivan's top row: the sibling pages of the current department ---------------- */
function renderPageRow(resource) {
  let row = document.getElementById('pagerow');
  const ws = WORKSPACES.find((w) => w.tabs.includes(resource.path));
  if (!row) {
    row = document.createElement('div'); row.id = 'pagerow'; row.className = 'pagerow'; row.dataset.testid = 'page-row';
    document.getElementById('crumb')?.after(row);
  }
  row.replaceChildren();
  if (!ws) { row.hidden = true; return; }
  const pages = ws.tabs.map((p) => visible.find((r) => r.path === p)).filter(Boolean);
  row.hidden = pages.length < 1;
  const pageButton = (r, short) => {
    const b = document.createElement('button'); b.type = 'button'; b.className = 'pagetab' + (r === resource ? ' on' : '');
    b.textContent = short || r.title; if (short) b.title = r.title; b.dataset.path = r.path;
    b.addEventListener('click', () => { active = r; offset = 0; listFilter = ''; listSortCol = null; stopEditing(); sessionStorage.setItem('bss.console.tab', r.path); renderTabs(); loadList(); });
    return b;
  };
  const grouped = Array.isArray(ws.groups) && ws.groups.length > 0;
  row.classList.toggle('grouped', grouped);
  if (!grouped) {
    for (const r of pages) row.append(pageButton(r));
    return;
  }
  // grouped: two short lines instead of one long one. Line 1 = the primaries
  // (Products · Pricing · Availability · Lifecycle · Tools), the active one
  // marked; line 2 = only the active primary's pages. A page the token cannot
  // see drops out, an emptied primary drops out with it; a page in `tabs` that
  // no primary claims still gets a seat under "More"; `quiet` pages (the
  // copilot) keep their tab stub but never sit in the row.
  // A `whole` primary IS one screen, and that screen carries its own areas —
  // Journal and Chart of accounts are two chips on the Accounting island, so a
  // second line here would be the same choice twice, one above the other. Its
  // pages keep their group membership (the right primary lights up), their tab
  // stub, their path and their role gate; they just get no seat in the row.
  const quiet = new Set(ws.quiet || []);
  const placed = new Set();
  const sets = ws.groups.map((g) => ({ g, pages: g.tabs.map((p) => pages.find((r) => r.path === p)).filter(Boolean) })).filter((x) => x.pages.length);
  sets.forEach((x) => x.pages.forEach((r) => placed.add(r.path)));
  const rest = pages.filter((r) => !placed.has(r.path) && !quiet.has(r.path));
  if (rest.length) sets.push({ g: { label: 'More' }, pages: rest });
  let current = sets.find((x) => x.pages.includes(resource)) || sets[0];
  const primaries = document.createElement('div'); primaries.className = 'primaries'; primaries.dataset.testid = 'primaries';
  for (const x of sets) {
    const b = document.createElement('button'); b.type = 'button'; b.className = 'primary-tab' + (x === current ? ' on' : ''); b.textContent = x.g.label; b.dataset.group = x.g.label;
    b.addEventListener('click', () => { const first = x.pages.includes(active) ? active : x.pages[0]; active = first; offset = 0; listFilter = ''; listSortCol = null; stopEditing(); sessionStorage.setItem('bss.console.tab', first.path); renderTabs(); loadList(); });
    primaries.append(b);
  }
  const sub = document.createElement('div'); sub.className = 'subnav'; sub.dataset.testid = 'subnav'; sub.dataset.group = current.g.label;
  // `short` renames a page in the row only — it was declared here and never
  // passed, so every `short:` in nav.js has been dead data since the row was
  // built. The rail, the crumb and the resource title keep the full name.
  if (!current.g.whole) for (const r of current.pages) sub.append(pageButton(r, (current.g.short || {})[r.path]));
  row.append(primaries);
  if (sub.childElementCount) row.append(sub);
}
