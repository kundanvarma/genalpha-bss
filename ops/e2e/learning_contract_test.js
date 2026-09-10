/* #122 learning_contract_test — continuous learning, phases 3 + 4: a Learning Contract per
 * decision point (objective, guardrails, allowed actions, exploration cap, autonomy, fallback,
 * pause) is configuration the seam applies on every decision and every receipt cites; the
 * console shows the decision log with receipts and edits the contracts with a dry run.
 * Taranga tenant; the ENet tenant sees none of it. */
const { chromium } = require('playwright');
const API = 'http://localhost:8080';
const run = Date.now();
const fail = (m) => { throw new Error(m); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function token(realm, user, pass) {
  const r = await fetch(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: user, password: pass }) });
  if (!r.ok) fail(`token: ${r.status}`);
  return (await r.json()).access_token;
}
async function call(method, path, tok, body) {
  const r = await fetch(API + path, { method, headers: { Authorization: `Bearer ${tok}`, ...(body ? { 'Content-Type': 'application/json' } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) });
  const text = await r.text(); let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: r.status, body: json, text };
}
async function until(what, fn, tries = 30) {
  for (let i = 0; i < tries; i++) { const v = await fn(); if (v) return v; await sleep(2000); }
  fail(`timed out waiting for ${what}`);
}
const C = '/tmf-api/campaignManagement/v4/learningContract';
const J = '/tmf-api/campaignManagement/v4/journey';
const D = '/insight/v1/decisions';
const POINT = 'journey.enrolment';

let cleanup = async () => {};
(async () => {
  const staff = await token('taranga', 'demo', 'demo');
  await call('DELETE', `${C}/${POINT}`, staff); // a clean slate from earlier runs
  // a contract applies to EVERY journey of the tenant — leave nothing behind, pass or fail
  cleanup = async () => { await call('DELETE', `${C}/${POINT}`, staff); };

  /* 1. every point has a contract view — defaults until someone writes one */
  const list = await call('GET', C, staff);
  if (list.status !== 200 || !Array.isArray(list.body) || list.body.length < 4) fail(`contracts: ${list.status} ${list.text.slice(0, 120)}`);
  const before = list.body.find((p) => p.name === POINT);
  if (!before.contract.defaults || before.contract.version !== 0) fail(`expected defaults: ${JSON.stringify(before.contract)}`);
  console.log(`  ${list.body.length} decision points; ${POINT} on defaults (objective ${before.contract.objective})`);

  /* 2. write the contract: allowed actions without B, exploration capped at 10 %, autonomy low */
  const put = await call('PUT', `${C}/${POINT}`, staff, { objective: 'conversion', secondaryMetrics: ['revenue'],
    guardrails: ['no marketing without consent', 'never more than one holdout per customer'], allowedActions: ['holdout', 'A'],
    explorationMaxPercent: 10, autonomy: 'low', notes: `suite ${run}` });
  if (put.status !== 200 || put.body.contract.version !== 1 || put.body.contract.defaults) fail(`put: ${put.status} ${put.text.slice(0, 200)}`);
  const ref = put.body.contract.ref;
  const bad = await call('PUT', `${C}/${POINT}`, staff, { explorationMaxPercent: 95 });
  if (bad.status !== 400) fail(`a cap above 90 must be refused, got ${bad.status}`);
  console.log(`  contract ${ref}: allowed [holdout, A], cap 10 %, autonomy low; a 95 % cap is refused (400)`);

  /* 3. dry run: what the point would decide — nothing recorded */
  const dry = await call('POST', `${C}/${POINT}/dryRun`, staff, { subjectId: 'dry-1', candidates: ['holdout', 'A', 'B'],
    context: { partyId: 'dry-1', holdoutPercent: 40, seed: 'dry' } });
  if (dry.status !== 200 || !dry.body.dryRun || dry.body.decisionId) fail(`dry run: ${dry.status} ${dry.text.slice(0, 200)}`);
  if (dry.body.eligibleActions.includes('B') || dry.body.context.holdoutPercent !== 10 || dry.body.autonomy !== 'low' || dry.body.contract !== ref) fail(`dry run ignores the contract: ${JSON.stringify(dry.body).slice(0, 300)}`);
  if (!dry.body.constraints.some((c) => c.startsWith('learning-contract: B')) || !dry.body.constraints.some((c) => c.includes('capped at 10 %'))) fail(`constraints: ${JSON.stringify(dry.body.constraints)}`);
  console.log(`  dry run: "${dry.body.action}" of [${dry.body.eligibleActions}] — ${dry.body.constraints.join('; ')}`);

  /* 4. a real journey asks for 40 % holdout and a B arm; the seam applies the contract and the receipt cites it */
  const j = await call('POST', J, staff, { name: `Contract ${run}`, triggerEventType: 'NeverFiresEvent', holdoutPercent: 40,
    arms: [{ name: 'A', subject: `A ${run}`, content: 'A' }, { name: 'B', subject: `B ${run}`, content: 'B' }],
    steps: [{ type: 'message', stage: 'Nudge', channel: 'inApp', subject: 'x', content: 'x' }] });
  if (j.status >= 300) fail(`journey: ${j.status}`);
  const parties = Array.from({ length: 40 }, (_, i) => `lc-${run}-${i}`);
  const en = (await call('POST', `${J}/${j.body.id}/enrollments`, staff, { partyIds: parties })).body;
  const dealt = Object.values(en.dealt);
  if (dealt.includes('B')) fail('B was dealt although the contract does not allow it');
  const heldShare = dealt.filter((v) => v === 'holdout').length / dealt.length;
  if (heldShare > 0.25) fail(`holdout share ${heldShare} — the 10 % cap did not bite`);
  const treated = parties.find((p) => en.dealt[p] === 'A');
  const rec = await until('the contracted decision', async () => {
    const r = await call('GET', `${D}?decisionPoint=${POINT}&subjectId=${treated}`, staff);
    return r.status === 200 && r.body.find((d) => d.context && d.context.journeyId === j.body.id);
  });
  if (rec.contract !== ref || rec.autonomy !== 'low' || rec.eligibleActions.includes('B') || rec.context.holdoutPercent !== 10) fail(`record: ${JSON.stringify(rec).slice(0, 300)}`);
  if (Math.abs(rec.propensity - 0.9) > 0.001) fail(`propensity ${rec.propensity} — with B removed, A takes every treated customer (0.9)`);
  const receipt = (await call('GET', `${D}/${rec.decisionId}`, staff)).body;
  if (!receipt.receipt.some((l) => l.includes(`under learning contract ${ref}`))) fail(`receipt does not cite the contract: ${JSON.stringify(receipt.receipt)}`);
  console.log(`  live: 40 enrolled, none dealt B, holdout ${Math.round(heldShare * 100)} %, propensity ${rec.propensity}; receipt cites ${ref}`);

  /* 5. pause: the fallback answers every decision, and says so */
  const pause = await call('PUT', `${C}/${POINT}`, staff, { objective: 'conversion', allowedActions: ['holdout', 'A'], explorationMaxPercent: 10,
    autonomy: 'low', fallbackAction: 'A', enabled: false });
  if (pause.status !== 200 || pause.body.contract.version !== 2 || pause.body.contract.enabled !== false) fail(`pause: ${pause.text.slice(0, 200)}`);
  const more = Array.from({ length: 10 }, (_, i) => `lp-${run}-${i}`);
  const en2 = (await call('POST', `${J}/${j.body.id}/enrollments`, staff, { partyIds: more })).body;
  if (Object.values(en2.dealt).some((v) => v !== 'A')) fail(`paused point should deal the fallback A to everyone: ${JSON.stringify(en2.dealt)}`);
  const paused = await until('the paused decision', async () => {
    const r = await call('GET', `${D}?decisionPoint=${POINT}&subjectId=${more[0]}`, staff);
    return r.status === 200 && r.body.find((d) => d.context && d.context.journeyId === j.body.id);
  });
  if (!paused.fallback || paused.contract !== `${put.body.contract.id}@2` || !paused.reason.includes('paused')) fail(`paused record: ${JSON.stringify(paused).slice(0, 300)}`);
  console.log(`  paused (v2): all 10 new customers got the fallback "A"; the record says "${paused.reason}"`);

  /* 6. the console: Decisions with a receipt, Learning contracts with the editor and the dry run */
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  await page.goto('http://console.taranga.localhost:8080/console/'); // the Taranga console, not the default tenant's
  await page.waitForSelector('input[name="username"]', { timeout: 20000 });
  await page.fill('input[name="username"]', 'demo'); await page.fill('input[name="password"]', 'demo');
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('#main:not([hidden])', { timeout: 20000 });
  await page.waitForSelector('#tabs .tab', { timeout: 10000 });
  await page.locator('#tabs .tab', { hasText: 'Decisions' }).first().click();
  await page.locator('[data-testid=decisions-pane]').waitFor({ timeout: 20000 });
  await page.locator('[data-testid=kpi]').first().waitFor({ timeout: 20000 });
  await page.fill('[data-testid=decisions-subject]', treated);
  await page.click('[data-testid=decisions-show]');
  const row = page.locator('[data-testid=decision-row]').first();
  await row.waitFor({ timeout: 20000 });
  await row.click();
  await page.locator('[data-testid=decision-receipt] li').first().waitFor({ timeout: 10000 });
  const lines = await page.locator('[data-testid=decision-receipt] li').allTextContents();
  if (!lines.some((l) => l.startsWith('It chose: message variant A')) || !lines.some((l) => l.includes('under learning contract version 1'))) fail(`console receipt: ${JSON.stringify(lines)}`);
  if (lines.some((l) => /z-threshold|holdout-then|minPerArm|propensity/.test(l.replace(/\(rule “[^”]*”, version \d+\)/, '')))) fail(`receipt speaks machine: ${JSON.stringify(lines)}`);
  const kpis = await page.locator('[data-testid=kpi]').allTextContents();
  console.log(`  console Decisions: KPIs ${kpis.join(' · ')}; receipt for ${treated} reads ${lines.length} lines`);

  await page.locator('#tabs .tab', { hasText: 'Learning contracts' }).first().click();
  await page.locator('[data-testid=contract-pane], [data-testid=contracts-pane]').first().waitFor({ timeout: 20000 });
  const crow = page.locator(`[data-testid=contract-row][data-point="${POINT}"]`);
  await crow.waitFor({ timeout: 10000 });
  const state = await crow.locator('[data-testid=contract-state]').textContent();
  if (!state.includes('Version 2') || !state.includes('PAUSED')) fail(`contract row state: ${state}`);
  const sentence = await crow.locator('.contract-sentence').textContent();
  if (!sentence.includes('only the control group, variant A') || !sentence.includes('at most 10 %')) fail(`contract sentence: ${sentence}`);
  await crow.locator('[data-testid=contract-edit]').click();
  await page.locator('[data-testid=contract-editor]').waitFor({ timeout: 5000 });
  if (await page.isChecked('[data-testid=contract-enabled]')) fail('editor should show the point paused');
  await page.check('[data-testid=contract-enabled]');
  await page.check('[data-testid=contract-allowed-any]');
  await page.fill('[data-testid=contract-cap]', '20');
  await page.click('[data-testid=contract-save]');
  await page.locator('[data-testid=contract-msg]', { hasText: 'saved as version 3' }).waitFor({ timeout: 10000 });
  await page.selectOption('[data-testid=dryrun-journey]', { label: `Contract ${run}` });
  await page.fill('[data-testid=dryrun-subject]', `try-${run}`);
  await page.click('[data-testid=dryrun-run]');
  await page.locator('[data-testid=dryrun-result]', { hasText: 'would get' }).waitFor({ timeout: 10000 });
  const dryText = await page.locator('[data-testid=dryrun-result]').textContent();
  if (!dryText.includes('capped at 20 %') || !dryText.includes('learning contract version 3')) fail(`console dry run: ${dryText}`);
  console.log(`  console Learning contracts: "${sentence}" → edited to v3 (running, any variant, cap 20 %); try-it says: ${dryText}`);
  await browser.close();

  /* 7. another tenant reads and writes nothing of it */
  const enet = await token('enet', 'demo', 'demo');
  const other = await call('GET', `${C}/${POINT}`, enet);
  if (other.status !== 200 || !other.body.contract.defaults) fail(`ENet should see its own defaults, got ${other.status} ${JSON.stringify(other.body?.contract).slice(0, 100)}`);
  console.log('  isolation: the ENet tenant sees only its own defaults');

  await cleanup();
  console.log('PASS learning_contract_test');
})().catch(async (e) => { await cleanup().catch(() => {}); console.error('FAIL:', e.message); process.exit(1); });
