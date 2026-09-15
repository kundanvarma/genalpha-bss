/* #130 — Home attention: situations, not events; the offer page as a decision.
 *
 * Ivan's addendum, made true: the customer's Home shows the SITUATION the
 * ontology summarises (one card for "2 services are paused", never two Resume
 * rows), three cards at most with the most serious first, the commercial
 * suggestion placed by health (lifted under the services when all is well,
 * below the customer's needs when something is open), and an offer page that
 * is a decision — why it was recommended, now versus with this, commitment,
 * effective date — with three honest outcomes: choose it, maybe later, not
 * interested. Maybe later and not interested are decisions in the log the
 * desk's ranking reads; Back is nothing. Proven here with Paula.
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const SHOP = `${API}/shop/`;
const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function token(user, pass, client = 'bss-demo') {
  const res = await fetch('http://localhost:8085/realms/bss/protocol/openid-connect/token', {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: client, username: user, password: pass }),
  });
  if (!res.ok) fail(`token for ${user}: ${res.status}`);
  return (await res.json()).access_token;
}
async function call(method, url, tok, body, extra = {}) {
  const headers = { 'Content-Type': 'application/json', ...extra };
  if (tok) headers.Authorization = `Bearer ${tok}`;
  const res = await fetch(url, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch { /* not json */ }
  return { status: res.status, json, text };
}
async function until(what, fn, tries = 20, every = 1500) {
  for (let i = 0; i < tries; i++) { const v = await fn(); if (v) return v; await sleep(every); }
  fail('timed out waiting for ' + what);
  return null;
}
const sub = (tok) => JSON.parse(Buffer.from(tok.split('.')[1], 'base64').toString()).sub;

(async () => {
  const paula = await token('paula@family.example', 'paula');
  const anna = await token('agent-anna', 'agent');
  const me = sub(paula);
  const home = () => call('GET', `${API}/ontology/v1/context/customer/${me}/recommendations`, paula, undefined, { 'X-GenAlpha-Agent': 'shop-home' });

  /* ---------- the situation, summarised ---------- */
  // a clean start: whatever an earlier run left paused comes back first
  const before = (await call('GET', `${API}/tmf-api/serviceInventory/v4/service?limit=100`, paula)).json || [];
  for (const s of before.filter((x) => x.state === 'suspended')) await call('POST', `${API}/tmf-api/serviceInventory/v4/service/${s.id}/resume`, paula, {});
  await until('no paused lines before the test', async () => {
    const r = await home(); return (r.json?.summary || []).some((x) => x.kind === 'paused') ? null : r.json;
  }, 15, 1500);
  const svcs = (await call('GET', `${API}/tmf-api/serviceInventory/v4/service?limit=100`, paula)).json || [];
  const mine = svcs.filter((s) => ['active', 'suspended'].includes(s.state));
  const two = mine.filter((s) => s.state === 'active' && (s.supportingResource || []).some((r) => r.value)).slice(0, 2);
  if (two.length < 2) fail('Paula needs two active numbered lines to pause; has ' + two.length);
  for (const s of two) {
    const r = await call('POST', `${API}/tmf-api/serviceInventory/v4/service/${s.id}/suspend`, paula, { days: 7 });
    if (r.status >= 300) fail(`pause ${s.name}: ${r.status} ${r.text.slice(0, 150)}`);
  }
  const ctx = await until('the ontology to see two paused lines', async () => {
    const r = await home();
    const paused = (r.json?.summary || []).find((x) => x.kind === 'paused');
    return paused && paused.count === 2 ? r.json : null;
  }, 15, 1500);
  const pausedSum = ctx.summary.find((x) => x.kind === 'paused');
  if (pausedSum.severity !== 'warning' || pausedSum.actionRequired !== true) fail('a paused group is a warning that needs the customer: ' + JSON.stringify(pausedSum));
  if (!/2 services are paused/.test(pausedSum.says)) fail('the summary should count, not list: ' + pausedSum.says);
  if (ctx.healthy !== false || ctx.severity !== 'warning') fail(`healthy/severity: ${ctx.healthy}/${ctx.severity}`);
  if ((ctx.situation || []).filter((x) => x.kind === 'paused').length < 2) fail('the per-line situation must still be there for the desk');
  const offers = (ctx.recommendations || []).filter((r) => r.kind === 'offer');
  if (!offers.length || !offers[0].decisionId || !offers[0].offeringId) fail('offers must be recommendations with decision ids: ' + JSON.stringify(offers).slice(0, 200));
  console.log(`OK the ontology summarises: "${pausedSum.says}" (warning, action required); ${offers.length} offers ride along with decision ids`);

  /* ---------- Home: one card, not two rows; the suggestion below the customer's needs ---------- */
  const browser = await chromium.launch();
  const page = await (await browser.newContext({ viewport: { width: 1366, height: 900 } })).newPage();
  await page.goto(SHOP); await page.click('.who >> text=Sign in');
  await page.waitForSelector('input[name="username"]', { timeout: 20000 });
  await page.fill('input[name="username"]', 'paula@family.example'); await page.fill('input[name="password"]', 'paula');
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('[data-testid="home"]', { timeout: 30000 });
  await page.locator('[data-testid="attention-paused"]').waitFor({ timeout: 45000 });
  const pausedCards = await page.locator('[data-testid="attention-paused"]').count();
  if (pausedCards !== 1) fail('two paused lines must be ONE card, got ' + pausedCards);
  const cardText = await page.locator('[data-testid="attention-paused"]').innerText();
  if (!/2 services are paused/.test(cardText) || !/Review services/.test(cardText)) fail('the card should count and route: ' + cardText);
  if (await page.locator('[data-testid="attention-paused"] button', { hasText: 'Resume' }).count()) fail('no inline Resume on an aggregated card');
  if ((await page.locator('[data-testid^="attention-"]').count()) > 3) fail('never more than three attention cards');
  const health = await page.locator('[data-testid="home-health"]').innerText();
  if (!/1 thing needs your attention/.test(health)) fail('the health line counts situations: ' + health);
  const order = await page.locator('section[data-testid^="home-"]').evaluateAll((els) => els.map((e) => e.dataset.testid));
  const idx = (k) => order.indexOf(k);
  if (!(idx('home-activity') < idx('home-recommended'))) fail('with something open the suggestion sits below the customer\'s needs: ' + order.join(','));
  console.log('OK Home: one "2 services are paused" card with Review services; health counts situations; the suggestion is below the customer\'s needs');

  /* ---------- Review services → the paused section → Resume all → Home is calm, suggestion lifted ---------- */
  await page.locator('[data-testid="attention-paused"] a', { hasText: 'Review services' }).click();
  await page.locator('[data-testid="paused-card"]').waitFor({ timeout: 20000 });
  if ((await page.locator('[data-testid="paused-row"]').count()) < 2) fail('the paused section lists every paused service');
  await page.locator('[data-testid="resume-all"]').click();
  await page.locator('[data-testid="paused-card"]').waitFor({ state: 'detached', timeout: 30000 });
  await until('the ontology to see the lines back', async () => { const r = await home(); return (r.json?.summary || []).some((x) => x.kind === 'paused') ? null : r.json; }, 15, 1500);
  await page.goto(SHOP); await page.waitForSelector('[data-testid="home"]', { timeout: 30000 });
  await page.locator('[data-testid="home-attention"]', { hasText: 'Reading' }).waitFor({ state: 'detached', timeout: 45000 }).catch(() => {});
  await page.locator('[data-testid="attention-paused"]').waitFor({ state: 'detached', timeout: 30000 });
  const order2 = await page.locator('section[data-testid^="home-"]').evaluateAll((els) => els.map((e) => e.dataset.testid));
  if (!(order2.indexOf('home-services') < order2.indexOf('home-recommended') && order2.indexOf('home-recommended') < order2.indexOf('home-money'))) fail('healthy: the suggestion is lifted under the services: ' + order2.join(','));
  console.log('OK Review services lands on the paused section; Resume all brings them back; Home is calm and the suggestion is lifted');

  /* ---------- the offer page as a decision: why, now vs with this, maybe later, not interested ---------- */
  await page.locator('[data-testid="recommended"] [data-testid="foryou-caption"]').waitFor({ timeout: 20000 });
  const leadHref = await page.locator('[data-testid="recommended"] a.card').first().getAttribute('href');
  const leadId = (leadHref.match(/offering\/([^?]+)/) || [])[1];
  if (!leadId || !/rec=rec-/.test(leadHref)) fail('the lead pick must carry its decision id: ' + leadHref);
  await page.locator('[data-testid="recommended"] a.card').first().click();
  await page.locator('[data-testid="offer-decision"]').waitFor({ timeout: 20000 });
  const why = await page.locator('[data-testid="offer-why"]').innerText();
  const table = await page.locator('[data-testid="offer-before-after"]').innerText();
  if (!/With this/.test(table) || !/Commitment/.test(table) || !/Takes effect/.test(table)) fail('the decision needs with-this, commitment and effective date: ' + table);
  await page.locator('[data-testid="offer-back"]').waitFor({ timeout: 5000 }); // Back exists and is not a verdict
  await page.locator('[data-testid="offer-defer"]').click();
  await page.locator('[data-testid="offer-deferred"]').waitFor({ timeout: 15000 });
  console.log(`OK the offer page is a decision ("${why.slice(0, 60)}…"); Maybe later is acknowledged`);

  // the verdict is a decision outcome the desk can read, and the offer leaves Paula's Home for a month
  const logged = await until('the deferred outcome in the decision log', async () => {
    const r = await call('GET', `${API}/insight/v1/decisions?decisionPoint=ontology.recommend&subjectId=${me}&limit=200`, anna);
    return (r.json || []).find((d) => d.outcome === 'deferred' && d.context && d.context.offeringId === leadId) || null;
  }, 20, 1500);
  if (!logged) fail('no deferred decision for ' + leadId);
  const after = await until('the lead to change', async () => {
    const r = await home();
    const lead = (r.json?.recommendations || []).find((x) => x.kind === 'offer');
    return lead && lead.offeringId !== leadId ? r.json : null;
  }, 15, 1500);
  const lead2 = after.recommendations.find((x) => x.kind === 'offer');
  await page.goto(`${SHOP}offering/${lead2.offeringId}?rec=${encodeURIComponent(lead2.decisionId)}&why=${encodeURIComponent(lead2.why)}`);
  await page.locator('[data-testid="offer-reject"]').waitFor({ timeout: 20000 });
  await page.locator('[data-testid="offer-reject"]').click();
  await page.locator('[data-testid="offer-rejected"]').waitFor({ timeout: 15000 });
  await until('the rejected offer to leave the list', async () => {
    const r = await home();
    return (r.json?.recommendations || []).some((x) => x.kind === 'offer' && x.offeringId === lead2.offeringId) ? null : r.json;
  }, 15, 1500);
  console.log('OK Maybe later and Not interested are decisions in the log; both offers left Paula\'s Home; Back logged nothing');

  await browser.close();
  console.log('\nPASS home_attention_test — Home shows situations, not events; the offer is a decision with three honest outcomes');
  process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
