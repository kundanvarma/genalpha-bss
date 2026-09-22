/* Page KPIs and the rows behind them. */
'use strict';

/* ---------------- The page's KPIs, live, beside its goal — and the rows that make them ----------------
 * Each entry answers "how are we doing against this page's goal" from the APIs, and
 * names the row ids that need attention so the table can mark them. */
const DAY = 24 * 3600 * 1000;
const ageDays = (iso) => Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / DAY));
const PAGE_KPIS = {
  // the advisor's page speaks the advisor's language (recommendations, not drafts);
  // its rows are what the list just loaded — the advisor computes on request, so
  // a second call would double the wait
  findings: async () => {
    const rows = Array.isArray(lastListItems) ? lastListItems : [];
    const adoptable = rows.filter((r) => r.proposal);
    return [
      { label: 'recommendations', value: rows.length, tone: 'ok' },
      { label: 'ready to adopt as a draft', value: adoptable.length, tone: adoptable.length ? 'warn' : 'ok', ids: adoptable.map((r) => r.id) },
    ];
  },
  productOffering: async () => {
    const [offers, queue] = await Promise.all([
      authFetch(`${API_BASE}/productOffering?limit=100`).then((r) => (r.ok ? r.json() : [])).catch(() => []),
      authFetch(`${API_BASE}/governance/queue`).then((r) => (r.ok ? r.json() : [])).catch(() => []),
    ]);
    const now = Date.now();
    const drafts = offers.filter((o) => ['In study', 'In design', 'In test'].includes(o.lifecycleStatus));
    const past = offers.filter((o) => ['Active', 'Launched'].includes(o.lifecycleStatus) && o.validFor?.endDateTime && new Date(o.validFor.endDateTime).getTime() < now);
    const waiting = queue.filter((q) => ['requested', 'approved', 'held'].includes(q.governanceState));
    return [
      { label: 'drafts', value: drafts.length, tone: drafts.length ? 'warn' : 'ok', ids: drafts.map((o) => o.id) },
      { label: 'waiting on a decision', value: waiting.length, tone: waiting.length ? 'warn' : 'ok', ids: waiting.map((q) => q.id) },
      { label: 'past their window', value: past.length, tone: past.length ? 'bad' : 'ok', ids: past.map((o) => o.id) },
    ];
  },
  approvals: async () => {
    const queue = await authFetch(`${API_BASE}/governance/queue`).then((r) => (r.ok ? r.json() : [])).catch(() => []);
    const waiting = queue.filter((q) => q.governanceState === 'requested');
    const held = queue.filter((q) => q.governanceState === 'held');
    const oldest = waiting.length ? Math.max(...waiting.map((q) => ageDays(q.requestedAt || q.lastUpdate))) : 0;
    return [
      { label: 'waiting for approval', value: waiting.length, tone: waiting.length ? 'warn' : 'ok' },
      { label: 'oldest wait (days)', value: oldest, tone: oldest > 3 ? 'bad' : 'ok' },
      { label: 'on hold', value: held.length, tone: held.length ? 'warn' : 'ok' },
    ];
  },
  envelopes: async () => {
    const rules = await authFetch(`${POLICY_BASE}/policyRule?limit=200`).then((r) => (r.ok ? r.json() : [])).catch(() => []);
    const envs = rules.filter((r) => r.domain === 'launch');
    return [{ label: 'pre-approved launch rules', value: envs.length, tone: 'ok' }, { label: 'switched off', value: envs.filter((e) => e.enabled === false).length, tone: 'ok' }];
  },
  customerBill: async () => {
    const bills = await authFetch(`${active.base}/customerBill?limit=100`).then((r) => (r.ok ? r.json() : [])).catch(() => []);
    const now = Date.now();
    const unpaid = bills.filter((b) => !/settled|paid|closed/i.test(b.state || ''));
    const overdue = unpaid.filter((b) => b.paymentDueDate && new Date(b.paymentDueDate).getTime() < now);
    return [
      { label: 'unpaid', value: unpaid.length, tone: unpaid.length ? 'warn' : 'ok', ids: unpaid.map((b) => b.id) },
      { label: 'overdue', value: overdue.length, tone: overdue.length ? 'bad' : 'ok', ids: overdue.map((b) => b.id) },
    ];
  },
  productOrder: async () => {
    const orders = await authFetch(`${active.base}/productOrder?limit=100`).then((r) => (r.ok ? r.json() : [])).catch(() => []);
    const open = orders.filter((o) => !/completed|cancelled|closed|rejected/i.test(o.state || ''));
    const stale = open.filter((o) => ageDays(o.orderDate || o.creationDate || o.lastUpdate) > 2);
    return [
      { label: 'in flight', value: open.length, tone: 'ok' },
      { label: 'older than 2 days', value: stale.length, tone: stale.length ? 'bad' : 'ok', ids: stale.map((o) => o.id) },
    ];
  },
  journey: async () => {
    const js = await authFetch(`${active.base}/journey?limit=100`).then((r) => (r.ok ? r.json() : [])).catch(() => []);
    const live = js.filter((j) => /active|live|running/i.test(j.state || j.status || ''));
    const noHoldout = js.filter((j) => !(Number(j.holdoutPercent) > 0));
    return [
      { label: 'live', value: live.length, tone: 'ok' },
      { label: 'without a holdout', value: noHoldout.length, tone: noHoldout.length ? 'warn' : 'ok', ids: noHoldout.map((j) => j.id) },
    ];
  },
  article: async () => {
    const [arts, gaps] = await Promise.all([
      authFetch(`${KNOWLEDGE_BASE}/article?limit=500`).then((r) => (r.ok ? r.json() : [])).catch(() => []),
      authFetch('/ai/v1/knowledgeGaps').then((r) => (r.ok ? r.json() : [])).catch(() => []),
    ]);
    const drafts = arts.filter((a) => a.status !== 'published');
    return [
      { label: 'published', value: arts.length - drafts.length, tone: 'ok' },
      { label: 'drafts', value: drafts.length, tone: drafts.length ? 'warn' : 'ok', ids: drafts.map((a) => a.id) },
      { label: 'unanswered questions', value: gaps.length, tone: gaps.length ? 'warn' : 'ok' },
    ];
  },
  decisions: async () => {
    const s = await authFetch(`${DECISIONS_BASE}/summary`).then((r) => (r.ok ? r.json() : null)).catch(() => null);
    if (!s) return [];
    const fallbacks = (s.points || []).reduce((n, p) => n + (p.fallbacks || 0), 0);
    return [
      { label: 'decisions on the log', value: s.decisions, tone: 'ok' },
      { label: 'led to a purchase or an adoption', value: s.decisions ? `${Math.round(1000 * s.withOutcome / s.decisions) / 10} %` : '0 %', tone: 'ok' },
      { label: 'fell back to the default', value: fallbacks, tone: fallbacks ? 'warn' : 'ok' },
    ];
  },
  'learning-contracts': async () => {
    const rows = await authFetch(`${CAMPAIGN_BASE}/learningContract`).then((r) => (r.ok ? r.json() : [])).catch(() => []);
    const written = rows.filter((r) => r.contract && !r.contract.defaults).length;
    const paused = rows.filter((r) => r.contract && r.contract.enabled === false).length;
    return [
      { label: 'kinds of decision', value: rows.length, tone: 'ok' },
      { label: 'without a written contract', value: rows.length - written, tone: rows.length - written ? 'warn' : 'ok' },
      { label: 'paused', value: paused, tone: paused ? 'warn' : 'ok' },
    ];
  },
  'device-entitlements': async () => {
    const [subs, reqs] = await Promise.all([
      authFetch(`${ENTITLEMENT_BASE}/subscriber`).then((r) => (r.ok ? r.json() : [])).catch(() => []),
      loadEntitlementRequests(),
    ]);
    const today = reqs.filter((r) => isToday(r.createdAt));
    const phones = new Set(today.map((r) => r.terminalId).filter(Boolean)).size;
    const refusals = today.filter((r) => ecsRefused(r.outcome)).length;
    return [
      { label: 'lines bound', value: Array.isArray(subs) ? subs.length : 0, tone: 'ok' },
      { label: 'phones seen today', value: phones, tone: 'ok' },
      { label: 'refusals today', value: refusals, tone: refusals ? 'warn' : 'ok' },
    ];
  },
};
let rowFlagIds = new Set();
// a health chip clicked = the list narrowed to the rows it counted (null = no chip on)
let kpiFilterIds = null;
let lastListItems = [];
function applyRowFlags() {
  let shown = 0, rows = 0;
  document.querySelectorAll('#listing-body tr[data-id]').forEach((tr) => {
    tr.classList.toggle('flag', rowFlagIds.has(tr.dataset.id));
    tr.hidden = Boolean(kpiFilterIds) && !kpiFilterIds.has(tr.dataset.id);
    rows++; if (!tr.hidden) shown++;
  });
  // the chip counted the whole resource, the table shows one page: say so
  // instead of a blank table when none of them live on this page
  // (idempotent: the list observer re-runs this on every child change, so
  // only touch the DOM when the note's presence has to change)
  const need = Boolean(kpiFilterIds) && rows > 0 && shown === 0;
  const note = document.getElementById('kpi-filter-note');
  if (!need && note) note.remove();
  if (need && !note) {
    const tr = document.createElement('tr'); tr.id = 'kpi-filter-note';
    const td = document.createElement('td'); td.colSpan = (active.columns || []).length + 1; td.className = 'dim';
    td.textContent = 'None of these are on this page — turn the pager, or click the chip again to show everything.';
    tr.append(td); document.getElementById('listing-body')?.append(tr);
  }
}
async function renderKpis(resource) {
  let row = document.getElementById('kpis');
  const fn = PAGE_KPIS[resource.path];
  kpiFilterIds = null; // a new page starts unfiltered
  if (!fn) { if (row) row.hidden = true; rowFlagIds = new Set(); applyRowFlags(); return; }
  if (!row) {
    row = document.createElement('div'); row.id = 'kpis'; row.className = 'kpis'; row.dataset.testid = 'page-kpis';
    (document.getElementById('tab-intro') || document.querySelector('.panel-head'))?.after(row);
  }
  row.hidden = false; row.replaceChildren();
  const current = resource;
  let kpis = [];
  try { kpis = await fn(); } catch { kpis = []; }
  if (active !== current) return; // the user moved on while we counted
  row.replaceChildren(...kpis.map((k) => {
    const c = document.createElement('span'); c.className = `kpi ${k.tone || ''}`; c.dataset.testid = 'kpi';
    const v = document.createElement('strong'); v.textContent = String(k.value);
    c.append(v, document.createTextNode(' ' + k.label));
    // a chip that counted specific rows is also a filter: click narrows the
    // list to those rows, click again shows everything; static chips stay text
    const ids = (k.ids || []).map(String);
    if (ids.length) {
      c.classList.add('click'); c.setAttribute('role', 'button'); c.tabIndex = 0; c.setAttribute('aria-pressed', 'false');
      c.title = `Show only these ${ids.length}`;
      const toggle = () => {
        const on = !c.classList.contains('on');
        row.querySelectorAll('.kpi.on').forEach((o) => { o.classList.remove('on'); o.setAttribute('aria-pressed', 'false'); });
        kpiFilterIds = on ? new Set(ids) : null;
        c.classList.toggle('on', on); c.setAttribute('aria-pressed', String(on));
        applyRowFlags();
        desk('kpi.filter', current.path, { label: k.label, on });
      };
      c.addEventListener('click', toggle);
      c.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(); } });
    }
    return c;
  }));
  rowFlagIds = new Set(kpis.flatMap((k) => (k.ids || []).map(String)));
  applyRowFlags();
}
