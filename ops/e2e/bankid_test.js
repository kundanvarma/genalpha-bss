/* Verified-identity step-up (BankID/Vipps) at checkout. A postpaid offering
 * flagged requiresVerifiedIdentity cannot be ordered by an ordinary session;
 * the order is refused with a machine-readable step-up signal. After the
 * customer steps up (here: a token from the step-up client that carries the
 * verified_identity claim — the dev stand-in for a completed BankID), the same
 * order goes through. A normal offering is unaffected either way. */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const run = Date.now();

async function tokenVia(request, clientId) {
  const res = await request.post(KC, {
    form: { grant_type: 'password', client_id: clientId, username: 'demo', password: 'demo' },
  });
  return (await res.json()).access_token;
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const fail = (msg) => { console.error('FAIL: ' + msg); process.exit(1); };

  const normal = await tokenVia(ctx.request, 'bss-demo');       // ordinary session
  const steppedUp = await tokenVia(ctx.request, 'bss-stepup');  // completed BankID (simulated)
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });

  // Find the flagged postpaid offering and a plain one.
  const offerings = await (await ctx.request.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=100`,
    { headers: H(normal) })).json();
  const flagged = offerings.find((o) => o.requiresVerifiedIdentity);
  const plain = offerings.find((o) => !o.requiresVerifiedIdentity && (o.name || '').includes('Unlimited'));
  if (!flagged) fail('no requiresVerifiedIdentity offering seeded');
  console.log('OK flagged offering present:', flagged.name);

  const orderFor = (offeringId) => ({
    productOrderItem: [{ action: 'add', productOffering: { id: offeringId } }],
    relatedParty: [{ id: `bankid-${run}`, role: 'customer' }],
  });

  // 1. Ordinary session is refused, with the step-up signal (not a generic 403).
  const blocked = await ctx.request.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`,
    { headers: H(normal), data: orderFor(flagged.id) });
  if (blocked.status() !== 403) fail('flagged order should be 403 without step-up, got ' + blocked.status());
  if (blocked.headers()['x-step-up'] !== 'verified-identity') fail('missing X-Step-Up signal');
  if ((await blocked.json()).code !== 'VERIFIED_IDENTITY_REQUIRED') fail('missing machine-readable code');
  console.log('OK ordinary checkout refused with a BankID step-up prompt');

  // 2. A plain offering is unaffected — no step-up demanded.
  if (plain) {
    const ok = await ctx.request.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`,
      { headers: H(normal), data: orderFor(plain.id) });
    if (ok.status() !== 201) fail('plain offering should not require step-up, got ' + ok.status());
    console.log('OK a normal offering checks out without any step-up');
  }

  // 3. After BankID step-up, the flagged offering goes through.
  const allowed = await ctx.request.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`,
    { headers: H(steppedUp), data: orderFor(flagged.id) });
  if (allowed.status() !== 201) fail('flagged order should succeed after step-up, got '
    + allowed.status() + ' ' + await allowed.text());
  console.log('OK after verified-identity step-up, the postpaid order completes');

  await browser.close();
  console.log('\nALL BANKID CHECKS PASSED');
})().catch((e) => { console.error('FAIL:', e.message.split('\n')[0]); process.exit(1); });
