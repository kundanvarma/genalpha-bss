/* THE CLOSED LOOP — late-CDR auto re-rating (foundation #8 made real).
 *
 *  - a period is rated; a CDR lands AFTER rating (the classic late CDR)
 *  - before: settlement flagged reconciled=false and a human had a to-do
 *  - now: the sweep re-rates the drifted row automatically, stamps the
 *    receipt (rerateCount, lastReratedAt), settlement reconciles itself,
 *    and revenue books the DELTA as an adjustment — idempotent per
 *    (ledger id, rerate #), so replays and double-sweeps book once
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const USAGE = `${API}/tmf-api/usageManagement/v4`;
const SPEC = `LateData${run}`;   // unique usage type: shared-period isolation

async function token(ctx) {
  const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const T = await token(ctx);
  const H = { Authorization: 'Bearer ' + T, 'Content-Type': 'application/json' };

  const now = new Date();
  const y = now.getUTCFullYear(); const m = String(now.getUTCMonth() + 1).padStart(2, '0');
  const first = `${y}-${m}-01`;
  const lastDay = new Date(Date.UTC(y, now.getUTCMonth() + 1, 0)).getUTCDate();
  const last = `${y}-${m}-${String(lastDay).padStart(2, '0')}`;
  const stamp = (d) => `${y}-${m}-${String(d).padStart(2, '0')}T10:00:00Z`;

  /* ---------- rate card + an on-time CDR, rated ---------- */
  await ctx.post(`${USAGE}/wholesaleRateCard`, { headers: H,
    data: { usageSpecName: SPEC, wholesaleRate: 2.0, unit: 'GB', currency: 'EUR', hostName: 'Host' } });
  await ctx.post(`${USAGE}/usage`, { headers: H, data: { usageType: SPEC,
    usageCharacteristic: { value: 10, units: 'GB' }, usageDate: stamp(2),
    relatedParty: [{ id: `late-${run}`, role: 'customer' }] } });
  const rated = await (await ctx.post(
    `${USAGE}/rateWholesale?periodStart=${first}&periodEnd=${last}`, { headers: H })).json();
  const row = rated.find((r) => r.usageSpecName === SPEC);
  if (!row || Number(row.totalUnits) !== 10) fail('period not rated at 10 GB: ' + JSON.stringify(rated).slice(0, 200));
  console.log(`OK rated: 10 GB @ 2.00 = ${row.amount} — the month is booked`);

  /* ---------- the LATE CDR lands after rating ---------- */
  await ctx.post(`${USAGE}/usage`, { headers: H, data: { usageType: SPEC,
    usageCharacteristic: { value: 3, units: 'GB' }, usageDate: stamp(3),
    relatedParty: [{ id: `late-${run}`, role: 'customer' }] } });
  console.log('OK a 3 GB CDR landed AFTER rating — the ledger is now wrong by 6.00');

  /* ---------- the loop closes it: no human, no endpoint call ---------- */
  let ledgerRow = null;
  for (let i = 0; i < 30; i++) {
    const ledger = await (await ctx.get(
      `${USAGE}/wholesaleUsageLedger?periodStart=${first}`, { headers: H })).json();
    ledgerRow = ledger.find((r) => r.usageSpecName === SPEC);
    if (ledgerRow && Number(ledgerRow.totalUnits) === 13) break;
    await sleep(3000);
  }
  if (!ledgerRow || Number(ledgerRow.totalUnits) !== 13) fail('the sweep never re-rated the drifted row');
  if (Number(ledgerRow.amount) !== 26) fail('re-rated amount wrong: ' + ledgerRow.amount);
  if (ledgerRow.rerateCount !== 1 || !ledgerRow.lastReratedAt) fail('missing the re-rate receipt');
  console.log(`OK the loop CLOSED itself: 13 GB = ${ledgerRow.amount}, rerate #${ledgerRow.rerateCount} stamped`);

  /* ---------- settlement reconciles without anyone touching it ---------- */
  const st = await (await ctx.get(
    `${USAGE}/mobileWholesaleSettlement?periodStart=${first}&periodEnd=${last}`, { headers: H })).json();
  const line = (st.line || []).find((l) => l.usageSpecName === SPEC);
  if (!line || !line.reconciled) fail('settlement line still unreconciled after the auto re-rate');
  console.log('OK settlement reconciled on its own — the flag stopped being a human to-do');

  /* ---------- revenue booked the DELTA exactly once ---------- */
  const REV = `${API}/revenue/v1`;
  let deltaEntry = null;
  for (let i = 0; i < 20 && !deltaEntry; i++) {
    const entries = await (await ctx.get(`${REV}/journalEntry?limit=200`, { headers: H })).json();
    deltaEntry = (entries || []).find((e) =>
      String(e.sourceRef || '').startsWith(`mobile-wholesale-rerate:${ledgerRow.id}:`));
    if (!deltaEntry) await sleep(2000);
  }
  if (!deltaEntry) fail('revenue never booked the re-rate delta');
  const detail = await (await ctx.get(`${REV}/journalEntry/${deltaEntry.id}`, { headers: H })).json();
  const debit5110 = (detail.lines || []).find((l) => l.accountCode === '5110');
  if (!debit5110 || Number(debit5110.debit) !== 6) fail('delta booking wrong: ' + JSON.stringify(detail.lines));
  console.log('OK revenue booked the 6.00 delta (DR 5110 / CR 2110) exactly once, keyed to rerate #1');

  console.log('\nALL CLOSED-LOOP CHECKS PASSED — a late CDR now re-rates the ledger, reconciles the '
    + 'settlement and books its delta automatically, with a receipt at every step.');
})();
