# Device commerce — instalments, upgrades, trade-in, and the subsidy ledger — plan

*2026-08-27. The shop sells phones with color pricing and stock, billing
can split a charge into instalments, and an insurance VAS exists — but
there is no device agreement anchoring any of it: no financing terms,
no upgrade eligibility, no trade-in, no 14-day withdrawal flow, no
subsidy accounting. Every operator in this market runs an
instalment + swap + trade-in program (Telenor SWAP, Telia Svitsj, the
US carriers' device-payment programs), the grading-partner ecosystem
(Foxway, Likewize, Assurant) is commoditized, and IFRS 15 makes the
subsidy ledger an audit requirement, not a feature. This arc builds
the device lifecycle as first-class BSS state.*

## Research findings

- **Program mechanics converge**: 24–36-month device financing tied to
  a subscription, an upgrade rider (eligibility at ~50 % paid or month
  N) that trades the device in early and settles the remainder, and a
  trade-in flow of IMEI + guided condition self-assessment → instant
  estimate applied at checkout → mail-in/drop-off → partner grading →
  revised value with a delta charge or refund the customer can accept
  or reject. Norway's consumer ombudsman forced pricing honesty on one
  such program ("half price" framing hiding a total above cash price)
  — total-cost transparency belongs in the offer face, not fine print.
- **Financing is not one model — the BSS must be agnostic.** Three
  models exist in the wild and a tenant may run any of them:
  1. **Operator-book**: the operator carries the receivable (our AR,
     instalment plan) and the IFRS 15 subsidy math applies in full.
  2. **Third-party loan**: a partner bank originates at checkout,
     **owns title and receivable**, pays the operator out upfront;
     upgrades require an early-settlement quote from the financier;
     the operator may carry a residual-value/buy-back guarantee
     liability toward the bank instead of a receivable.
  3. **BNPL**: a Klarna-style provider pays the operator upfront
     through the payment rail; the customer owes the provider;
     upgrade = customer settles the provider (or the provider exposes
     a settlement API), never an operator write-off.
  Open-banking affordability checks (Tink-style account data) are an
  optional pre-origination signal, not a financing model.
- **IFRS 15** (operator-book model): the handset is a separate
  performance obligation; allocation by relative standalone selling
  prices puts more revenue on the device at delivery than cash
  received — the gap is a **contract asset** unwound monthly against
  service billings. An early-termination fee economically recovers
  the *unearned subsidy* and offsets the remaining contract asset.
  Swap = write off remaining instalments against trade-in inventory
  received. Bank/BNPL models book full device revenue at payout and
  accrue a residual-value guarantee where the program promises one.
- **Consumer law**: angrerettloven (EU 2011/83) — unconditional 14
  days from physical receipt on distance sales, refund incl. standard
  shipping within 14 days of return, deduction only for documented
  diminished value beyond shop-style inspection. The withdrawal case
  needs its own mini-grading record.
- **Device identity**: EIR/GSMA IMEI blacklisting is network-side
  (MAP/S13); the BSS's role is to originate the lost/stolen event,
  push the IMEI through an adapter seam, and treat a blacklisted IMEI
  as zero trade-in value.
- **TMF fit**: device = TMF639 resource (IMEI), purchased offering =
  TMF637, financing/upgrade terms = TMF651 agreement referencing both
  (the wholesale arc already proved TMF651 in-repo). There is no TMF
  device-financing API — the entities below are ours, TMF-styled.
- **Repo recon**: instalment plans live in billing; stock in
  product-stock; parcel **delivery** events already drive SIM
  activation (the bundle arc), so the withdrawal clock and trade-in
  transit states have an event source; insurance VAS and commitment
  gating exist; revenue/GL posts journals off bus events.

## Design

### The agreements (new `device-commerce` service)

`device_agreement` — party + subscription ref + device resource
(IMEI/serial) + terms: principal, term, monthly amount, **financing
model** `OPERATOR_BOOK | THIRD_PARTY_LOAN | BNPL`, financier ref +
external agreement no. (when not operator-book), title holder,
upgrade-eligibility rule (`paidShare ≥ x` | `month ≥ n`), residual /
guaranteed buy-back value, status `active | settled | swapped |
defaulted | withdrawn`. Total-cost-of-ownership is a required,
displayed field on every offer face (the ombudsman lesson).

`trade_in_valuation` — IMEI, catalog device ref, condition answers,
estimated value, offer expiry, channel, status `quoted → accepted →
in-transit → graded → revalued → settled | rejected-returned`.
Estimates come from a tenant-editable residual table; a blacklisted
IMEI quotes zero.

`grading_event` — valuation ref, partner ref, final grade + value,
delta vs estimate, evidence note. Delta > 0 refunds through the PSP
path; delta < 0 raises a delta rate line; a rejected revision returns
the device.

`withdrawal_case` — order ref, clock start = parcel delivery event,
return grading, diminished-value deduction, refund record (full
price + standard shipping).

### The financing port (adapter seam, PSP doctrine)

`FinancingProvider`: `quote(terms) → originate(order, party) →
payoutWebhook → earlySettlementQuote(agreement) → settle(agreement)`
with drivers: **internal** (operator-book — delegates to billing's
instalment plan), **mock-bank** (third-party loan, for demos/e2e:
async approve, payout, settlement quotes), **BNPL via the existing
Klarna PSP adapter** (originate = checkout payment; settlement
delegates to provider). Credit decisioning belongs to the financier
in models 2–3; operator-book reuses the TMF696 risk hook. An
optional `AffordabilitySignal` port (open-banking, Tink-style driver
later) feeds pre-origination risk only.

### Upgrade / swap orchestration

Eligibility check on the agreement → trade-in quote for the current
device → model-specific settlement (operator-book: write off
remaining instalments against the graded value; loan/BNPL: fetch and
pay the early-settlement quote, trade-in value applied against it) →
new device order + new agreement. One saga in device-commerce,
emitting per-step events; every path idempotent.

### Money and the subsidy subledger (revenue)

Revenue consumes the agreement events and posts, per model:
delivery-time equipment revenue with contract-asset (operator-book,
subsidised bundles); monthly contract-asset unwind rows over the
commitment; ETF offset against remaining contract asset; swap
write-off vs trade-in inventory; payout-time full recognition +
residual-value-guarantee accrual (bank/BNPL programs); withdrawal
reversal incl. diminished-value line. Same journal machinery as the
wholesale COGS/revenue listeners.

### Lost/stolen → blacklist seam

The existing SIM block flow (reason lost/stolen) additionally emits
`DeviceBlacklistRequestedEvent` → `DeviceRegistryAdapter` (mock
driver now; a real EIR/GSMA feed is deployment config). The resource
carries the blacklist flag the trade-in quote reads.

### Faces

Shop checkout: trade-in widget (IMEI + condition → live estimate as a
cart credit), financing chooser showing per-model monthly/total cost.
Selfcare: "my devices" — agreement status, paid share, upgrade
eligibility date, trade-in tracking, withdrawal button inside the
window. Console: device desk — agreements, valuations awaiting
grading, delta approvals, insurance-IMEI mismatch repair.

### Events

`DeviceAgreementActivated/Settled/Swapped/Withdrawn`,
`TradeInQuoted/Accepted/Graded/Revalued/Settled`,
`FinancingPayoutReceived`, `DeviceBlacklistRequested` — all wired to
insight traits, campaign topics, and bridge mappings per the standing
rule (upgrade-eligibility reached is a growth trigger, not just a
ledger fact).

### Proof

`device_commerce_test.js`: buy with trade-in estimate → delivery
starts the withdrawal clock → grading delta refund; upgrade at
eligibility on operator-book (write-off math) and on mock-bank
(settlement quote); BNPL purchase via Klarna adapter; withdrawal
inside 14 days refunds incl. shipping; blacklisted IMEI quotes zero.

## Phases

- **P1** — `device_agreement` + operator-book driver + shop/selfcare
  faces + events.
- **P2** — trade-in: valuation, grading, delta money movements,
  console desk.
- **P3** — financing port: mock-bank driver, BNPL via Klarna,
  upgrade/swap saga, early settlement.
- **P4** — withdrawal cases, subsidy subledger postings, blacklist
  seam, residual-value guarantee.

Docs upkeep on ship: manual + architecture + README per house rule.
