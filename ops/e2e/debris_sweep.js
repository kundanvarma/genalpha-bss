/* Battery preflight: collect the debris of dead runs.
 *
 * Suites mint fixtures with timestamped names and clean up when they finish —
 * but a run that dies mid-suite leaves ACTIVE journeys/campaigns firing on
 * every order forever (eating other parties' frequency-cap budget) and Active
 * uncategorized offerings sinking the seeds below the first catalog page.
 * One sweep before the battery keeps every suite honest about its own world. */
const API = 'http://localhost:8080';

async function token() {
  const res = await fetch('http://localhost:8085/realms/bss/protocol/openid-connect/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: 'grant_type=password&client_id=bss-demo&username=demo&password=demo',
  });
  return (await res.json()).access_token;
}

(async () => {
  const tok = await token();
  const H = { Authorization: 'Bearer ' + tok, 'Content-Type': 'application/json' };
  // a fixture name carries the run's epoch — seconds (10 digits) or millis (13),
  // anywhere in the name: "Advisor 1787910483898 7 GB" and "Mig Plan 1787398347"
  // sat on the shelf for weeks because the old pattern wanted it at the end;
  // a glued prefix ("SUITE1790318986519 …") counts too, so no word boundary in front
  const stale = (name) => /(?:^|\D)1[6-9]\d{8}(\d{3})?\b/.test(name || '');
  const get = async (path) => {
    const r = await fetch(`${API}${path}`, { headers: H });
    return r.ok ? r.json() : [];
  };

  let paused = 0;
  for (const kind of ['journey', 'campaign']) {
    const rows = await get(`/tmf-api/campaignManagement/v4/${kind}?limit=200`);
    for (const row of (Array.isArray(rows) ? rows : [])) {
      if (stale(row.name) && ['active', 'running'].includes(row.status)) {
        await fetch(`${API}/tmf-api/campaignManagement/v4/${kind}/${row.id}`, {
          method: 'PATCH', headers: H, body: JSON.stringify({ status: 'paused' }),
        }).catch(() => {});
        paused++;
      }
    }
  }

  // Offerings: DELETE, not retire. Retiring kept every dead run's fixtures on
  // the shelf for ever — 105 retired rows by 24 Sep 2026, most of the first
  // catalog page — and everything that reads "the first 100" (suites and, until
  // that day, the quote service) lost the real offerings behind them.
  // Collect first, delete after: deleting while paging shifts the offsets.
  const debris = [];
  for (let offset = 0; ; offset += 100) {
    const page = await get(`/tmf-api/productCatalogManagement/v4/productOffering?limit=100&offset=${offset}`);
    if (!Array.isArray(page) || !page.length) break;
    for (const o of page) if (stale(o.name)) debris.push(o.id);
    if (page.length < 100) break;
  }
  let deleted = 0;
  for (const id of debris) {
    const r = await fetch(`${API}/tmf-api/productCatalogManagement/v4/productOffering/${id}`, {
      method: 'DELETE', headers: H }).catch(() => null);
    if (r && r.ok) deleted++;
  }
  console.log(`debris sweep: ${paused} stale journeys/campaigns paused, ${deleted}/${debris.length} stale offerings deleted`);
})().catch((e) => { console.error('debris sweep skipped:', e.message); });
