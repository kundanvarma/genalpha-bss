/* Event-context tokens: a message can reference WHY the customer entered.
 *   - a journey triggers on an order event and greets by name AND quotes the
 *     order number: "Hi {{party.firstName}}, your order {{order.id}} ..."
 *   - the enrollment captures the trigger event's context at enrol time, so the
 *     token resolves at send time (even days later)
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

  const offering = (await (await ctx.get(OFFERINGS)).json()).find((o) => o.name === 'GenAlpha Mobile 10 GB');
  if (!offering) fail('GenAlpha Mobile 10 GB offering missing — run the catalog seed');

  /* ---------- a journey that greets by name AND quotes the order number ---------- */
  const firstName = `Ada${run}`;
  const j = await (await ctx.post(JOURNEY, { headers: H(staff), data: {
    name: `Order context ${run}`, triggerEventType: 'ProductOrderCreateEvent', holdoutPercent: 0,
    steps: [{ type: 'message', stage: 'Welcome', channel: 'inApp',
      subject: `Order received, {{party.firstName}}`,
      content: 'Hi {{party.firstName}}, your order {{order.id}} is being processed.' }] } })).json();
  if (!j.id) fail('journey not created: ' + JSON.stringify(j));

  /* ---------- a customer places an order (fires ProductOrderCreateEvent) ---------- */
  const email = `octx-${run}@example.com`;
  const login = await (await ctx.post(USER, { headers: H(staff),
    data: { email, givenName: firstName, familyName: `O${run}` } })).json();
  await ctx.post(PARTY, { headers: H(staff), data: { id: login.id, givenName: firstName, familyName: `O${run}` } });
  const order = await (await ctx.post(ORDER, { headers: H(staff), data: {
    productOrderItem: [{ action: 'add', productOffering: { id: offering.id, name: offering.name } }],
    relatedParty: [{ id: login.id, role: 'customer' }] } })).json();
  if (!order.id) fail('order not created: ' + JSON.stringify(order));
  console.log(`OK a customer placed order ${order.id} — the journey triggers on ProductOrderCreateEvent`);

  /* ---------- the delivered message carries BOTH the name and the order number ---------- */
  const custTok = await token(ctx, 'bss-biz', email, login.temporaryPassword);
  if (!custTok) fail('customer login failed — no token (temporaryPassword=' + login.temporaryPassword + ')');
  let msg = null;
  for (let i = 0; i < 30; i++) {
    const res = await ctx.get(INBOX, { headers: H(custTok) });
    const inbox = res.ok() ? await res.json() : [];
    msg = inbox.find((m) => m.content && m.content.includes(firstName) && m.content.includes(order.id));
    if (msg) break;
    await sleep(1500);
  }
  if (!msg) fail('the message did not resolve BOTH {{party.firstName}} and {{order.id}}');
  if (msg.content.includes('{{')) fail('an unresolved token leaked: ' + msg.content);
  console.log(`OK the message resolved event context — "${msg.content}"`);

  await ctx.delete(`${JOURNEY}/${j.id}`, { headers: H(staff) });
  console.log('\nALL CONTEXT CHECKS PASSED — the enrollment captures the trigger event, so a message can '
    + 'greet by name AND reference the order/tracking that brought them in.');
})();
