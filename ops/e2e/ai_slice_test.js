/* AI-slice PoC E2E — the 'Autonomy Accelerated' lead-to-assure loop end to end:
 * B2B intent -> autonomous feasibility + network-side AI upsell -> priced
 * TMF648 quote with token economics -> accept places the order -> the SOM
 * provisions the slice on a fibre path -> a fibre cut mid-match -> assurance
 * re-homes the slice to the edge on its own, restores the SLA, and files the
 * ITSM ticket. Four vendors' worth of workflow, one event-driven BSS/OSS. */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();

async function staffToken(request) {
  const res = await request.post(
    'http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } });
  return (await res.json()).access_token;
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const fail = (msg) => { console.error('FAIL: ' + msg); process.exit(1); };
  const token = await staffToken(ctx.request);
  const H = { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' };
  const org = `stadium-e2e-${run}`;
  const path = 'fibre-route-stadium-north';

  // 1. Business intent: a sub-10ms slice for a stadium, with AI capacity.
  const intent = await (await ctx.request.post(`${API}/tmf-api/intentManagement/v4/intent`, {
    headers: H,
    data: {
      name: `Tournament slice ${run}`,
      relatedParty: [{ id: org, role: 'customer' }],
      expression: { place: 'stadium-north', latencyMs: 8, bandwidthMbps: 2000, aiTokensMillions: 80 },
    },
  })).json();
  if (intent.status !== 'feasibilityChecked') fail('intent not feasible: ' + JSON.stringify(intent));
  const proposed = intent.intentReport.proposedItems.map((p) => p.service);
  if (!proposed.includes('edge-ai-inferencing')) fail('network did not upsell edge AI: ' + proposed);
  console.log('OK intent feasibility ran autonomously; network proposed', proposed.join(' + '),
    'at', intent.intentReport.deliveryPoint);

  // 2. Quote from the intent: prices, and the token economics on the AI line.
  const quote = await (await ctx.request.post(`${API}/tmf-api/quoteManagement/v4/quote`, {
    headers: H, data: { intentId: intent.id },
  })).json();
  const aiLine = quote.quoteItem.find((i) => i.offering.name === 'Edge AI Inferencing');
  if (!aiLine || !aiLine.allowance || aiLine.allowance.usageType !== 'AI inference tokens') {
    fail('quote missing token-metered AI line: ' + JSON.stringify(quote.quoteItem));
  }
  if (!(quote.quoteTotalPrice.value > 0)) fail('quote has no price');
  console.log('OK quote priced the proposal:', quote.quoteTotalPrice.value,
    quote.quoteTotalPrice.unit + '/month, AI metered at',
    aiLine.allowance.included.value + aiLine.allowance.included.units, 'included');

  // 3. Approve + accept: the quote becomes a product order, no swivel chair.
  await ctx.request.patch(`${API}/tmf-api/quoteManagement/v4/quote/${quote.id}`,
    { headers: H, data: { state: 'approved' } });
  const accepted = await (await ctx.request.post(
    `${API}/tmf-api/quoteManagement/v4/quote/${quote.id}/accept`, { headers: H })).json();
  if (accepted.state !== 'accepted' || !accepted.productOrder) fail('accept did not place an order');
  console.log('OK quote accepted; product order', accepted.productOrder.id.slice(0, 8), 'placed');

  // 4. The SOM provisions the slice onto a fibre delivery path.
  let slice = null;
  for (let attempt = 0; attempt < 20 && !slice; attempt++) {
    await new Promise((r) => setTimeout(r, 2000));
    const services = await (await ctx.request.get(
      `${API}/tmf-api/serviceInventory/v4/service?relatedPartyId=${org}`, { headers: H })).json();
    slice = services.find((s) => s.name === 'Stadium 5G Slice' && s.deliveryPath);
  }
  if (!slice || slice.deliveryPath !== path) fail('slice not provisioned on the fibre path');
  console.log('OK SOM provisioned the slice on', slice.deliveryPath);

  // 5. A clean object for this run: resolve any pre-existing problem on the path.
  const open = await (await ctx.request.get(
    `${API}/tmf-api/serviceProblemManagement/v4/serviceProblem?status=open`, { headers: H })).json();
  for (const p of open.filter((p) => p.affectedObject === path)) {
    await ctx.request.patch(`${API}/tmf-api/serviceProblemManagement/v4/serviceProblem/${p.id}`,
      { headers: H, data: { status: 'resolved' } });
  }

  // 6. FIBRE CUT mid-match: a critical alarm on the delivery path.
  await ctx.request.post(`${API}/tmf-api/alarmManagement/v4/alarm`, {
    headers: H,
    data: { alarmedObject: path, perceivedSeverity: 'critical',
            probableCause: `fibre cut during the final (${run})` },
  });

  // 7. The system works out the fix on its own: slice re-homed to the edge.
  let healed = null;
  for (let attempt = 0; attempt < 20 && !(healed && healed.deliveryPath.startsWith('edge:')); attempt++) {
    await new Promise((r) => setTimeout(r, 2000));
    const services = await (await ctx.request.get(
      `${API}/tmf-api/serviceInventory/v4/service?relatedPartyId=${org}`, { headers: H })).json();
    healed = services.find((s) => s.name === 'Stadium 5G Slice');
  }
  if (!healed || !healed.deliveryPath.startsWith('edge:')) {
    fail('slice was not re-homed to the edge: ' + (healed && healed.deliveryPath));
  }
  if (healed.state !== 'active') fail('slice not active after heal');
  console.log('OK fibre cut healed autonomously; slice moved to', healed.deliveryPath, '— SLA restored');

  // 8. The problem closed itself, and the ITSM ticket documents it.
  const problems = await (await ctx.request.get(
    `${API}/tmf-api/serviceProblemManagement/v4/serviceProblem`, { headers: H })).json();
  const mine = problems.filter((p) => p.affectedObject === path).pop();
  if (!mine || mine.status !== 'resolved') fail('problem did not auto-resolve: ' + JSON.stringify(mine));
  const tickets = await (await ctx.request.get(
    `${API}/tmf-api/troubleTicket/v4/troubleTicket?limit=100`, { headers: H })).json();
  if (!tickets.some((t) => (t.name || '').includes('re-homed to edge'))) {
    fail('no ITSM ticket documenting the self-heal');
  }
  console.log('OK service problem auto-resolved; ITSM ticket filed — the loop closed itself');

  await browser.close();
  console.log('\nALL AI-SLICE CHECKS PASSED');
})().catch((e) => { console.error('FAIL:', e.message.split('\n')[0]); process.exit(1); });
