/* The freeform drag-connect canvas editor for journeys.
 *   - toggle List -> Canvas; every step is a node on the surface
 *   - add a node from the palette
 *   - drag a node to reposition it (spatial layout)
 *   - drag from a node's out-port to another node to re-wire the run order
 *   - the same steps object serializes underneath (Create still works)
 */
const { chromium, request } = require('playwright');

const API = 'http://localhost:8080';
const CONSOLE = 'http://localhost:8080/console/';
const run = Date.now();
const JOURNEY = `${API}/tmf-api/campaignManagement/v4/journey`;

async function token(ctx, client, user, pass) {
  const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: client, username: user, password: pass } });
  return (await res.json()).access_token;
}
const mid = (b) => ({ x: b.x + b.width / 2, y: b.y + b.height / 2 });

(async () => {
  const apictx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const staff = await token(apictx, 'bss-demo', 'demo', 'demo');
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
  const stepsOf = async (page) => JSON.parse(await page.inputValue('[name="steps"]'));
  // scroll a node clear of the sticky header, into the middle of the viewport
  const centerNode = async (page, i) => { await page.locator('[data-testid="cnode"]').nth(i).evaluate((e) => e.scrollIntoView({ block: 'center' })); await page.waitForTimeout(150); };

  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1300, height: 1500 } });
  await page.goto(CONSOLE);
  await page.waitForSelector('#username, input[name="username"]', { timeout: 15000 });
  if (await page.locator('input[name="username"]').count()) {
    await page.fill('input[name="username"]', 'demo'); await page.fill('input[name="password"]', 'demo');
    await page.click('input[type="submit"], button[type="submit"]');
  }
  await page.waitForSelector('#main:not([hidden])', { timeout: 15000 });
  await page.locator('.tab', { hasText: 'Journeys' }).click();
  await page.waitForSelector('#ai-draft', { timeout: 15000 });

  /* ---------- draft a multi-node journey, then switch to Canvas ---------- */
  await page.fill('#ai-brief', `welcome new customers ${run} and nudge them to activate`);
  await page.click('#ai-draft');
  for (let i = 0; i < 25; i++) { try { if ((await stepsOf(page)).filter((s) => s.type === 'message').length >= 2) break; } catch {} await page.waitForTimeout(1000); }
  const drafted = (await stepsOf(page)).length;
  await page.click('[data-testid="view-canvas"]');
  await page.waitForSelector('[data-testid="cnode"]', { timeout: 8000 });
  if ((await page.locator('[data-testid="cnode"]').count()) !== drafted) fail('canvas node count != steps');
  console.log(`OK List -> Canvas: ${drafted} steps rendered as draggable nodes on the surface`);

  /* ---------- add a node from the palette ---------- */
  await page.click('[data-testid="palette-exit"]');
  await page.waitForTimeout(300);
  let steps = await stepsOf(page);
  if ((await page.locator('[data-testid="cnode"]').count()) !== drafted + 1 || steps[steps.length - 1].type !== 'exit') {
    fail('palette add did not append an exit node: ' + JSON.stringify(steps.map((s) => s.type)));
  }
  console.log(`OK the palette added an "exit" node — now ${drafted + 1} nodes`);

  /* ---------- drag a node to reposition it ---------- */
  await centerNode(page, 0);
  const n0 = page.locator('[data-testid="cnode"]').nth(0);
  const startLeft = await n0.evaluate((e) => e.style.left);
  const lab = await n0.locator('.cnlabel').boundingBox();
  await page.mouse.move(mid(lab).x, mid(lab).y); await page.mouse.down();
  await page.mouse.move(mid(lab).x + 150, mid(lab).y + 70, { steps: 10 }); await page.mouse.up();
  await page.waitForTimeout(150);
  const endLeft = await page.locator('[data-testid="cnode"]').nth(0).evaluate((e) => e.style.left);
  if (startLeft === endLeft) fail(`dragging a node did not move it (${startLeft})`);
  console.log(`OK a node dragged freely on the canvas (${startLeft} -> ${endLeft})`);

  /* ---------- drag from a node's out-port to another node = re-wire order ---------- */
  await centerNode(page, 1);
  // wire node #2's out-port to node #1 -> node #1 moves after node #2 (its step leaves index 0)
  const type0Before = (await stepsOf(page))[0].type;
  const out1 = await page.locator('[data-testid="cnode"]').nth(1).locator('[data-testid="cport-out"]').boundingBox();
  const node0Box = await page.locator('[data-testid="cnode"]').nth(0).boundingBox();
  await page.mouse.move(mid(out1).x, mid(out1).y); await page.mouse.down();
  await page.mouse.move(mid(node0Box).x, mid(node0Box).y, { steps: 12 }); await page.mouse.up();
  await page.waitForTimeout(300);
  steps = await stepsOf(page);
  if (steps[0].type === type0Before && steps[1].type !== type0Before) fail('re-wire did not change the order: ' + JSON.stringify(steps.map((s) => s.type)));
  console.log(`OK dragging a connection re-wired the run order (step #1 was "${type0Before}", now "${steps[0].type}")`);

  /* ---------- the canvas serializes: Create makes a real journey ---------- */
  const existing = new Set((await (await apictx.get(JOURNEY, { headers: H(staff) })).json()).map((j) => j.id));
  await page.selectOption('[name="status"]', 'draft');
  await page.click('#save');
  await page.waitForTimeout(2500);
  const list = await (await apictx.get(JOURNEY, { headers: H(staff) })).json();
  const made = list.find((j) => !existing.has(j.id));
  if (!made) fail('Create from the canvas did not make a journey');
  if (made.steps[made.steps.length - 1].type !== 'exit') fail('the created journey lost the canvas edits');
  console.log(`OK Create from the canvas made a real journey — ${made.steps.length} nodes`);

  await apictx.delete(`${JOURNEY}/${made.id}`, { headers: H(staff) });
  await browser.close();
  console.log('\nALL CANVAS-EDITOR CHECKS PASSED — a freeform drag-connect canvas: nodes drag on a surface, '
    + 'the palette adds them, dragging a port re-wires the run order, and it all serializes to a real journey.');
})();
