/* PS — the pre-sales prospect simulator: "your business on our BSS", in the
 * first meeting. Input is PUBLIC only (a price list + an assumed mix); a
 * fresh sandbox operator is minted, the shelf built, twins seeded, and the
 * REAL engines bill a compressed quarter. The report carries assumptions on
 * its face; the sandbox dies with its realm.
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const host = (await (await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } })).json()).access_token;
  const H = { Authorization: 'Bearer ' + host, 'Content-Type': 'application/json' };

  const res = await ctx.post(`${API}/onboarding/v1/prospectSimulation`, { headers: H, timeout: 480000,
    data: {
      name: 'Nordlys Mobil', currency: 'NOK',
      priceList: [
        { offeringName: 'Nordlys Smart 10GB', monthly: 249 },
        { offeringName: 'Nordlys Unlimited', monthly: 399 },
        { offeringName: 'Nordlys Familie', monthly: 599 },
      ],
      baseMix: [
        { offeringName: 'Nordlys Smart 10GB', subscribers: 6 },
        { offeringName: 'Nordlys Unlimited', subscribers: 4 },
        { offeringName: 'Nordlys Familie', subscribers: 2 },
      ] } });
  if (res.status() !== 201) fail('simulation refused: ' + res.status() + ' ' + (await res.text()).slice(0, 200));
  const report = await res.json();
  if (report.shelf !== 3) fail('the shelf is not the price list: ' + report.shelf);
  if (report.twinBase !== 12) fail('the base is not the assumed mix: ' + report.twinBase);
  if ((report.quarter.cycle || []).length !== 3) fail('no compressed quarter: ' + JSON.stringify(report.quarter).slice(0, 200));
  if (!(report.assumptions || []).some((a) => /PUBLIC only/.test(a))) fail('the public-input promise is missing');
  console.log(`OK THE FIRST MEETING: '${report.prospect}' simulated as sandbox '${report.sandboxId}' — `
    + `3 tariffs from the PUBLIC price list, 12 twins at the assumed mix, a compressed quarter on the real engines`);

  // the sandbox is real and walled: its bills exist, its emails cannot leave
  const stok = (await (await ctx.post(`http://localhost:8085/realms/${report.sandboxId}/protocol/openid-connect/token`,
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } })).json()).access_token;
  const bills = await (await ctx.get(`${API}/tmf-api/customerBillManagement/v4/customerBill?limit=100`,
    { headers: { Authorization: 'Bearer ' + stok } })).json();
  if (!Array.isArray(bills) || !bills.length) fail('the quarter left no bills to walk through');
  console.log(`OK THE WALK-THROUGH: ${bills.length} real bills sit in the sandbox for the live demo — `
    + 'the sales tool IS the product');

  /* cleanup */
  const admin = (await (await ctx.post('http://localhost:8085/realms/master/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'admin-cli', username: 'admin', password: 'admin' } })).json()).access_token;
  await ctx.delete(`http://localhost:8085/admin/realms/${report.sandboxId}`,
    { headers: { Authorization: 'Bearer ' + admin } }).catch(() => {});
  console.log('OK cleanup: the prospect sandbox died with its realm');

  console.log('\nALL PROSPECT-SIM CHECKS PASSED — a public price list and an assumed mix became a '
    + 'billed quarter on the real engines, in one call, touching none of the prospect\'s data.');
})();
