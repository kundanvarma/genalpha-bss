/* #121 decision_log_test — continuous learning, phases 1 + 2: every adaptive choice
 * goes through a DecisionPoint seam (constraints → policy → fallback) and lands in
 * insight's decision log with its context, eligible actions, chosen action, policy,
 * version and propensity; the outcome joins back by id; the receipt reads in plain
 * sentences; the registry names every point and the policy answering it. Taranga
 * tenant; the ENet tenant sees none of it. */
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
const J = '/tmf-api/campaignManagement/v4/journey';
const D = '/insight/v1/decisions';

(async () => {
  const staff = await token('taranga', 'demo', 'demo');

  /* 1. the registry: every DecisionPoint, the policy answering it, its autonomy class */
  const reg = await call('GET', '/tmf-api/campaignManagement/v4/decisionPoint', staff);
  if (reg.status !== 200 || !Array.isArray(reg.body)) fail(`registry: ${reg.status} ${reg.text.slice(0, 120)}`);
  const names = reg.body.map((p) => p.name);
  for (const n of ['journey.enrolment', 'campaign.treatment', 'journey.nextBestAction', 'journey.armWeights']) if (!names.includes(n)) fail(`registry misses ${n}`);
  const enrolPoint = reg.body.find((p) => p.name === 'journey.enrolment');
  if (enrolPoint.policy !== 'holdout-then-weighted-hash' || !enrolPoint.policyVersion || enrolPoint.autonomy !== 'high') fail(`registry entry: ${JSON.stringify(enrolPoint)}`);
  console.log(`  registry: ${reg.body.map((p) => `${p.name} ← ${p.policy} v${p.policyVersion} (${p.autonomy})`).join(' · ')}`);

  /* 2. a journey with two arms behind a holdout; 60 customers enrolled by hand */
  const j = await call('POST', J, staff, { name: `Decision log ${run}`, triggerEventType: 'NeverFiresEvent', holdoutPercent: 20, autoTune: true,
    arms: [{ name: 'A', subject: `Your 10 GB is waiting ${run}`, content: 'Tap to add 10 GB.' }, { name: 'B', subject: `Running low? ${run}`, content: 'Top up in a tap.' }],
    steps: [{ type: 'message', stage: 'Nudge', channel: 'inApp', subject: 'fallback', content: 'fallback' }] });
  if (j.status >= 300) fail(`journey: ${j.status} ${j.text.slice(0, 160)}`);
  const id = j.body.id;
  const parties = Array.from({ length: 60 }, (_, i) => `dl-${run}-${i}`);
  const en = (await call('POST', `${J}/${id}/enrollments`, staff, { partyIds: parties })).body;
  if (en.enrolled !== 60 || !en.dealt) fail(`enrol: ${JSON.stringify(en).slice(0, 200)}`);
  const treated = parties.find((p) => en.dealt[p] === 'A' || en.dealt[p] === 'B');
  const held = parties.find((p) => en.dealt[p] === 'holdout');
  if (!treated || !held) fail(`split odd: ${JSON.stringify(Object.values(en.dealt).reduce((m, v) => ({ ...m, [v]: (m[v] || 0) + 1 }), {}))}`);
  console.log(`  enrolled 60 — ${treated} dealt ${en.dealt[treated]}, ${held} held out`);

  /* 3. each enrolment is ONE logged decision: context, eligible set, action, policy, propensity */
  const dec = await until('the treated customer\'s decision in the log', async () => {
    const r = await call('GET', `${D}?decisionPoint=journey.enrolment&subjectId=${treated}`, staff);
    return r.status === 200 && r.body.find((d) => d.context && d.context.journeyId === id);
  });
  if (dec.action !== en.dealt[treated]) fail(`log says ${dec.action}, the journey dealt ${en.dealt[treated]}`);
  if (!['holdout', 'A', 'B'].every((a) => dec.eligibleActions.includes(a))) fail(`eligible set: ${JSON.stringify(dec.eligibleActions)}`);
  if (typeof dec.propensity !== 'number' || dec.propensity <= 0 || dec.propensity >= 1) fail(`propensity: ${dec.propensity}`);
  if (dec.policy !== 'holdout-then-weighted-hash' || dec.policyVersion !== '1' || dec.source !== 'campaign') fail(`policy: ${dec.policy} v${dec.policyVersion} from ${dec.source}`);
  if (dec.fallback !== false || dec.autonomy !== 'high') fail(`fallback/autonomy: ${dec.fallback}/${dec.autonomy}`);
  const expected = (1 - 0.2) * 0.5;
  if (Math.abs(dec.propensity - expected) > 0.001) fail(`propensity ${dec.propensity} ≠ (1 − holdout) × weight = ${expected}`);
  console.log(`  decision ${dec.decisionId.slice(0, 8)}…: "${dec.action}" of [${dec.eligibleActions}] by ${dec.policy} v${dec.policyVersion}, propensity ${dec.propensity} — ${dec.reason}`);
  const heldDec = await until('the holdout decision', async () => {
    const r = await call('GET', `${D}?decisionPoint=journey.enrolment&subjectId=${held}`, staff);
    return r.status === 200 && r.body.find((d) => d.context && d.context.journeyId === id);
  });
  if (heldDec.action !== 'holdout' || Math.abs(heldDec.propensity - 0.2) > 0.001) fail(`holdout decision: ${JSON.stringify(heldDec)}`);
  console.log(`  holdout decision: "${heldDec.action}" with propensity ${heldDec.propensity}`);

  /* 4. the outcome joins back by id, and the receipt reads in sentences */
  const conv = await call('POST', `${J}/${id}/conversion`, staff, { partyId: treated, value: 299 });
  if (conv.status >= 300) fail(`conversion: ${conv.status}`);
  const receipt = await until('the conversion on the receipt', async () => {
    const r = await call('GET', `${D}/${dec.decisionId}`, staff);
    return r.status === 200 && r.body.outcome === 'conversion' ? r.body : null;
  });
  if (Number(receipt.outcomeValue) !== 299) fail(`outcome value: ${receipt.outcomeValue}`);
  const lines = receipt.receipt || [];
  if (!lines.find((l) => l.startsWith('Chosen:')) || !lines.find((l) => l.startsWith('Outcome: conversion'))) fail(`receipt lines: ${JSON.stringify(lines)}`);
  console.log(`  receipt:\n    ${lines.join('\n    ')}`);

  /* 5. the tuner's judgement is a decision too — deterministic, with its evidence */
  const t = (await call('POST', `${J}/${id}/tune`, staff)).body;
  if (!t.decisionId || !['waiting', 'hold'].includes(t.decision)) fail(`tune: ${JSON.stringify(t)}`);
  const tuneRec = await until('the tuner decision', async () => { const r = await call('GET', `${D}/${t.decisionId}`, staff); return r.status === 200 ? r.body : null; });
  if (tuneRec.decisionPoint !== 'journey.armWeights' || tuneRec.action !== t.decision || tuneRec.propensity !== null || tuneRec.policy !== 'z-threshold-tuner') fail(`tuner record: ${JSON.stringify(tuneRec).slice(0, 300)}`);
  if (!tuneRec.eligibleActions.includes('shift') || tuneRec.eligibleActions.length !== 3) fail(`tuner eligible set: ${JSON.stringify(tuneRec.eligibleActions)}`);
  if (!tuneRec.context.rows || !tuneRec.evidence.after) fail('tuner record lacks rows/after');
  console.log(`  tuner: "${tuneRec.action}" by ${tuneRec.policy} (deterministic) — ${tuneRec.reason}`);

  /* 6. the summary: propensity coverage and outcome rate per point */
  const sum = (await call('GET', `${D}/summary`, staff)).body;
  const ep = (sum.points || []).find((p) => p.decisionPoint === 'journey.enrolment' && p.policy === 'holdout-then-weighted-hash');
  if (!ep || ep.decisions < 60 || ep.withPropensity !== ep.decisions || ep.withOutcome < 1) fail(`summary: ${JSON.stringify(ep)}`);
  console.log(`  summary: journey.enrolment ${ep.decisions} decisions, ${ep.withPropensity} with propensity, ${ep.withOutcome} with outcome (${ep.outcomeRate} %)`);

  /* 7. the log holds identifiers and numbers, never message text */
  const dump = JSON.stringify((await call('GET', `${D}?subjectId=${treated}`, staff)).body);
  if (dump.includes('Your 10 GB') || dump.includes('Running low') || dump.includes('Tap to add')) fail('the decision log leaks message text');
  console.log('  privacy: no message text in the log — identifiers, actions and numbers only');

  /* 8. another tenant cannot read the receipt */
  const enet = await token('enet', 'demo', 'demo');
  const other = await call('GET', `${D}/${dec.decisionId}`, enet);
  if (other.status !== 404) fail(`cross-tenant receipt should be 404, got ${other.status}`);
  console.log('  isolation: the ENet tenant cannot read a Taranga decision');

  console.log('PASS decision_log_test');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
