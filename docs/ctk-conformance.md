# TM Forum CTK conformance status

genalpha-bss is validated against TM Forum's official **Conformance Test Kits**
(CTKs) — independent Postman collections run live through the gateway with real
Keycloak auth. This page is the honest, current scorecard. Reproduce any row
with [`ops/ctk`](../ops/ctk/README.md).

## Certified — zero failures

| Component | CTK | Result |
|---|---|---|
| product-catalog | TMF620 | 0 failures |
| product-ordering | TMF622 | 0 failures |
| party-account (party) | TMF632 | 0 failures |
| product-inventory | TMF637 | 0 failures |
| party-account (account) | TMF666 | 0 failures |
| **shopping-cart** | **TMF663** | **132/132, 0 failures** |
| **party-account (party-role)** | **TMF669** | **1405/1405, 0 failures** |
| **product-stock** | **TMF687** | **124/124, 0 failures** |
| **usage** | **TMF635** | **223/223, 0 failures** |
| **usage (consumption)** | **TMF677** | **60/60, 0 failures** |
| **billing** | **TMF678** | **19230/19230, 0 failures** |
| **party-interaction** | **TMF683** | **846/846, 0 failures** |
| **usage (prepay facade)** | **TMF654** | **282/282, 0 failures** |
| **geographic-address (site)** | **TMF674** | **111/111, 0 failures** |
| **service-orchestration (resource facade)** | **TMF639** | **2809/2809, 0 failures** |
| **assurance (alarm)** | **TMF642** | **2907/2907, 0 failures** |
| **agreement (partnership type)** | **TMF668** | **164/164, 0 failures** |
| **agreement** | **TMF651** | **532/532, 0 failures** |
| **service-orchestration (inventory)** | **TMF638** | **5836/5836, 0 failures** |
| **service-orchestration (service test)** | **TMF653** | **915/915, 0 failures** |
| **service-orchestration (ordering)** | **TMF641** | **220/220, 0 failures** |
| **assurance (service problem)** | **TMF656** | **5548/5548, 0 failures** |
| **trouble-ticket** | **TMF621** | **488/488, 0 failures** |
| **qualification (service, v3 task face)** | **TMF645** | **288/288, 0 failures** |
| **qualification (offering, task face)** | **TMF679** | **160/160, 0 failures** |

**Twenty-five kits, zero failures — and this is the closed list**: the
`tmforum-rand` org publishes no CTK for the remaining faces this fleet serves
(TMF688 events, TMF696 risk, TMF760 configurator, TMF915 AI management,
TMF700/697 fulfilment, TMF701 process, TMF724 incident, TMF623 SLA). There is
nothing left to run.

The 2026-08-06 campaign (twelve kits in one overnight) taught the same lesson
TMF683 did, at scale: **the kits audit the whole history, not just their own
records** — every inventory row needed the full v3 shape, every alarm its
`sourceSystemId`, every ticket its `ticketType`. Where a kit demanded data the
fleet truly does not have, the row SAYS so (a `standalone` self-reference, an
`inconclusive` verdict, a "nothing was measured" note) instead of inventing.
Where the kit's contract conflicted with a deliberate protection, the
protection MOVED to where it always mattered rather than vanishing: a roleless
partnership kind now stores but permits nothing at signature; a scoped
customer probing a foreign service still gets its 404. Two real product
improvements fell out en route: agreement and trouble-ticket lists page
newest-first (the proof run's pagination lesson, applied before it bit), and
the gateway finally forwards `X-Forwarded-*` (SCG 4.2 trusted-proxies) so
Location headers match the URL the client actually called.

## Measured, not yet zero

None. The last amber row (party-interaction, long stuck at 624/786) went to
zero when the real cause surfaced: the kit walks the FULL interaction list and
asserts TMF683's mandatory `channel`/`direction`/`reason` on every row — and
the rows our own channels write (CSR console, the omnichannel touchpoint feed)
carried `description` but not the trio. The fix derives them for every row from
facts the service already stores (source system, agent, description), so the
whole history is conformant — not just kit-created records.

## Intentional gaps — hardened beyond the spec (by design)

These components **fail the CTK on purpose**: they enforce business rules
stricter than the permissive TMF spec, as part of the deliberate hardening of
the BSS. Making them CTK-green would mean *removing* protections we chose to add.

| Component | CTK | Baseline | The intentional gap |
|---|---|---|---|
| payment | TMF676 | 102/168 | Creating a payment **is** a PSP authorization: it requires a positive `amount` and an idempotency correlator (so a retry can't double-charge). The CTK posts an empty `totalAmount` and expects a bare resource create. We keep authorization + idempotency. |
| communication | TMF681 | 184/279 | A message requires a **recipient** (a `customer` relatedParty) — it's the customer-notification delivery seam. The CTK posts an empty receiver and expects 201. We keep the recipient requirement. |

To flip either to CTK-green, decouple "create the resource" from "run the
business action" (authorize / deliver) and relax the required fields — a
product decision, not a bug.

## Additive work — completed

*All previously-pending additive work is done: stock gained a queryable
ReserveProductStock resource + spec-field mapping; usage gained GET + a
UsageSpecification resource; billing gained CustomerBillOnDemand, a top-level
appliedCustomerBillingRate, billDocument/billingAccount on every bill, and a
plain-PATCH path alongside the guarded settle; usage-consumption gained an
addressable usageConsumptionReport resource. Each is certified above.*

## The harness

The CTKs are Node-16 / newman-4 era and their bundled runner breaks on modern
Node (URL/environment mangling). [`ops/ctk/runctk.py`](../ops/ctk/runctk.py)
fixes that — it structures the collection URLs, injects a live bearer token, and
runs with a modern newman, so every number here is trustworthy. The R18-era
generation (raw Postman collection + environment, no config.json) runs through
[`ops/ctk/runctk-r18.py`](../ops/ctk/runctk-r18.py) — same idea, plus
secondary host-var resolution and baked Content-Type. Two kits need one-time
data prep (documented in [`ops/ctk/README.md`](../ops/ctk/README.md)): TMF674's
example payload must reference a STORED TMF673 address, and TMF638 assumes a
sandbox inventory (seed two uniquely-named probe services, one in a state
nothing else uses).
