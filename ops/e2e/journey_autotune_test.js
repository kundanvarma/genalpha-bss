/* #117 journey_autotune_test — journey auto-tuning: two message variants behind a
 * holdout; each treated customer is dealt an arm; the first message speaks in that
 * arm; conversions read per arm; the tuner shifts traffic to the winner ONLY when
 * the evidence clears a z-threshold, keeps a floor on every arm, and writes every
 * decision (waiting / hold / shift) to a ledger with the numbers it saw. Taranga tenant. */
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
const J = '/tmf-api/campaignManagement/v4/journey';

(async () => {
  const staff = await token('taranga', 'demo', 'demo');

  /* 1. a journey with two arms and a holdout, auto-tune on */
  const j = await call('POST', J, staff, { name: `Autotune ${run}`, triggerEventType: 'NeverFiresEvent', holdoutPercent: 10, autoTune: true,
    arms: [{ name: 'A', subject: `Your 10 GB is waiting ${run}`, content: 'Tap to add 10 GB.' }, { name: 'B', subject: `Running low? ${run}`, content: 'Top up in a tap.' }],
    steps: [{ type: 'message', stage: 'Nudge', channel: 'inApp', subject: 'fallback', content: 'fallback' }] });
  if (j.status >= 300) fail(`journey: ${j.status} ${j.text.slice(0, 160)}`);
  const id = j.body.id;
  if (!j.body.arms || j.body.armWeights.A !== 50 || j.body.armWeights.B !== 50) fail(`arms not stored / not split: ${JSON.stringify(j.body.armWeights)}`);
  console.log(`  journey: 2 arms at ${JSON.stringify(j.body.armWeights)}, holdout ${j.body.holdoutPercent} %, auto-tune on`);

  /* 2. the tuner waits while arms are thin, and says so */
  const early = (await call('POST', `${J}/${id}/tune`, staff)).body;
  if (early.decision !== 'waiting') fail(`expected waiting: ${JSON.stringify(early)}`);
  console.log(`  no data yet → "${early.decision}": ${early.why}`);

  /* 3. 120 customers enrolled by hand; every treated one is dealt an arm */
  const parties = Array.from({ length: 120 }, (_, i) => `p-${run}-${i}`);
  const en = (await call('POST', `${J}/${id}/enrollments`, staff, { partyIds: parties })).body;
  if (en.enrolled !== 120 || !en.dealt) fail(`enrol: ${JSON.stringify(en).slice(0, 200)}`);
  const inA = parties.filter((p) => en.dealt[p] === 'A'), inB = parties.filter((p) => en.dealt[p] === 'B'), held = parties.filter((p) => en.dealt[p] === 'holdout');
  if (inA.length < 30 || inB.length < 30 || held.length < 4) fail(`split odd: A ${inA.length} B ${inB.length} holdout ${held.length}`);
  console.log(`  enrolled 120: A ${inA.length} · B ${inB.length} · holdout ${held.length}`);

  /* 4. the first message speaks in the arm: one A customer's inbox message carries A's subject */
  let spoke = null;
  for (let i = 0; i < 15 && !spoke; i++) {
    await sleep(2000);
    const msgs = (await call('GET', `/tmf-api/communicationManagement/v4/communicationMessage?limit=100`, staff)).body || [];
    spoke = msgs.find((m) => (m.subject || '').includes(`Your 10 GB is waiting ${run}`)) ? 'A' : (msgs.find((m) => (m.subject || '').includes(`Running low? ${run}`)) ? 'B' : null);
  }
  if (!spoke) fail('no message in an arm\'s voice reached the inbox');
  console.log(`  the tick spoke in arm ${spoke}'s subject (not the step's fallback)`);

  /* 5. conversions come after the message: wait until the tick has walked every enrolment */
  for (let i = 0; i < 30; i++) { const st = (await call('GET', `${J}/${id}/stats`, staff)).body; if (!Object.keys(st.activeAtStep || {}).length) break; await sleep(2000); }
  /* conversions: A converts 40 %, B 10 % — recorded by hand, as a store or a call centre would */
  const convert = async (list, share) => { let n = 0; for (let i = 0; i < list.length; i++) if (i % 100 < share) { const r = await call('POST', `${J}/${id}/conversion`, staff, { partyId: list[i], value: 299 }); if (r.status >= 300) fail(`conversion: ${r.status}`); n++; } return n; };
  const cA = await convert(inA, 40), cB = await convert(inB, 10);
  let s = (await call('GET', `${J}/${id}/stats`, staff)).body;
  const a = s.arms.find((x) => x.name === 'A'), b = s.arms.find((x) => x.name === 'B');
  if (a.converted !== cA || b.converted !== cB) fail(`per-arm conversions: ${JSON.stringify(s.arms)}`);
  console.log(`  conversions: A ${a.converted}/${a.enrolled} (${a.rate} %) · B ${b.converted}/${b.enrolled} (${b.rate} %) · revenue A ${a.revenue}`);

  /* 6. the tuner shifts traffic to A, keeps B's floor, and logs the evidence */
  const t = (await call('POST', `${J}/${id}/tune`, staff)).body;
  if (t.decision !== 'shift' || t.after.A !== 90 || t.after.B !== 10 || !(t.z >= 1.64)) fail(`tune: ${JSON.stringify(t)}`);
  const after = (await call('GET', `${J}/${id}/stats`, staff)).body;
  if (after.arms.find((x) => x.name === 'A').weight !== 90) fail('weights not applied to stats');
  if (!(after.tuningLog || []).find((e) => e.decision === 'shift')) fail('shift not in the ledger');
  console.log(`  tuner: "${t.decision}" ${JSON.stringify(t.before)} → ${JSON.stringify(t.after)} — ${t.why}`);

  /* 7. new enrolments follow the new weights; old ones keep their arm; a second judgement holds */
  const more = Array.from({ length: 100 }, (_, i) => `q-${run}-${i}`);
  const en2 = (await call('POST', `${J}/${id}/enrollments`, staff, { partyIds: more })).body;
  const newA = more.filter((p) => en2.dealt[p] === 'A').length, newTreated = more.filter((p) => en2.dealt[p] !== 'holdout').length;
  if (newA / newTreated < 0.75) fail(`new traffic should lean to A: ${newA}/${newTreated}`);
  const again = (await call('POST', `${J}/${id}/tune`, staff)).body;
  if (again.decision === 'shift' && (again.after.A !== 90 || again.after.B !== 10)) fail(`second judgement moved the weights: ${JSON.stringify(again)}`);
  console.log(`  after the shift: ${newA} of ${newTreated} new treated customers dealt A; second judgement "${again.decision}"`);

  /* 8. another tenant cannot see or tune it */
  const enet = await token('enet', 'demo', 'demo');
  const other = await call('POST', `${J}/${id}/tune`, enet);
  if (other.status < 400) fail('cross-tenant tune should fail');
  console.log('  isolation: the ENet tenant cannot see the journey');

  console.log('PASS journey_autotune_test');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
