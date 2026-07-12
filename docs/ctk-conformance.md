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

## Measured, not yet zero

| Component | CTK | Assertions | Why not zero |
|---|---|---|---|
| party-interaction | TMF683 | 624/786 | Improved 3× (relaxed create, rich fields round-trip). Long tail of nested-field/filter assertions remains. |

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

## Additive work pending — safe, but real (not just relaxing)

These are *not* business conflicts; they simply expose less of each API than the
CTK exercises. Reaching zero means **building additive resources/endpoints**
(no guarantees weakened), which is a focused reshape per component:

| Component | CTK | Baseline | What's missing |
|---|---|---|---|
| product-stock | TMF687 | 36/90 | `ProductStock` uses a typed `stockedQuantity`; the CTK uses `productStockLevel`. And `reserveProductStock` is a task operation here — reservations are persisted (`StockReservation`) but not exposed as a queryable CRUD resource. Needs: DTO field mapping + GET on reserveProductStock. |
| usage | TMF635 | 26/82 | `usage` intake is not exposed as a retrievable resource (no `GET /usage`), and there is **no `usageSpecification` resource** at all (the CTK tests full CRUD on it). Needs: usage GET + a new usageSpecification resource. |
| usage (consumption) | TMF677 | — | Query-oriented; the kit's structure needs harness handling before a clean run. |
| billing | TMF678 | 30/136 | Bills are **computed by billing runs**, not POST-able; the CTK posts `CustomerBillOnDemand`. Needs: an on-demand bill endpoint. |

## The harness

The CTKs are Node-16 / newman-4 era and their bundled runner breaks on modern
Node (URL/environment mangling). [`ops/ctk/runctk.py`](../ops/ctk/runctk.py)
fixes that — it structures the collection URLs, injects a live bearer token, and
runs with a modern newman, so every number here is trustworthy.
