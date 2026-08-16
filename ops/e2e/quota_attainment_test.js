/* O2c — quota + attainment. Author a quota for an owner in a period, put a
 * won deal and an open deal on that owner, and check attainment: won vs quota,
 * and coverage (won + weighted-open) vs quota. genalpha (bss realm); demo/demo.
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const SALES = `${API}/tmf-api/salesManagement/v4`;
const run = Date.now();
const OWNER = `AE-${run}`;
const now = new Date();
const PERIOD = `${now.getUTCFullYear()}-${String(now.getUTCMonth() + 1).padStart(2, '0')}`;

async function token(ctx) {
  const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const tok = await token(ctx);
  const H = { Authorization: 'Bearer ' + tok, 'Content-Type': 'application/json' };
  const num = (v) => Number(v);

  const mkOpp = async () => {
    const lead = await (await ctx.post(`${SALES}/salesLead`, { headers: H, data: { name: `Quota ${run} ${Math.random()}`, source: 'campaign' } })).json();
    const q = await (await ctx.patch(`${SALES}/salesLead/${lead.id}`, { headers: H, data: { state: 'qualified' } })).json();
    return q.salesOpportunity.id;
  };

  /* ---------- a quota for the owner this period ---------- */
  await ctx.post(`${SALES}/salesOpportunity/quota`, { headers: H, data: { ownerName: OWNER, quotaPeriod: PERIOD, amount: 10000 } });
  console.log(`OK authored a quota of 10000 for ${OWNER} in ${PERIOD}`);

  /* ---------- a won deal (4000) and an open deal (6000 @ proposal 50%) ---------- */
  const wonOpp = await mkOpp();
  await ctx.patch(`${SALES}/salesOpportunity/${wonOpp}`, { headers: H, data: { ownerName: OWNER, amount: 4000, stage: 'proposal' } });
  await ctx.patch(`${SALES}/salesOpportunity/${wonOpp}`, { headers: H, data: { state: 'won' } });

  const openOpp = await mkOpp();
  await ctx.patch(`${SALES}/salesOpportunity/${openOpp}`, { headers: H, data: { ownerName: OWNER, amount: 6000, stage: 'proposal' } });

  /* ---------- attainment ---------- */
  const att = await (await ctx.get(`${SALES}/salesOpportunity/quotaAttainment?period=${PERIOD}`, { headers: H })).json();
  const row = (att.owners || []).find((o) => o.owner === OWNER);
  if (!row) fail('no attainment row for the owner: ' + JSON.stringify(att));
  if (num(row.quota) !== 10000) fail('quota wrong: ' + JSON.stringify(row.quota));
  if (num(row.won) !== 4000) fail('won-in-period should be 4000: ' + JSON.stringify(row.won));
  if (num(row.weightedOpen) !== 3000) fail('weighted-open should be 6000×50% = 3000: ' + JSON.stringify(row.weightedOpen));
  if (row.attainmentPct !== 40) fail('attainment should be 40% (4000/10000): ' + JSON.stringify(row.attainmentPct));
  if (row.coveragePct !== 70) fail('coverage should be 70% ((4000+3000)/10000): ' + JSON.stringify(row.coveragePct));
  console.log(`OK ${OWNER}: won ${row.won}/${row.quota} = ${row.attainmentPct}% attainment, ${row.coveragePct}% covered with the weighted pipeline`);

  console.log('\nALL QUOTA-ATTAINMENT CHECKS PASSED — a quota per owner/period, with attainment (closed vs quota) '
    + 'and coverage (closed + weighted pipeline vs quota) — the number a VP of Sales opens on Monday.');
})();
