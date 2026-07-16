# Dealer channel — starter kits + dealer console (plan)

The CSP/Elkjøp/Power model: an operator with no own stores sells through
electronics chains. The chain sells a **SIM starter kit** like a chocolate
bar, or a clerk signs the customer up at the counter. Attribution and
commission must work in BOTH flows — including the one where no partner
system was ever involved.

## Research (summary)

- a CSP's physical distribution can BE Elkjøp + Power; kits
  self-activate with BankID; some CSPs' terms bill even unactivated signed
  agreements — agreement and activation are separate facts.
- Industry dealer portals (Cerillion et al.) converge on: on-behalf sign-up
  and activation, serialized SIM stock per store, expected commission,
  settlement statements, order search, audit per clerk.
- Norway's 14-day angrerett means commission must accrue as PENDING, harden
  after the withdrawal window, and CLAW BACK on early cancel.

## Build (this arc): C + A

**C — Starter kits** (SOM owns them; it owns SIMs and activation):
- `starter_kit`: activation code + pre-minted SIM + dealer/store attribution,
  status available → activated. Minted in batches for a dealer+store (staff
  or dealer admin).
- `POST /starterKit/activate` (customer, self): code + chosen offering →
  places the product order ON BEHALF (bss-som's existing ordering machine
  identity) stamped `relatedParty: {role: 'dealer'}` from the kit — the
  attribution is in the box, not in any partner system. The kit's SIM
  becomes the subscription's SIM.

**Commission ledger** (sales component — TMF699 lives there):
- Consumes dealer-stamped order events → accrues PENDING commission
  (rate as data: per-dealer agreement row). A tick hardens PENDING →
  EARNED after the withdrawal window (dev clock). Early termination inside
  the window → CLAWED_BACK with the reason. Fail-closed and explainable,
  like unapplied cash — money out deserves the money-in discipline.
- `GET /commission` dealer-scoped; staff sees all; statement = entries +
  totals per month.

**A — Dealer console** (channel app #6, `apps/dealer-console`):
- Dealer role signs in; org-scoped server-side like the business console.
- Sell at the counter: create customer + order on-behalf (dealer-stamped).
- Kits: see the store's kit stock (available/activated), mint a batch
  (dealer admin).
- Money: own store's sales and commission entries (pending/earned/clawed).
- Every sale logs itself on the TMF683 timeline ("sold at <store>").

## Deliberately later (named follow-ups)

Partner API hardening (per-partner rate limits), port-in from the console,
per-store stock allocation in TMF687, chain → store → clerk org hierarchy,
commission dispute worklist, marketing collateral in the dealer console.

## Proof (suite #48, dealer_channel_test.js)

A dealer admin mints kits for their store; a clerk sells at the counter
(customer + dealer-stamped order); a customer self-activates a kit bought
anonymously — BOTH orders carry the dealer attribution; commission accrues
PENDING at the configured rate, hardens to EARNED after the dev window;
a cancel inside the window CLAWS BACK with the reason; the dealer sees only
their own store's kits and money (stranger dealer = nothing); the sale is on
the customer's TMF683 timeline.
