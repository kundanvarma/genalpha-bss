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
runs with a modern newman, so every number here is trustworthy.
