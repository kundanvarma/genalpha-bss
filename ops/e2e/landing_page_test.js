/* Landing page + lead capture — the acquisition loop, closed.
 * Author a standalone landing page (not the storefront); a public URL renders it
 * with a consent-first form; a ticked submission becomes a CONSENTED prospect
 * stamped with the campaign — so a prospect audience {source=campaign} nurtures
 * the lead. No consent, no capture.
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const LANDING = `${API}/insight/v1/landing`;
const AUD = `${API}/insight/v1/audience`;

async function token(ctx) {
  const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const staff = await token(ctx);
  const H = { Authorization: 'Bearer ' + staff, 'Content-Type': 'application/json' };
  const CAMPAIGN = `iphone-upgrade-${run}`;
  const slug = `iphone-upgrade-${run}`;

  // author the landing page (back-office)
  const page = await (await ctx.post(LANDING, { headers: H, data: {
    slug, headline: 'Upgrade to the iPhone 17 — €0 upfront',
    subhead: 'Trade in your old phone and switch in minutes.', ctaLabel: 'Claim the offer',
    utmSource: CAMPAIGN } })).json();
  if (!page.url || page.slug !== slug) fail('landing page not created: ' + JSON.stringify(page));
  console.log(`OK a standalone landing page was authored at ${page.url}`);

  // the PUBLIC page renders — no auth — with the headline + a lead form
  const view = await ctx.get(`${LANDING}/${slug}/view`); // note: no Authorization header
  const html = await view.text();
  if (view.status() !== 200) fail('public landing view was not accessible: HTTP ' + view.status());
  if (!html.includes('iPhone 17') || !html.includes('<form') || !html.includes('type="checkbox"')) {
    fail('landing page HTML is missing the headline or the consent form');
  }
  console.log('OK the landing page is publicly reachable and renders the headline + a consent-first form');

  // a submit WITHOUT consent captures nothing
  const noConsent = await (await ctx.post(`${LANDING}/${slug}/lead`,
    { headers: { 'Content-Type': 'application/json' }, data: { name: 'No One', email: `noc-${run}@x.com`, consent: false } })).json();
  if (noConsent.captured !== false) fail('a lead was captured WITHOUT consent: ' + JSON.stringify(noConsent));
  console.log('OK no consent → no capture (consent is enforced, not wished for)');

  // a consented submit → a captured lead
  const email = `lead-${run}@example.com`;
  const cap = await (await ctx.post(`${LANDING}/${slug}/lead`,
    { headers: { 'Content-Type': 'application/json' }, data: { name: 'Real Lead', email, consent: true } })).json();
  if (cap.captured !== true || cap.source !== CAMPAIGN) fail('a consented lead was not captured: ' + JSON.stringify(cap));
  console.log('OK a consented submit captured the lead, stamped with the campaign source');

  // the loop closes: a prospect audience for the campaign contains the lead
  const aud = await (await ctx.post(AUD, { headers: H, data: {
    name: `__landing_${run}`, population: 'prospect', criteria: { all: [{ type: 'source', value: CAMPAIGN }] } } })).json();
  const members = await (await ctx.get(`${AUD}/${aud.id}/members`, { headers: H })).json();
  const emails = (Array.isArray(members) ? members : []).map((m) => m.email);
  if (!emails.includes(email)) fail('the captured lead is not in the campaign prospect audience: ' + JSON.stringify(emails).slice(0, 200));
  if (emails.includes(`noc-${run}@x.com`)) fail('the no-consent visitor leaked into the reachable audience');
  console.log('OK the loop closes — the consented lead is a reachable prospect in the campaign audience (the no-consent one is not)');

  await ctx.delete(`${AUD}/${aud.id}`, { headers: H });
  console.log('\nALL LANDING-PAGE CHECKS PASSED — a standalone campaign landing page (beyond the storefront) '
    + 'captures consented leads into the CDP, stamped with the campaign, so ad/email → landing → lead → '
    + 'prospect audience → nurture is one closed, consent-first loop.');
})();
