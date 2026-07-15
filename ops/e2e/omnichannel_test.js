/* The OMNICHANNEL record — one timeline, whoever spoke.
 *
 *  - a martech blast lands on the TMF683 interaction log automatically
 *    (communication events -> touchpoints, idempotent on the event id)
 *  - a system-minted order notification lands there too — every message,
 *    not only marketing
 *  - an EXTERNAL system in the operator's landscape (a legacy CRM, a
 *    retail till) logs a phone call through the same open TMF683 POST
 *  - the CSR reads ONE timeline before speaking: what was said, on which
 *    channel, by which system — the raw material of personal service and
 *    omnichannel shopping
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const PLAN_ID = '14291c1a-df26-4232-8084-500466888e46'; // GenAlpha Mobile 10 GB

async function token(ctx, client, user, pass) {
  const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: client, username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const staff = await token(ctx, 'bss-demo', 'demo', 'demo');
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
  const INTERACTIONS = `${API}/tmf-api/partyInteraction/v4/partyInteraction`;
  const timeline = async (partyId) => (await (await ctx.get(
    `${INTERACTIONS}?limit=100&relatedPartyId=${partyId}`, { headers: H(staff) })).json());

  /* ---------- a consented customer in a run-unique segment ---------- */
  const email = `omni-${run}@example.com`;
  const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(staff), data: { email, givenName: 'Omni', familyName: `Channel${run}` } })).json();
  const vid = `omni-vis-${run}`;
  const SEG = `OmniCat${run}`;
  await ctx.post(`${API}/insight/v1/consent`, { headers: { 'Content-Type': 'application/json' },
    data: { visitorId: vid, analytics: true, personalization: true } });
  await ctx.post(`${API}/insight/v1/event`, { headers: { 'Content-Type': 'application/json' },
    data: { visitorId: vid, type: 'view', category: SEG } });
  const tok = await token(ctx, 'bss-biz', email, login.temporaryPassword);
  await ctx.post(`${API}/insight/v1/stitch`, { headers: H(tok), data: { visitorId: vid } });

  /* ---------- touchpoint 1: the MARTECH BLAST logs itself ---------- */
  const subject = `Omni pitch ${run}`;
  const campaign = await (await ctx.post(`${API}/tmf-api/campaignManagement/v4/campaign`,
    { headers: H(staff), data: { name: `Omni campaign ${run}`, segmentName: SEG,
      message: { subject, content: 'One pitch, on the record.' } } })).json();
  await ctx.post(`${API}/tmf-api/campaignManagement/v4/campaign/${campaign.id}/execute`,
    { headers: H(staff), data: {} });
  let entries = [];
  for (let i = 0; i < 20; i++) {
    entries = await timeline(login.id);
    if (entries.some((ix) => (ix.description || '').includes(subject))) break;
    await sleep(1500);
  }
  const blast = entries.find((ix) => (ix.description || '').includes(subject));
  if (!blast) fail('the campaign message never reached the interaction log');
  if (blast.sourceSystem !== 'communication' || blast.direction !== 'outbound') {
    fail('the touchpoint lost its origin: ' + JSON.stringify(blast).slice(0, 300));
  }
  console.log('OK the MARTECH BLAST logged itself: outbound, source "communication" — nobody'
    + ' had to remember to write it down');

  /* ---------- touchpoint 2: a system notification is a touchpoint too ---------- */
  await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: H(tok), data: {
      productOrderItem: [{ action: 'add', productOffering: { id: PLAN_ID, name: 'GenAlpha Mobile 10 GB' } }] } });
  let orderTouch = null;
  for (let i = 0; i < 30 && !orderTouch; i++) {
    await sleep(2000);
    entries = await timeline(login.id);
    orderTouch = entries.find((ix) => ix.sourceSystem === 'communication'
      && !(ix.description || '').includes(subject));
  }
  if (!orderTouch) fail('the order notification never reached the interaction log');
  console.log(`OK the ORDER NOTIFICATION is on the record too ("${orderTouch.description}") —`
    + ' every message, not only marketing');

  /* ---------- touchpoint 3: an EXTERNAL system logs a call ---------- */
  const crmNote = `Called about the fiber offer — prefers email, has 12 vans (${run})`;
  const posted = await ctx.post(INTERACTIONS, { headers: H(staff), data: {
    description: crmNote, channel: 'phone', direction: 'inbound',
    sourceSystem: 'legacy-crm',
    relatedParty: [{ id: login.id, role: 'customer' }] } });
  if (posted.status() !== 201) fail('the external system could not log: ' + posted.status());
  entries = await timeline(login.id);
  const call = entries.find((ix) => ix.description === crmNote);
  if (!call || !(call.channel === 'phone'
      || (Array.isArray(call.channel) && call.channel.some((c) => c.name === 'phone')))) {
    fail('the CRM call lost its channel: ' + JSON.stringify(call).slice(0, 200));
  }
  console.log('OK an EXTERNAL CRM logged a phone call through the same open TMF683 door —'
    + ' bring-your-own landscape, one log');

  /* ---------- the CSR reads ONE timeline ---------- */
  const sources = new Set(entries.map((ix) => ix.sourceSystem || 'csr'));
  if (entries.length < 3) fail('the timeline is missing touchpoints: ' + entries.length);
  if (!sources.has('communication')) fail('no communication touchpoints in the timeline');
  console.log(`OK ONE TIMELINE: ${entries.length} touchpoints across systems — the CSR knows`
    + ' what was said, on which channel, by whom, BEFORE they speak');

  console.log('\nALL OMNICHANNEL CHECKS PASSED — martech speaks, orders notify, the legacy CRM'
    + ' calls: it all lands on one TMF683 timeline. Personal service starts with knowing what'
    + ' the customer already heard.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
