/* #116 desk_learning_test — the BSS as its own customer, slice 1.
 * Desks report what staff DO (never what they see); insight turns a week of it into
 * a friction report and suggestions with evidence. Proven on the Taranga tenant:
 * three same-shaped bundle submissions → a preset suggestion → accepted → a preset the
 * form can prefill; a journey created without a holdout → an action the desk applies
 * with the user's own token → the journey now holds 10 % out; abandoned forms and
 * empty searches counted; the anonymised export carries counts only; the ENet tenant
 * sees none of it. */
const API = 'http://localhost:8080';
const run = Date.now();
const fail = (m) => { throw new Error(m); };
async function token(realm, user, pass) {
  const r = await fetch(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: user, password: pass }) });
  if (!r.ok) fail(`token(${realm}/${user}): ${r.status}`);
  return (await r.json()).access_token;
}
async function call(method, path, tok, body) {
  const r = await fetch(API + path, { method, headers: { Authorization: `Bearer ${tok}`, ...(body ? { 'Content-Type': 'application/json' } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) });
  const text = await r.text(); let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: r.status, body: json, text };
}
const ev = (event, target, props, session) => ({ desk: 'console', event, target, props, session });

(async () => {
  const staff = await token('taranga', 'demo', 'demo');
  const s = `s-${run}`;

  /* 1. a marketer creates a journey without a holdout, on a real journey */
  const j = await call('POST', '/tmf-api/campaignManagement/v4/journey', staff, { name: `Desk test journey ${run}`, triggerEventType: 'IndividualCreateEvent', holdoutPercent: 0,
    steps: [{ type: 'message', stage: 'Hello', channel: 'inApp', subject: 'Hi', content: 'Welcome' }] });
  if (j.status >= 300) fail(`journey: ${j.status} ${j.text.slice(0, 120)}`);
  /* 2. the desk reports a week's worth of behaviour in one batch */
  const batch = [
    ev('desk.tabs', 'console', { tabs: ['productOffering', 'journeys', 'campaigns', 'audience-builder', 'runbook', 'workforce'] }, s),
    ev('tab.open', 'journeys', null, s), ev('tab.open', 'productOffering', null, s),
    // three bundles with the same shape by the same person → a preset
    ...[1, 2, 3].flatMap((i) => [ev('form.start', 'productOffering', null, `${s}-b${i}`), ev('form.field', 'productOffering', { field: 'name' }, `${s}-b${i}`),
      ev('form.submit', 'productOffering', { mode: 'create', values: { name: `Bundle ${i} ${run}`, isBundle: 'true', lifecycleStatus: 'Active', category: 'Bundles', isSellable: 'true' } }, `${s}-b${i}`)]),
    // two abandoned journey forms, both stopping at the trigger field
    ev('form.start', 'journeys', null, `${s}-a1`), ev('form.field', 'journeys', { field: 'name' }, `${s}-a1`), ev('form.field', 'journeys', { field: 'triggerEventType' }, `${s}-a1`),
    ev('form.start', 'journeys', null, `${s}-a2`), ev('form.field', 'journeys', { field: 'triggerEventType' }, `${s}-a2`),
    // the journey above, created without a holdout
    ev('form.start', 'journeys', null, `${s}-j`), ev('form.submit', 'journeys', { mode: 'create', values: { name: `Desk test journey ${run}`, holdoutPercent: '0' }, createdId: j.body.id, createdName: j.body.name }, `${s}-j`),
    // empty searches
    ev('search.empty', 'productOffering', { query: 'kampboost' }, s), ev('search.empty', 'productOffering', { query: 'kampboost' }, s),
    // a rewritten copilot draft
    ev('copilot.draft', 'journeys', { editRatio: 0.8 }, s), ev('copilot.draft', 'journeys', { editRatio: 0.7 }, s),
  ];
  const ing = await call('POST', '/insight/v1/desk/event', staff, batch);
  if (ing.status >= 300 || !ing.body?.enabled || ing.body.accepted !== batch.length) fail(`ingest: ${ing.status} ${ing.text.slice(0, 120)}`);
  console.log(`  ingest: ${ing.body.accepted} desk actions accepted (tenant has desk-learning on)`);

  /* 3. the friction report */
  const fr = (await call('GET', '/insight/v1/desk/friction?days=7', staff)).body;
  const ab = (fr.abandonedForms || []).find((x) => x.form === 'journeys');
  if (!ab || ab.count < 2 || ab.stopField !== 'triggerEventType') fail(`abandoned journeys not seen: ${JSON.stringify(fr.abandonedForms)}`);
  if (!(fr.emptySearches || []).find((x) => x.query === 'kampboost' && x.count >= 2)) fail('empty searches not counted');
  if (!(fr.unusedFeatures || []).includes('runbook')) fail(`unused features: ${JSON.stringify(fr.unusedFeatures)}`);
  console.log(`  friction: ${fr.events} actions, ${fr.activeStaff} people; journeys abandoned ${ab.count}× at "${ab.stopField}"; "kampboost" empty ${fr.emptySearches.find((x) => x.query === 'kampboost').count}×; unused ${fr.unusedFeatures.join(', ')}`);

  /* 4. suggestions, with evidence and actions */
  const sugg = (await call('GET', '/insight/v1/desk/suggestions', staff)).body || [];
  const preset = sugg.find((x) => x.kind === 'preset' && x.target === 'productOffering');
  const hold = sugg.find((x) => x.kind === 'holdout' && x.target === j.body.id);
  const aban = sugg.find((x) => x.kind === 'abandon' && x.target === 'journeys');
  const rew = sugg.find((x) => x.kind === 'rewrite' && x.target === 'journeys');
  if (!preset || !preset.action?.values?.category) fail(`preset suggestion: ${JSON.stringify(preset)}`);
  if (!hold || hold.action?.method !== 'PATCH') fail(`holdout suggestion: ${JSON.stringify(hold)}`);
  if (!aban || aban.audience !== 'vendor' || !rew) fail('abandon/rewrite suggestions missing');
  console.log(`  suggestions: ${sugg.length} — "${preset.title}" · "${hold.title}" · "${aban.title}"`);

  /* 5. accept the preset → a preset the form can prefill; accept the holdout → the desk applies the action */
  const acc = (await call('POST', `/insight/v1/desk/suggestions/${preset.id}/accept`, staff)).body;
  if (!acc?.preset?.id) fail(`preset accept: ${JSON.stringify(acc)}`);
  const presets = (await call('GET', '/insight/v1/desk/presets?desk=console&form=productOffering', staff)).body || [];
  if (!presets.find((p) => p.id === acc.preset.id && p.values.category === 'Bundles')) fail('preset not listed for the form');
  const acc2 = (await call('POST', `/insight/v1/desk/suggestions/${hold.id}/accept`, staff)).body;
  if (!acc2?.action?.path) fail(`holdout accept: ${JSON.stringify(acc2)}`);
  const applied = await call(acc2.action.method, acc2.action.path, staff, acc2.action.body);
  if (applied.status >= 300) fail(`applying the action: ${applied.status}`);
  const after = ((await call('GET', '/tmf-api/campaignManagement/v4/journey?limit=100', staff)).body || []).find((x) => x.id === j.body.id) || {};
  if (Number(after.holdoutPercent) !== 10) fail(`holdout not applied: ${after.holdoutPercent}`);
  const again = (await call('GET', '/insight/v1/desk/suggestions', staff)).body || [];
  if (again.find((x) => x.id === preset.id && !x.quiet)) fail('accepted suggestion should be quiet');
  console.log(`  accepted: preset "${acc.preset.name}" saved for the offering form; journey holdout 0 → ${after.holdoutPercent} % via the desk's own PATCH`);

  /* 6. the anonymised export: counts only */
  const exp = (await call('GET', '/insight/v1/desk/export?days=7', staff)).body;
  const dump = JSON.stringify(exp);
  if (/Bundle \d|Desk test journey|actorHash|[0-9a-f]{32}/.test(dump)) fail('export leaks values or hashes');
  if (!exp.repeatedForms?.length || !exp.abandonedForms?.length) fail(`export thin: ${dump.slice(0, 200)}`);
  console.log(`  export: ${Object.keys(exp).length} aggregate fields, no names, no values, no hashes`);

  /* 7. tenant isolation: ENet sees nothing of Taranga's desk */
  const enet = await token('enet', 'demo', 'demo');
  const other = (await call('GET', '/insight/v1/desk/friction?days=7', enet)).body;
  if ((other.emptySearches || []).find((x) => x.query === 'kampboost')) fail('desk events leaked across tenants');
  console.log('  isolation: the ENet tenant sees none of it');

  console.log('PASS desk_learning_test');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
