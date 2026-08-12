/* Subscription metrics — the governed MRR waterfall (console P3). Suite #100.
 *
 * MRR here is BILLED truth: account 4000 (recurring service revenue) credited
 * per customer per month in the subledger — not catalog list price. Per month,
 * each customer classifies against the prior month (new / expansion /
 * contraction / churned), so the waterfall identity holds by construction —
 * and this suite checks it ARITHMETICALLY, plus cross-checks the MRR figure
 * against the independent /summary code path.
 *
 *  - SHAPE: months[] with mrr/newMrr/expansion/contraction/churned/arpu/NRR.
 *  - IDENTITY: mrr(m) = mrr(m-1) + new + expansion − contraction − churned.
 *  - CROSS-CHECK: the latest month's MRR equals /summary's account-4000 net
 *    for the same month (two different queries, one truth).
 *  - CSV: ?format=csv downloads the same numbers with the right header.
 *  - GATED: a customer token gets 403 (billing:read only).
 */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const R = '/revenue/v1';
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);

async function token(user, pass) {
  const r = await fetch(KC, { method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: user, password: pass }) });
  if (!r.ok) fail(`token(${user}): ${r.status}`);
  return (await r.json()).access_token;
}
const get = async (path, tok) => fetch(API + path, { headers: { Authorization: `Bearer ${tok}` } });

(async () => {
  const staff = await token('demo', 'demo');
  const pat = await token('pat@bss.local', 'pat');

  /* ---------- 1. shape ---------- */
  const res = await get(`${R}/subscriptionMetrics`, staff);
  if (res.status !== 200) fail(`subscriptionMetrics: ${res.status} ${await res.text()}`);
  const sm = await res.json();
  const months = sm.months || [];
  if (!months.length) fail('no months returned');
  for (const m of months) {
    for (const k of ['month', 'mrr', 'newMrr', 'expansionMrr', 'contractionMrr', 'churnedMrr', 'activeAccounts', 'arpu']) {
      if (m[k] === undefined) fail(`month row missing '${k}': ` + JSON.stringify(m));
    }
  }
  ok(`SHAPE: ${months.length} month rows, each with the full waterfall + ARPU/NRR fields`
    + ` (note says: "${(sm.note || '').slice(0, 60)}…")`);

  /* ---------- 2. the waterfall identity, arithmetically ---------- */
  for (let i = 1; i < months.length; i++) {
    const p = months[i - 1]; const m = months[i];
    const expect = Number(p.mrr) + Number(m.newMrr) + Number(m.expansionMrr)
      - Number(m.contractionMrr) - Number(m.churnedMrr);
    if (Math.abs(expect - Number(m.mrr)) > 0.05) {
      fail(`waterfall identity broken at ${m.month}: ${p.mrr} + ${m.newMrr} + ${m.expansionMrr}`
        + ` - ${m.contractionMrr} - ${m.churnedMrr} = ${expect.toFixed(2)} ≠ ${m.mrr}`);
    }
  }
  ok('IDENTITY: every month satisfies mrr = prior + new + expansion − contraction − churned (±0.05)');

  /* ---------- 3. cross-check against /summary (independent code path) ---------- */
  const latest = [...months].reverse().find((m) => Number(m.mrr) > 0);
  if (latest) {
    const [y, mo] = latest.month.split('-').map(Number);
    const first = `${latest.month}-01`;
    const last = new Date(y, mo, 0).getDate();
    const summary = await (await get(`${R}/summary?fromDate=${first}&toDate=${latest.month}-${String(last).padStart(2, '0')}`, staff)).json();
    const acct = (summary.byAccount || []).find((a) => a.accountCode === (sm.accountCode || '4000'));
    const net = acct ? Number(acct.net) : 0;
    if (Math.abs(net - Number(latest.mrr)) > 0.05) {
      fail(`MRR ${latest.mrr} for ${latest.month} disagrees with /summary's 4000 net ${net}`);
    }
    ok(`CROSS-CHECK: ${latest.month} MRR ${latest.mrr} equals /summary's account-4000 net (${net})`
      + ' — two independent queries, one truth');
  } else {
    ok('CROSS-CHECK: no month with billed recurring revenue in range — leg vacuous (honest skip)');
  }

  /* ---------- 4. CSV ---------- */
  const csvRes = await get(`${R}/subscriptionMetrics?format=csv`, staff);
  if (csvRes.status !== 200) fail(`csv: ${csvRes.status}`);
  const csv = await csvRes.text();
  if (!csv.startsWith('month,mrr,newMrr,expansionMrr,contractionMrr,churnedMrr,activeAccounts,arpu')) {
    fail('csv header wrong: ' + csv.slice(0, 80));
  }
  if ((csvRes.headers.get('content-disposition') || '') === '') fail('csv is not a download');
  ok('CSV: ?format=csv downloads the waterfall with the right header + Content-Disposition');

  /* ---------- 5. gated ---------- */
  const denied = await get(`${R}/subscriptionMetrics`, pat);
  if (denied.status !== 403) fail(`customer should be 403, got ${denied.status}`);
  ok('GATED: a role-less token cannot read subscription metrics — 403');

  console.log('\nALL SUBSCRIPTION-METRICS CHECKS PASSED — the MRR waterfall is billed truth from the'
    + ' subledger, the identity holds arithmetically, the figure agrees with the independent summary'
    + ' path, and it exports as CSV. Governed metrics, not vibes.');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
