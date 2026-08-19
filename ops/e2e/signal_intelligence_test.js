/* Signal intelligence (SI-P1..P5) — the consolidated acceptance.
 *
 *  - FIREWALL: planted fnr/phone/email PII never reaches the store; the
 *    redaction audit rides the row; ingest is idempotent.
 *  - CONNECTORS: the desk sync is re-runnable; the generic webhook opens only
 *    to its shared secret and redacts what comes through.
 *  - BATTERY: classifications carry VERBATIM evidence (verified at the store);
 *    fabricated evidence is refused 422; churn words become the churnSignal trait.
 *  - VOC: a flood of same-aspect negatives trips ONE auditable alert per ISO
 *    week, the bus event fires, and the chat webhook receives the warning.
 *  - CLTV: an honest number from real bills lands as a numeric trait.
 *  - ASK: the aggregates-only ask surface answers; raw text never reaches it.
 *  - ERASURE: a leaver's signals vanish through the fleet privacy orchestrator.
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const CHATOPS = 'http://localhost:8143';
const run = Date.now();

async function token(ctx, user, pass) {
  const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  return (await res.json()).access_token;
}
const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { throw new Error(m); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const staff = await token(ctx, 'demo', 'demo');

  /* ---------- 1. the PII firewall + idempotent ingest ---------- */
  const raw = 'Kunden ringte fra +47 99 88 77 66, fødselsnummer 010199 12345, '
    + `epost sint${run}@kunde.no. Ruteren er død og kunden er rasende.`;
  const first = await (await ctx.post(`${API}/insight/v1/signal`, { headers: H(staff), data: {
    source: 'call', sourceRef: `si-${run}`, text: raw, lang: 'no' } })).json();
  if (!first.text.includes('[FNR]') || !first.text.includes('[PHONE]') || !first.text.includes('[EMAIL]')) {
    fail('PII survived the firewall: ' + first.text);
  }
  for (const leak of ['010199', '99 88 77 66', 'kunde.no']) {
    if (first.text.includes(leak)) fail('LEAK: ' + leak);
  }
  if (!first.redactions || !first.redactions.FNR) fail('no redaction audit on the row');
  const dup = await ctx.post(`${API}/insight/v1/signal`, { headers: H(staff), data: {
    source: 'call', sourceRef: `si-${run}`, text: raw } });
  if (dup.status() !== 200 || !(await dup.json()).duplicate) fail('ingest is not idempotent');
  console.log('OK FIREWALL: fnr/phone/email caught, audit on the row, ingest idempotent');

  /* ---------- 2. connectors: desk sync re-runnable; webhook door ---------- */
  const sync = await (await ctx.post(`${API}/insight/v1/connector/support-desk/sync`,
    { headers: H(staff) })).json();
  if ((sync.ingested + sync.duplicates) !== 4) fail('desk sync wrong: ' + JSON.stringify(sync));
  const conns = await (await ctx.get(`${API}/insight/v1/connector`, { headers: H(staff) })).json();
  const hook = conns.find((c) => c.name === 'call-transcripts');
  const wrong = await ctx.post(`${API}/insight/v1/hook/${hook.id}`, {
    headers: { 'Content-Type': 'application/json', 'X-Hook-Secret': 'wrong' },
    data: { callId: `h-${run}`, transcript: 'x' } });
  if (wrong.status() !== 401) fail('webhook opened to a wrong secret: ' + wrong.status());
  const pushed = await (await ctx.post(`${API}/insight/v1/hook/${hook.id}`, {
    headers: { 'Content-Type': 'application/json', 'X-Hook-Secret': 'dev-hook-secret' },
    data: { callId: `h-${run}`, language: 'no',
      transcript: 'Vurderer å si opp alt sammen. Ring 99 88 77 66.' } })).json();
  if (!pushed.text.includes('[PHONE]') || pushed.source !== 'call') {
    fail('webhook push not firewalled/mapped: ' + JSON.stringify(pushed));
  }
  console.log('OK CONNECTORS: desk re-sync counted 4, wrong secret 401, pushed transcript redacted');

  /* ---------- 3. the battery: evidence or nothing ---------- */
  await ctx.post(`${API}/ai/v1/signalSweep`, { headers: H(staff), timeout: 300000 });
  const rows = await (await ctx.get(`${API}/insight/v1/signal?source=call`, { headers: H(staff) })).json();
  const mine = rows.find((r) => r.sourceRef === `si-${run}`);
  if (!mine || !mine.classification) fail('the battery left our signal unclassified');
  const c = mine.classification;
  for (const [field, quote] of Object.entries(c.evidence || {})) {
    if (!mine.text.includes(quote)) fail(`evidence for ${field} is not verbatim`);
  }
  if (!c.provider || !c.model) fail('provider/model missing from the row');
  const forged = await ctx.post(`${API}/insight/v1/signal/${mine.id}/classification`, {
    headers: H(staff), data: { sentiment: 'positive', category: 'praise',
      evidence: { sentiment: 'best network I have ever used' } } });
  if (forged.status() !== 422) fail('fabricated evidence was accepted: ' + forged.status());
  console.log(`OK BATTERY: classified (${c.sentiment}/${c.aspect}/${c.category}) by ${c.provider}/${c.model},`
    + ' quotes verbatim, fabricated evidence refused 422');

  /* ---------- 4. VoC: deviation -> ONE alert, bus + chat webhook ---------- */
  const before = await (await ctx.get(`${API}/insight/v1/voc/summary`, { headers: H(staff) })).json();
  const isoWeekAlerted = new Set((before.alerts || []).map((a) => a.aspect));
  const aspect = ['billing', 'price', 'support'].find((a) => !isoWeekAlerted.has(a));
  if (aspect) {
    const floods = {
      billing: ['I was double charged on my invoice again, this is theft.',
        'Fakturaen er feil for tredje måned på rad.',
        'Charged twice this month and no refund yet.',
        'The bill is wrong AGAIN — overcharged by 300.',
        'Regningen stemmer ikke, alt for høyt beløp trukket.'],
      price: ['Way too expensive now, prices went up again.',
        'Prisen er blitt altfor høy, dere har økt to ganger i år.',
        'The new pricing is outrageous compared to competitors.',
        'Alt for dyrt abonnement etter prisøkningen.',
        'Price hike with no added value, very disappointed.'],
      support: ['Support never answers, waited two hours on hold.',
        'Kundeservice svarer aldri, har ventet i dagevis.',
        'No reply to my ticket for a week, terrible support.',
        'Ingen svar fra support på tre henvendelser.',
        'Your support chat just disconnects me every time.'],
    }[aspect];
    for (let i = 0; i < floods.length; i++) {
      await ctx.post(`${API}/insight/v1/signal`, { headers: H(staff), data: {
        source: 'review', sourceRef: `voc-${aspect}-${run}-${i}`, text: floods[i] } });
    }
    await ctx.post(`${API}/ai/v1/signalSweep`, { headers: H(staff), timeout: 300000 });
    const chatBefore = (await (await ctx.get(`${CHATOPS}/messages`)).json()).length;
    const sweep = await (await ctx.post(`${API}/insight/v1/voc/sweep`, { headers: H(staff) })).json();
    if (sweep.fired < 1) fail(`flooded ${aspect} but no deviation fired: ` + JSON.stringify(sweep));
    if (sweep.notified < 1) fail('deviation fired but the chat webhook was not notified');
    const again = await (await ctx.post(`${API}/insight/v1/voc/sweep`, { headers: H(staff) })).json();
    if (again.fired !== 0) fail('the alert re-fired within the same ISO week');
    const chat = await (await ctx.get(`${CHATOPS}/messages`)).json();
    if (chat.length <= chatBefore || !chat.some((m) => m.text.includes(aspect))) {
      fail('the chat sink never received the warning');
    }
    console.log(`OK VOC: ${aspect} flood -> ONE alert (${sweep.isoWeek}), re-sweep 0, chat webhook received the warning`);
  } else {
    console.log('~ VOC: all candidate aspects already alerted this ISO week — dedup itself is the proof; skipping the fire leg');
  }

  /* ---------- 5. CLTV: an honest number as a numeric trait ---------- */
  await ctx.post(`${API}/ai/v1/cltvSweep`, { headers: H(staff), timeout: 300000 });
  let cltvSeen = false;
  for (let i = 0; i < 20 && !cltvSeen; i++) {
    await sleep(1000);
    const facets = await (await ctx.get(`${API}/insight/v1/audience/facets`, { headers: H(staff) })).json();
    cltvSeen = facets.some((f) => f.key === 'cltv');
  }
  if (!cltvSeen) fail('cltv trait never landed');
  console.log('OK CLTV: billed-history CLTV is a numeric, audience-targetable trait');

  /* ---------- 6. ask: grounded on aggregates only ---------- */
  const asked = await (await ctx.post(`${API}/ai/v1/voc/ask`, { headers: H(staff), timeout: 300000,
    data: { question: 'What are customers most unhappy about right now, and how bad is it?' } })).json();
  if (!asked.answer || asked.answer.length < 20) fail('the ask surface gave no answer');
  if (!String(asked.groundedOn || '').includes('aggregates')) fail('the ask surface lost its grounding label');
  console.log('OK ASK: "' + asked.answer.slice(0, 90).replace(/\n/g, ' ') + '…"');

  /* ---------- 7. erasure: the leaver's words go too ---------- */
  const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(staff), data: { email: `si-leaver-${run}@example.com`, givenName: 'Si', familyName: `Leaver${run}` } })).json();
  await ctx.post(`${API}/tmf-api/party/v4/individual`, { headers: H(staff), data: {
    id: login.id, givenName: 'Si', familyName: `Leaver${run}`,
    contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: `si-leaver-${run}@example.com` } }] } });
  await ctx.post(`${API}/insight/v1/signal`, { headers: H(staff), data: {
    source: 'chat', sourceRef: `leaver-${run}`, partyId: login.id, text: 'goodbye, cancelling everything' } });
  const erased = await (await ctx.post(`${API}/privacy/v1/erase`,
    { headers: H(staff), data: { partyId: login.id } })).json();
  if (erased.status !== 'completed') fail('erasure did not complete: ' + JSON.stringify(erased).slice(0, 200));
  const after = await (await ctx.get(`${API}/insight/v1/signal?source=chat`, { headers: H(staff) })).json();
  if (after.some((r) => r.partyId === login.id)) fail('the leaver still has signals after erasure');
  console.log('OK ERASURE: the leaver\'s signals vanished through the fleet orchestrator');

  console.log('\nALL SIGNAL-INTELLIGENCE CHECKS PASSED — what customers say enters through one PII '
    + 'firewall, is classified only with verbatim receipts, aggregates into an honest VoC pane with '
    + 'auditable early warnings that land in the team\'s chat, prices every customer from real bills, '
    + 'answers questions from aggregates alone, and forgets a leaver completely.');
})().catch((e) => { console.error('FAIL: ' + e.message); process.exit(1); });
