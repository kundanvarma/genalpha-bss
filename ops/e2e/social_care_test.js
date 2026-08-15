/* Social care: an inbound DM is a support conversation, not just chatter. The
 * CDP pulls direct messages, triages sentiment + intent, and for the ones that
 * need a human it REQUESTS a trouble ticket over the bus — insight never calls
 * the ticket service (no cross-service write scope); the trouble-ticket service
 * consumes SocialCareTicketRequested and opens a TMF621 case. A happy DM opens
 * nothing. Re-syncing is idempotent — no duplicate tickets.
 */
const { chromium, request } = require('playwright');

const API = 'http://localhost:8080';
const CONSOLE = 'http://localhost:8080/console/';
const SOCIAL = 'http://localhost:8122';
const ACCOUNT = 'genalpha-brand';           // the insight tenant's brand handle
const run = Date.now();
const CARE = `${API}/insight/v1/care`;
const TICKETS = `${API}/tmf-api/troubleTicket/v4/troubleTicket?limit=100`;

async function token(ctx) {
  const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const staff = await token(ctx);
  const H = { Authorization: 'Bearer ' + staff, 'Content-Type': 'application/json' };

  /* ---------- three DMs land: a complaint, a support ask, and praise ---------- */
  const seed = async (author, text) => {
    const r = await ctx.post(`${SOCIAL}/v1/${ACCOUNT}/dms`, { data: { author, handle: '@' + author, text } });
    return `social-dm:x:${(await r.json()).id}`;
  };
  const angry = await seed(`pat-${run}`, 'your service is terrible, my internet has been down all day');
  const asking = await seed(`sam-${run}`, `hi, how do I reset my password?`);
  const happy = await seed(`hana-${run}`, 'love the new plan, thanks so much!');
  console.log('OK three inbound DMs seeded: a complaint, a support ask, and a compliment');

  /* ---------- the CDP triages and requests tickets for the two that need a human ---------- */
  // mock-social accumulates DMs across runs (in-memory), so counts are aggregate;
  // this run's exactly-2 routing is proven per-DM below (run-unique authors).
  const sync = await (await ctx.post(`${CARE}/sync`, { headers: H })).json();
  if (!sync.enabled) fail('care sync is not wired to the social platform');
  if (sync.ingested < 3 || sync.ticketsRequested < 2) fail('sync did not triage this run\'s DMs: ' + JSON.stringify(sync));
  console.log(`OK triage: ${sync.ingested} DMs ingested, ${sync.ticketsRequested} routed to care (the happy one is not among them)`);

  const summary = await (await ctx.get(`${CARE}/summary`, { headers: H })).json();
  if (summary.needCare < 2) fail('care summary undercounts the queue: ' + JSON.stringify(summary));

  /* ---------- the trouble-ticket service opened cases from the events ---------- */
  const ticketFor = async (src) => {
    for (let i = 0; i < 25; i++) {
      const list = await (await ctx.get(TICKETS, { headers: H })).json();
      const arr = Array.isArray(list) ? list : (list.content || []);
      const hit = arr.find((t) => Array.isArray(t.relatedEntity)
        && t.relatedEntity.some((e) => e.id === src));
      if (hit) return hit;
      await sleep(800);
    }
    return null;
  };

  const patTicket = await ticketFor(angry);
  if (!patTicket) fail('the complaint DM never opened a trouble ticket');
  if (patTicket.ticketType !== 'socialCare') fail('ticket is not typed socialCare: ' + patTicket.ticketType);
  if (patTicket.severity !== 'major') fail('a negative-sentiment DM should open a MAJOR ticket, got: ' + patTicket.severity);
  console.log(`OK the complaint opened a MAJOR socialCare ticket (${patTicket.id}) — reply handle carried on relatedEntity`);

  const samTicket = await ticketFor(asking);
  if (!samTicket) fail('the support-ask DM never opened a trouble ticket');
  if (samTicket.severity !== 'minor') fail('a support ask should open a MINOR ticket, got: ' + samTicket.severity);
  console.log(`OK the support ask opened a MINOR socialCare ticket (${samTicket.id})`);

  const happyTicket = await ticketFor(happy);
  if (happyTicket) fail('a happy DM should NOT open a ticket, but did: ' + happyTicket.id);
  console.log('OK the compliment opened NO ticket — care queue is not spammed with praise');

  /* ---------- re-sync is idempotent: no duplicate tickets ---------- */
  const resync = await (await ctx.post(`${CARE}/sync`, { headers: H })).json();
  if (resync.ticketsRequested !== 0 || resync.ingested !== 0) fail('re-sync re-ingested/re-ticketed: ' + JSON.stringify(resync));
  await sleep(1500);
  const after = await (await ctx.get(TICKETS, { headers: H })).json();
  const arr = Array.isArray(after) ? after : (after.content || []);
  const dupes = arr.filter((t) => Array.isArray(t.relatedEntity) && t.relatedEntity.some((e) => e.id === angry));
  if (dupes.length !== 1) fail('idempotency broke — the complaint has ' + dupes.length + ' tickets');
  console.log('OK re-sync opened nothing new — one DM, one ticket (at-least-once dedup holds)');

  /* ================= the console Social care pane ================= */
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1200, height: 1200 } });
  await page.goto(CONSOLE);
  await page.waitForSelector('#username, input[name="username"]', { timeout: 15000 });
  if (await page.locator('input[name="username"]').count()) {
    await page.fill('input[name="username"]', 'demo'); await page.fill('input[name="password"]', 'demo');
    await page.click('input[type="submit"], button[type="submit"]');
  }
  await page.waitForSelector('#main:not([hidden])', { timeout: 15000 });
  await page.locator('.tab', { hasText: 'Social care' }).click();
  await page.waitForSelector('[data-testid="social-care"]', { timeout: 10000 });
  await page.locator('[data-testid="care-sync"]').click();
  await page.waitForFunction(() => (document.querySelector('[data-testid="care-summary"]')?.textContent || '').includes('needs care'), { timeout: 10000 });
  await page.waitForSelector('[data-testid="care-row"]', { timeout: 10000 });
  const opened = await page.locator('[data-testid="care-row"]', { hasText: 'ticket opened' }).count();
  if (opened < 1) fail('the console care queue shows no ticket-opened row');
  console.log(`OK the console Social care pane shows the triaged queue with ${opened} ticket(s) opened`);
  await browser.close();

  console.log('\nALL SOCIAL-CARE CHECKS PASSED — inbound DMs are triaged by the CDP and negative/support '
    + 'ones become TMF621 trouble tickets over the bus (no cross-service write scope), while praise is left '
    + 'in the listening feed. One DM opens exactly one case, even under re-delivery.');
})();
