/* The desk's state: the active page, paging, desk learning, cell formatting, picklists. */
'use strict';

let active = RESOURCES[0];
let offset = 0;
// #200: every list tab gets search + sortable columns — one engine, all tabs.
let listFilter = '';

// ---- DESK LEARNING: what staff DO on this desk (a tab, a form, a field, an empty
// search, a rewritten draft) — never what they see, never a customer. Batched to
// insight; the tenant can switch it off (desk-learning) and the desk goes quiet.
const DESK = {
  queue: [], on: true, started: null, lastDraft: null,
  session: (() => { let v = sessionStorage.getItem('bss.desk.session'); if (!v) { v = Math.random().toString(36).slice(2, 12); sessionStorage.setItem('bss.desk.session', v); } return v; })(),
};
function desk(event, target, props) {
  if (!DESK.on) return;
  DESK.queue.push({ desk: 'console', event, target: target || null, props: props || null, session: DESK.session });
  if (DESK.queue.length >= 20) deskFlush();
}
async function deskFlush() {
  if (!DESK.on || !DESK.queue.length) return;
  const batch = DESK.queue.splice(0, 50);
  try {
    const r = await authFetch('/insight/v1/desk/event', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(batch), keepalive: true });
    if (r.status === 403 || r.status === 404) { DESK.on = false; return; }
    if (r.ok) { const j = await r.json().catch(() => null); if (j && j.enabled === false) DESK.on = false; }
  } catch { /* fail-soft: learning never breaks the desk */ }
}
setInterval(deskFlush, 5000);
window.addEventListener('pagehide', deskFlush);
// a cheap edit distance for "did the copilot draft survive": 1 - Jaccard over words
function deskEditRatio(a, b) {
  const w = (t) => new Set(String(t || '').toLowerCase().split(/\W+/).filter((x) => x.length > 2));
  const A = w(a), B = w(b); if (!A.size && !B.size) return 0;
  let inter = 0; for (const x of A) if (B.has(x)) inter++;
  return 1 - inter / (A.size + B.size - inter);
}
let listSortCol = null;
let listSortDir = 1;
let editingId = null;
let controls = {}; // field name -> {get, set, reset}

function fmtCell(value) {
  if (value == null) return '—';
  if (typeof value === 'boolean') return value ? 'yes' : '—';
  if (Array.isArray(value)) return value.map((v) => v.name || v.id).join(', ') || '—';
  if (typeof value === 'object') {
    if (value.value != null) return `${value.value} ${value.unit || ''}`.trim();
    if (value.amount != null) return `${value.amount} ${value.units || ''}`.trim();
    if (value.startDateTime) return `${value.startDateTime} → ${value.endDateTime || ''}`.trim();
    return value.name || value.id || '—';
  }
  if (/^\d{4}-\d{2}-\d{2}T/.test(String(value))) {
    return String(value).slice(0, 19).replace('T', ' ');
  }
  return String(value);
}

function refObject(field, option) {
  return {
    id: option.value,
    href: `${field.base || API_BASE}/${field.resource}/${option.value}`,
    name: option.dataset.name,
    '@referredType': field.referredType,
  };
}

async function loadPicklist(field) {
  // the catalog API caps a page at 100 — page through, so a picker never
  // silently misses the offerings past the first hundred
  const all = [];
  for (let off = 0; off < 1000; off += REF_PICKLIST_LIMIT) {
    const page = await authFetch(`${field.base || API_BASE}/${field.resource}?offset=${off}&limit=${REF_PICKLIST_LIMIT}`).then((r) => r.json());
    if (!Array.isArray(page)) return all.length ? all : page;
    all.push(...page);
    if (page.length < REF_PICKLIST_LIMIT) break;
  }
  return all;
}
