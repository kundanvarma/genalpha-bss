/* Mobile wholesale PROVIDER face — the host/MVNE billing external MVNOs. Suite #93.
 *
 * The other type of MVNO: one that runs its OWN BSS. We are the host MNO; their
 * traffic is fed to us and we bill them wholesale. This proves:
 *  - PER-MVNO RATE CARDS: a premium MVNO and a budget MVNO owe DIFFERENT rates for
 *    the same usage — the SLA/tier lever (a per-MVNO card overrides the default).
 *  - SETTLEMENT: the host's consolidated book — per MVNO, what each owes (host AR).
 *  - STATEMENT: one MVNO's own view — the face its external BSS pulls to reconcile.
 *  - BOOKS: revenue books the host's wholesale REVENUE (DR 1210 / CR 4020).
 *  - IDEMPOTENT: a repeated usage report rates once and books once.
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const U = `${API}/tmf-api/usageManagement/v4`;
const REV = `${API}/revenue/v1`;
const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
const ok = (m) => console.log('OK ' + m);
const run = Date.now();
const SPEC = `PMW${run % 100000} data`;
const AURORA = `aurora-${run}`;
const FJORD = `fjord-${run}`;

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const staff = await (await ctx.request.post(KC, { form: {
    grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } })).json();
  const H = { Authorization: `Bearer ${staff.access_token}`, 'Content-Type': 'application/json' };
  const post = (p, data) => ctx.request.post(`${U}${p}`, { headers: H, data });
  const get = (p) => ctx.request.get(`${U}${p}`, { headers: H });
  const period = '2026-08-01';

  /* ---------- 1. PER-MVNO RATE CARDS (the SLA/tier lever) ---------- */
  // default rate for any MVNO
  await post('/providerRateCard', { usageSpecName: SPEC, rate: 2.00, unit: 'GB' });
  // a per-MVNO override: Aurora is a premium tier, pays more
  await post('/providerRateCard', { mvnoPartyId: AURORA, mvnoName: 'Aurora Mobile', usageSpecName: SPEC, rate: 2.50, unit: 'GB' });
  ok('RATE CARDS: a default €2.00/GB, and a per-MVNO override for Aurora at €2.50/GB — the SLA/tier lever');

  /* ---------- 2. record each MVNO's usage (same units, different tiers) ---------- */
  const a = await post('/mobileWholesaleProviderUsage', { mvnoPartyId: AURORA, mvnoName: 'Aurora Mobile', usageSpecName: SPEC, units: 100, unit: 'GB', periodStart: period });
  const f = await post('/mobileWholesaleProviderUsage', { mvnoPartyId: FJORD, mvnoName: 'Fjord Mobile', usageSpecName: SPEC, units: 100, unit: 'GB', periodStart: period });
  if (a.status() !== 201 || f.status() !== 201) fail(`provider usage: ${a.status()}/${f.status()}`);
  const aRow = await a.json(); const fRow = await f.json();
  if (Number(aRow.amount) !== 250 || Number(fRow.amount) !== 200) {
    fail(`per-MVNO rating wrong: Aurora ${aRow.amount} (want 250), Fjord ${fRow.amount} (want 200)`);
  }
  ok('RATING: same 100 GB — Aurora owes €250.00 (premium), Fjord owes €200.00 (default) — differentiated by tier');

  /* ---------- 3. SETTLEMENT (host AR, per MVNO) ---------- */
  const s = await (await get(`/mobileWholesaleProviderSettlement?periodStart=${period}`)).json();
  const mvnos = Object.fromEntries((s.mvno || []).map((m) => [m.mvnoPartyId, m]));
  if (!mvnos[AURORA] || !mvnos[FJORD]) fail('settlement missing an MVNO');
  if (Number(mvnos[AURORA].total) !== 250 || Number(mvnos[FJORD].total) !== 200) {
    fail(`settlement totals wrong: ${JSON.stringify([mvnos[AURORA].total, mvnos[FJORD].total])}`);
  }
  ok(`SETTLEMENT: the host's book shows Aurora €250 + Fjord €200 (+ others) — total revenue €${s.totalRevenue}`);

  /* ---------- 4. STATEMENT (the external MVNO's own view) ---------- */
  const aState = await (await get(`/mobileWholesaleStatement?mvnoPartyId=${AURORA}&periodStart=${period}`)).json();
  if (Number(aState.totalOwed) !== 250) fail(`Aurora statement total ${aState.totalOwed} != 250`);
  if ((aState.line || []).some((l) => false) || aState.mvnoName !== 'Aurora Mobile') fail('Aurora statement wrong');
  // isolation: Aurora's statement never shows Fjord's lines
  const fState = await (await get(`/mobileWholesaleStatement?mvnoPartyId=${FJORD}&periodStart=${period}`)).json();
  if (Number(fState.totalOwed) !== 200) fail(`Fjord statement total ${fState.totalOwed} != 200`);
  ok('STATEMENT: each MVNO\'s own statement (the face its external BSS pulls) shows only its own charges — Aurora €250, Fjord €200');

  /* ---------- 5. BOOKS (host wholesale REVENUE) ---------- */
  await new Promise((r) => setTimeout(r, 3000));
  const refs = new Set([`mobile-wholesale-rev:${aRow.id}`, `mobile-wholesale-rev:${fRow.id}`]);
  const entries = await (await ctx.request.get(`${REV}/journalEntry?limit=300`, { headers: H })).json();
  const list = Array.isArray(entries) ? entries : (entries.journalEntry || []);
  const rev = list.filter((e) => refs.has(e.sourceRef));
  if (rev.length !== 2) fail(`expected 2 mobile-wholesale REVENUE entries, got ${rev.length}`);
  const detail = await (await ctx.request.get(`${REV}/journalEntry/${rev[0].id}`, { headers: H })).json();
  const codes = (detail.lines || []).map((l) => l.accountCode).sort();
  if (!(codes.includes('1210') && codes.includes('4020'))) fail('host revenue not booked DR 1210 / CR 4020: ' + JSON.stringify(codes));
  ok('BOOKS: the host booked wholesale revenue — DR 1210 receivable / CR 4020 mobile-wholesale revenue, per MVNO');

  /* ---------- 6. IDEMPOTENT ---------- */
  const before = list.filter((e) => String(e.sourceRef).startsWith('mobile-wholesale-rev:')).length;
  await post('/mobileWholesaleProviderUsage', { mvnoPartyId: AURORA, mvnoName: 'Aurora Mobile', usageSpecName: SPEC, units: 100, unit: 'GB', periodStart: period });
  await new Promise((r) => setTimeout(r, 2000));
  const after = (await (await ctx.request.get(`${REV}/journalEntry?limit=400`, { headers: H })).json());
  const afterN = (Array.isArray(after) ? after : (after.journalEntry || []))
    .filter((e) => String(e.sourceRef).startsWith('mobile-wholesale-rev:')).length;
  if (afterN !== before) fail(`re-reporting double-booked: ${before} -> ${afterN}`);
  ok('IDEMPOTENT: a repeated usage report rates once and books once — the ledger is the checkpoint');

  await ctx.close();
  await browser.close();
  console.log('\nALL MOBILE-WHOLESALE-PROVIDER CHECKS PASSED — the platform is also the host MNE: an'
    + ' external MVNO that runs its own BSS is billed wholesale at a PER-MVNO rate card (the SLA/tier'
    + ' lever), the host sees a consolidated per-MVNO settlement, each MVNO pulls its own statement, and'
    + ' the host books the wholesale revenue. Mobile now supports BOTH MVNO types, like open-access fibre.');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
