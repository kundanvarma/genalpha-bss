/* The party sweep counts before it removes, and removes by the rule. Suite #239.
 *
 * Every fixture in this suite is minted by this suite and taken away by it:
 * three throwaway people in genalpha and one in taranga. Nothing the demo
 * cares about is touched, and the counting mode is proved read-only by
 * counting three people and then finding all three still there.
 *
 * What it proves:
 *   1. counting is counting — the fixtures are reported, and survive the report
 *   2. nothing points at them  -> delete, and delete really removes the row
 *   3. something points at them -> retire: the privacy path, anonymized in
 *      place, the id still resolving so the record that points at it still does
 *   4. an ACTIVE service -> kept, with the reason, because the eraser refuses
 *   5. a person seeded in the realm file is never a candidate
 *   6. THE TENANT WALL: a party that exists only in taranga is invisible to a
 *      genalpha token — the sweep cannot reach across tenants because the
 *      tenant is the token's issuer and row-level security is under it
 *   7. the truncation guard goes red when the journal cannot be read whole
 *   8. a suite's own cleanup AND the sweep itself, run against a gateway with
 *      one component down, refuse — they never read unreachable as empty
 *
 * One counting sweep answers 1-6: every question is a row in the same report,
 * and a report costs a walk of the tenant's bills, orders and services.
 */
const { execFileSync } = require('child_process');
const http = require('node:http');
const path = require('path');
const parties = require('./party_debris');

const run = Date.now();
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);
const SWEEP = path.join(__dirname, 'debris_sweep.js');

/* Three days of journal, not the default year: this suite's fixtures are
 * minutes old, and a full-year walk per sweep would make this the slowest
 * suite in the battery for nothing. The window's own guard is proved below. */
const DAYS = '--journal-days=3';
const sweep = (args) => execFileSync('node', [SWEEP, DAYS, ...args],
  { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });

/** One throwaway fixture person, named the way a dead suite run names one. */
async function mint(ctx, who) {
  const r = await ctx.call('POST', '/tmf-api/party/v4/individual', {
    givenName: who, familyName: `Sweepfix${run}`,
    contactMedium: [{ mediumType: 'email',
      characteristic: { emailAddress: `sweepfix-${who.toLowerCase()}-${run}@example.com` } }],
  });
  if (r.status !== 201 || !r.body || !r.body.id) {
    fail(`mint ${who}: ${r.status} ${r.text.slice(0, 160)}`);
  }
  return r.body;
}

async function giveProduct(ctx, partyId, status) {
  const r = await ctx.call('POST', '/tmf-api/productInventory/v4/product', {
    name: `Sweepfix${run} line`, status,
    relatedParty: [{ id: partyId, role: 'customer' }],
  });
  if (r.status !== 201 || !r.body || !r.body.id) {
    fail(`product(${status}): ${r.status} ${r.text.slice(0, 160)}`);
  }
  return r.body.id;
}

/* Does this party still exist? 200 or 404 are answers; anything else is the
 * fleet not answering, and this suite must not read that as either — the very
 * mistake it exists to catch. Ask again rather than conclude. */
async function resolve(ctx, id) {
  for (let go = 0; ; go++) {
    const r = await ctx.call('GET', `/tmf-api/party/v4/individual/${id}`);
    if (r.status === 200 || r.status === 404 || go === 5) {
      if (r.status !== 200 && r.status !== 404) {
        fail(`could not tell whether ${id} still exists: HTTP ${r.status}`);
      }
      return r;
    }
    await new Promise((done) => setTimeout(done, 2000 * (go + 1)));
  }
}

/* the report row for one person: "action bills orders svc post name why" */
const rowFor = (out, person) => {
  const name = parties.displayName(person).slice(0, 30);
  return out.split('\n').find((l) => l.includes(name) && /^ {2}\S+ /.test(l));
};

(async () => {
  const staff = parties.ctxFor(await parties.tokenFor('bss', 'demo', 'demo'));
  const other = parties.ctxFor(await parties.tokenFor('taranga', 'demo', 'demo'));

  /* ---------- the fixtures this suite is allowed to touch ---------- */
  const clean = await mint(staff, 'Clean');      // nothing will point at it
  const held = await mint(staff, 'Held');        // a cancelled service will
  const live = await mint(staff, 'Live');        // an ACTIVE service will
  const cancelled = await giveProduct(staff, held.id, 'cancelled');
  const active = await giveProduct(staff, live.id, 'active');
  const abroad = await mint(other, 'Abroad');    // taranga's, and only taranga's
  ok(`four throwaway fixtures minted: Clean/Held/Live Sweepfix${run} here, Abroad in taranga`);

  let leftBehind = [[staff, clean.id], [staff, held.id], [staff, live.id], [other, abroad.id]];
  const forget = (id) => { leftBehind = leftBehind.filter(([, x]) => x !== id); };

  try {
    /* ---------- one report answers six questions ---------- */
    const people = await staff.pageAll('/tmf-api/party/v4/individual');
    const keep = parties.loadKeep('bss');
    if (!keep.names.size) {
      fail('the keep-list is empty — bss-realm.json did not load');
    }
    const persona = people.find((p) => keep.names.has(parties.displayName(p)));
    if (!persona) {
      fail(`no seeded persona found among ${people.length} individuals`);
    }
    const asked = [clean.id, held.id, live.id, persona.id, abroad.id].join(',');
    const counted = sweep(['--parties', '--include-named', `--only=${asked}`, '--show=10']);

    if (!/COUNTED ONLY — nothing was changed/.test(counted)) {
      fail(`the counting mode did not say it counted only:\n${counted}`);
    }
    const rows = { clean: rowFor(counted, clean), held: rowFor(counted, held),
      live: rowFor(counted, live) };
    for (const [who, row] of Object.entries(rows)) {
      if (!row) {
        fail(`${who} was not reported by the counting mode:\n${counted}`);
      }
    }
    if (!/^ +delete +0 +0 +0 +0 /.test(rows.clean)) {
      fail(`a party nothing points at should read "delete 0 0 0 0": ${rows.clean}`);
    }
    if (!/^ +retire +0 +0 +1 +0 /.test(rows.held)) {
      fail(`a party with one cancelled service should read "retire 0 0 1 0": ${rows.held}`);
    }
    if (!/^ +keep /.test(rows.live) || !/ACTIVE service/.test(counted)) {
      fail(`a party with an ACTIVE service should be kept, with the reason: ${rows.live}`);
    }
    ok('counted: delete / retire / keep decided per party, each with its reason');

    if (!counted.includes(`--only ${persona.id}: in this tenant, but not a fixture`)) {
      fail(`${parties.displayName(persona)} is seeded in bss-realm.json and must never be`
        + ` a candidate, even with --include-named:\n${counted}`);
    }
    ok(`seeded persona ${parties.displayName(persona)} is not a candidate`);

    if (!counted.includes(`--only ${abroad.id}: not in this tenant`)) {
      fail(`a genalpha sweep saw a taranga party — the tenant wall is open:\n${counted}`);
    }
    const seen = sweep(['--parties', '--realm=taranga', `--only=${abroad.id}`]);
    if (!rowFor(seen, abroad)) {
      fail(`the taranga sweep did not see its own party:\n${seen}`);
    }
    ok('tenant wall: invisible to a genalpha token, visible to taranga\'s own');

    for (const p of [clean, held, live]) {
      if ((await resolve(staff, p.id)).status !== 200) {
        fail(`counting removed ${parties.displayName(p)} — the report must be read-only`);
      }
    }
    ok('counting changed nothing: all three are still there');

    /* ---------- the journal guard goes red on a journal it cannot read whole ---------- */
    const blinkered = parties.ctxFor(await parties.tokenFor('bss', 'demo', 'demo'));
    const honest = blinkered.call;
    const CEILING = 2;
    blinkered.call = async (method, p, body) => {
      const r = await honest(method, p.includes('/revenue/v1/journalEntry')
        ? p.replace(/&offset=\d+/, '') : p, body);
      if (!p.includes('/revenue/v1/journalEntry') || !Array.isArray(r.body)) {
        return r;
      }
      // a journal that honours `limit` but ignores `offset`: every ask lands
      // on the same first page, which is exactly how a posting goes unseen
      const askedFor = Number((p.match(/limit=(\d+)/) || [])[1] || CEILING);
      return { ...r, body: r.body.slice(0, Math.min(askedFor, CEILING)) };
    };
    const partial = await parties.buildLedger(blinkered, { journalDays: 3 });
    if (!partial.journal.truncated) {
      fail('the journal walk did not notice it could not see past the first page');
    }
    ok(`journal guard red on a capped journal: ${partial.journal.truncated} day(s) unreadable`);

    /* ---------- unreachable is never empty ---------- */
    // The dangerous branch, and the reason this stands a real gateway up
    // rather than stubbing one in: when a component is down every list comes
    // back empty, and an empty list reads exactly like "nothing hangs off this
    // person" — which ends in deleting somebody who has bills.
    const brownout = http.createServer((req, res) => {
      if (req.url.includes('customerBill')) {
        res.writeHead(503).end('billing is down');
        return;
      }
      fetch(parties.API + req.url, { headers: { authorization: req.headers.authorization } })
        .then(async (up) => res.writeHead(up.status).end(await up.text()))
        .catch(() => res.writeHead(502).end());
    });
    await new Promise((up) => brownout.listen(0, '127.0.0.1', up));
    const down = `http://127.0.0.1:${brownout.address().port}`;
    try {
      const refused = await parties.removeFixtureParty(staff, { uname: 'demo', pass: 'demo',
        partyId: held.id, realm: 'bss', api: down });
      if (refused.action !== 'blocked' || !/refusing to guess/.test(refused.detail)) {
        fail(`an unreadable footprint must refuse, not delete: ${JSON.stringify(refused)}`);
      }
      if ((await resolve(staff, held.id)).status !== 200) {
        fail('the refusing branch removed the party anyway');
      }
      ok(`suite cleanup refuses an unreadable footprint: ${refused.detail}`);

      let exit = 0;
      let said = '';
      try {
        said = sweep(['--parties', `--api=${down}`, '--show=0']);
      } catch (refusedRun) {
        exit = refusedRun.status;
        said = `${refusedRun.stdout || ''}${refusedRun.stderr || ''}`;
      }
      if (exit === 0) {
        fail(`the sweep read a half-down fleet as a tenant to report on:\n${said}`);
      }
      if (/0 fixture parties/.test(said)) {
        fail(`the sweep printed a clean tenant while a component was down:\n${said}`);
      }
      ok(`sweep refuses a half-down fleet (exit ${exit}) instead of printing it clean`);
    } finally {
      brownout.closeAllConnections();  // fetch keeps sockets alive; close() alone hangs exit
      brownout.close();
    }

    /* ---------- acting: delete removes, retire anonymizes in place ---------- */
    // Asserted by OUTCOME, not by the tally line: a retry after a component
    // blinked would report a different tally (the deleted one is gone, the
    // retired one is no longer a candidate) while the outcome is the same.
    let acted = '';
    for (let go = 0; go < 3; go++) {
      acted = sweep(['--parties-act', `--only=${clean.id},${held.id},${live.id}`]);
      if (/, 0 failed/.test(acted)) {
        break;
      }
      console.log(`  (a component blinked mid-act, asking again: `
        + `${(acted.match(/FAILED .*/) || ['?'])[0].slice(0, 120)})`);
    }
    if (!/, 0 failed/.test(acted)) {
      fail(`acting kept failing:\n${acted}`);
    }
    if ((await resolve(staff, clean.id)).status !== 404) {
      fail('a party nothing points at should be gone after --parties-act');
    }
    forget(clean.id);
    const after = await resolve(staff, held.id);
    if (after.status !== 200) {
      fail('a retired party must still resolve — the records that point at it still do');
    }
    if (parties.displayName(after.body) !== parties.ERASED) {
      fail(`a retired party should read "${parties.ERASED}", not `
        + `"${parties.displayName(after.body)}"`);
    }
    if ((await staff.call('GET',
      `/tmf-api/productInventory/v4/product/${cancelled}`)).status !== 200) {
      fail('retiring a party must not take its service records with it');
    }
    const survivor = await resolve(staff, live.id);
    if (survivor.status !== 200 || parties.displayName(survivor.body) === parties.ERASED) {
      fail('a party with an ACTIVE service must be left exactly as it was');
    }
    ok('acted: the clean one deleted, the one with history retired in place, the live one left');
  } finally {
    /* ---------- leave every tenant as we found it ---------- */
    for (const id of [cancelled, active]) {
      await staff.call('DELETE', `/tmf-api/productInventory/v4/product/${id}`);
    }
    for (const [ctx, id] of leftBehind) {
      await ctx.call('DELETE', `/tmf-api/party/v4/individual/${id}`);
    }
  }
  const left = await staff.pageAll('/tmf-api/party/v4/individual');
  const leaked = left.filter((p) => parties.displayName(p).includes(`Sweepfix${run}`));
  if (leaked.length) {
    fail(`this suite leaked ${leaked.length} of its own fixtures: `
      + leaked.map((p) => parties.displayName(p)).join(', '));
  }
  ok('every fixture this suite minted is gone');
  console.log('\nPARTY-DEBRIS CHECKS PASSED — the sweep counts before it removes, removes by'
    + ' the rule, cannot see past its own tenant, and refuses rather than mistaking a'
    + ' component that is down for a tenant with nothing in it.');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
