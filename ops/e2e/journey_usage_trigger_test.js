/* Usage/balance trigger: a journey can react to a data-balance change.
 *   - the usage service emits BucketBalanceChangeEvent when a customer's data
 *     balance grows (a top-up bought, loyalty data gifted)
 *   - a journey triggered on that event fires and thanks them by name
 * NOTE: real-time "running low" is the network OCS/CHF's job (this batch-rating
 * service says so in its own docs); the honest in-BSS trigger is balance-CHANGED.
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const JOURNEY = `${API}/tmf-api/campaignManagement/v4/journey`;
const ORDER = `${API}/tmf-api/productOrderingManagement/v4/productOrder`;
const OFFERINGS = `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=100`;
const PARTY = `${API}/tmf-api/party/v4/individual`;
const USER = `${API}/tmf-api/rolesAndPermissionsManagement/v4/user`;
const INBOX = `${API}/tmf-api/communicationManagement/v4/communicationMessage?limit=100`;
const PLAN = 'GenAlpha Mobile 10 GB';
const TOPUP = 'Data Top-Up 5 GB';

async function token(ctx, client, user, pass) {
  const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: client, username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const staff = await token(ctx, 'bss-demo', 'demo', 'demo');
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });

  const offers = await (await ctx.get(OFFERINGS)).json();
  const plan = offers.find((o) => o.name === PLAN);
  const topup = offers.find((o) => o.name === TOPUP);
  if (!plan || !topup) fail('plan/top-up offering missing — run the catalog seed');

  /* ---------- a journey that fires when the data balance grows ---------- */
  const firstName = `Bea${run}`;
  const j = await (await ctx.post(JOURNEY, { headers: H(staff), data: {
    name: `Top-up thanks ${run}`, triggerEventType: 'BucketBalanceChangeEvent', holdoutPercent: 0,
    steps: [{ type: 'message', stage: 'Welcome', channel: 'inApp',
      subject: `Thanks, {{party.firstName}}!`,
      content: 'Hi {{party.firstName}}, your extra data has landed — enjoy.' }] } })).json();
  if (!j.id) fail('journey not created: ' + JSON.stringify(j));

  /* ---------- a customer takes a plan, then buys a top-up (balance grows) ---------- */
  const email = `utrig-${run}@example.com`;
  const login = await (await ctx.post(USER, { headers: H(staff),
    data: { email, givenName: firstName, familyName: `B${run}` } })).json();
  if (!login.temporaryPassword) fail('user create failed: ' + JSON.stringify(login));
  await ctx.post(PARTY, { headers: H(staff), data: { id: login.id, givenName: firstName, familyName: `B${run}` } });
  const custTok = await token(ctx, 'bss-biz', email, login.temporaryPassword);
  const CH = H(custTok);
  await ctx.post(ORDER, { headers: CH, data: {
    productOrderItem: [{ action: 'add', productOffering: { id: plan.id, name: plan.name } }] } });
  const boost = await (await ctx.post(ORDER, { headers: CH, data: {
    productOrderItem: [{ action: 'add', productOffering: { id: topup.id, name: topup.name } }] } })).json();
  if (!boost.id) fail('top-up order failed: ' + JSON.stringify(boost));
  console.log(`OK ${firstName} bought a top-up — the usage service emits BucketBalanceChangeEvent`);

  /* ---------- the balance-change journey fired and greeted by name ---------- */
  let msg = null;
  for (let i = 0; i < 30; i++) {
    const res = await ctx.get(INBOX, { headers: CH });
    const inbox = res.ok() ? await res.json() : [];
    msg = inbox.find((m) => m.content && m.content.includes(firstName) && m.content.includes('extra data has landed'));
    if (msg) break;
    await sleep(1500);
  }
  if (!msg) fail('the BucketBalanceChangeEvent journey never fired (no thank-you in the inbox)');
  if (msg.content.includes('{{')) fail('an unresolved token leaked: ' + msg.content);
  console.log(`OK the balance-change journey fired — "${msg.content}"`);

  await ctx.delete(`${JOURNEY}/${j.id}`, { headers: H(staff) });
  console.log('\nALL USAGE-TRIGGER CHECKS PASSED — a data-balance change (top-up / loyalty gift) is a real, '
    + 'selectable journey trigger; real-time "running low" stays where it belongs, on the network OCS/CHF.');
})();
