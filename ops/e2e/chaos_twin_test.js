/* The chaos twin — a failure mode priced in currency off the real ledger.
 * psp-outage: N days where nothing collects. Read-only; the horizon reads
 * the tenant clock, so in a sandbox clone it composes with time compression. */
const { request } = require('playwright');
const API = 'http://localhost:8080';

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const tok = (await (await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } })).json()).access_token;
  const H = { Authorization: 'Bearer ' + tok };
  const get = async (days) => (await (await ctx.get(
    `${API}/tmf-api/customerBillManagement/v4/chaosReport?days=${days}`, { headers: H })).json());

  const week = await get(7);
  if (!(week.openBills > 0) || !(Number(week.revenueAtRisk) > 0)) {
    fail('the outage prices at zero on an aged ledger: ' + JSON.stringify(week).slice(0, 200));
  }
  if (!(week.assumptions || []).some((a) => /read-only/.test(a))) fail('the read-only promise is missing');
  console.log(`OK PRICED IN CURRENCY: a 7-day PSP outage = ${week.revenueAtRisk} ${week.currency} `
    + `across ${week.openBills} open bills, ${week.billsAgedBeyondTermsAtHorizon} aging beyond terms`);

  const month = await get(30);
  if (Number(month.billsAgedBeyondTermsAtHorizon) < Number(week.billsAgedBeyondTermsAtHorizon)) {
    fail('a longer outage aged FEWER bills — the horizon is broken');
  }
  if (Number(month.revenueAtRisk) !== Number(week.revenueAtRisk)) {
    fail('exposure should not depend on duration (nothing collects either way)');
  }
  console.log('OK THE HORIZON: 30 days ages at least as many bills as 7 — monotone, and the '
    + 'exposure itself is duration-independent, as the assumptions say');

  console.log('\nALL CHAOS-TWIN CHECKS PASSED — the cost of a failure mode is a report with '
    + 'assumptions on its face, not a war-room guess.');
})();
