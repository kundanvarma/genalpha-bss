/* S6 — the migration rehearsal: the parallel bill run pointed at a LEGACY
 * export, before anyone migrates anything. Three legacy rows: one lands and
 * matches to the øre, one differs by exactly 10, one has no offering — the
 * exceptions BY NAME, the ready-to-cut-over flag honest, the report persisted.
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const CAT = `${API}/tmf-api/productCatalogManagement/v4`;
const BILLS = `${API}/tmf-api/customerBillManagement/v4`;

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

  const price = await (await ctx.post(`${CAT}/productOfferingPrice`, { headers: H,
    data: { name: `Mig ${run} monthly`, priceType: 'recurring', recurringChargePeriodType: 'month',
      lifecycleStatus: 'Active', price: { unit: 'EUR', value: 100.0 } } })).json();
  await ctx.post(`${CAT}/productOffering`, { headers: H,
    data: { name: `Mig Plan ${run}`, lifecycleStatus: 'Active', isSellable: true,
      productOfferingPrice: [{ id: price.id, name: price.name }] } });

  const report = await (await ctx.post(`${BILLS}/migrationRehearsal`, { headers: H, data: {
    name: `Rehearsal ${run}`,
    rows: [
      { externalRef: 'LEG-001', offeringName: `Mig Plan ${run}`, expectedMonthly: 100.00 },
      { externalRef: 'LEG-002', offeringName: `Mig Plan ${run}`, expectedMonthly: 90.00 },
      { externalRef: 'LEG-003', offeringName: `No Such Plan ${run}`, expectedMonthly: 50.00 },
    ] } })).json();
  if (report.matched !== 1 || report.priceDiffers !== 1 || report.offeringMissing !== 1) {
    fail('classification wrong: ' + JSON.stringify(report).slice(0, 300));
  }
  if (report.readyToCutOver !== false) fail('ready flag must be honest with exceptions open');
  const differ = report.exceptions.priceDiffers[0];
  if (Number(differ.delta) !== 10) fail('delta must be exactly 10: ' + differ.delta);
  if (report.exceptions.offeringMissing[0].externalRef !== 'LEG-003') fail('missing row misattributed');
  if (!(report.assumptions || []).some((a) => /read-only/i.test(a))) fail('read-only promise missing');
  console.log(`OK rehearsal: 3 rows -> 1 matched, 1 differs by exactly 10.00, 1 missing (LEG-003 by name), `
    + 'readyToCutOver=false — the exceptions are the deliverable');

  const list = await (await ctx.get(`${BILLS}/migrationRehearsal`, { headers: H })).json();
  if (!list.find((r) => r.id === report.id)) fail('the rehearsal did not persist');
  console.log('OK the receipt persisted — the de-risking report survives the meeting it was made for');

  console.log('\nALL S6 CHECKS PASSED — the scariest sentence in a BSS sale is now a report '
    + 'with exceptions by name and an honest ready flag.');
})();
