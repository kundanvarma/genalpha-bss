/* CDP backfill — seed the trait store from EXISTING customers.
 *
 * The martech trait store is fed by the event bus going forward, but customers
 * created before the CDP existed (the demo personas, any pre-migration base) have
 * no traits — so no trait audience can reach them. This one-shot job reads the
 * operational source of truth (party individuals + product inventory) and writes
 * the traits through insight's admin ingest, so every existing customer becomes
 * reachable by who they are — no browsing, no personalization consent.
 *
 *   node ops/backfill_cdp.js            # backfill the default (bss/genalpha) tenant
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const PARTY = `${API}/tmf-api/party/v4/individual`;
const INVENTORY = `${API}/tmf-api/productInventory/v4/product`;
const INGEST = `${API}/insight/v1/traits/backfill`;
const PAGE = 100;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

async function token(ctx) {
  const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } });
  return (await res.json()).access_token;
}

async function pageAll(ctx, url, H, onItem, cap = 5000) {
  let offset = 0; let total = null; let seen = 0;
  while (seen < cap) {
    const res = await ctx.get(`${url}?offset=${offset}&limit=${PAGE}`, { headers: H });
    if (!res.ok()) break;
    const tc = Number(res.headers()['x-total-count']);
    if (Number.isFinite(tc)) total = tc;
    const arr = await res.json();
    const items = Array.isArray(arr) ? arr : [];
    if (!items.length) break;
    for (const it of items) { onItem(it); seen++; }
    offset += PAGE;
    if (total != null && offset >= total) break;
    if (items.length < PAGE) break;
  }
  return seen;
}

const emailOf = (ind) => {
  for (const m of ind.contactMedium || []) {
    const c = m && m.characteristic;
    if (c && c.emailAddress && String(m.mediumType || '').toLowerCase() === 'email') return c.emailAddress;
    if (c && c.emailAddress) return c.emailAddress;
  }
  return null;
};
const ownerOf = (prod) => {
  const rp = prod.relatedParty || [];
  const named = rp.find((r) => ['owner', 'customer'].includes(String(r.role || '').toLowerCase()));
  return (named || rp[0] || {}).id || null;
};

(async () => {
  const ctx = await request.newContext();
  const staff = await token(ctx);
  const H = { Authorization: 'Bearer ' + staff, 'Content-Type': 'application/json' };
  const traits = [];

  // 1. individuals -> email + region
  const inds = await pageAll(ctx, PARTY, H, (ind) => {
    if (!ind || !ind.id) return;
    const email = emailOf(ind);
    if (email) traits.push({ partyId: ind.id, key: 'email', value: email });
    if (ind.region) traits.push({ partyId: ind.id, key: 'region', value: String(ind.region) });
  });
  console.log(`read ${inds} individuals`);

  // 2. products -> product holdings (skip UUID-named / cancelled)
  const prods = await pageAll(ctx, INVENTORY, H, (p) => {
    if (!p || !p.name || UUID_RE.test(p.name)) return;
    if (String(p.status || '').toLowerCase() === 'cancelled') return;
    const owner = ownerOf(p);
    if (owner) traits.push({ partyId: owner, key: 'product', value: p.name, multi: true });
  });
  console.log(`read ${prods} products`);

  // 3. ingest in batches
  let written = 0;
  for (let i = 0; i < traits.length; i += 200) {
    const batch = traits.slice(i, i + 200);
    const res = await ctx.post(INGEST, { headers: H, data: { traits: batch } });
    if (!res.ok()) { console.error('ingest failed HTTP ' + res.status() + ': ' + (await res.text()).slice(0, 200)); process.exit(1); }
    written += (await res.json()).written || 0;
  }
  const keys = traits.reduce((m, t) => ((m[t.key] = (m[t.key] || 0) + 1), m), {});
  console.log(`\nBACKFILL DONE — wrote ${written} traits across ${Object.keys(keys).length} keys: `
    + Object.entries(keys).map(([k, n]) => `${k}=${n}`).join(', '));
  console.log('Existing customers are now in the CDP: reachable by email, region and product holdings — '
    + 'no browsing, no personalization consent. Re-runnable (upserts are idempotent per party+key).');
})();
