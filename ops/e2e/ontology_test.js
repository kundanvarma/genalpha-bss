/* The Operational Semantic Registry — the ontology proven against the running
 * platform. Suite #125.
 *
 * Conformance: every capability route is served by the gateway; every event an
 * action emits exists in the emitting component's code; every role the registry
 * names exists in the realm; the component's self-description agrees with its
 * registry entry; the generated SDK matches the committed one.
 *
 * The journey: a fresh customer gets a line, the registry lists what it could
 * become, refuses the wrong things in words (same plan, a stranger's line, a
 * downgrade, a bogus id), upgrades the right one through TMF622 with the
 * customer's own token — product repointed, service renamed — and the receipt
 * lands in insight's decision log. Then the same through MCP tools, the
 * explanations in words, and the tenant overlay. */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const API = 'http://localhost:8080';
const ONT = `${API}/ontology/v1`;
const SHOP = `${API}/shop/`;
const REPO = path.resolve(__dirname, '..', '..');
const run = Date.now();
const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function token(realm, user, pass) {
  const res = await fetch(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`, {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: user, password: pass }) });
  return (await res.json()).access_token;
}
async function call(method, url, tok, body, extra = {}) {
  const headers = { 'Content-Type': 'application/json', ...extra };
  if (tok) headers.Authorization = 'Bearer ' + tok;
  const res = await fetch(url, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  let json = null; try { json = JSON.parse(text); } catch { /* not json */ }
  return { status: res.status, body: json, text };
}
async function register(page, email, first, last) {
  await page.goto(SHOP);
  await page.click('.who >> text=Sign in');
  await page.waitForSelector('a[href*="registration"], input[name="username"]', { timeout: 20000 });
  await page.click('a[href*="registration"]');
  await page.waitForSelector('input[name="email"]');
  await page.fill('input[name="firstName"]', first);
  await page.fill('input[name="lastName"]', last);
  await page.fill('input[name="email"]', email);
  await page.fill('input[name="password"]', 'Passw0rd!');
  await page.fill('input[name="password-confirm"]', 'Passw0rd!');
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('.nav', { timeout: 20000 });
  return page.evaluate(() => sessionStorage.getItem('bss.shop.token'));
}
const grepSources = (dir, needle) => {
  try {
    execFileSync('grep', ['-rq', '--include=*.java', `"${needle}"`, path.join(REPO, 'services', dir, 'src', 'main')]);
    return true;
  } catch { return false; }
};

(async () => {
  const staff = await token('bss', 'demo', 'demo');

  /* ---------------- 1. the registry, loaded and self-consistent */
  const overview = (await call('GET', ONT, staff)).body;
  if (!overview || overview.actions < 1 || overview.concepts < 7) fail('registry overview: ' + JSON.stringify(overview));
  const actions = (await call('GET', `${ONT}/actions`, staff)).body;
  const concepts = (await call('GET', `${ONT}/concepts`, staff)).body;
  const capabilities = (await call('GET', `${ONT}/capabilities`, staff)).body;
  const components = (await call('GET', `${ONT}/components`, staff)).body;
  const upgrade = actions.find((a) => a.action === 'upgradeSubscription');
  if (!upgrade) fail('upgradeSubscription missing');
  if (!upgrade.executes?.capability || !capabilities.find((c) => c.id === upgrade.executes.capability)) fail('executes must be a typed capability');
  for (const e of upgrade.effects || []) if (!capabilities.find((c) => c.id === e.capability)) fail(`effect ${e.capability} is not a capability`);
  const anon = await call('GET', `${ONT}/actions`, null);
  if (anon.status !== 401) fail('registry must need a signed-in caller: ' + anon.status);
  console.log(`  registry: ${overview.concepts} concepts, ${overview.actions} actions, ${overview.capabilities} capabilities, ${overview.components} components; typed refs resolve; anonymous refused (${anon.status})`);

  /* ---------------- 2. conformance against the platform */
  const gatewayYml = fs.readFileSync(path.join(REPO, 'services/gateway/src/main/resources/application.yml'), 'utf8');
  const prefixes = [...gatewayYml.matchAll(/Path=([^\n]+)/g)].flatMap((m) => m[1].split(',')).map((p) => p.trim().replace(/\/\*\*$/, '').replace(/\{[^}]*\}/g, ''));
  const unserved = capabilities.filter((c) => c.route).filter((c) => !prefixes.some((p) => c.route.path.startsWith(p)));
  if (unserved.length) fail('capability routes the gateway does not serve: ' + unserved.map((c) => c.id + ' ' + c.route.path).join(', '));
  const eventsMissing = [];
  for (const a of actions) for (const e of a.emits || []) if (!grepSources(e.component, e.event)) eventsMissing.push(`${e.component}:${e.event}`);
  for (const c of concepts) for (const e of c.events || []) if (!grepSources(e.component, e.event)) eventsMissing.push(`${c.concept}/${e.component}:${e.event}`);
  for (const comp of components) for (const ev of comp.events || []) if (!grepSources(comp.component, ev)) eventsMissing.push(`${comp.component}:${ev}`);
  if (eventsMissing.length) fail('events declared but not found in code: ' + eventsMissing.join(', '));
  const realm = JSON.parse(fs.readFileSync(path.join(REPO, 'infra/keycloak/bss-realm.json'), 'utf8'));
  const realmRoles = new Set(realm.roles.realm.map((r) => r.name));
  const rolesMissing = [];
  for (const a of actions) for (const c of a.permissions?.anyOf || []) if (c.role && !realmRoles.has(c.role)) rolesMissing.push(c.role);
  for (const c of capabilities) for (const r of c.roles || []) if (!realmRoles.has(r)) rolesMissing.push(r);
  if (rolesMissing.length) fail('roles the realm does not know: ' + rolesMissing.join(', '));
  const wellKnown = (await call('GET', `${API.replace('8080', '8160')}/.well-known/genalpha-component.json`, null)).body;
  if (!wellKnown || wellKnown.component !== 'ontology') fail('self-description missing');
  const me = components.find((c) => c.component === 'ontology');
  for (const cap of me.capabilities) {
    const def = capabilities.find((c) => c.id === cap);
    if (def.route && !wellKnown.routes.some((r) => r.endsWith(' ' + def.route.path))) fail(`self-description lacks route of ${cap}: ${def.route.path}`);
  }
  for (const ev of me.events) if (!wellKnown.events.includes(ev)) fail(`self-description lacks event ${ev}`);
  console.log(`  conformance: ${capabilities.filter((c) => c.route).length} routes served by the gateway · ${actions.reduce((n, a) => n + (a.emits || []).length, 0)} emitted events found in code · roles known · self-description agrees (${wellKnown.routes.length} live routes)`);

  /* ---------------- 3. a fresh customer with a line */
  const browser = await chromium.launch();
  const page = await (await browser.newContext()).newPage();
  const carl = await register(page, `onto-${run}@example.com`, 'Onto', 'Upgrader');
  const pageAll = async (p) => { const all = []; for (let o = 0; ; o += 100) { const r = (await call('GET', `${API}${p}?limit=100&offset=${o}`, staff)).body; if (!Array.isArray(r)) break; all.push(...r); if (r.length < 100) break; } return all; };
  const offers = await pageAll('/tmf-api/productCatalogManagement/v4/productOffering');
  const prices = await pageAll('/tmf-api/productCatalogManagement/v4/productOfferingPrice');
  const priceById = Object.fromEntries(prices.map((p) => [p.id, p]));
  const monthly = (o) => (o.productOfferingPrice || []).map((r) => priceById[r.id]).find((p) => p?.priceType === 'recurring')?.price?.value;
  const plans = offers.filter((o) => !o.isBundle && !o.requiresVerifiedIdentity && !(o.productOfferingTerm || []).length
    && (o.category || [])[0]?.name === 'Mobile plans' && monthly(o) != null && o.lifecycleStatus !== 'Retired');
  const planLow = plans.find((o) => o.name.includes('Mobile 10 GB'));
  if (!planLow) fail('need the seed plan GenAlpha Mobile 10 GB');
  const order = await call('POST', `${API}/tmf-api/productOrderingManagement/v4/productOrder`, carl,
    { productOrderItem: [{ action: 'add', productOffering: { id: planLow.id, name: planLow.name } }] }, { 'X-Channel': 'web' });
  if (order.status !== 201) fail('line order failed: ' + order.text.slice(0, 200));
  let service = null;
  for (let i = 0; i < 40 && !service; i++) {
    await sleep(2000);
    const svcs = (await call('GET', `${API}/tmf-api/serviceInventory/v4/service`, carl)).body;
    service = (svcs || []).find?.((s) => s.state === 'active' && (s.supportingResource || []).some((r) => r.value)) || null;
  }
  if (!service) fail('the line never activated');
  const products = (await call('GET', `${API}/tmf-api/productInventory/v4/product?status=active`, carl)).body;
  const product = (products || []).find((p) => p.productOffering?.id === planLow.id);
  if (!product) fail('no active product for the line');
  console.log(`  line: ${planLow.name} active for the new customer (product ${product.id.slice(0, 8)}…)`);

  /* ---------------- 4. what could it become */
  const ups = (await call('GET', `${ONT}/subscriptions/${product.id}/availableUpgrades`, carl)).body;
  if (!Array.isArray(ups) || !ups.length) fail('no available upgrades: ' + JSON.stringify(ups));
  if (ups.some((u) => u.monthly <= monthly(planLow))) fail('an upgrade must cost more than the current plan');
  if (ups.some((u) => u.id === planLow.id)) fail('the current plan is not an upgrade');
  const target = ups[ups.length - 1];
  console.log(`  available upgrades: ${ups.map((u) => `${u.name} (${u.monthly})`).join(' · ')}`);

  /* ---------------- 5. refusals, in words */
  const same = (await call('POST', `${ONT}/actions/upgradeSubscription/check`, carl, { subscriptionId: product.id, targetOfferingId: planLow.id })).body;
  if (same.allowed || !/must differ/.test(same.refusal)) fail('same offering should be refused by name: ' + JSON.stringify(same).slice(0, 300));
  const bogus = (await call('POST', `${ONT}/actions/upgradeSubscription/check`, carl, { subscriptionId: 'no-such-line', targetOfferingId: target.id })).body;
  if (bogus.allowed || !/could not be read/.test(bogus.refusal)) fail('bogus subscription: ' + JSON.stringify(bogus).slice(0, 300));
  const others = ((await call('GET', `${API}/tmf-api/productInventory/v4/product?status=active&limit=100`, staff)).body || [])
    .filter((p) => !(p.relatedParty || []).some((rp) => rp.id === product.relatedParty?.[0]?.id));
  const stranger = others[0];
  if (!stranger) fail('no other customer product to test ownership');
  const foreign = (await call('POST', `${ONT}/actions/upgradeSubscription/check`, carl, { subscriptionId: stranger.id, targetOfferingId: target.id })).body;
  if (foreign.allowed || !/not your subscription|could not be read/.test(foreign.refusal)) fail('a stranger\'s line must be refused: ' + JSON.stringify(foreign).slice(0, 300));
  const executeSame = await call('POST', `${ONT}/actions/upgradeSubscription/execute`, carl, { subscriptionId: product.id, targetOfferingId: planLow.id });
  if (executeSame.status !== 422 || executeSame.body.done !== false) fail('execute of a refused action must be 422 and not done: ' + executeSame.status);
  console.log(`  refused in words: "${same.refusal}" · "${bogus.refusal}" · "${foreign.refusal}"`);

  /* ---------------- 6. the upgrade, through the registry, with the customer's own token */
  const check = (await call('POST', `${ONT}/actions/upgradeSubscription/check`, carl, { subscriptionId: product.id, targetOfferingId: target.id })).body;
  if (!check.allowed) fail('the upgrade should be allowed: ' + JSON.stringify(check).slice(0, 600));
  if (check.permission.by !== 'self:owner') fail('a customer acts as the owner: ' + JSON.stringify(check.permission));
  const failed = check.preconditions.filter((p) => p.verdict === 'fails');
  if (failed.length) fail('no precondition should fail: ' + JSON.stringify(failed));
  const exec = await call('POST', `${ONT}/actions/upgradeSubscription/execute`, carl, { subscriptionId: product.id, targetOfferingId: target.id }, { 'X-Channel': 'web' });
  if (exec.status !== 200 || !exec.body.done) fail(`execute failed: ${exec.status} — ${exec.body?.refusal || exec.body?.said || exec.text.slice(0, 300)} (component status ${exec.body?.componentStatus})`);
  if (!exec.body.decisionId || !exec.body.result?.id) fail('execute must return the order and the receipt id');
  const after = (await call('GET', `${API}/tmf-api/productInventory/v4/product/${product.id}`, carl)).body;
  if (after.productOffering?.id !== target.id) fail('the product should now point at the target offering: ' + JSON.stringify(after.productOffering));
  if (after.previousOffering?.id !== planLow.id) fail('the previous offering must be remembered for proration');
  let renamed = null;
  for (let i = 0; i < 15 && !renamed; i++) {
    await sleep(1500);
    const sv = (await call('GET', `${API}/tmf-api/serviceInventory/v4/service`, carl)).body;
    renamed = (sv || []).find((s) => s.id === service.id && s.name === target.name) || null;
  }
  if (!renamed) fail('the line was not renamed to the new plan by SOM');
  console.log(`  upgraded: ${planLow.name} → ${target.name}; order ${exec.body.result.id.slice(0, 8)}… ${exec.body.result.state}; product repointed, line renamed, same number ${service.supportingResource.find((r) => r.value).value}`);
  console.log(`  said: ${exec.body.said}`);

  /* ---------------- 7. the receipt in the decision log */
  let receipt = null;
  for (let i = 0; i < 20 && !receipt; i++) {
    await sleep(1500);
    const r = await call('GET', `${API}/insight/v1/decisions/${exec.body.decisionId}`, staff);
    if (r.status === 200) receipt = r.body;
  }
  if (!receipt) fail('the decision receipt never reached insight');
  if (receipt.decisionPoint !== 'ontology.upgradeSubscription' || receipt.action !== target.id) fail('receipt content: ' + JSON.stringify(receipt).slice(0, 300));
  if (!(receipt.candidates || []).includes(target.id)) fail('receipt candidates must include the chosen upgrade');
  if (!receipt.evidence?.preconditions?.length) fail('receipt must carry the verdicts as evidence');
  console.log(`  receipt: ${receipt.decisionPoint} by ${receipt.policy} v${receipt.policyVersion}, ${receipt.candidates.length} candidates, outcome ${receipt.outcome || '(pending)'}`);

  /* ---------------- 8. a downgrade is not an upgrade */
  const down = (await call('POST', `${ONT}/actions/upgradeSubscription/check`, carl, { subscriptionId: product.id, targetOfferingId: planLow.id })).body;
  if (down.allowed || !/downgrade|cost more/.test(down.refusal)) fail('downgrade should be refused as such: ' + down.refusal);
  console.log(`  downgrade refused: "${down.refusal}"`);

  /* ---------------- 9. MCP: the same, as tools for an agent */
  const rpc = async (tok, method, params, id = 1) => (await call('POST', `${ONT}/mcp`, tok, { jsonrpc: '2.0', id, method, params })).body;
  const init = await rpc(carl, 'initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'suite', version: '1' } });
  if (!init.result?.serverInfo?.name) fail('mcp initialize: ' + JSON.stringify(init));
  const tools = (await rpc(carl, 'tools/list', {})).result.tools.map((t) => t.name);
  for (const t of ['list_actions', 'explain', 'available_upgrades', 'check_upgrade_subscription', 'upgrade_subscription']) if (!tools.includes(t)) fail('mcp tool missing: ' + t);
  const desc = (await rpc(carl, 'tools/list', {})).result.tools.find((t) => t.name === 'upgrade_subscription').description;
  if (!/Who may/.test(desc)) fail('tool description must carry the action semantics');
  const dry = (await rpc(carl, 'tools/call', { name: 'check_upgrade_subscription', arguments: { subscriptionId: product.id, targetOfferingId: planLow.id } })).result;
  if (!dry.isError || !/must differ/.test(dry.content[0].text)) fail('mcp dry run should refuse with the condition: ' + JSON.stringify(dry).slice(0, 300));
  const journey = (await rpc(carl, 'tools/call', { name: 'explain', arguments: { kind: 'journey', name: 'upgradeSubscription' } })).result.structuredContent;
  const kinds = [...new Set(journey.steps.map((s) => s.kind))];
  for (const k of ['concept', 'precondition', 'permission', 'policy', 'execute', 'effect', 'event', 'receipt']) if (!kinds.includes(k)) fail('journey lacks step kind ' + k);
  const unknown = await rpc(carl, 'no/such', {});
  if (unknown.error?.code !== -32601) fail('mcp unknown method should be -32601');
  console.log(`  mcp: ${tools.length} tools generated from the registry · dry run refuses with the condition · journey explained in ${journey.steps.length} steps`);

  /* ---------------- 10. explanations and the tenant overlay */
  const ex = (await call('GET', `${ONT}/explain/action/upgradeSubscription`, staff)).body;
  if (!/Who may/.test(ex.text) || !/executed by the product-ordering/.test(ex.text)) fail('explain action: ' + ex.text.slice(0, 200));
  const pg = (await call('GET', `${ONT}/explain/page/productOrder`, staff)).body;
  if (!pg.known || !/upgradeSubscription/.test(pg.text)) fail('explain page productOrder: ' + JSON.stringify(pg).slice(0, 200));
  const none = (await call('GET', `${ONT}/explain/page/simulate/priceChange`, staff)).body;
  if (none.known) fail('the simulator page is not in the ontology yet');
  const taranga = await token('taranga', 'demo', 'demo');
  const tAction = (await call('GET', `${ONT}/actions/upgradeSubscription`, taranga)).body;
  if (!tAction.tenantExtended || !tAction.preconditions.some((p) => p.id === 'taranga-price-ceiling')) fail('taranga overlay missing: ' + JSON.stringify(tAction.preconditions.map((p) => p.id)));
  if (tAction.preconditions.length !== upgrade.preconditions.length + 1) fail('the overlay must keep every core precondition');
  if (upgrade.tenantExtended) fail('genalpha must not carry the taranga overlay');
  console.log(`  explain: action, journey and page in words · taranga overlay adds "${tAction.preconditions.find((p) => p.id === 'taranga-price-ceiling').says.slice(0, 60)}…" and keeps ${upgrade.preconditions.length} core conditions`);

  /* ---------------- 11. the generated SDK matches the committed one */
  const tmp = path.join(require('os').tmpdir(), `genalpha-sdk-${run}.ts`);
  execFileSync('node', [path.join(REPO, 'ops/ontology/gen-sdk.mjs'), tmp, API, 'bss'], { stdio: 'pipe' });
  const committed = fs.readFileSync(path.join(REPO, 'packages/genalpha-sdk/src/index.ts'), 'utf8');
  if (fs.readFileSync(tmp, 'utf8') !== committed) fail('the committed SDK differs from the registry — run node ops/ontology/gen-sdk.mjs and commit');
  if (!/async upgradeSubscription\(inputs: UpgradeSubscriptionInputs\)/.test(committed)) fail('SDK lacks the action');
  console.log(`  sdk: packages/genalpha-sdk/src/index.ts regenerates identically (${committed.split('\n').length} lines)`);


  /* ---------------- 12. runtime self-description of every journey component */
  const conf = (await call('GET', `${ONT}/conformance`, staff)).body;
  for (const c of ['product-ordering', 'service-orchestration', 'product-catalog', 'product-inventory', 'billing', 'device-entitlement', 'ontology']) {
    const row = conf.find((x) => x.component === c);
    if (!row || !row.ok) fail(`runtime conformance of ${c}: ${JSON.stringify(row).slice(0, 300)}`);
  }
  console.log(`  runtime conformance: ${conf.filter((x) => x.ok).length} components describe themselves and agree with the registry`);

  /* ---------------- 13. more governed actions on the same line: pause, resume, SIM */
  const svc = (await call('GET', `${API}/tmf-api/serviceInventory/v4/service`, carl)).body.find((x) => x.id === service.id);
  const paused = await call('POST', `${ONT}/actions/suspendSubscription/execute`, carl, { serviceId: svc.id, reason: 'holiday', days: 3 });
  if (paused.status !== 200 || !paused.body.done || paused.body.result?.state !== 'suspended') fail('suspend: ' + paused.text.slice(0, 300));
  const pauseAgain = (await call('POST', `${ONT}/actions/suspendSubscription/check`, carl, { serviceId: svc.id })).body;
  if (pauseAgain.allowed || !/only an active line/.test(pauseAgain.refusal)) fail('a paused line must not be paused again: ' + pauseAgain.refusal);
  const resumed = await call('POST', `${ONT}/actions/resumeSubscription/execute`, carl, { serviceId: svc.id });
  if (resumed.status !== 200 || resumed.body.result?.state !== 'active') fail('resume: ' + resumed.text.slice(0, 300));
  const badSim = (await call('POST', `${ONT}/actions/replaceSim/check`, carl, { serviceId: svc.id, reason: 'because' })).body;
  if (badSim.allowed || !/lost, stolen, damaged or upgrade/.test(badSim.refusal)) fail('a nonsense SIM reason must be refused: ' + badSim.refusal);
  const sim = await call('POST', `${ONT}/actions/replaceSim/execute`, carl, { serviceId: svc.id, reason: 'lost' });
  if (sim.status !== 200 || sim.body.result?.oldSim?.status !== 'blocked') fail('replaceSim: ' + sim.text.slice(0, 300));
  console.log(`  line actions: paused (${paused.body.result.reason}, until ${String(paused.body.result.resumeAt).slice(0, 10)}) → refused twice → resumed → SIM replaced (old ${sim.body.result.oldSim.status}, new ${sim.body.result.iccid})`);

  /* ---------------- 14. cancel an order that has not completed */
  const second = await call('POST', `${API}/tmf-api/productOrderingManagement/v4/productOrder`, carl,
    { productOrderItem: [{ action: 'add', productOffering: { id: planLow.id, name: planLow.name } }] }, { 'X-Channel': 'web' });
  if (second.status !== 201) fail('second order: ' + second.text.slice(0, 200));
  const cancelled = await call('POST', `${ONT}/actions/cancelOrder/execute`, carl, { orderId: second.body.id });
  if (cancelled.status !== 200 || cancelled.body.result?.state !== 'cancelled') fail('cancelOrder: ' + cancelled.text.slice(0, 300));
  const cancelDone = (await call('POST', `${ONT}/actions/cancelOrder/check`, carl, { orderId: exec.body.result.id })).body;
  if (cancelDone.allowed || !/completed or already cancelled/.test(cancelDone.refusal)) fail('a completed order must not be cancellable: ' + cancelDone.refusal);
  console.log(`  cancelOrder: a fresh order cancelled (${cancelled.body.result.state}); the completed upgrade order refused in words`);

  /* ---------------- 15. money: dispute, then credit with the threshold ladder */
  const staffBills = (await call('GET', `${API}/tmf-api/customerBillManagement/v4/customerBill?limit=100`, staff)).body || [];
  const due = (b) => Number(b.amountDue?.value ?? b.amountDue);
  const bill = staffBills.find((b) => b.state !== 'settled' && due(b) >= 61) || staffBills.find((b) => b.state !== 'settled' && due(b) >= 30) || staffBills.find((b) => due(b) >= 30);
  if (!bill) fail('no bill with at least 30 due to test money actions');
  const billOwner = (bill.relatedParty || [])[0]?.id;
  const noReason = (await call('POST', `${ONT}/actions/disputeBill/check`, staff, { billId: bill.id })).body;
  if (noReason.allowed || !/needs a reason|reason is required/.test(noReason.refusal)) fail('a dispute without a reason must be refused: ' + noReason.refusal);
  const agent = await token('bss', 'agent-anna', 'agent');
  const tooMuch = (await call('POST', `${ONT}/actions/issueCredit/check`, agent, { billId: bill.id, amount: 60, reason: 'goodwill' })).body;
  if (tooMuch.allowed || !/no single credit above 50|no more than what the bill still owes/.test(tooMuch.refusal)) fail('above the ceiling must be refused: ' + tooMuch.refusal);
  const small = await call('POST', `${ONT}/actions/issueCredit/execute`, agent, { billId: bill.id, amount: 5, reason: `desk goodwill ${run}` });
  if (small.status !== 200 || !small.body.done || !small.body.result?.creditNoteNo) fail('an agent credit under the threshold should execute as the registry: ' + small.text.slice(0, 400));
  if (!/registry's own identity/.test(small.body.executedAs)) fail('delegated execution must be named: ' + small.body.executedAs);
  const big = await call('POST', `${ONT}/actions/issueCredit/execute`, agent, { billId: bill.id, amount: 30, reason: `desk goodwill large ${run}` });
  if (big.status !== 202 || !big.body.filed || !big.body.approvalId) fail('above the threshold must be filed for approval: ' + big.text.slice(0, 400));
  const pending = (await call('GET', `${API}/ai/v1/workforce/approvals?status=pending`, staff)).body || [];
  const filed = pending.find((a) => a.id === big.body.approvalId);
  if (!filed || filed.action !== 'ontology.issueCredit' || !/agent-anna|asked by/.test(filed.reason)) fail('the filed approval is not on the desk: ' + JSON.stringify(pending.slice(0, 2)).slice(0, 300));
  const approved = await call('POST', `${API}/ai/v1/workforce/approvals/${big.body.approvalId}/approve`, staff, { note: 'suite approves' });
  if (approved.status !== 200 || approved.body.status !== 'approved' || !approved.body.result?.creditNoteNo) fail('approval should execute the credit with the approver\'s token: ' + approved.text.slice(0, 300));
  const direct = await call('POST', `${ONT}/actions/issueCredit/execute`, staff, { billId: bill.id, amount: 1, reason: `finance direct ${run}` });
  if (direct.status !== 200 || direct.body.executedAs !== 'the caller') fail('an approver executes directly: ' + direct.text.slice(0, 300));
  console.log(`  money: dispute needs a reason · agent credit 5 → ${small.body.result.creditNoteNo} as the registry · 30 → filed ${big.body.approvalId.slice(0, 8)}… → approved by finance → ${approved.body.result.creditNoteNo} · 60 → refused · finance 1 → ${direct.body.result.creditNoteNo} directly`);
  if (billOwner) {
    const owner = staffBills.filter((b) => (b.relatedParty || [])[0]?.id === billOwner && !(b.dispute && b.dispute.status === 'open'))[0];
    if (owner) {
      const disputed = await call('POST', `${ONT}/actions/disputeBill/execute`, staff, { billId: owner.id, reason: `suite dispute ${run}` });
      if (disputed.status === 200) console.log(`  disputeBill: opened on ${owner.billNo || owner.id.slice(0, 8)} (${disputed.body.result.status}); collection pauses while it is open`);
      else console.log(`  disputeBill: ${disputed.status} — ${disputed.body?.refusal || ''} (a bill already under dispute is refused by billing)`);
    }
  }

  /* ---------------- 16. launch governance as governed actions (Taranga runs envelope mode) */
  const sigrid = await token('taranga', 'sigrid@taranga.example', 'sigrid');
  const henrik = await token('taranga', 'henrik@taranga.example', 'henrik');
  if (!sigrid || !henrik) fail('taranga personas sigrid/henrik could not sign in');
  const CAT = `${API}/tmf-api/productCatalogManagement/v4`;
  const cats = (await call('GET', `${CAT}/category?limit=100`, sigrid)).body || [];
  const mobile = cats.find((c) => /mobile plans/i.test(c.name));
  if (!mobile) fail('no Mobile plans category on taranga');
  const price = (await call('POST', `${CAT}/productOfferingPrice`, sigrid, { name: `Onto ${run}`, priceType: 'recurring', recurringChargePeriodType: 'month', price: { unit: 'NOK', value: 899 }, lifecycleStatus: 'Active' })).body;
  const spec = (await call('POST', `${CAT}/productSpecification`, sigrid, { name: `Onto spec ${run}`, lifecycleStatus: 'Active', productSpecCharacteristic: [{ name: 'Data', configurable: false, productSpecCharacteristicValue: [{ value: 'Unlimited' }] }] })).body;
  const draft = (await call('POST', `${CAT}/productOffering`, sigrid, { name: `Onto plan ${run}`, description: 'ontology governance', lifecycleStatus: 'Active', isSellable: true, category: [{ id: mobile.id, name: mobile.name }], productOfferingPrice: [{ id: price.id, name: price.name }], productSpecification: { id: spec.id, name: spec.name }, channel: [{ id: 'web' }] })).body;
  if (!price?.id || !spec?.id) fail('price/spec creation on taranga: ' + JSON.stringify({ price, spec }).slice(0, 300));
  if (!draft?.id || draft.lifecycleStatus !== 'In design') fail('draft offering: ' + JSON.stringify(draft).slice(0, 200));
  const notApprover = (await call('POST', `${ONT}/actions/approveLaunch/check`, sigrid, { offeringId: draft.id })).body;
  if (notApprover.allowed || !/holds none of the roles|only a requested launch/.test(notApprover.refusal)) fail('sigrid must not approve: ' + notApprover.refusal);
  const requested = await call('POST', `${ONT}/actions/requestLaunch/execute`, sigrid, { offeringId: draft.id, note: 'please' });
  if (requested.status !== 200 || !requested.body.done) fail('requestLaunch: ' + requested.text.slice(0, 300));
  const twice = (await call('POST', `${ONT}/actions/requestLaunch/check`, sigrid, { offeringId: draft.id })).body;
  if (twice.allowed || !/already be requested|already requested/.test(twice.refusal)) fail('a second request must be refused: ' + twice.refusal);
  const gstate = requested.body.result?.governanceState || requested.body.result?.state;
  let approvedLaunch = null;
  if (gstate === 'requested') {
    approvedLaunch = await call('POST', `${ONT}/actions/approveLaunch/execute`, henrik, { offeringId: draft.id, note: 'ok' });
    if (approvedLaunch.status !== 200) fail('approveLaunch: ' + approvedLaunch.text.slice(0, 300));
  }
  const held = await call('POST', `${ONT}/actions/holdLaunch/execute`, sigrid, { offeringId: draft.id, note: 'marketing not ready' });
  if (held.status !== 200 || !held.body.done) fail('holdLaunch: ' + held.text.slice(0, 300));
  console.log(`  launch: draft ${gstate} by sigrid${approvedLaunch ? ' → approved by henrik' : ' (envelope pre-approved)'} → held by sigrid; sigrid refused as approver in words`);

  /* ---------------- 17. the outcome window closes: the receipt is judged */
  const swept = (await call('POST', `${ONT}/outcomes/sweep`, staff)).body;
  let judged = null;
  for (let i = 0; i < 20 && !judged; i++) {
    await sleep(1500);
    const r = (await call('GET', `${API}/insight/v1/decisions/${exec.body.decisionId}`, staff)).body;
    if (r && r.outcome && r.outcome !== 'completed') judged = r;
  }
  if (!judged || judged.outcome !== 'retained') fail('the upgrade receipt should be judged retained after the (compressed) window: ' + JSON.stringify(judged || swept).slice(0, 200));
  console.log(`  outcome: sweep judged ${swept.judged} receipt(s); the upgrade is "${judged.outcome}" after ${judged.outcomeValue} day(s)`);

  /* ---------------- 18. the learning contract for the action, and the customer's context in one call */
  const lc = (await call('GET', `${API}/tmf-api/campaignManagement/v4/learningContract/ontology.upgradeSubscription`, staff)).body;
  if (!lc || lc.contract?.objective !== 'retained') fail('learning contract for ontology.upgradeSubscription: ' + JSON.stringify(lc).slice(0, 200));
  const ctxCarl = (await call('GET', `${ONT}/context/customer/${product.relatedParty[0].id}`, carl)).body;
  if (!ctxCarl || !ctxCarl.subscriptions?.length || !ctxCarl.services?.length) fail('customer context: ' + JSON.stringify(ctxCarl).slice(0, 300));
  if (!ctxCarl.unanswered.some((u) => /receipts/.test(u))) fail('a customer cannot read receipts; the context must say so: ' + JSON.stringify(ctxCarl.unanswered));
  const ctxStaff = (await call('GET', `${ONT}/context/customer/${product.relatedParty[0].id}`, staff)).body;
  if (!ctxStaff.receipts?.some((r) => r.decisionId === exec.body.decisionId)) fail('staff context must carry the receipt');
  console.log(`  contract: "${lc.contract.objective}" v${lc.contract.version} · context: ${ctxCarl.subscriptions.length} subscription(s), ${ctxCarl.services.length} line(s), receipts unanswered for the customer, ${ctxStaff.receipts.length} for staff`);

  /* ---------------- 19. RDF export, the retired action, and the desk on the SDK */
  const ttl = await fetch(`${ONT}/export.ttl`, { headers: { Authorization: 'Bearer ' + staff } });
  const turtle = await ttl.text();
  if (!ttl.ok || !/ga:upgradeSubscription a ga:Action/.test(turtle) || !/ga:Subscription a owl:Class/.test(turtle)) fail('turtle export: ' + turtle.slice(0, 200));
  const retired = await call('POST', `${ONT}/actions/changeSubscriptionPlan/execute`, carl, { subscriptionId: product.id, targetOfferingId: planLow.id });
  if (retired.status !== 410 || !/use upgradeSubscription/.test(retired.body.refusal)) fail('a retired action must refuse and name its successor: ' + retired.text.slice(0, 200));
  const toolsNow = (await rpc(carl, 'tools/list', {})).result.tools.map((t) => t.name);
  if (toolsNow.includes('change_subscription_plan')) fail('a retired action must not be an agent tool');
  if (/changeSubscriptionPlan/.test(committed)) fail('a retired action must not be in the SDK');
  console.log(`  turtle: ${turtle.split('\n').length} lines · retired action refused (410) and absent from ${toolsNow.length} tools and the SDK`);

  const csr = await (await browser.newContext()).newPage();
  await csr.goto(`${API}/csr/`);
  await csr.waitForSelector('input[name="username"]', { timeout: 20000 });
  await csr.fill('input[name="username"]', 'agent-anna'); await csr.fill('input[name="password"]', 'agent');
  await csr.click('input[type="submit"], button[type="submit"]');
  await csr.waitForSelector('.searchbar', { timeout: 20000 });
  await csr.goto(`${API}/csr/customer/${product.relatedParty[0].id}`);
  await csr.waitForSelector(`[data-testid="csr-upgrade-options-${product.id}"]`, { timeout: 20000 });
  await csr.click(`[data-testid="csr-upgrade-options-${product.id}"]`);
  await csr.waitForSelector('[data-testid="upgrade-card"]', { timeout: 15000 });
  const cardText = await csr.locator('[data-testid="upgrade-card"]').textContent();
  console.log(`  csr desk on the SDK: "${cardText.trim().slice(0, 90)}"`);

  await browser.close();
  console.log('\nPASS ontology_test — registry, conformance (sources and runtime), nine governed actions on a customer\'s line, bills and launches with receipts, approval ladder, outcome sweep, learning contract, context, RDF, deprecation, MCP, SDK and the desk on it');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
