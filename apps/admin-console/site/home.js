/*
 * Home / My Work — the console's first screen.
 *
 * Five sections, in the order a person reads them: ATTENTION (what needs my
 * decision), MY WORK (things waiting on me), OPERATIONAL HEALTH (is the floor
 * running), RECENT (where I was), QUICK ACTIONS (where I usually go).
 *
 * Every card reads data the console ALREADY serves, and every call is gated by
 * the same role gate as the tab it opens: a card renders only when its tab is in
 * `visible` (computeVisible, TAB_ROLE). A refused or failing call never shows as
 * an error — the card is simply absent (fail soft). Zero states stay quiet: a
 * "No action needed" chip, never red.
 *
 * Loaded after app.js; uses its globals (RESOURCES, visible, active, authFetch,
 * renderTabs, loadList, stopEditing, API_BASE, BILLING_BASE …).
 */
'use strict';

const HOME_STYLE = `
.home { display: grid; gap: 22px; margin-top: 4px; }
.home[hidden] { display: none; }
.home h2 { font-size: 12px; text-transform: uppercase; letter-spacing: .1em; color: var(--dim); margin: 0 0 10px; font-weight: 600; }
.home-hello { font-size: 15px; color: var(--ink); margin: 0 0 2px; }
.home-cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(250px, 1fr)); gap: 12px; }
.home-card { display: grid; grid-template-rows: auto 1fr auto; gap: 6px; border: 1px solid var(--line); border-radius: 10px; padding: 14px 16px 12px; background: var(--card); }
.home-card .n { font-size: 26px; line-height: 1; font-weight: 600; color: var(--ink); }
.home-card.warn .n { color: #b45309; } .home-card.warn { border-color: #f59e0b; }
.home-card.bad .n { color: #b91c1c; } .home-card.bad { border-color: #dc2626; }
.home-card .what { font-size: 13.5px; color: var(--ink); line-height: 1.35; }
.home-card .why { font-size: 12.5px; color: var(--dim); line-height: 1.35; }
.home-card button { justify-self: start; margin-top: 4px; }
.home-quiet { display: inline-flex; align-items: center; gap: 8px; border: 1px solid var(--line); border-radius: 999px; padding: 6px 14px; font-size: 13px; color: var(--dim); background: var(--card); }
.home-quiet::before { content: ''; width: 8px; height: 8px; border-radius: 50%; background: #2e7d32; display: inline-block; }
.home-health { display: flex; flex-wrap: wrap; gap: 8px; }
.home-chips { display: flex; flex-wrap: wrap; gap: 8px; }
.home-chips .chip.off { opacity: .55; cursor: default; }
.home-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.home-actions button { border: 1px solid var(--line); background: var(--card); color: var(--ink); font: inherit; font-size: 13px; padding: 7px 13px; border-radius: 8px; cursor: pointer; }
.home-actions button:hover { border-color: var(--teal); color: var(--teal-text); }
.home-actions button.primary { background: var(--teal); color: #fff; border-color: var(--teal); }
.home .dim { color: var(--dim); font-size: 13px; }
`;

const HOME_RECENT_KEY = 'bss.console.recent';
const HOME_RECENT_MAX = 8;

function homeRecent() {
  try { const v = JSON.parse(localStorage.getItem(HOME_RECENT_KEY) || '[]'); return Array.isArray(v) ? v : []; } catch { return []; }
}
function homeRemember(path) {
  if (!path || path === 'home') return;
  try {
    const list = [path, ...homeRecent().filter((p) => p !== path)].slice(0, HOME_RECENT_MAX);
    localStorage.setItem(HOME_RECENT_KEY, JSON.stringify(list));
  } catch { /* storage may be blocked — Recent just stays empty */ }
}

// Recent = the last tabs opened. Every tab click, page-row click and department
// click ends in loadList(), so one wrapper sees them all — no app.js hook needed.
(function hookRecent() {
  if (typeof loadList !== 'function') return;
  const original = loadList;
  loadList = function homeLoadList() {
    try {
      if (active && active.path) homeRemember(active.path);
      document.getElementById('pagerow')?.style.removeProperty('display'); // Home hid it; every other page shows its row
    } catch { /* never breaks the desk */ }
    return original.apply(this, arguments);
  };
})();

function homeCanSee(path) { return visible.some((r) => r.path === path); }
function homeTitle(path) { const r = RESOURCES.find((x) => x.path === path); return r ? r.title : path; }

// The same click path every tab button takes (renderTabs), so the desk telemetry,
// the saved tab and the list state all behave as if the tab itself was clicked.
function homeOpen(path, then) {
  const r = visible.find((x) => x.path === path);
  if (!r) return;
  if (typeof desk === 'function') desk('tab.open', r.path);
  active = r; offset = 0; listFilter = ''; listSortCol = null; stopEditing();
  sessionStorage.setItem('bss.console.tab', r.path);
  renderTabs(); loadList();
  if (then) setTimeout(then, 120);
}

// A refusal (403), a missing service (404) or a network blip all read as "no data" —
// the card is skipped, never an error on the first screen.
const homeGet = (url) => authFetch(url).then((r) => (r.ok ? r.json() : null)).catch(() => null);
const homeDays = (iso) => Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / (24 * 3600 * 1000)));
const homePlural = (n, one, many) => `${n} ${n === 1 ? one : many}`;

function homePanel() {
  let panel = document.getElementById('home-panel');
  if (!panel) {
    if (!document.getElementById('home-style')) {
      const st = document.createElement('style'); st.id = 'home-style'; st.textContent = HOME_STYLE; document.head.append(st);
    }
    panel = document.createElement('div');
    panel.id = 'home-panel'; panel.className = 'home'; panel.dataset.testid = 'home';
    document.querySelector('.table-wrap').after(panel);
  }
  panel.hidden = false;
  return panel;
}

function homeCard(kind, tone, n, what, why, openPath, label) {
  const c = document.createElement('div');
  c.className = `home-card ${tone || ''}`; c.dataset.testid = 'home-card'; c.dataset.kind = kind;
  const num = document.createElement('div'); num.className = 'n'; num.textContent = String(n);
  const w = document.createElement('div'); w.className = 'what'; w.textContent = what;
  c.append(num, w);
  if (why) { const y = document.createElement('div'); y.className = 'why'; y.textContent = why; c.append(y); }
  if (openPath && homeCanSee(openPath)) {
    const b = document.createElement('button'); b.type = 'button'; b.className = 'ghost small';
    b.textContent = label || `Open ${homeTitle(openPath)}`; b.dataset.testid = 'home-open'; b.dataset.open = openPath;
    b.addEventListener('click', () => homeOpen(openPath));
    c.append(b);
  }
  return c;
}

function homeSection(title, testid) {
  const s = document.createElement('section'); s.dataset.testid = testid;
  const h = document.createElement('h2'); h.textContent = title; s.append(h);
  return s;
}

function homeQuiet(text) {
  const q = document.createElement('span'); q.className = 'home-quiet'; q.dataset.testid = 'home-quiet'; q.textContent = text; return q;
}

/* ---- the readers: each returns {attention:[cards], work:[cards], health:[chips]} or null ---- */
const HOME_READERS = [
  // Catalog: drafts and offers past their window — the product desk's attention (gate: the Product Offerings tab)
  async function catalog() {
    if (!homeCanSee('productOffering')) return null;
    const offers = await homeGet(`${API_BASE}/productOffering?limit=100`);
    if (!Array.isArray(offers)) return null;
    const now = Date.now();
    const drafts = offers.filter((o) => ['In study', 'In design', 'In test'].includes(o.lifecycleStatus));
    const past = offers.filter((o) => ['Active', 'Launched'].includes(o.lifecycleStatus) && o.validFor?.endDateTime && new Date(o.validFor.endDateTime).getTime() < now);
    const out = { attention: [], work: [], health: [] };
    if (drafts.length) out.attention.push(homeCard('drafts', 'warn', drafts.length,
      `${homePlural(drafts.length, 'offer is', 'offers are')} still in draft`,
      'In study, in design or in test — finish them or retire them so the shelf stays honest.', 'productOffering'));
    if (past.length) out.attention.push(homeCard('past-window', 'bad', past.length,
      `${homePlural(past.length, 'offer is', 'offers are')} on sale past ${past.length === 1 ? 'its' : 'their'} window`,
      'Still active after the date they were meant to leave the shelf.', 'productOffering'));
    out.health.push({ label: 'offers on the shelf', value: offers.length - drafts.length });
    return out;
  },
  // Launch decisions: the governance queue (gate: the Approvals desk)
  async function approvals() {
    if (!homeCanSee('approvals')) return null;
    const queue = await homeGet(`${API_BASE}/governance/queue`);
    if (!Array.isArray(queue)) return null;
    const waiting = queue.filter((q) => q.governanceState === 'requested');
    const held = queue.filter((q) => q.governanceState === 'held');
    const oldest = waiting.length ? Math.max(...waiting.map((q) => homeDays(q.requestedAt || q.lastUpdate))) : 0;
    const out = { attention: [], work: [], health: [] };
    if (waiting.length) out.attention.push(homeCard('approvals', oldest > 3 ? 'bad' : 'warn', waiting.length,
      `${homePlural(waiting.length, 'launch waits', 'launches wait')} for a decision`,
      oldest > 3 ? `The oldest has waited ${oldest} days.` : 'Approve, hold or send back — nothing launches without its decision.', 'approvals', 'Decide'));
    if (held.length) out.work.push(homeCard('held', '', held.length,
      `${homePlural(held.length, 'launch is', 'launches are')} on hold`, 'Waiting on something you asked for.', 'approvals'));
    return out;
  },
  // Money: unpaid and overdue bills (gate: the Customer Bills tab)
  async function money() {
    if (!homeCanSee('customerBill')) return null;
    const bills = await homeGet(`${BILLING_BASE}/customerBill?limit=100`);
    if (!Array.isArray(bills)) return null;
    // the bill says what is true about itself; this card used to test `paymentDueDate`, which no bill carries
    const overdue = bills.filter((b) => (b.billSituation || {}).value === 'overdue');
    const out = { attention: [], work: [], health: [] };
    if (overdue.length) out.attention.push(homeCard('overdue', 'bad', overdue.length,
      `${homePlural(overdue.length, 'bill is', 'bills are')} overdue`,
      'Past the due date with no arrangement — the collections ladder starts here.', 'customerBill'));
    const open = bills.filter((b) => !['paid', 'writtenOff', 'issued'].includes((b.billSituation || {}).value)).length;
    out.health.push({ label: 'bills open', value: open, tone: open ? 'warn' : 'ok' });
    return out;
  },
  // Orders: in flight and stalled (gate: the Orders tab)
  async function orders() {
    if (!homeCanSee('productOrder')) return null;
    const orders = await homeGet(`${ORDERING_BASE}/productOrder?limit=100`);
    if (!Array.isArray(orders)) return null;
    const open = orders.filter((o) => !/completed|cancelled|closed|rejected/i.test(o.state || ''));
    const stale = open.filter((o) => homeDays(o.orderDate || o.creationDate || o.lastUpdate) > 2);
    const out = { attention: [], work: [], health: [] };
    if (stale.length) out.attention.push(homeCard('stale-orders', 'bad', stale.length,
      `${homePlural(stale.length, 'order has', 'orders have')} been open for more than two days`,
      'An order that stalls is a customer waiting — find the step it stuck on.', 'productOrder'));
    out.health.push({ label: 'orders in flight', value: open.length });
    return out;
  },
  // Journeys live (gate: the Journeys tab) — health only, nothing here needs a decision
  async function journeys() {
    if (!homeCanSee('journey')) return null;
    const js = await homeGet(`${CAMPAIGN_BASE}/journey?limit=100`);
    if (!Array.isArray(js)) return null;
    const live = js.filter((j) => /active|live|running/i.test(j.state || j.status || ''));
    return { attention: [], work: [], health: [{ label: 'journeys live', value: live.length }] };
  },
  // The AI workforce: tasks waiting for a human, and whether the crew is keeping up (gate: the AI Workforce tab)
  async function workforce() {
    if (!homeCanSee('workforce')) return null;
    const [kpis, pending] = await Promise.all([homeGet('/ai/v1/workforce/kpis'), homeGet('/ai/v1/workforce/approvals?status=pending')]);
    if (!kpis && !Array.isArray(pending)) return null;
    const out = { attention: [], work: [], health: [] };
    if (Array.isArray(pending) && pending.length) out.work.push(homeCard('workforce-approvals', 'warn', pending.length,
      `${homePlural(pending.length, 'task from the AI workforce waits', 'tasks from the AI workforce wait')} for your approval`,
      'The crew stopped where the contract says a person decides.', 'workforce', 'Review'));
    if (kpis) {
      const staffing = kpis.staffing || {};
      if (kpis.workingNow != null) out.health.push({ label: 'AI workers busy', value: kpis.workingNow });
      if (staffing.backlogDepth != null) out.health.push({ label: staffing.surge ? 'AI backlog — surge' : 'AI backlog', value: staffing.backlogDepth, tone: staffing.surge ? 'warn' : 'ok' });
    }
    return out;
  },
  // Desk suggestions: what the desk noticed about how it is used (gate: the Desk suggestions tab)
  async function suggestions() {
    if (!homeCanSee('desk-suggestions')) return null;
    const [sugg, friction] = await Promise.all([homeGet('/insight/v1/desk/suggestions'), homeGet('/insight/v1/desk/friction?days=7')]);
    if (!Array.isArray(sugg)) return null;
    if (friction && friction.enabled === false) return null; // the tenant switched desk learning off — nothing to say
    const live = sugg.filter((x) => !x.quiet);
    const out = { attention: [], work: [], health: [] };
    if (live.length) out.work.push(homeCard('suggestions', '', live.length,
      `${homePlural(live.length, 'suggestion', 'suggestions')} about how this desk is used`,
      'Patterns from the last week — presets to save, forms people abandon.', 'desk-suggestions'));
    return out;
  },
];

function homeQuickActions() {
  const acts = [];
  if (homeCanSee('productOffering')) acts.push({ id: 'new-offering', label: '+ New product offering', primary: true,
    run: () => homeOpen('productOffering', () => document.getElementById('new-button')?.click()) });
  if (homeCanSee('copilot')) acts.push({ id: 'copilot', label: 'Describe a product to the copilot', run: () => homeOpen('copilot') });
  if (homeCanSee('growthCopilot')) acts.push({ id: 'growth-copilot', label: 'Describe an outreach', run: () => homeOpen('growthCopilot') });
  if (homeCanSee('approvals')) acts.push({ id: 'approvals', label: 'Approvals', run: () => homeOpen('approvals') });
  return acts;
}

async function renderHome() {
  const current = active;
  const panel = homePanel();
  const crumb = document.getElementById('crumb'); if (crumb) crumb.textContent = 'Home';
  // a one-page department needs no page row (.pagerow is display:flex, so `hidden` alone would not take)
  const pagerow = document.getElementById('pagerow'); if (pagerow) pagerow.style.display = 'none';

  const claims = typeof tokenClaims === 'function' ? tokenClaims() : {};
  const who = claims.given_name || claims.name || claims.preferred_username || '';
  const hour = new Date().getHours();
  const greet = hour < 5 ? 'Still up' : hour < 12 ? 'Good morning' : hour < 18 ? 'Good afternoon' : 'Good evening';

  const hello = document.createElement('p'); hello.className = 'home-hello'; hello.dataset.testid = 'home-hello';
  hello.textContent = who ? `${greet}, ${who}.` : `${greet}.`;

  const attention = homeSection('Attention — what needs your decision', 'home-attention');
  const work = homeSection('My work', 'home-work');
  const health = homeSection('Operational health', 'home-health');
  const recent = homeSection('Recent', 'home-recent');
  const quick = homeSection('Quick actions', 'home-quick');

  // sections that need no data render at once; the cards land when the reads return
  const wait = document.createElement('p'); wait.className = 'dim'; wait.textContent = 'Looking at what needs you…';
  attention.append(wait);
  work.append(Object.assign(document.createElement('p'), { className: 'dim', textContent: '…' }));
  health.append(Object.assign(document.createElement('p'), { className: 'dim', textContent: '…' }));

  const chips = document.createElement('div'); chips.className = 'home-chips';
  const paths = homeRecent().filter(homeCanSee);
  if (!paths.length) chips.append(Object.assign(document.createElement('span'), { className: 'dim', textContent: 'Pages you open will be listed here.' }));
  for (const p of paths) {
    const b = document.createElement('button'); b.type = 'button'; b.className = 'chip'; b.dataset.testid = 'home-recent-chip'; b.dataset.open = p;
    b.textContent = homeTitle(p); b.addEventListener('click', () => homeOpen(p)); chips.append(b);
  }
  recent.append(chips);

  const acts = document.createElement('div'); acts.className = 'home-actions';
  for (const a of homeQuickActions()) {
    const b = document.createElement('button'); b.type = 'button'; b.className = a.primary ? 'primary' : ''; b.dataset.testid = 'home-quick-action'; b.dataset.action = a.id;
    b.textContent = a.label; b.addEventListener('click', a.run); acts.append(b);
  }
  if (!acts.children.length) acts.append(Object.assign(document.createElement('span'), { className: 'dim', textContent: 'Your desk has no shortcuts yet.' }));
  quick.append(acts);

  panel.replaceChildren(hello, attention, work, health, recent, quick);

  const results = await Promise.all(HOME_READERS.map((fn) => fn().catch(() => null)));
  if (active !== current) return; // the person moved on while we read
  const cardsA = results.flatMap((r) => (r ? r.attention : []));
  const cardsW = results.flatMap((r) => (r ? r.work : []));
  const chipsH = results.flatMap((r) => (r ? r.health : []));

  const grid = (cards) => { const g = document.createElement('div'); g.className = 'home-cards'; g.append(...cards); return g; };
  attention.replaceChildren(attention.firstElementChild, cardsA.length ? grid(cardsA) : homeQuiet('No action needed'));
  work.replaceChildren(work.firstElementChild, cardsW.length ? grid(cardsW) : homeQuiet('Nothing is waiting on you'));
  const hrow = document.createElement('div'); hrow.className = 'home-health'; hrow.dataset.testid = 'home-health-row';
  for (const k of chipsH) {
    const c = document.createElement('span'); c.className = `kpi ${k.tone || 'ok'}`; c.dataset.testid = 'home-health-chip';
    const v = document.createElement('strong'); v.textContent = String(k.value); c.append(v, document.createTextNode(' ' + k.label)); hrow.append(c);
  }
  if (!chipsH.length) hrow.append(homeQuiet('Nothing to watch from this desk'));
  health.replaceChildren(health.firstElementChild, hrow);
  panel.dataset.ready = '1';
}
