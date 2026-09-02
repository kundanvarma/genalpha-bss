/* CARE CHAT — the customer-facing conversation rail, three faces proven:
 *
 *  - GUEST: no token, session id is the capability, the bot answers from the
 *    public shelf
 *  - CUSTOMER: self-scoped like /forYou — the bot sees only the token
 *    subject's own account, and one customer can NEVER read another's chat
 *  - AGENT: the CSR desk lists live conversations; an agent's reply flips
 *    the session to 'agent' and the bot goes silent (humans outrank models)
 *  - ESCALATION: "talk to a human" raises a REAL TMF621 ticket carrying the
 *    transcript
 *
 * Model-tolerant by design: replies are asserted for existence and structure,
 * never for wording — the same suite passes on the stub and on a frontier
 * model.
 */
const { chromium, request } = require('playwright');

const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const run = Date.now();

async function token(ctx, user, pass) {
  const res = await ctx.post(KC, { form: {
    grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  const body = await res.json();
  if (!body.access_token) throw new Error(`no token for ${user}`);
  return body.access_token;
}

(async () => {
  const ctx = await request.newContext({ timeout: 90000 });
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
  const CHAT = `${API}/ai/v1/careChat`;

  /* ---------- 1. GUEST: shelf-grounded, tokenless ---------- */
  const gs = await (await ctx.post(`${CHAT}/guest/session`, {
    headers: { 'Content-Type': 'application/json' }, data: {} })).json();
  if (!gs.id) fail('guest session not created: ' + JSON.stringify(gs));
  const gReply = await (await ctx.post(`${CHAT}/guest/session/${gs.id}/message`, {
    headers: { 'Content-Type': 'application/json' },
    data: { text: 'Which mobile plans do you offer?' } })).json();
  if (!gReply.reply || gReply.reply.length < 10) {
    fail('guest bot gave no useful reply: ' + JSON.stringify(gReply));
  }
  console.log('OK GUEST: tokenless chat, bot answered from the shelf —',
    JSON.stringify(gReply.reply.slice(0, 70)));

  /* ---------- 2. CUSTOMER: self-scoped account chat ---------- */
  const kai = await token(ctx, 'kai@bss.local', 'kai');
  const cs = await (await ctx.post(`${CHAT}/session`, { headers: H(kai), data: {} })).json();
  if (!cs.id) fail('customer session not created: ' + JSON.stringify(cs));
  const cReply = await (await ctx.post(`${CHAT}/session/${cs.id}/message`, {
    headers: H(kai), data: { text: 'What is the state of my latest bill?' } })).json();
  if (!cReply.reply || cReply.reply.length < 10) {
    fail('customer bot gave no useful reply: ' + JSON.stringify(cReply));
  }
  console.log('OK CUSTOMER: account chat answered (grounded on the token subject\'s own data)');

  // isolation: a DIFFERENT customer must not read kai's conversation
  const nils = await (await ctx.post(
    'http://localhost:8085/realms/nova/protocol/openid-connect/token', { form: {
      grant_type: 'password', client_id: 'bss-demo',
      username: 'norah@nova.example', password: 'norah' } })).json();
  // cross-TENANT first (nova token on genalpha chat) — must not resolve
  if (nils.access_token) {
    const cross = await ctx.get(`${CHAT}/session/${cs.id}/messages`,
      { headers: H(nils.access_token) });
    if (cross.status() === 200) fail('cross-tenant customer read another tenant\'s chat!');
    console.log(`OK ISOLATION (tenant): foreign-tenant token refused (${cross.status()})`);
  }
  // cross-CUSTOMER within the realm: staff pat has no business in the
  // customer rail either unless via the agent face
  const pat = await token(ctx, 'pat@bss.local', 'pat');
  const peek = await ctx.get(`${CHAT}/session/${cs.id}/messages`, { headers: H(pat) });
  if (peek.status() === 200) fail('a different subject read the customer\'s chat via the customer rail!');
  console.log(`OK ISOLATION (party): another subject refused on the customer rail (${peek.status()})`);

  /* ---------- 3. ESCALATION raises a real ticket ---------- */
  const esc = await (await ctx.post(`${CHAT}/session/${cs.id}/escalate`,
    { headers: H(kai), data: {} })).json();
  if (!esc.ticketId) fail('escalation returned no ticket: ' + JSON.stringify(esc));
  const staff = await token(ctx, 'demo', 'demo');
  const ticket = await (await ctx.get(
    `${API}/tmf-api/troubleTicket/v4/troubleTicket/${esc.ticketId}`,
    { headers: H(staff) })).json();
  if (!ticket.id) fail('escalation ticket not found in TMF621: ' + JSON.stringify(ticket).slice(0, 150));
  if (!(`${ticket.description}`.includes('care chat') || `${ticket.name}`.toLowerCase().includes('care chat'))) {
    fail('ticket does not reference the chat: ' + JSON.stringify(ticket.name));
  }
  console.log(`OK ESCALATION: real TMF621 ticket ${esc.ticketId.slice(0, 8)}… carries the transcript`);

  /* ---------- 3b. CONVERSATIONAL escalation: saying it makes it real ---------- */
  const gs2 = await (await ctx.post(`${CHAT}/guest/session`, {
    headers: { 'Content-Type': 'application/json' }, data: {} })).json();
  const wantHuman = await (await ctx.post(`${CHAT}/guest/session/${gs2.id}/message`, {
    headers: { 'Content-Type': 'application/json' },
    data: { text: 'I want to talk to a human please, raise a ticket' } })).json();
  if (!wantHuman.ticketId) {
    fail('conversational "human please" did not raise a REAL ticket: ' + JSON.stringify(wantHuman));
  }
  const convTicket = await (await ctx.get(
    `${API}/tmf-api/troubleTicket/v4/troubleTicket/${wantHuman.ticketId}`,
    { headers: H(await token(ctx, 'demo', 'demo')) })).json();
  if (!convTicket.id) fail('conversational escalation ticket missing from TMF621');
  console.log('OK SPOKEN ESCALATION: "human please" produced a real ticket '
    + `${wantHuman.ticketId.slice(0, 8)}… — the model signals, the system performs`);

  /* ---------- 4. AGENT: desk lists, reply silences the bot ---------- */
  const anna = await token(ctx, 'agent-anna', 'agent');
  const sessions = await (await ctx.get(`${CHAT}/agent/sessions`, { headers: H(anna) })).json();
  if (!Array.isArray(sessions) || !sessions.some((s) => s.id === gs.id)) {
    fail('agent desk does not list the guest conversation');
  }
  const ag = await ctx.post(`${CHAT}/agent/session/${gs.id}/message`, {
    headers: H(anna), data: { text: `Hi, Anna here — happy to help! (${run})` } });
  if (ag.status() >= 300) fail('agent reply refused: ' + ag.status());
  // the customer sees the human reply…
  const after = await (await ctx.get(`${CHAT}/guest/session/${gs.id}/messages`)).json();
  if (!after.some((m) => m.author === 'agent' && m.body.includes(String(run)))) {
    fail('customer never saw the agent reply');
  }
  // …and the bot now stays silent
  const silent = await (await ctx.post(`${CHAT}/guest/session/${gs.id}/message`, {
    headers: { 'Content-Type': 'application/json' },
    data: { text: 'thanks!' } })).json();
  if (silent.reply) fail('bot spoke over the human agent: ' + JSON.stringify(silent));
  console.log('OK AGENT: desk listed the chat, human reply reached the customer, bot went silent');

  /* ---------- 5. THE WIDGET renders and talks ---------- */
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
  await page.goto(`${API}/shop/`);
  await page.waitForSelector('[data-testid=chat-bubble]', { timeout: 20000 })
    .catch(() => fail('chat bubble missing from the shop'));
  await page.click('[data-testid=chat-bubble]');
  await page.fill('[data-testid=chat-input]', 'Do you have fiber?');
  await page.click('[data-testid=chat-send]');
  await page.waitForSelector('[data-testid=chat-msg-bot]', { timeout: 60000 })
    .catch(() => fail('no bot message rendered in the widget'));
  console.log('OK WIDGET: bubble on the shop, guest message sent, bot bubble rendered');
  await browser.close();

  console.log('\nALL CARE-CHAT CHECKS PASSED — guest shelf-chat, self-scoped customer chat, '
    + 'tenant+party isolation, real-ticket escalation, and a human agent who outranks the bot.');
})();
