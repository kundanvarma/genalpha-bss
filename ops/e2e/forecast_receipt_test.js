/* P2 — forecast receipts: an AI proposal that reprices an EXISTING offering
 * arrives pre-scored by the commercial simulator. The owner approves a
 * number, not a vibe. Model-in-the-loop, so the suite retries model variance
 * (up to 3 asks) but never relaxes the assertion itself.
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';

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

  let reply = null;
  for (let attempt = 1; attempt <= 3 && !(reply && reply.forecast); attempt++) {
    reply = await (await ctx.post(`${API}/ai/v1/productCopilot`, { headers: H, data: {
      messages: [{ role: 'owner', content: 'Repropose the existing offering GenAlpha Mobile 10 GB '
        + 'with a new recurring monthly price of 17.99 EUR — same offering name, just the new price. '
        + 'Produce the proposal now.' }],
      catalog: { offerings: [{ name: 'GenAlpha Mobile 10 GB', price: '15.00 EUR/month' }] },
    } })).json();
  }
  if (!reply || reply.kind !== 'proposal') fail('the copilot gave no proposal in 3 asks: ' + JSON.stringify(reply).slice(0, 200));
  const fc = reply.forecast;
  if (!fc || !(fc.lines || []).length) fail('the proposal arrived WITHOUT a forecast receipt');
  const line = fc.lines[0];
  if (line.offeringName !== 'GenAlpha Mobile 10 GB') fail('forecast is for the wrong offering');
  if (!(line.subscribers > 0)) fail('forecast has no subscriber base');
  if (line.annualRevenueDelta == null) fail('forecast has no revenue delta');
  if (!(fc.assumptions || []).length) fail('forecast assumptions missing');
  console.log(`OK the proposal arrived PRE-SCORED: ${line.subscribers} subs, `
    + `${line.currentMonthly} -> ${line.proposedMonthly} = ${line.annualRevenueDelta} ${fc.currency || ''}/yr, `
    + `assumption: "${fc.assumptions[0]}"`);

  console.log('\nALL P2 CHECKS PASSED — an AI proposal without a forecast is just an opinion; '
    + 'this one carried its number.');
})();
