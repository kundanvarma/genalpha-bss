/* Battery preflight: collect the debris of dead runs.
 *
 * Suites mint fixtures with timestamped names and clean up when they finish —
 * but a run that dies mid-suite leaves ACTIVE journeys/campaigns firing on
 * every order forever (eating other parties' frequency-cap budget) and Active
 * uncategorized offerings sinking the seeds below the first catalog page.
 * One sweep before the battery keeps every suite honest about its own world.
 *
 * PARTIES are a separate mode, and they are NOT swept before a battery.
 * `node debris_sweep.js --parties` COUNTS and reports; acting on a person
 * needs `--parties-act` typed on purpose, because a customer is not a draft
 * offering — see party_debris.js for the rule and ../../docs/demo-data-hygiene.md
 * for why. Everything the party mode does rides one realm's token, so it can
 * only ever see and touch that one tenant. */
const parties = require('./party_debris');

const API = 'http://localhost:8080';

async function token() {
  const res = await fetch('http://localhost:8085/realms/bss/protocol/openid-connect/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: 'grant_type=password&client_id=bss-demo&username=demo&password=demo',
  });
  return (await res.json()).access_token;
}

/* ---------------------------- the party mode ---------------------------- */

const flag = (name, fallback = null) => {
  const hit = process.argv.find((a) => a === `--${name}` || a.startsWith(`--${name}=`));
  if (!hit) {
    return fallback;
  }
  return hit.includes('=') ? hit.slice(hit.indexOf('=') + 1) : true;
};

async function sweepParties() {
  const realm = flag('realm', 'bss');
  const user = flag('user', 'demo');
  const pass = flag('pass', 'demo');
  const act = flag('parties-act', false) === true;
  const includeNamed = flag('include-named', false) === true;
  const journalDays = Number(flag('journal-days', 400));
  const only = typeof flag('only') === 'string'
    ? new Set(String(flag('only')).split(',').map((s) => s.trim()).filter(Boolean))
    : null;

  const gateway = typeof flag('api') === 'string' ? String(flag('api')) : parties.API;
  const ctx = parties.ctxFor(await parties.tokenFor(realm, user, pass), gateway);
  const keep = parties.loadKeep(realm);
  console.log(`party debris — realm ${realm} as ${user}; the token's issuer is the tenant,`
    + ' and row-level security is the wall under it');
  console.log(`  keep-list: ${keep.source}`);

  const people = await ctx.pageAll('/tmf-api/party/v4/individual');
  console.log(`  individuals: ${people.length}`);
  const ledger = await parties.buildLedger(ctx, { journalDays, log: (m) => console.log(m) });

  const rows = [];
  for (const person of people) {
    if (only && !only.has(person.id)) {
      continue;
    }
    const hit = parties.classify(person, keep, { includeNamed });
    if (!hit) {
      continue;
    }
    const fp = parties.footprint(ledger, person.id);
    rows.push({ person, hit, fp, decision: parties.decide(fp) });
  }
  if (only) {
    for (const id of only) {
      if (rows.some((r) => r.person.id === id)) {
        continue;
      }
      // silence would read like "handled"; say which kind of nothing this is
      console.log(people.some((p) => p.id === id)
        ? `  --only ${id}: in this tenant, but not a fixture — left alone`
        : `  --only ${id}: not in this tenant`);
    }
  }

  const tally = { delete: 0, retire: 0, keep: 0 };
  for (const r of rows) {
    tally[r.decision.action]++;
  }
  console.log(`\n  ${rows.length} fixture parties: `
    + `${tally.delete} deletable, ${tally.retire} to retire, ${tally.keep} held by active services`);
  console.log('  action   bills orders  svc post  party                          '
    + 'caught by · why that action');
  for (const r of rows.slice(0, Number(flag('show', 40)))) {
    const f = r.fp;
    console.log(`  ${r.decision.action.padEnd(8)} `
      + `${String(f.bills).padStart(5)} ${String(f.orders).padStart(6)} `
      + `${String(f.services).padStart(4)} ${String(f.postings).padStart(4)}  `
      + `${parties.displayName(r.person).slice(0, 30).padEnd(30)} `
      + `${r.hit.tier} · ${r.decision.reason}`);
  }
  if (rows.length > Number(flag('show', 40))) {
    console.log(`  … ${rows.length - Number(flag('show', 40))} more (--show=N)`);
  }

  if (!act) {
    console.log('\n  COUNTED ONLY — nothing was changed.'
      + ' Add --parties-act to carry these out, --only=<id>[,<id>] to act on named parties.');
    return;
  }
  if (ledger.journal.truncated) {
    throw new Error('the journal walk was truncated — the posting counts are a floor,'
      + ' so "nothing points at this person" cannot be proved. Refusing to act.');
  }
  console.log('\n  acting:');
  const done = { delete: 0, retire: 0, keep: 0, failed: 0 };
  for (const r of rows) {
    const out = await parties.apply(ctx, r.person, r.decision);
    if (!out.ok) {
      done.failed++;
      console.log(`  FAILED ${parties.displayName(r.person)}: ${out.detail}`);
      continue;
    }
    done[r.decision.action]++;
  }
  console.log(`  ${done.delete} deleted, ${done.retire} retired, `
    + `${done.keep} left in place, ${done.failed} failed`);
}

(async () => {
  if (flag('parties', false) || flag('parties-act', false)) {
    await sweepParties();
    return;
  }
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
})().catch((e) => {
  // the battery preflight is allowed to be skipped; a party run asked for
  // on purpose is not — it must not print a failure and exit 0
  console.error('debris sweep skipped:', e.message);
  if (flag('parties', false) || flag('parties-act', false)) {
    process.exitCode = 1;
  }
});
