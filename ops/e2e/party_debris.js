/* THE RULE FOR REMOVING A DEMO FIXTURE PERSON — one place, so the sweep and
 * the suites that mint customers cannot disagree about it.
 *
 * Deleting a customer is a bigger act than deleting a draft offering. The
 * bills, orders, services and journal postings that hang off a person are
 * real records, and the privacy arc already decided what may happen to them:
 * `PrivacyService.erase` anonymizes the profile IN PLACE and reports bills,
 * payments, orders, usage and agreements as retained under bookkeeping law.
 * So there is no second way to remove a person here. The rule is:
 *
 *   nothing points at them   -> DELETE  (TMF632 DELETE /individual/{id})
 *   anything points at them  -> RETIRE  (POST /privacy/v1/erase, the DPO door)
 *   an ACTIVE service        -> KEEP    (the eraser refuses with 409, and it
 *                                        is right to: terminate the contract
 *                                        first. We report, we do not fight it.)
 *
 * RETIRE is not a new state — this codebase has no party lifecycle status.
 * It is the privacy arc's anonymize-in-place: the id survives as the
 * pseudonymous reference every retained accounting record points at, the
 * name becomes "Erased Erased", and an erasure_record row records that it
 * happened. A desk listing customers then reads a retired row as erased
 * rather than as a wall of timestamps, and the journal still balances.
 *
 * TENANT SCOPING. Every call here rides ONE token, minted from ONE realm.
 * Tenancy for an authenticated caller comes from the VERIFIED TOKEN ISSUER
 * (TenantScope: issuer beats the X-Tenant-Id header, which the gateway
 * stamps from the hostname and which only steers anonymous traffic), and
 * row-level security pins the session to that tenant underneath. There is
 * no tenant argument and no cross-tenant mode: to sweep another operator
 * you re-run with that operator's realm and credentials, and you can only
 * ever see and touch what that token can. Suite #239 proves it by asking
 * this module, with a genalpha token, about a party that exists only in
 * taranga — and getting "not in this tenant".
 */
const fs = require('fs');
const path = require('path');

const API = 'http://localhost:8080';
const KC = 'http://localhost:8085';

/* A fixture name carries the run's epoch — seconds (10 digits) or millis
 * (13), anywhere in the name. Wider than the catalog sweep's twin above it
 * in debris_sweep.js: a party name glues a counter straight onto the epoch
 * ("Resume Proof1786032393155x8"), so the match must not demand a word
 * boundary after the digits — only that the digit run ends there. */
const EPOCH = /(?:^|\D)1[6-9]\d{8}(\d{3})?(?!\d)/;

/* Names today's suites mint. Every one of these suites now puts the run
 * epoch in the email too, so a fresh leak is caught by the epoch tiers
 * above; this list is what reaches back to the older rows that carry the
 * same name from before the epoch moved into the email. Each entry names
 * the suite that mints the name — nothing goes on this list untraced. */
const FIXTURE_NAMES = new Map([
  ['Alice Berg', 'storefront_test.js'],
  ['Bob Berg', 'storefront_test.js'],
  ['Appy User', 'app_test.js'],
  ['Gina Guest', 'guest_test.js'],
  ['Onto Upgrader', 'ontology_test.js'],
  ['Bundle Tester', 'bundle_decomposition_test.js'],
  ['Cfs Tester', 'cfs_decomposition_test.js'],
  ['Rfs Tester', 'cfs_realisation_test.js'],
  ['Obeys Tester', 'cfs_obeys_test.js'],
  ['Keep Number', 'porting_test.js'],
  ['Kolla Debitor', 'collections_test.js'],
  ['Carl Changer', 'plan_change_test.js'],
  ['Sam Simsson', 'sim_test.js'],
  ['Eve Else', 'sim_test.js'],
]);
/* billing_scale_test.js mints a numbered cohort rather than one name. */
const FIXTURE_PATTERNS = [[/^Scale Cohort\d+$/, 'billing_scale_test.js']];

/* Already erased by the privacy path — nothing left to remove. */
const ERASED = 'Erased Erased';

const displayName = (p) => `${p.givenName || ''} ${p.familyName || ''}`.trim();
const emailsOf = (p) => (p.contactMedium || [])
  .map((m) => (m && m.characteristic && m.characteristic.emailAddress) || '')
  .filter(Boolean).join(' ');

/** The people a realm is SEEDED with — the demo's own cast, never candidates.
 * Read from the realm file rather than typed here, so the keep-list is
 * whatever Keycloak is actually told to create. Missing file = empty set,
 * and the caller is told, because an empty keep-list is not a safe default. */
function loadKeep(realm) {
  const file = path.join(__dirname, '..', '..', 'infra', 'keycloak', `${realm}-realm.json`);
  if (!fs.existsSync(file)) {
    return { names: new Set(), source: `no ${realm}-realm.json — keep-list EMPTY` };
  }
  const users = JSON.parse(fs.readFileSync(file, 'utf8')).users || [];
  const names = new Set(users
    .map((u) => `${u.firstName || ''} ${u.lastName || ''}`.trim())
    .filter(Boolean));
  return { names, source: `${realm}-realm.json (${names.size} seeded people)` };
}

/** Is this individual suite debris? Returns null for "leave alone", else the
 * tier that caught it and why, in words a person can check by eye. */
function classify(person, keep, { includeNamed = false } = {}) {
  const name = displayName(person);
  if (!name || name === ERASED) {
    return null;                       // already retired, or nothing to read
  }
  if (keep.names.has(name)) {
    return null;                       // one of the realm's own people
  }
  if (EPOCH.test(name)) {
    return { tier: 'epoch', why: `run epoch in the name "${name}"` };
  }
  const mail = emailsOf(person);
  if (EPOCH.test(mail)) {
    return { tier: 'epoch-email', why: `run epoch in the email "${mail}"` };
  }
  if (!includeNamed) {
    return null;
  }
  if (FIXTURE_NAMES.has(name)) {
    return { tier: 'name', why: `"${name}" is minted by ${FIXTURE_NAMES.get(name)}` };
  }
  for (const [re, suite] of FIXTURE_PATTERNS) {
    if (re.test(name)) {
      return { tier: 'name', why: `"${name}" is minted by ${suite}` };
    }
  }
  return null;
}

/* ---------------- talking to the fleet, on one tenant's token ---------------- */

async function tokenFor(realm, user, pass, kc = KC) {
  const r = await fetch(`${kc}/realms/${realm}/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'password', client_id: 'bss-demo', username: user, password: pass }),
  });
  if (!r.ok) {
    throw new Error(`token ${realm}/${user}: ${r.status}`);
  }
  const body = await r.json();
  if (!body.access_token) {
    throw new Error(`token ${realm}/${user}: no access_token`);
  }
  return body.access_token;
}

function ctxFor(tok, api = API) {
  const H = { Authorization: `Bearer ${tok}`, 'Content-Type': 'application/json' };
  const call = async (method, p, body) => {
    // Two things worth waiting for rather than believing. The gateway
    // rate-limits, and a sweep is exactly the shape of traffic it limits. And
    // a component that is restarting answers 5xx for a few seconds — on a READ
    // that is a question not yet answered, so ask again; a write is never
    // retried, because a POST that may have landed must not be sent twice.
    // A restart is seconds, so three tries; a rate limit is a queue, so six.
    const tries = (status) => (status === 429 ? 6
      : (status >= 500 && method === 'GET') ? 3 : 0);
    for (let attempt = 0; ; attempt++) {
      const r = await fetch(api + p, { method, headers: H,
        ...(body ? { body: JSON.stringify(body) } : {}) });
      const text = await r.text();
      if (attempt < tries(r.status)) {
        await new Promise((done) => setTimeout(done, 400 * (attempt + 1)));
        continue;
      }
      let json = null;
      try { json = text ? JSON.parse(text) : null; } catch { /* not json */ }
      return { status: r.status, body: json, text };
    }
  };
  const get = async (p) => {
    const r = await call('GET', p);
    return r.status === 200 ? r.body : null;
  };
  /* Throws rather than returning [] when a list cannot be read. A component
   * being down would otherwise turn into "this tenant has no fixtures", which
   * reads exactly like a clean tenant — and a sweep that prints a clean tenant
   * while the fleet is on fire is the failure this whole file exists to avoid. */
  const pageAll = async (p, limit = 100) => {
    const out = [];
    for (let offset = 0; ; offset += limit) {
      const path = `${p}${p.includes('?') ? '&' : '?'}limit=${limit}&offset=${offset}`;
      const r = await call('GET', path);
      if (r.status !== 200 || !Array.isArray(r.body)) {
        throw new Error(`GET ${p} -> ${r.status} ${String(r.text || '').slice(0, 120)}`
          + ' — refusing to read an unreachable list as an empty one');
      }
      if (!r.body.length) {
        break;
      }
      out.push(...r.body);
      if (r.body.length < limit) {
        break;
      }
    }
    return out;
  };
  return { api, call, get, pageAll };
}

const partyIdsOf = (row) => (row.relatedParty || [])
  .filter((r) => r && r.id && (!r.role || String(r.role).toLowerCase() === 'customer'))
  .map((r) => r.id);

const bump = (map, key) => map.set(key, (map.get(key) || 0) + 1);

/** One pass over the tenant's bills, orders, services and journal, indexed by
 * party — so "what hangs off this person" is a lookup, not a fan of calls.
 * The journal has no party filter, so it is walked by ENTRY DATE over a
 * stated window (idx_journal_date makes each day cheap); the window and the
 * oldest posting seen are reported, because a posting older than the window
 * would otherwise be silently missing from a decision to delete. */
async function buildLedger(ctx, { journalDays = 400, log = () => {} } = {}) {
  const bills = new Map();
  const orders = new Map();
  const services = new Map();
  const activeServices = new Map();
  const postings = new Map();

  const billRows = await ctx.pageAll('/tmf-api/customerBillManagement/v4/customerBill');
  for (const b of billRows) {
    for (const id of partyIdsOf(b)) {
      bump(bills, id);
    }
  }
  log(`  bills: ${billRows.length}`);

  const orderRows = await ctx.pageAll('/tmf-api/productOrderingManagement/v4/productOrder');
  for (const o of orderRows) {
    for (const id of partyIdsOf(o)) {
      bump(orders, id);
    }
  }
  log(`  orders: ${orderRows.length}`);

  const productRows = await ctx.pageAll('/tmf-api/productInventory/v4/product');
  for (const p of productRows) {
    for (const id of partyIdsOf(p)) {
      bump(services, id);
      if (String(p.status || '').toLowerCase() === 'active') {
        bump(activeServices, id);
      }
    }
  }
  log(`  services: ${productRows.length}`);

  /* The journal has no party filter, so it is read a DAY at a time. On this
   * codebase that endpoint hands back the whole day and honours no `limit`,
   * so one call is the day. A fleet running a build that DOES page would
   * silently hand back a first page instead, and a first page would make an
   * innocent-looking party look free of accounting history — so the first
   * non-empty day is probed with limit=1 to find out which it is. */
  const ASK = 100000;
  let why = null;                      // why a day could not be read, if any
  const dayJournal = async (iso, paging) => {
    const r = await ctx.call('GET', `/revenue/v1/journalEntry?date=${iso}&limit=${ASK}`);
    const first = r.status === 200 && Array.isArray(r.body) ? r.body : null;
    if (!first) {
      // a day we could not read is not a day with nothing in it. 401 here is
      // its own fact worth reporting: on some tenants the operator who may
      // read parties and bills may not read the subledger at all.
      why = `HTTP ${r.status}`;
      return null;
    }
    let rows = first;
    if (!paging || !rows.length) {
      return rows;
    }
    for (;;) {
      const page = await ctx.call('GET',
        `/revenue/v1/journalEntry?date=${iso}&limit=${ASK}&offset=${rows.length}`);
      const next = page.status === 200 && Array.isArray(page.body) ? page.body : null;
      if (!next) {
        why = `HTTP ${page.status}`;
        return null;
      }
      if (!next.length) {
        return rows;                               // the day, whole
      }
      if (next[0].id === rows[0].id) {
        why = 'the offset went nowhere';           // a ceiling we cannot see past
        return null;
      }
      rows = rows.concat(next);
    }
  };

  let entries = 0;
  let oldest = null;
  let truncated = 0;
  let paging = null;                   // unknown until the first non-empty day
  const day = new Date();
  for (let i = 0; i < journalDays; i++) {
    const iso = day.toISOString().slice(0, 10);
    if (paging === null) {
      const whole = (await ctx.call('GET',
        `/revenue/v1/journalEntry?date=${iso}&limit=${ASK}`)).body;
      if (Array.isArray(whole) && whole.length > 1) {
        const one = (await ctx.call('GET',
          `/revenue/v1/journalEntry?date=${iso}&limit=1`)).body;
        paging = Array.isArray(one) && one.length === 1;
        log(`  journal endpoint ${paging ? 'pages — walking offsets' : 'returns whole days'}`);
      }
    }
    const rows = await dayJournal(iso, paging === true);
    if (rows === null) {
      truncated++;
    }
    for (const e of (rows || [])) {
      entries++;
      oldest = iso;
      for (const id of partyIdsOf(e)) {
        bump(postings, id);
      }
    }
    day.setUTCDate(day.getUTCDate() - 1);
  }
  const journalFrom = day.toISOString().slice(0, 10);
  log(`  journal: ${entries} postings over ${journalDays} days back to ${journalFrom}`
    + (oldest ? ` (oldest seen ${oldest})` : ' (none seen)'));
  if (truncated) {
    log(`  WARNING: ${truncated} day(s) of the journal could not be read whole`
      + `${why ? ` (${why})` : ''} — the posting counts below are a floor, not a`
      + ' count. Do not act on them.');
  }

  return { bills, orders, services, activeServices, postings,
    journal: { days: journalDays, from: journalFrom, entries, oldest, truncated, why } };
}

function footprint(ledger, partyId) {
  return {
    bills: ledger.bills.get(partyId) || 0,
    orders: ledger.orders.get(partyId) || 0,
    services: ledger.services.get(partyId) || 0,
    activeServices: ledger.activeServices.get(partyId) || 0,
    postings: ledger.postings.get(partyId) || 0,
  };
}

/** The rule itself. */
function decide(fp) {
  if (fp.activeServices > 0) {
    return { action: 'keep',
      reason: `${fp.activeServices} ACTIVE service(s) — terminate the contract first; `
        + 'the eraser refuses this and is right to' };
  }
  if (fp.bills || fp.orders || fp.services || fp.postings) {
    return { action: 'retire',
      reason: `accounting history: ${fp.bills} bill(s), ${fp.orders} order(s), `
        + `${fp.services} service(s), ${fp.postings} posting(s) — anonymize in place` };
  }
  return { action: 'delete', reason: 'nothing points at this person' };
}

/** Carry out one decision. Read-only callers never reach here. */
async function apply(ctx, person, decision) {
  if (decision.action === 'keep') {
    return { ok: true, detail: 'left in place' };
  }
  if (decision.action === 'delete') {
    const r = await ctx.call('DELETE', `/tmf-api/party/v4/individual/${person.id}`);
    return { ok: r.status === 204 || r.status === 200, detail: `DELETE individual -> ${r.status}` };
  }
  const r = await ctx.call('POST', '/privacy/v1/erase', { partyId: person.id });
  const status = r.body && r.body.status;
  return { ok: r.status === 200, detail: `privacy erase -> ${r.status}${status ? ` (${status})` : ''}` };
}

/** A master-realm admin token, minted when it is about to be used. These are
 * short-lived: one taken at the top of a long suite is expired by the end. */
async function kcAdminToken(base = KC, user = 'admin', pass = 'admin') {
  const r = await fetch(`${base}/realms/master/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'password', client_id: 'admin-cli', username: user, password: pass }),
  });
  return r.ok ? (await r.json()).access_token : null;
}

/** A SUITE TIDYING UP AFTER ITSELF — the same rule, one party at a time.
 *
 * A suite that mints a customer should take them away at the end, the way it
 * already takes away its offerings. It cannot afford the tenant-wide ledger
 * above, and it does not need it: the fixture's OWN token is confined to the
 * fixture (PartyScope pins a `customer` to its subject), so one page each of
 * products, orders and bills IS that person's whole footprint.
 *
 * Both tokens are minted HERE, not handed in. A suite runs for minutes and an
 * access token lives five; a cleanup holding the token the suite opened with
 * reads 401 on every list — and an empty list reads exactly like "nothing
 * hangs off this person", which is the one wrong answer that ends in a delete.
 * For the same reason an unreadable footprint refuses rather than guesses.
 *
 * What it will and will not do:
 *   - a BILL means accounting history, and bookkeeping law outranks tidiness:
 *     retire the person (privacy erase, anonymized in place) and stop. The
 *     bill, and any journal posting behind it, are left exactly as they are.
 *   - otherwise the products and orders are the SUITE'S OWN fixtures, made by
 *     this run and no one else's records: take them away, then the person.
 *   - the login goes either way, and first — a fixture nobody can sign in as
 *     is the one harmless thing about a fixture.
 *
 * Returns what it did, so a suite can print one honest line about it. */
async function removeFixtureParty(staff, fixture) {
  const { uname, pass, userId, partyId, realm = 'bss', kcBase = KC, api = API } = fixture;
  const done = { action: 'none', products: 0, orders: 0, bills: 0, detail: '' };

  const own = ctxFor(await tokenFor(realm, uname, pass, kcBase), api);
  if (userId) {
    const admin = await kcAdminToken(kcBase);
    if (admin) {
      await fetch(`${kcBase}/admin/realms/${realm}/users/${userId}`,
        { method: 'DELETE', headers: { Authorization: `Bearer ${admin}` } }).catch(() => {});
    }
  }

  const list = async (p) => {
    const r = await own.call('GET', `${p}${p.includes('?') ? '&' : '?'}limit=100`);
    if (r.status !== 200 || !Array.isArray(r.body)) {
      return null;                     // unreadable is NOT empty
    }
    return r.body;
  };
  const products = await list('/tmf-api/productInventory/v4/product');
  const orders = await list('/tmf-api/productOrderingManagement/v4/productOrder');
  const bills = await list('/tmf-api/customerBillManagement/v4/customerBill');
  if (!products || !orders || !bills) {
    // a component being down is not evidence that nothing hangs off this
    // person. Blocked, not failed: the sweep will catch the fixture later.
    done.action = 'blocked';
    done.detail = 'could not read the fixture\'s own footprint — refusing to guess at it'
      + ` (products ${products ? 'ok' : 'unreadable'},`
      + ` orders ${orders ? 'ok' : 'unreadable'}, bills ${bills ? 'ok' : 'unreadable'})`;
    return done;
  }
  done.products = products.length;
  done.orders = orders.length;
  done.bills = bills.length;

  if (bills.length) {
    const r = await staff.call('POST', '/privacy/v1/erase', { partyId });
    done.action = r.status === 200 ? 'retired' : 'left';
    done.detail = r.status === 200
      ? `${bills.length} bill(s) — anonymized in place, the bills kept`
      : `${bills.length} bill(s) and the eraser said ${r.status}`;
    return done;
  }
  for (const p of products) {
    await staff.call('DELETE', `/tmf-api/productInventory/v4/product/${p.id}`);
  }
  for (const o of orders) {
    await staff.call('DELETE', `/tmf-api/productOrderingManagement/v4/productOrder/${o.id}`);
  }
  const gone = await staff.call('DELETE', `/tmf-api/party/v4/individual/${partyId}`);
  // 404 = the run died before the person was created; nothing was left behind
  done.action = [200, 204, 404].includes(gone.status) ? 'deleted' : 'left';
  done.detail = `${products.length} service(s) and ${orders.length} order(s) taken away first`
    + (done.action === 'left' ? `; DELETE individual -> ${gone.status}` : '');
  return done;
}

module.exports = { API, KC, EPOCH, FIXTURE_NAMES, FIXTURE_PATTERNS, ERASED,
  removeFixtureParty, kcAdminToken,
  displayName, emailsOf, loadKeep, classify, tokenFor, ctxFor, buildLedger,
  footprint, decide, apply };
