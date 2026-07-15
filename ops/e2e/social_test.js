/* Social + analytics platform seams — the martech engine talks OUT.
 *
 *  - GA4 Data API import: nova's audience catalog arrives through the
 *    REAL wire shape (runReport: audienceName x activeUsers) — swap the
 *    mock for analyticsdata.googleapis.com and nothing else changes
 *  - Custom Audience push: a genalpha insight segment goes to "its"
 *    social platform as SHA-256 hashed emails (Meta schema) — PII never
 *    leaves in the clear, and a tenant with no platform is told so
 *  - Lead Ads import: the platform's lead-gen form entries land in the
 *    TMF699 pipeline as salesLeads, idempotent on the platform's id
 */
const { request } = require('playwright');
const crypto = require('crypto');

const API = 'http://localhost:8080';
const ANALYTICS = 'http://localhost:8120';
const SOCIAL = 'http://localhost:8122';
const run = Date.now();

async function token(ctx, realm, user, pass) {
  const res = await ctx.post(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`,
    { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });

  /* ---------- GA4 Data API: the audience catalog, through the real wire ---------- */
  const audienceName = `spenders-${run}`;
  await ctx.post(`${ANALYTICS}/audiences`, { headers: { 'Content-Type': 'application/json' },
    data: { client_id: `social-vis-${run}`, audiences: [audienceName] } });
  const novaStaff = await token(ctx, 'nova', 'demo', 'demo');
  const catalog = await (await ctx.get(`${API}/insight/v1/audiences`,
    { headers: H(novaStaff) })).json();
  const found = catalog.find((a) => a.name === audienceName);
  if (!found || found.size < 1 || found.source !== 'analytics') {
    fail('the audience never arrived through the Data API shape: ' + JSON.stringify(catalog).slice(0, 300));
  }
  const staff = await token(ctx, 'bss', 'demo', 'demo');
  const gaCatalog = await (await ctx.get(`${API}/insight/v1/audiences`, { headers: H(staff) })).json();
  if (gaCatalog.some((a) => a.name === audienceName)) {
    fail('nova\'s analytics audience leaked into genalpha\'s catalog');
  }
  console.log(`OK GA4 DATA API: nova's own analytics computed "${audienceName}" and the BSS`
    + ' imported it through the real runReport wire — and genalpha sees none of it');

  /* ---------- Custom Audience push: a segment leaves as hashes ---------- */
  const email = `pusher-${run}@example.com`;
  const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(staff), data: { email, givenName: 'Push', familyName: `Er${run}` } })).json();
  await ctx.post(`${API}/tmf-api/party/v4/individual`, { headers: H(staff), data: {
    id: login.id, givenName: 'Push', familyName: `Er${run}`,
    contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: email } }] } });
  const vid = `social-push-vis-${run}`;
  const SEG = `SocialCat${run}`;
  await ctx.post(`${API}/insight/v1/consent`, { headers: { 'Content-Type': 'application/json' },
    data: { visitorId: vid, analytics: true, personalization: true } });
  await ctx.post(`${API}/insight/v1/event`, { headers: { 'Content-Type': 'application/json' },
    data: { visitorId: vid, type: 'view', category: SEG } });
  const custTok = await token(ctx, 'bss', email, login.temporaryPassword);
  await ctx.post(`${API}/insight/v1/stitch`, { headers: H(custTok), data: { visitorId: vid } });

  const audienceId = `aud-${run}`;
  const sync = await (await ctx.post(`${API}/tmf-api/campaignManagement/v4/audienceSync`,
    { headers: H(staff), data: { segmentName: SEG, audienceId } })).json();
  if (sync.members !== 1 || sync.withEmail !== 1 || sync.pushed !== 1) {
    fail('the push miscounted: ' + JSON.stringify(sync));
  }
  const hashes = await (await ctx.get(`${SOCIAL}/v1/${audienceId}/users`,
    { headers: { Authorization: 'Bearer x' } })).json();
  const expected = crypto.createHash('sha256').update(email.toLowerCase()).digest('hex');
  if (!hashes.includes(expected)) fail('the platform did not receive the SHA-256 of the email');
  if (hashes.some((h) => h.includes('@'))) fail('an email left the building IN THE CLEAR');
  console.log('OK CUSTOM AUDIENCE: the segment reached the platform as SHA-256 hashes — the'
    + ' Meta schema, and not one address in the clear');

  /* the seam is per-tenant: nova has no platform, and is told so */
  const wall = await ctx.post(`${API}/tmf-api/campaignManagement/v4/audienceSync`,
    { headers: H(novaStaff), data: { segmentName: SEG, audienceId } });
  if (wall.status() !== 400) fail('a tenant without a platform was allowed to push: ' + wall.status());
  console.log('OK the seam is per-tenant: nova has no social platform configured and the push'
    + ' says so instead of pretending');

  /* ---------- Lead Ads import: the form feeds the TMF699 pipeline ---------- */
  const FORM = 'form-genalpha-fiber';
  await ctx.post(`${SOCIAL}/v1/${FORM}/leads`, { headers: { 'Content-Type': 'application/json' },
    data: { full_name: 'Lea D. Gen', email: `lea-${run}@ads.example`,
      company: 'AdCo', need: `Office fiber for 12 people ${run}` } });
  await ctx.post(`${SOCIAL}/v1/${FORM}/leads`, { headers: { 'Content-Type': 'application/json' },
    data: { full_name: 'Sofia Scroll', email: `sofia-${run}@ads.example`,
      need: `Backup 5G router ${run}` } });

  const imported = await (await ctx.post(
    `${API}/tmf-api/salesManagement/v4/salesLead/importSocial`,
    { headers: H(staff), data: {} })).json();
  if (imported.imported !== 2) fail('the import missed the form entries: ' + JSON.stringify(imported));
  const leads = await (await ctx.get(`${API}/tmf-api/salesManagement/v4/salesLead`,
    { headers: H(staff) })).json();
  const lea = leads.find((l) => l.name === `Office fiber for 12 people ${run}`);
  if (!lea || lea.source !== 'social' || lea.contactEmail !== `lea-${run}@ads.example`
      || lea.company !== 'AdCo' || lea.state !== 'acknowledged') {
    fail('the social lead lost its story: ' + JSON.stringify(lea));
  }
  const again = await (await ctx.post(
    `${API}/tmf-api/salesManagement/v4/salesLead/importSocial`,
    { headers: H(staff), data: {} })).json();
  if (again.imported !== 0) fail('re-import duplicated leads: ' + JSON.stringify(again));
  console.log('OK LEAD ADS: two form entries became acknowledged TMF699 salesLeads with their'
    + ' story intact — and the re-import found nothing new (idempotent on the platform\'s id)');

  console.log('\nALL SOCIAL CHECKS PASSED — the BSS speaks the platforms\' own wire shapes:'
    + ' GA4 runReport in, Custom Audiences out (hashed), Lead Ads into the sales pipeline.'
    + ' Swap a mock host for the real one and it is config, not code.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
