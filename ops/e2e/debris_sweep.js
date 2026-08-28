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
  const stale = (name) => / \d{12,}$/.test(name || '');
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

  let retired = 0;
  for (let offset = 0; ; offset += 100) {
    const page = await get(`/tmf-api/productCatalogManagement/v4/productOffering?limit=100&offset=${offset}`);
    if (!Array.isArray(page) || !page.length) break;
    for (const o of page) {
      if (stale(o.name) && o.lifecycleStatus === 'Active') {
        await fetch(`${API}/tmf-api/productCatalogManagement/v4/productOffering/${o.id}`, {
          method: 'PATCH', headers: H, body: JSON.stringify({ lifecycleStatus: 'Retired' }),
        }).catch(() => {});
        retired++;
      }
    }
    if (page.length < 100) break;
  }
  console.log(`debris sweep: ${paused} stale journeys/campaigns paused, ${retired} stale offerings retired`);
})().catch((e) => { console.error('debris sweep skipped:', e.message); });
