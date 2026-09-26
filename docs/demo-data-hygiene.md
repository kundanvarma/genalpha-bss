# Demo data hygiene — removing a fixture person

The Bills desk put a customer column on screen and the demo tenant's customer
list turned out to be mostly suite debris: people minted by a browser suite
that created a customer, proved something, and never removed the person. No
suite was wrong. Each created what it needed and asserted what it wanted. The
damage only shows on a screen that lists customers — and it shows most on the
hosted demo box, in front of the people the demo is for.

This is demo hygiene, not a defect in the product. A real operator's customer
list is their own, and nothing here runs against one.

## The rule: retire versus delete

Deleting a customer is a bigger act than deleting a draft offering. A draft
offering is a thing somebody typed; a customer is the anchor that bills,
orders, services and journal postings point at. The privacy arc already
decided what may happen to a person — `PrivacyService.erase` anonymizes the
profile **in place** and reports bills, payments, orders, usage and agreements
as retained under bookkeeping law, naming the basis for each. There is no
second way to remove a person in this codebase, and this sweep does not invent
one. It reuses that door.

So, per fixture party:

| what hangs off them | what happens | how |
| --- | --- | --- |
| nothing at all | **delete** | `DELETE /tmf-api/party/v4/individual/{id}` — no record loses its anchor, because there is no record |
| a bill, an order, a service, a journal posting | **retire** | `POST /privacy/v1/erase` — the DPO door: profile anonymized in place, the id surviving as the pseudonymous reference every retained record points at, an `erasure_record` row written |
| an **active** service | **keep**, and say why | nothing. The eraser refuses a running contract with 409 and it is right to: terminate first. The sweep reports it and moves on |

Retire is not a new lifecycle state — a party in this codebase has no status
field, and adding one to paper over demo debris would be a worse idea than the
debris. Retire is the privacy arc's anonymize-in-place, which is exactly the
shape the problem wants: the desks stop reading a wall of timestamps, the
accounting history stays intact and still balances, and the erasure audit trail
records that it happened and who did it.

A bill is the line that must not be crossed. A fixture customer with a settled
bill and a posting in the journal is **not** safe to erase, and no flag on the
sweep will erase one.

## Counting comes first

`ops/e2e/debris_sweep.js --parties` **reports and changes nothing**. It prints,
per fixture party, the action it would take, how many bills, orders, services
and journal postings hang off them, and why. Acting needs `--parties-act`
typed on purpose, and `--only=<id>[,<id>]` narrows it to named parties — the
safe first step on any tenant that matters.

A party is a candidate when a run epoch sits in the display name, or in an
email contact medium, or (with `--include-named`) when the name is one a suite
is known to mint — `ops/e2e/party_debris.js` carries that list with the minting
suite named beside every entry. The people a realm is *seeded* with are never
candidates, and that keep-list is read out of `infra/keycloak/<realm>-realm.json`
rather than typed, so it is whatever Keycloak is actually told to create.

## One tenant at a time, by construction

Row-level security means a careless sweep could reach across tenants, so the
sweep has no tenant argument and no cross-tenant mode. Everything it does
rides **one token, minted from one realm**. For an authenticated caller the
tenant comes from the verified token issuer — `TenantScope` puts the issuer
above the `X-Tenant-Id` header the gateway stamps from the hostname, which
only ever steers anonymous traffic — and row-level security pins the session
underneath. To sweep another operator you re-run with that operator's realm
and credentials. The suite proves it: a party that exists only in taranga is
reported as *not in this tenant* by a genalpha sweep, and seen by taranga's own.

## Journal postings, and when the count is a floor

The journal has no party filter, so postings are counted by walking the journal
a day at a time over a stated window (`--journal-days`, a bit over a year by
default) and indexing by related party. The walk first probes whether the
endpoint pages; if it does, it walks the offsets, and if the offsets go
nowhere it says so and `--parties-act` **refuses to run**. A first page
mistaken for a whole day would make a party with accounting history look
innocent, which is the one mistake this sweep must never make.

## Suites take their own people away

A suite that mints a customer removes them at the end, the way it already
removes its catalog fixtures — `parties.removeFixtureParty` in
`ops/e2e/party_debris.js` applies the same rule one party at a time, using the
fixture's **own** token to list its footprint (a `customer` token is confined
to its subject, so one page each of products, orders and bills is that person's
whole world). A bill stops it: it retires the person and leaves the bill alone.
Otherwise the products and orders are the suite's own fixtures from this run,
so they go, and then the person does.

A suite that dies mid-run still leaks, which is why the sweep exists.

## Proof

`ops/e2e/party_debris_test.js` mints three throwaway people, proves the
counting mode reports all three and removes none of them, proves each of the
three branches of the rule on its own fixture, proves a seeded persona is never
a candidate, proves the tenant wall, and forces the truncation guard red on a
journal it deliberately cannot read whole. Every fixture it mints, it takes
away.

## Honest limits

- **The name tier cannot see everything.** Rows whose provenance could not be
  traced to a suite are left out of the fixture-name list on purpose, so some
  older debris stays. Suite-minted duplicates of a *seeded* persona's name
  (several dozen copies of a demo user, say) are indistinguishable from the
  persona by name and are never candidates.
- **An active service blocks removal.** On a tenant whose fixtures all hold
  running contracts the sweep will honestly remove nothing. Terminating those
  contracts first is a separate, deliberate act, and not one this sweep will
  do for you.
- **Postings are counted over a window.** A posting older than
  `--journal-days` is not seen. The sweep prints the window and the oldest
  posting it saw so the gap is visible rather than implied.
- **A sweep of another realm needs that realm's config actually mounted in the
  components it reads.** `tenants.yml` is bind-mounted, and a component whose
  `/config` is empty does not know the other realms' issuers at all, so it
  rejects their tokens with **401 before authorization runs** — which reads
  exactly like a product limit on multi-realm access, and is not one. Seen
  while writing this: `revenue` had lost its mount, and every taranga journal
  read came back 401 until the container was recreated. (On this laptop a
  branch switch replaces that file's inode, so a running container keeps a
  handle on a file that no longer exists.) While it lasts the posting count is
  *unknown*, the sweep names the status that stopped it, and acting is refused
  — which is the right behaviour either way. The only thing wrong in that state
  is the conclusion a reader might draw from the 401.
- **Retiring is slow, one party at a time.** Each erase re-proves its own
  precondition by walking the product inventory for that party, so a tenant
  with thousands of fixtures to retire is an hours-long run, not a minutes-long
  one. Counting is seconds; acting is not. Start with `--only`.
- **Retiring leaves a row.** A retired fixture still occupies a line on the
  customer list, reading as erased. That is better than a timestamp and worse
  than nothing, and it is the price of keeping the books whole.
- **Nothing here has been run against the hosted box.** The counted mode has
  been run against the laptop's demo tenants; a destructive run anywhere needs
  the owner's explicit go-ahead.
