/* TMF699 Sales Management — the funnel BEFORE anyone is a customer.
 *
 *  - a PROSPECT (no account, no token) uses the storefront's "Talk to
 *    sales" form; the gateway's hostname mapping decides the tenant
 *  - staff see the lead in the console list, QUALIFY it — which mints a
 *    salesOpportunity — and close the opportunity WON with a quote ref
 *  - decisions are final: a lead qualifies once, a closed deal stays
 *    closed; and nova never sees genalpha's pipeline
 */
const { chromium, request } = require('playwright');

const API = 'http://localhost:8080';
const SALES = `${API}/tmf-api/salesManagement/v4`;
const run = Date.now();

async function token(ctx, realm, user, pass) {
  const res = await ctx.post(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`,
    { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const browser = await chromium.launch();
  const page = await (await browser.newContext()).newPage();
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
  const need = `Fleet of 40 SIMs ${run}`;

  /* ---------- the prospect knocks: storefront form, no account ---------- */
  await page.goto('http://localhost:8080/shop/');
  await page.waitForSelector('[data-testid="talk-to-sales"]', { timeout: 30000 });
  await page.fill('[data-testid="sales-name"]', 'Pia Prospect');
  await page.fill('[data-testid="sales-email"]', `pia-${run}@vanco.example`);
  await page.fill('[data-testid="sales-company"]', 'VanCo Logistics');
  await page.fill('[data-testid="sales-need"]', need);
  await page.click('[data-testid="sales-submit"]');
  await page.waitForSelector('[data-testid="sales-thanks"]', { timeout: 15000 });
  console.log('OK a prospect with NO account asked for sales through the storefront');

  /* ---------- staff work the lead ---------- */
  const staff = await token(ctx, 'bss', 'demo', 'demo');
  const leads = await (await ctx.get(`${SALES}/salesLead`, { headers: H(staff) })).json();
  const lead = leads.find((l) => l.name === need);
  if (!lead) fail('the lead never reached the pipeline');
  if (lead.state !== 'acknowledged') fail('a fresh lead must be acknowledged: ' + lead.state);
  if (lead.source !== 'storefront' || lead.company !== 'VanCo Logistics') {
    fail('the lead lost its story: ' + JSON.stringify(lead));
  }
  console.log('OK the lead is in the pipeline — acknowledged, with the prospect\'s story intact');

  const qualified = await (await ctx.patch(`${SALES}/salesLead/${lead.id}`,
    { headers: H(staff), data: { state: 'qualified' } })).json();
  if (qualified.state !== 'qualified' || !qualified.salesOpportunity?.id) {
    fail('qualifying must mint an opportunity: ' + JSON.stringify(qualified));
  }
  console.log('OK QUALIFIED: the lead minted a salesOpportunity — suspect became prospect-with-a-deal');

  /* decisions are final */
  const again = await ctx.patch(`${SALES}/salesLead/${lead.id}`,
    { headers: H(staff), data: { state: 'unqualified' } });
  if (again.status() !== 400) fail('a decided lead was re-litigated: ' + again.status());

  /* ---------- the opportunity closes WON, with the quote that sealed it ---------- */
  const oppId = qualified.salesOpportunity.id;
  const won = await (await ctx.patch(`${SALES}/salesOpportunity/${oppId}`,
    { headers: H(staff), data: { state: 'won', quote: { id: `q-${run}` } } })).json();
  if (won.state !== 'won' || won.quote?.id !== `q-${run}`) {
    fail('winning lost the quote ref: ' + JSON.stringify(won));
  }
  const reopen = await ctx.patch(`${SALES}/salesOpportunity/${oppId}`,
    { headers: H(staff), data: { state: 'lost' } });
  if (reopen.status() !== 400) fail('a closed deal was reopened: ' + reopen.status());
  console.log('OK WON with the quote that sealed it — and closed deals stay closed');

  /* ---------- the tenant wall ---------- */
  const novaStaff = await token(ctx, 'nova', 'demo', 'demo');
  const novaLeads = await (await ctx.get(`${SALES}/salesLead`, { headers: H(novaStaff) })).json();
  if (novaLeads.some((l) => l.name === need)) fail('genalpha\'s pipeline leaked into nova');
  console.log('OK the tenant wall: nova cannot see genalpha\'s pipeline');

  await browser.close();
  console.log('\nALL SALES CHECKS PASSED — TMF699 closes the loop: marketing makes the interest,'
    + ' the storefront catches it, sales qualifies it into an opportunity and wins it with a'
    + ' quote. The BSS is the sales tool.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
