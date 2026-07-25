# Revenue export — the feed your ledger ingests (closing the GL gap's seam) — plan

*2026-07-25. The capability map says "Account revenue (GL, rev-rec ASC 606)
— ❌ export to ERP", and the ledger itself will stay ❌: a BSS must never
BE the general ledger. But the map's own doctrine is that every gap should
sit behind a seam — and today the GL gap has no seam, only raw material.
A finance team would have to reverse-engineer journal entries from TMF
resources. This arc builds the missing half: the BSS as an honest
SUBLEDGER with an accounting-shaped export.*

## Research findings

- **The industry pattern is settled**: high-volume billing systems act as
  the SUBLEDGER; the ERP's general ledger receives SUMMARIZED journal
  batches (daily is the norm) posted against GL CONTROL accounts, with
  the mapping from business events to debit/credit lines defined as
  "posting profiles" — accounting-rules-as-data, not code (Oracle,
  Dynamics and utility-billing practice all agree). Period-end
  reconciliation ties the subledger total back to the GL control
  account. This is exactly the house pattern: rules as data, an export
  finance can rerun, a tie-out endpoint.
- **There is no TM Forum Open API for the general ledger** — TMF666 is
  customer billing accounts, TMF678 is the customer bill. GL export sits
  deliberately outside the ODA map, so the API here is a house API
  (`/revenue/v1`, like `/ai/v1`) with the TMF disciplines kept: tenancy,
  RLS, events, machine identity.
- **ASC 606 / IFRS 15 decouple billing from revenue**: one billing event
  can legitimately produce MULTIPLE accounting entries — revenue now for
  delivered service, CONTRACT LIABILITY (deferred revenue) for
  paid-but-undelivered (prepaid top-ups, loyalty points). The rev-rec
  ENGINE (performance obligations, SSP allocation) belongs in the ERP
  (SAP RAR / NetSuite ARM class); the BSS's job is clean inputs.
- **What finance books from a BSS**, per event: invoice issued → debit
  AR / credit revenue (by line category, discounts as contra-revenue);
  cash received → debit cash / credit AR; refund → the reverse of cash.
  Points are a liability — but a POINT has no currency value until the
  program prices one, so v1 exposes the loyalty points balance as a
  reconciliation CONTROL NUMBER (the loyalty component's `/liability`)
  and leaves currency valuation of points as configurable P2 — never a
  made-up rate booked silently.

## Repo recon (what the feed can actually consume)

- Billing publishes on `bss.billing.events`: `CustomerBillCreateEvent`
  carries `{id, billNo, amountDue{unit,value}, relatedParty}` but NO
  line items — the journal builder must follow up with a machine GET of
  `/customerBill/{id}/appliedCustomerBillingRate`. Line `type` values:
  `recurringCharge`, `usageCharge`, `discount` (stored NEGATIVE),
  `priceAdjustment` (either sign); summing `taxExcludedAmount.value`
  reconstructs `amountDue` — the balance test is built into the data.
- Payment publishes on `bss.payment.events`: `PaymentCreateEvent`
  (auth + external/giro records), `PaymentStateChangeEvent` (capture —
  where money moves), `PaymentRefundEvent`. **Double-booking trap**: a
  matched bank payment fires BOTH `RemittanceAppliedEvent` (billing) and
  `PaymentCreateEvent` (payment, recordExternal) — cash is booked from
  PAYMENT events only; remittance events are memo, never journaled.
- Amount shapes are inconsistent: money maps `{unit,value}` on
  bill/payment events, but `RemittanceAppliedEvent.amount` is a
  `"<value> <ccy>"` STRING (moot in v1 — memo only).
- Delivery is at-least-once; the journal must be idempotent per source
  event: `journal_entry.source_ref` UNIQUE per tenant.
- Next free port: **8107**. Scaffold: clone `services/loyalty`
  (newest), fix the stale `agreement` artifactId it still carries;
  `event_outbox` DDL must match the shared entity exactly; parent
  `pom.xml` modules, compose block, gateway route are manual adds.

## Design — component #35: `revenue` (subledger, port 8107)

### The journal (double-entry, append-only)

`journal_entry` (id, tenant, entry_date, source_ref UNIQUE-per-tenant,
source_type, description, currency) + `journal_line` (entry, seq,
account_code, account_name, debit, credit, party, ref). An entry REFUSES
to save unless debits equal credits to the cent — balance is an
invariant, not a report.

### Posting rules as data (the chart-of-accounts mapping)

`account_mapping` per tenant, seeded with an editable default CoA:

| key | default | meaning |
|---|---|---|
| `ar` | 1200 Accounts receivable | control account, invoice debit / cash credit |
| `cash` | 1000 Cash / PSP clearing | cash debit on capture & external payments |
| `rate:recurringCharge` | 4000 Service revenue | credit per bill line |
| `rate:usageCharge` | 4010 Usage revenue | credit per bill line |
| `rate:discount` | 4090 Discounts (contra) | negative line → debit contra-revenue |
| `rate:priceAdjustment` | 4091 Pricing adjustments | either sign |
| `refund` | 4095 Refunds (contra) | refund debit / cash credit |

Editable over the API (finance's names and codes, not ours); the
journal snapshots code+name onto each line so a later remap never
rewrites history.

### The builders (Kafka listeners, loyalty-listener pattern)

- `CustomerBillCreateEvent` → machine GET of the bill's rate lines
  (`bss-revenue` client, `billing:read`) → one balanced entry: debit AR
  for `amountDue`, credit revenue per line by `rate:<type>` mapping.
  Fail-soft: if the rates fetch fails, the event retries (at-least-once
  + idempotency makes this safe).
- `PaymentStateChangeEvent` (captured) and `PaymentCreateEvent` with
  settled/external status → debit cash / credit AR.
- `PaymentRefundEvent` → debit refunds-contra / credit cash.
- All idempotent on `source_ref` (`bill:<id>`, `payment:<id>:<status>`,
  `refund:<refundRef>`).

### The export & the tie-out

- `GET /revenue/v1/journalEntry?date=&offset=&limit=` — JSON, lines
  nested.
- `GET /revenue/v1/journalExport?date=` — CSV (one row per line:
  date, entry, account code/name, debit, credit, description, ref) —
  the file a period-close import job actually wants.
- `GET /revenue/v1/reconciliation?date=` — the numbers finance ties:
  AR debits vs billing's own bill total for the day, cash vs payments,
  per-account totals, every entry balanced (asserted live), and the
  loyalty points liability as a control number (live pull, labeled
  "points — no currency valuation configured").
- `POST /revenue/v1/backfill {billId}` — idempotent onboarding of
  pre-arc bills; also the suite's idempotency probe.
- Auth: reads `billing:read`, mapping writes + backfill `billing:admin`
  (finance-grade, staff-only); no customer-facing surface.

### Console

Read-only **Journal** tab (billing:admin): entries by date with lines;
Account mapping editor lands in P2.

## The proof (suite #70, revenue_test.js)

1. A settled-and-rated bill has ONE journal entry: AR debit equals
   `amountDue` to the cent; revenue lines equal the bill's rate lines
   (discounts as contra); the entry balances.
2. Paying a bill books cash against AR; the refund path books the
   reverse.
3. `reconciliation?date=` ties journal AR to the billing API's own
   sum for the same day, cash to payments, and reports the loyalty
   points control number.
4. CSV export carries the entry; re-`backfill` of the same bill creates
   NOTHING (idempotency proven, not promised).
5. nova's wall holds: genalpha journals are invisible cross-tenant.

## Order of work

1. **P1 — the subledger**: service #35 (journal + mapping + builders +
   export + reconciliation + backfill), compose/gateway/realm wiring,
   read-only console tab, suite #70, capability-map row update
   ("❌ ledger — ✅ the feed your ledger ingests"), books.
2. **P2 — finance polish**: account-mapping console editor, tax-split
   configuration (prices are tax-inclusive by convention today — the
   gross/net/tax-payable split is mapping config, honest until a tax
   engine exists), loyalty points currency valuation (configurable
   EUR-per-point, then earn/redeem/expiry journal entries), period
   close (lock date; entries before it immutable), ERP-flavored export
   formats (SAP/NetSuite column layouts).
3. **P3 — rev-rec inputs**: contract/commitment export from TMF651
   agreements (performance-obligation raw material for the ERP's
   rev-rec engine), deferred-revenue entries for prepaid top-ups,
   dispute/write-off postings.

## Shipped

**Phase 1 — 2026-07-25, suite #70 green (eight legs).** Component #35
`revenue` (port 8107, `/revenue/v1`) is live: the journal with balance
as a save-time invariant, the seeded editable chart, both event
builders, backfill, CSV export, and the reconciliation tie-out — plus a
read-only console Journal tab (billing:admin). Proven live: paula's
50 EUR bill became ONE balanced entry whose AR debit equals the bill to
the cent and whose revenue/contra lines mirror the rate lines; a second
backfill created NOTHING; a captured payment booked cash exactly once
and its refund booked the reverse; the reconciliation tied billed and
cash totals and carried the loyalty points liability (1442 pts) as a
control number that MATCHES the loyalty component's own; finance
renamed an account and booked history kept its snapshot; nova saw
nothing. Regressions green: storefront, console.

**Phase 2 — 2026-07-25, suite #70 at thirteen legs, all green.**
- **Tax split as config**: prices stay tax-INCLUSIVE; setting a VAT
  percent on the `tax` posting key (config_value — data, not code)
  splits every bill posting into net revenue lines + one VAT-payable
  credit, with the tax computed as gross-minus-sum-of-nets so ROUNDING
  CAN NEVER UNBALANCE an entry. AR stays gross. Proven at 25%.
- **Dispute credits**: an UNPAID bill credited after journaling books
  contra-revenue against AR (DisputeResolvedEvent listener) — the
  subledger follows the bill down, no silent reconciliation drift.
  Settled-bill credits refund via the PSP and book on the refund event
  (one path, never two). NOTE, corrected on the operator's ask: a
  FORMAL CREDIT NOTE (separate numbered document reversing a posted
  invoice — a bookkeeping-rules requirement in e.g. Norway) does NOT
  exist in billing or any frontend; dispute credits and refunds are
  the adjacent machinery. Credit-note-proper is a billing arc, listed
  under P3/next.
- **Loyalty points priced**: config_value on `loyalty:liability` =
  currency per point; the daily accrual (TickGuard tick + on-demand
  POST /loyaltyAccrual) books the DELTA between the loyalty
  component's live liability and what the journal carries — unpriced
  points remain a control number, and a same-day rerun books nothing.
- **Period close**: POST /periodClose {through} — postings for bills
  dated inside the closed period refuse 409, the reconciliation
  announces closedThrough, reopening is an explicit act.
- **ERP layouts**: journalExport?format=sap|netsuite — SAP- and
  NetSuite-shaped CSV, labeled shaped-not-certified.
- **Console**: the Chart of accounts tab (billing:admin) edits posting
  keys, codes, names and config values.
- Regressions green: console.

P3 / next (open): formal CREDIT NOTE document in billing (+ storefront/
CSR faces) feeding a `creditNote` posting here; TMF651 rev-rec inputs;
deferred revenue for prepaid top-ups.

Build notes (the instructive failures): the `revenue` DATABASE must be
created live on an existing postgres volume (init-databases.sql only
runs on first init). Keycloak imports realms with IGNORE_EXISTING — a
NEW machine client in the realm FILE does not reach a LIVE keycloak;
create it via the admin API too (file + live, like role grants). And
the admin API auto-grants `default-roles-<realm>` to new service
accounts, WHICH INCLUDES `customer` — billing's PartyScope then
politely confined the machine to "its own" bills and 404'd everyone
else's. A machine identity holds exactly the roles it needs: strip the
default composite.
