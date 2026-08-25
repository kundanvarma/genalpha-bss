/* Regulatory rehearsal — the price-rise letter counted before it exists:
 * cohort = notification list, port-out exposure = churn-flagged members,
 * upside vs at-risk in currency, read-only. */
const { request } = require('playwright');
const API = 'http://localhost:8080';

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const tok = (await (await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } })).json()).access_token;
  const r = await (await ctx.get(`${API}/ai/v1/priceRiseRehearsal?offeringName=${encodeURIComponent('GenAlpha Mobile Unlimited 5G')}&percent=10`,
    { headers: { Authorization: 'Bearer ' + tok }, timeout: 120000 })).json();
  if (!(r.notificationLetters > 0)) fail('no cohort: ' + JSON.stringify(r).slice(0, 200));
  if (Number(r.newMonthly).toFixed(2) !== (Number(r.currentMonthly) * 1.1).toFixed(2)) fail('the rise math is wrong: ' + r.newMonthly);
  if (Number(r.portOutExposureCustomers) > Number(r.notificationLetters)) fail('exposure exceeds cohort');
  if (!(r.assumptions || []).some((a) => /not fate/.test(a))) fail('the score-honesty line is missing');
  console.log(`OK REHEARSED: +10% on '${r.offeringName}' = ${r.notificationLetters} letters, `
    + `${r.monthlyUpsideIfNobodyLeaves} upside/mo if nobody leaves, ${r.portOutExposureCustomers} `
    + `churn-flagged (${r.annualRevenueAtRisk} annual at risk) — counted before any letter exists`);
  console.log('\nALL REG-REHEARSAL CHECKS PASSED — the right-to-exit window is a report, not a surprise.');
})();
