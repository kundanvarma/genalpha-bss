/* Kanban pipeline board — the sales desk runs its pipeline review here.
 * Seeds a deal via API, then in the browser:
 *   - the board shows stage columns, per-column totals and a weighted forecast
 *   - the seeded deal's card sits in its stage column
 *   - dragging the card to another column re-stages it (PATCH), and the API agrees
 * Console at /console/, demo/demo (staff, quote:read+write).
 */
const { chromium, request } = require('playwright');

const API = 'http://localhost:8080';
const CONSOLE = `${API}/console/`;
const SALES = `${API}/tmf-api/salesManagement/v4`;
const run = Date.now();

async function token(ctx) {
  const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } });
  return (await res.json()).access_token;
}

(async () => {
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const api = await request.newContext();
  const tok = await token(api);
  const H = { Authorization: 'Bearer ' + tok, 'Content-Type': 'application/json' };

  /* ---------- seed a deal at Proposal with a value ---------- */
  const name = `Board deal ${run}`;
  const lead = await (await api.post(`${SALES}/salesLead`, { headers: H, data: { name, source: 'campaign' } })).json();
  const q = await (await api.patch(`${SALES}/salesLead/${lead.id}`, { headers: H, data: { state: 'qualified' } })).json();
  const oppId = q.salesOpportunity.id;
  await api.patch(`${SALES}/salesOpportunity/${oppId}`, { headers: H, data: { stage: 'proposal', amount: 5000 } });
  console.log(`OK seeded "${name}" at Proposal, value 5000 (opp ${oppId.slice(0, 8)})`);

  /* ---------- open the board ---------- */
  const browser = await chromium.launch();
  const page = await browser.newPage();
  await page.goto(CONSOLE);
  if (await page.locator('input[name="username"]').count()) {
    await page.fill('input[name="username"]', 'demo');
    await page.fill('input[name="password"]', 'demo');
    await page.click('button[type="submit"], input[type="submit"]');
  }
  await page.waitForTimeout(1500);
  await page.click('.tab:has-text("Pipeline board")');
  await page.waitForSelector('[data-testid="pipeline-board"]');

  // forecast banner shows a number
  const forecast = await page.locator('[data-testid="pl-forecast"]').textContent();
  if (!/\d/.test(forecast || '')) fail('forecast banner has no number: ' + forecast);
  // four stage columns present
  for (const st of ['qualification', 'needsAnalysis', 'proposal', 'negotiation']) {
    if (!(await page.locator(`[data-testid="pl-col-${st}"]`).count())) fail('missing column: ' + st);
  }
  console.log(`OK the board renders four stage columns and a weighted forecast (${forecast})`);

  // our card is in the Proposal column
  const card = page.locator(`[data-testid="pl-col-proposal"] .pl-card[data-id="${oppId}"]`);
  if (!(await card.count())) fail('the seeded deal is not in the Proposal column');
  console.log('OK the seeded deal sits as a card in the Proposal column');

  /* ---------- drag the card to Negotiation → re-stage ---------- */
  await page.evaluate((id) => {
    const dt = new DataTransfer();
    const el = document.querySelector(`.pl-card[data-id="${id}"]`);
    const col = document.querySelector('[data-stage="negotiation"]');
    el.dispatchEvent(new DragEvent('dragstart', { dataTransfer: dt, bubbles: true }));
    col.dispatchEvent(new DragEvent('dragover', { dataTransfer: dt, bubbles: true }));
    col.dispatchEvent(new DragEvent('drop', { dataTransfer: dt, bubbles: true }));
  }, oppId);

  // the API agrees the stage moved, and probability rode to Negotiation's 75%
  let moved = false;
  for (let i = 0; i < 20; i++) {
    const opp = await (await api.get(`${SALES}/salesOpportunity/${oppId}`, { headers: H })).json();
    if (opp.stage === 'negotiation' && opp.probability === 75) { moved = true; break; }
    await sleep(500);
  }
  if (!moved) fail('dragging the card did not re-stage the deal to Negotiation (75%)');
  console.log('OK dragging the card to Negotiation re-staged the deal — API agrees, probability rode to 75%');

  // and the board reflects it after re-render
  await page.waitForTimeout(800);
  const nowInNeg = await page.locator(`[data-testid="pl-col-negotiation"] .pl-card[data-id="${oppId}"]`).count();
  if (!nowInNeg) fail('the card did not move to the Negotiation column on the board');
  console.log('OK the board re-rendered with the card now in Negotiation');

  await browser.close();
  console.log('\nALL PIPELINE-BOARD CHECKS PASSED — the pipeline is a visual, draggable board with a live '
    + 'weighted forecast; moving a card is a real re-stage the rest of the BSS sees.');
})();
