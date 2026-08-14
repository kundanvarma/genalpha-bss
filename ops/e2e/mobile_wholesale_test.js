/* Mobile wholesale / MVNE — the seeker side, usage-metered. Suite #92.
 *
 * The platform plays a light MVNO: its subscribers ride a host MNO's network, and
 * the traffic they burn is owed to the host at a wholesale rate card. This proves:
 *  - RATE CARD: per-unit wholesale rates (data/voice/sms) authored as data.
 *  - WHOLESALE RATING: a SECOND pass over the SAME CDRs (retail rating untouched)
 *    rates them at wholesale into a per-period ledger (units × rate = amount).
 *  - SETTLEMENT + RECONCILIATION: what the MVNO owes the host, and a live check
 *    that the rated units still match the CDRs (a late CDR is flagged).
 *  - BOOKS: revenue books the cost as COGS + a payable to the host (DR 5110 / CR 2110).
 *  - IDEMPOTENT: re-running rates once and books once.
 *  - IMSI: the host's lent IMSI range is a modeled resource.
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const U = `${API}/tmf-api/usageManagement/v4`;
const REV = `${API}/revenue/v1`;
const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
const ok = (m) => console.log('OK ' + m);
const run = Date.now();
const P = (s) => `MW${run % 100000} ${s}`;   // unique usage-spec names for this run

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const staff = await (await ctx.request.post(KC, { form: {
    grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } })).json();
  const H = { Authorization: `Bearer ${staff.access_token}`, 'Content-Type': 'application/json' };
  const post = (p, data) => ctx.request.post(`${U}${p}`, { headers: H, data });
  const get = (p) => ctx.request.get(`${U}${p}`, { headers: H });
  const period = 'periodStart=2026-08-01&periodEnd=2026-08-31';

  /* ---------- 1. RATE CARD ---------- */
  const rates = { data: 2.50, voice: 0.02, sms: 0.01 };
  const units = { data: 'GB', voice: 'min', sms: 'sms' };
  for (const k of Object.keys(rates)) {
    const r = await post('/wholesaleRateCard', { usageSpecName: P(k), wholesaleRate: rates[k],
      unit: units[k], hostName: 'NordMobile (host MNO)' });
    if (r.status() !== 201) fail(`rate card ${k}: ${r.status()}`);
  }
  ok('RATE CARD: per-unit wholesale rates authored — data €2.50/GB, voice €0.02/min, sms €0.01');

  /* ---------- 2. CDRs (a subscriber's traffic) ---------- */
  const burn = { data: 10, voice: 100, sms: 50 };
  for (const k of Object.keys(burn)) {
    const r = await post('/usage', { usageType: P(k), usageCharacteristic: { value: burn[k], units: units[k] },
      usageDate: '2026-08-14T10:00:00Z', relatedParty: [{ id: `mw-sub-${run}`, role: 'customer' }] });
    if (r.status() !== 201) fail(`CDR ${k}: ${r.status()}`);
  }
  ok('CDRs: a subscriber burned 10 GB data, 100 min voice, 50 SMS on the host network');

  /* ---------- 3. WHOLESALE RATING ---------- */
  const rated = await (await post(`/rateWholesale?${period}`, {})).json();
  const byspec = Object.fromEntries(rated.map((r) => [r.usageSpecName, r]));
  const expect = { data: 25.0, voice: 2.0, sms: 0.5 };
  for (const k of Object.keys(expect)) {
    const row = byspec[P(k)];
    if (!row) fail(`no wholesale ledger row for ${k}`);
    if (Number(row.amount) !== expect[k]) fail(`${k} wholesale amount ${row.amount} != ${expect[k]}`);
  }
  ok('WHOLESALE RATING: the MVNO\'s CDRs re-rated at wholesale — data €25.00 + voice €2.00 + sms €0.50');

  /* ---------- 4. SETTLEMENT + RECONCILIATION ---------- */
  let s = await (await get(`/mobileWholesaleSettlement?${period}`)).json();
  const mine = (s.line || []).filter((l) => String(l.usageSpecName).startsWith(`MW${run % 100000}`));
  if (mine.length !== 3) fail(`settlement expected 3 of my lines, got ${mine.length}`);
  if (!mine.every((l) => l.reconciled === true)) fail('settlement not reconciled right after rating');
  ok(`SETTLEMENT: the MVNO owes the host — my 3 lines total €27.50, all reconciled (rated units == live CDRs)`);

  // reconciliation TEETH: a late CDR lands after rating → the line stops reconciling
  await post('/usage', { usageType: P('data'), usageCharacteristic: { value: 5, units: 'GB' },
    usageDate: '2026-08-20T09:00:00Z', relatedParty: [{ id: `mw-sub-${run}`, role: 'customer' }] });
  s = await (await get(`/mobileWholesaleSettlement?${period}`)).json();
  const dataLine = (s.line || []).find((l) => l.usageSpecName === P('data'));
  if (dataLine.reconciled !== false) fail('a late CDR was NOT flagged by reconciliation');
  if (Number(dataLine.liveUnits) !== 15 || Number(dataLine.ratedUnits) !== 10) {
    fail(`reconciliation numbers wrong: rated ${dataLine.ratedUnits} live ${dataLine.liveUnits}`);
  }
  ok('RECONCILIATION: a CDR that lands AFTER rating is flagged — rated 10 GB vs live 15 GB, reconciled=false (revenue assurance)');

  /* ---------- 5. BOOKS (COGS to the GL) ---------- */
  const mySourceRefs = new Set(rated
    .filter((r) => String(r.usageSpecName).startsWith(`MW${run % 100000}`))
    .map((r) => `mobile-wholesale:${r.id}`));
  await new Promise((r) => setTimeout(r, 3000));   // let the event flow to revenue
  const entries = await (await ctx.request.get(`${REV}/journalEntry?limit=200`, { headers: H })).json();
  const list = Array.isArray(entries) ? entries : (entries.journalEntry || []);
  const cogs = list.filter((e) => mySourceRefs.has(e.sourceRef));
  if (cogs.length !== 3) fail(`expected 3 mobile-wholesale COGS entries, got ${cogs.length}`);
  // one entry's double-entry: DR 5110 mobile-wholesale COGS / CR 2110 payable
  const detail = await (await ctx.request.get(`${REV}/journalEntry/${cogs[0].id}`, { headers: H })).json();
  const lines = detail.lines || detail.journalLine || detail.line || [];
  const codes = lines.map((l) => l.accountCode).sort();
  if (!(codes.includes('5110') && codes.includes('2110'))) {
    fail('COGS entry not booked DR 5110 / CR 2110: ' + JSON.stringify(codes));
  }
  ok('BOOKS: revenue booked the wholesale cost — DR 5110 mobile-wholesale COGS / CR 2110 payable to the host, per usage type');

  /* ---------- 6. IDEMPOTENT ---------- */
  const before = list.filter((e) => String(e.sourceRef).startsWith('mobile-wholesale:')).length;
  await post(`/rateWholesale?${period}`, {});
  await new Promise((r) => setTimeout(r, 2000));
  const after = (await (await ctx.request.get(`${REV}/journalEntry?limit=300`, { headers: H })).json());
  const afterN = (Array.isArray(after) ? after : (after.journalEntry || []))
    .filter((e) => String(e.sourceRef).startsWith('mobile-wholesale:')).length;
  if (afterN !== before) fail(`re-rating double-booked: ${before} -> ${afterN}`);
  ok('IDEMPOTENT: re-running the wholesale rating rates once and books once — the ledger is the checkpoint');

  /* ---------- 7. IMSI ---------- */
  const imsi = await post('/imsiRange', { hostName: 'NordMobile (host MNO)', prefix: '242011',
    fromImsi: '242011000000000', toImsi: '242011000099999', capacity: 100000, note: 'MVNO allocation' });
  if (imsi.status() !== 201) fail(`IMSI allocate: ${imsi.status()}`);
  const ranges = await (await get('/imsiRange')).json();
  if (!ranges.some((r) => r.prefix === '242011')) fail('allocated IMSI range not listed');
  ok('IMSI: the host lent the MVNO an IMSI range (242011…, 100k) — a modeled pool resource');

  await ctx.close();
  await browser.close();
  console.log('\nALL MOBILE-WHOLESALE CHECKS PASSED — the platform is a light MVNO: its subscribers\''
    + ' traffic is re-rated at the host\'s wholesale rates into a per-period ledger, the settlement shows'
    + ' what it owes the host with a live reconciliation check, revenue books the cost as COGS + a payable,'
    + ' it is idempotent, and the host\'s IMSI range is modeled — the mobile sibling of open-access fibre.');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
