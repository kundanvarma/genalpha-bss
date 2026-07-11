/* Number porting (keep your number). A Norwegian customer ports their number
 * in through NRDB (the national clearinghouse), the cutover completes, and
 * when they order a mobile plan the orchestrator activates on the PORTED
 * number instead of drawing a fresh one from the pool. A bad number is
 * rejected by the clearinghouse; country rules apply. */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const PORT = `${API}/tmf-api/numberPortingManagement/v1`;
const run = Date.now();

async function staffToken(request) {
  const res = await request.post(
    'http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } });
  return (await res.json()).access_token;
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const fail = (msg) => { console.error('FAIL: ' + msg); process.exit(1); };
  const token = await staffToken(ctx.request);
  const H = { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' };
  const party = `porttest-${run}`;
  const number = '+4790' + String(run).slice(-6);

  // 1. The clearinghouse rejects a malformed number (country rules apply).
  const bad = await (await ctx.request.post(`${PORT}/numberPortingOrder`, {
    headers: H, data: { direction: 'portIn', phoneNumber: '12345', country: 'NO', otherOperator: 'OtherTelco' },
  })).json();
  if (bad.status !== 'rejected' || !bad.rejectReason) fail('bad number should be rejected: ' + JSON.stringify(bad));
  console.log('OK a malformed number is rejected by the clearinghouse:', bad.clearinghouse, '·', bad.regulator);

  // 2. A valid Norwegian port-in is validated and scheduled by the clearinghouse.
  const created = await ctx.request.post(`${PORT}/numberPortingOrder`, {
    headers: H,
    data: { direction: 'portIn', phoneNumber: number, country: 'NO', otherOperator: 'OtherTelco',
            relatedParty: [{ id: party, role: 'customer' }] },
  });
  if (created.status() !== 201) fail('port-in create failed: ' + created.status());
  const port = await created.json();
  if (port.status !== 'scheduled' || !port.scheduledCutover) fail('port-in not scheduled: ' + JSON.stringify(port));
  console.log('OK port-in scheduled via', port.clearinghouse, '— cutover', port.scheduledCutover.slice(0, 16),
    '· regulator', port.regulator);

  // 3. Before cutover, the number is not yet the customer's.
  let ported = await (await ctx.request.get(`${PORT}/portedNumber?relatedPartyId=${party}`, { headers: H })).json();
  if (ported.phoneNumber) fail('number should not be available before cutover');

  // 4. Cutover completes.
  const done = await ctx.request.post(`${PORT}/numberPortingOrder/${port.id}/complete`, { headers: H });
  if ((await done.json()).status !== 'completed') fail('cutover did not complete');
  ported = await (await ctx.request.get(`${PORT}/portedNumber?relatedPartyId=${party}`, { headers: H })).json();
  if (ported.phoneNumber !== number) fail('ported number not available after cutover');
  console.log('OK cutover completed — the number is now the customer\'s');

  // 5. Order a mobile plan → the orchestrator activates on the PORTED number.
  const offers = await (await ctx.request.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=50`, { headers: H })).json();
  const plan = offers.find((o) => (o.name || '').includes('Unlimited'));
  await ctx.request.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: H, data: { productOrderItem: [{ action: 'add', productOffering: { id: plan.id } }],
      relatedParty: [{ id: party, role: 'customer' }] },
  });
  let kept = false;
  for (let attempt = 0; attempt < 20 && !kept; attempt++) {
    await new Promise((r) => setTimeout(r, 2000));
    const services = await (await ctx.request.get(
      `${API}/tmf-api/serviceInventory/v4/service?relatedPartyId=${party}`, { headers: H })).json();
    kept = services.some((s) => (s.supportingResource || []).some((r) => r.value === number));
  }
  if (!kept) fail('service did not activate on the ported number');
  console.log('OK the plan activated on the ported number', number, '— they kept their number, no pool draw');

  await browser.close();
  console.log('\nALL PORTING CHECKS PASSED');
})().catch((e) => { console.error('FAIL:', e.message.split('\n')[0]); process.exit(1); });
