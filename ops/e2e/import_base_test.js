/* B-M1 — the real base importer: the migration door. On a THROWAWAY
 * operator: legacy rows become logins + parties + active products carrying
 * real MSISDNs; a missing offering is an exception BY NAME; a re-run is
 * idempotent (alreadyPresent, nothing created); the imported customer can
 * actually SIGN IN and sees their own number. */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const OP = `ib${String(run).slice(-6)}`;

async function token(ctx, realm, client, user, pass) {
  const res = await ctx.post(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`,
    { form: { grant_type: 'password', client_id: client, username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const host = await token(ctx, 'bss', 'bss-demo', 'demo', 'demo');
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });

  await ctx.post(`${API}/onboarding/v1/operator`, { headers: H(host),
    data: { id: OP, name: 'Import Probe', locale: 'en', currency: 'EUR' } });
  let staff = null;
  for (let i = 0; i < 30 && !staff; i++) {
    await sleep(3000);
    staff = await token(ctx, OP, 'bss-demo', 'demo', 'demo').catch(() => null);
  }
  if (!staff) fail('no staff token from the fresh realm');
  console.log(`OK operator '${OP}' minted (the onboard seed gives it a small catalog)`);

  const offerings = await (await ctx.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=10`, { headers: H(staff) })).json();
  const plan = offerings.find((o) => /Mobile/.test(o.name || '')) || offerings[0];
  if (!plan) fail('the newborn has no offering to import onto');

  const rows = [
    { externalRef: 'LEG-001', givenName: 'Astrid', familyName: 'Berg', email: `astrid-${run}@example.com`,
      msisdn: '+4740000001', offeringName: plan.name },
    { externalRef: 'LEG-002', givenName: 'Ola', familyName: 'Vik', email: `ola-${run}@example.com`,
      msisdn: '+4740000002', offeringName: plan.name },
    { externalRef: 'LEG-003', givenName: 'Kari', familyName: 'Holm', email: `kari-${run}@example.com`,
      offeringName: `No Such Plan ${run}` },
  ];
  // the newborn tenant's machine tokens propagate to the fleet on the
  // registry refresh tick — a first-seconds import can 401 downstream, so
  // retry the import (it is idempotent: alreadyPresent rows re-classify)
  let report = null;
  for (let attempt = 1; attempt <= 4; attempt++) {
    const res = await ctx.post(`${API}/onboarding/v1/operator/${OP}/importBase`,
      { headers: H(host), data: { rows }, timeout: 240000 });
    if (res.status() !== 201) fail('import refused: ' + res.status() + ' ' + (await res.text()).slice(0, 200));
    report = await res.json();
    const settled = (report.imported + report.alreadyPresent) === 2 && report.offeringMissing === 1;
    if (settled) break;
    const auth401 = JSON.stringify(report.exceptions || {}).includes('401');
    if (!auth401 || attempt === 4) break;
    await new Promise((r) => setTimeout(r, 15000));
  }
  if ((report.imported + report.alreadyPresent) !== 2 || report.offeringMissing !== 1) {
    fail('classification wrong: ' + JSON.stringify(report).slice(0, 300));
  }
  if (report.exceptions.offeringMissing[0].externalRef !== 'LEG-003') fail('missing row misattributed');
  if (report.readyForCutover !== false) fail('the cutover flag must be honest with exceptions open');
  const astrid = report.customers.find((c) => c.externalRef === 'LEG-001');
  const astridCarried = JSON.stringify(report.exceptions?.alreadyPresent || []).includes('LEG-001');
  if (!astridCarried && (!astrid || !astrid.temporaryPassword)) {
    fail('no handover credential for LEG-001');
  }
  console.log('OK IMPORTED: 2 customers landed with logins + products + numbers; the missing '
    + 'offering is an exception BY NAME and the cutover flag stays honest');

  /* idempotency: the re-run creates NOTHING */
  const again = await (await ctx.post(`${API}/onboarding/v1/operator/${OP}/importBase`,
    { headers: H(host), data: { rows }, timeout: 240000 })).json();
  if (again.imported !== 0 || again.alreadyPresent !== 2) {
    fail('the re-run was not idempotent: ' + JSON.stringify(again).slice(0, 200));
  }
  console.log('OK IDEMPOTENT: the same file twice — 0 imported, 2 alreadyPresent, nothing duplicated');

  /* the proof that matters: the migrated customer SIGNS IN and sees her number */
  const her = await token(ctx, OP, 'bss-biz', astrid.email, astrid.temporaryPassword).catch(() => null);
  if (!her) fail('the imported customer cannot sign in with the handover credential');
  const services = await (await ctx.get(`${API}/tmf-api/serviceInventory/v4/service`,
    { headers: H(her) })).json();
  const hers = (Array.isArray(services) ? services : []).find((sv) =>
    (sv.supportingResource || []).some((r) => r.value === '+4740000001'));
  if (!hers) fail('her service/number is not visible to her own login: '
    + JSON.stringify(services).slice(0, 200));
  console.log('OK THE CUSTOMER IS REAL: Astrid signs in with the handover password and her own '
    + 'MSISDN rides her product — day one on the new BSS works');

  /* cleanup */
  const admin = (await (await ctx.post('http://localhost:8085/realms/master/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'admin-cli', username: 'admin', password: 'admin' } })).json()).access_token;
  await ctx.delete(`http://localhost:8085/admin/realms/${OP}`,
    { headers: { Authorization: 'Bearer ' + admin } }).catch(() => {});
  console.log('OK cleanup: probe realm deleted');

  console.log('\nALL BASE-IMPORT CHECKS PASSED — a legacy export becomes logins, parties, products '
    + 'and numbers, idempotently, with exceptions by name and an honest cutover flag: the migration door exists.');
})();
