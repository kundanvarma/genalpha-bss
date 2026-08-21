/* P4 — the negotiation twin + the prospect simulator.
 *
 *  NEGOTIATION TWIN: the period's REAL CDRs replayed against a hypothetical
 *  host rate card — exact arithmetic, read-only, assumptions on the face.
 *  PROSPECT SIMULATOR: "your business on this BSS" from stated assumptions
 *  only — and the report must SAY no real data was read.
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const USAGE = `${API}/tmf-api/usageManagement/v4`;
const SPEC = `NegData${run}`;

async function token(ctx) {
  const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const T = await token(ctx);
  const H = { Authorization: 'Bearer ' + T, 'Content-Type': 'application/json' };

  const now = new Date();
  const y = now.getUTCFullYear(); const m = String(now.getUTCMonth() + 1).padStart(2, '0');
  const first = `${y}-${m}-01`;
  const lastDay = new Date(Date.UTC(y, now.getUTCMonth() + 1, 0)).getUTCDate();
  const last = `${y}-${m}-${String(lastDay).padStart(2, '0')}`;

  /* ---------- real card at 2.00, real CDRs: 10 GB ---------- */
  await ctx.post(`${USAGE}/wholesaleRateCard`, { headers: H,
    data: { usageSpecName: SPEC, wholesaleRate: 2.0, unit: 'GB', currency: 'EUR', hostName: 'Host' } });
  await ctx.post(`${USAGE}/usage`, { headers: H, data: { usageType: SPEC,
    usageCharacteristic: { value: 10, units: 'GB' }, usageDate: `${y}-${m}-02T10:00:00Z`,
    relatedParty: [{ id: `neg-${run}`, role: 'customer' }] } });

  /* ---------- what if the host gave us 1.50? ---------- */
  const sim = await (await ctx.post(
    `${USAGE}/simulateWholesale?periodStart=${first}&periodEnd=${last}`,
    { headers: H, data: { rateCard: [{ usageSpecName: SPEC, wholesaleRate: 1.5 }] } })).json();
  const line = (sim.line || []).find((l) => l.usageSpecName === SPEC);
  if (!line) fail('the twin ignored the probe spec: ' + JSON.stringify(sim).slice(0, 200));
  if (Number(line.currentCost) !== 20 || Number(line.proposedCost) !== 15
      || Number(line.delta) !== -5) {
    fail(`twin arithmetic wrong: ${JSON.stringify(line)}`);
  }
  if (!(sim.assumptions || []).some((a) => /read-only/i.test(a))) fail('read-only assumption missing');
  console.log('OK NEGOTIATION TWIN: 10 real GB — current 20.00, at the proposed 1.50 it is 15.00, '
    + 'delta -5.00; read-only stated on the face');

  /* ---------- the ledger must be UNTOUCHED (read-only means read-only) ---------- */
  const ledger = await (await ctx.get(`${USAGE}/wholesaleUsageLedger?periodStart=${first}`,
    { headers: H })).json();
  if ((ledger || []).find((r) => r.usageSpecName === SPEC)) {
    fail('THE TWIN WROTE A LEDGER ROW');
  }
  console.log('OK nothing was rated or booked — the twin only answered');

  /* ---------- prospect simulator: stated assumptions only ---------- */
  const prospect = await (await ctx.post(`${API}/ai/v1/simulate/prospect`, { headers: H, data: {
    currency: 'NOK', wholesaleDataRatePerGb: 8,
    offerings: [
      { name: 'Plan S', monthlyPrice: 199, subscribers: 1000, allowanceGb: 6 },
      { name: 'Plan L', monthlyPrice: 399, subscribers: 500, allowanceGb: 30 },
    ] } })).json();
  // 199*1000*12 + 399*500*12 = 2388000 + 2394000 = 4782000
  if (Number(prospect.annualRevenue) !== 4782000) fail('prospect revenue wrong: ' + prospect.annualRevenue);
  // ceilings: (6*8)*1000*12 + (30*8)*500*12 = 576000 + 1440000 = 2016000
  if (Number(prospect.annualWholesaleCostCeiling) !== 2016000) {
    fail('prospect cost ceiling wrong: ' + prospect.annualWholesaleCostCeiling);
  }
  if (!(prospect.assumptions || []).some((a) => /no real/i.test(a))) {
    fail('the prospect report does not admit it read no real data');
  }
  console.log('OK PROSPECT SIMULATOR: 4 782 000 revenue, 2 016 000 cost ceiling, '
    + `margin floor ${prospect.annualGrossMarginFloor} — and it says "no real data was read"`);

  console.log('\nALL P4 CHECKS PASSED — the negotiation twin answers from real CDRs without touching '
    + 'the books, and the prospect simulator computes from stated assumptions it openly names.');
})();
